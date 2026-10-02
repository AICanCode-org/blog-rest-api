"""Integration tests: the real app against a real PostgreSQL (docker compose up -d postgres).

Run with:  make test-integration   (sets -m integration)
Uses TEST_DATABASE_URL so your dev data is never touched.
"""

import os
import threading

import psycopg
import pytest
from fastapi.testclient import TestClient

from app.db import create_pool
from app.main import create_app
from app.repository import PostgresUserRepository
from tests.conftest import test_settings

pytestmark = pytest.mark.integration

DB_URL = os.getenv("TEST_DATABASE_URL", "postgresql://devblog:devblog@localhost:5432/devblog_test")
ADMIN = {"email": "admin@devblog.test", "password": "admin-password"}


def sql(statement: str, params: tuple = ()):
    with psycopg.connect(DB_URL, autocommit=True) as conn:
        cur = conn.execute(statement, params)
        return cur.fetchall() if cur.description else None


@pytest.fixture
def client():
    # Start once to run the migrations, wipe the tables, then start again so the admin is re-created.
    settings = test_settings(database_url=DB_URL, admin_email=ADMIN["email"], admin_password=ADMIN["password"])
    with TestClient(create_app(settings)):
        pass
    sql("TRUNCATE users, refresh_tokens, posts, tags, post_tags, comments RESTART IDENTITY CASCADE")
    with TestClient(create_app(settings)) as c:
        yield c


def login(client, email, password="password-123"):
    r = client.post("/api/v1/auth/login", json={"email": email, "password": password})
    assert r.status_code == 200, r.text
    return r.json()


def h(session):
    return {"Authorization": f"Bearer {session['accessToken']}"}


def make_user(client, email, role="READER"):
    r = client.post(
        "/api/v1/auth/register", json={"email": email, "password": "password-123", "displayName": email[:5]}
    )
    assert r.status_code == 201, r.text
    if role != "READER":
        admin = login(client, ADMIN["email"], ADMIN["password"])
        client.patch(f"/api/v1/admin/users/{r.json()['id']}/role", json={"role": role}, headers=h(admin))
    return login(client, email)


def test_health_and_migrations(client):
    assert client.get("/health").json() == {"status": "UP", "checks": {"database": "UP"}}
    assert [r[0] for r in sql("SELECT version FROM schema_migrations ORDER BY version")][:2] == [1, 2]


def test_passwords_and_refresh_tokens_are_never_stored_in_clear(client):
    session = make_user(client, "asha@devblog.test")
    (password_hash,) = sql("SELECT password_hash FROM users WHERE email = 'asha@devblog.test'")[0]
    assert password_hash.startswith("$2") and "password-123" not in password_hash
    hashes = [r[0] for r in sql("SELECT token_hash FROM refresh_tokens")]
    assert session["refreshToken"] not in hashes and all(len(x) == 64 for x in hashes)


def test_unique_email_is_enforced_by_the_database(client):
    make_user(client, "dup@devblog.test")
    r = client.post(
        "/api/v1/auth/register", json={"email": "DUP@devblog.test", "password": "password-123", "displayName": "D"}
    )
    assert r.status_code == 409


def test_concurrent_refreshes_with_one_token_let_exactly_one_win(client):
    session = make_user(client, "race@devblog.test")
    statuses = []

    def refresh():
        statuses.append(client.post("/api/v1/auth/refresh", json={"refreshToken": session["refreshToken"]}).status_code)

    threads = [threading.Thread(target=refresh) for _ in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert statuses.count(200) == 1 and statuses.count(401) == 3


def test_slug_uniqueness_and_tags_in_the_database(client):
    author = make_user(client, "ada@devblog.test", "AUTHOR")
    body = {"title": "Same Title", "body": "Body.", "status": "published", "tags": ["java", "sql"]}
    slugs = [client.post("/api/v1/posts", json=body, headers=h(author)).json()["slug"] for _ in range(3)]
    assert slugs == ["same-title", "same-title-2", "same-title-3"]
    assert sql("SELECT count(*) FROM tags")[0][0] == 2  # tags are shared, not duplicated
    assert client.get("/api/v1/tags").json()["items"] == [{"name": "java", "posts": 3}, {"name": "sql", "posts": 3}]


def test_full_text_search_uses_english_stemming(client):
    author = make_user(client, "ada@devblog.test", "AUTHOR")
    for title, text in [("Testing with containers", "We run tests against PostgreSQL."), ("Cooking", "Pasta.")]:
        client.post("/api/v1/posts", json={"title": title, "body": text, "status": "published"}, headers=h(author))
    found = client.get("/api/v1/posts", params={"q": "test container"}).json()["items"]
    assert [p["title"] for p in found] == ["Testing with containers"]  # "test" matches "Testing" and "tests"
    assert client.get("/api/v1/posts", params={"q": '"exact phrase" -pasta'}).status_code == 200  # websearch syntax


def test_keyset_pagination_over_the_real_index(client):
    author = make_user(client, "ada@devblog.test", "AUTHOR")
    for i in range(25):
        client.post("/api/v1/posts", json={"title": f"P{i}", "body": "x.", "status": "published"}, headers=h(author))
    sql("UPDATE posts SET published_at = '2026-10-02T09:30:00Z'")  # one shared timestamp: the id must break ties
    seen, cursor = [], None
    while True:
        page = client.get("/api/v1/posts", params={"limit": 10, **({"cursor": cursor} if cursor else {})}).json()
        seen += [p["id"] for p in page["items"]]
        cursor = page["nextCursor"]
        if not cursor:
            break
    assert len(seen) == 25 and len(set(seen)) == 25 and seen == sorted(seen, reverse=True)


def test_version_conflict_is_detected_by_the_update_statement(client):
    author = make_user(client, "ada@devblog.test", "AUTHOR")
    slug = client.post("/api/v1/posts", json={"title": "Race", "body": "v1"}, headers=h(author)).json()["slug"]
    assert (
        client.patch(f"/api/v1/posts/{slug}", json={"version": 1, "body": "v2"}, headers=h(author)).status_code == 200
    )
    assert (
        client.patch(f"/api/v1/posts/{slug}", json={"version": 1, "body": "v3"}, headers=h(author)).status_code == 409
    )
    assert sql("SELECT body, version FROM posts WHERE slug = %s", (slug,)) == [("v2", 2)]


def test_soft_delete_keeps_the_row(client):
    author = make_user(client, "ada@devblog.test", "AUTHOR")
    slug = client.post(
        "/api/v1/posts", json={"title": "Gone", "body": "b", "status": "published"}, headers=h(author)
    ).json()["slug"]
    assert client.delete(f"/api/v1/posts/{slug}", headers=h(author)).status_code == 204
    assert client.get(f"/api/v1/posts/{slug}").status_code == 404
    assert sql("SELECT deleted_at IS NOT NULL FROM posts WHERE slug = %s", (slug,)) == [(True,)]


def test_comments_and_moderation_are_persisted(client):
    author = make_user(client, "ada@devblog.test", "AUTHOR")
    reader = make_user(client, "rita@devblog.test")
    slug = client.post(
        "/api/v1/posts", json={"title": "Talk", "body": "b", "status": "published"}, headers=h(author)
    ).json()["slug"]
    ok = client.post(f"/api/v1/posts/{slug}/comments", json={"body": "Nice"}, headers=h(reader)).json()
    held = client.post(
        f"/api/v1/posts/{slug}/comments", json={"body": "see https://a.example https://b.example"}, headers=h(reader)
    ).json()
    assert held["moderation"] == {
        "label": "spam",
        "confidence": 0.75,
        "provider": "rules",
        "fallback": False,
        "reason": "2 links",
    }
    reply = client.post(
        f"/api/v1/posts/{slug}/comments", json={"body": "Thanks", "parentId": ok["id"]}, headers=h(author)
    )
    assert reply.status_code == 201
    assert [c["id"] for c in client.get(f"/api/v1/posts/{slug}/comments").json()["items"]] == [
        ok["id"],
        reply.json()["id"],
    ]
    admin = login(client, ADMIN["email"], ADMIN["password"])
    assert [c["id"] for c in client.get("/api/v1/admin/comments", headers=h(admin)).json()["items"]] == [held["id"]]
    client.post(f"/api/v1/admin/comments/{held['id']}/decision", json={"decision": "reject"}, headers=h(admin))
    assert sql("SELECT status, moderated_by IS NOT NULL FROM comments WHERE id = %s", (held["id"],)) == [
        ("rejected", True)
    ]


def test_cover_image_url_is_stored_by_the_v2_migration(client):
    author = make_user(client, "ada@devblog.test", "AUTHOR")
    body = {"title": "Cover", "body": "b", "status": "published", "coverImageUrl": "https://img.example/c.png"}
    slug = client.post("/api/v1/posts", json=body, headers=h(author)).json()["slug"]
    assert sql("SELECT cover_image_url FROM posts WHERE slug = %s", (slug,)) == [("https://img.example/c.png",)]
    assert client.get("/api/v1/posts").json()["items"][0]["coverImageUrl"] == "https://img.example/c.png"


def test_admin_is_bootstrapped_from_the_environment(client):
    assert sql("SELECT role FROM users WHERE email = %s", (ADMIN["email"],)) == [("ADMIN",)]


def test_health_reports_down_when_the_database_is_unreachable():
    pool = create_pool(DB_URL)
    repo = PostgresUserRepository(pool)
    assert repo.ping() is True
    pool.close()  # simulate the database going away: every new connection now fails
    assert repo.ping() is False

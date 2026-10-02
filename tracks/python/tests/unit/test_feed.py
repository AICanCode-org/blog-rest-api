"""M4: the public feed - keyset pagination, tag filter, search, tag counts (FR-6)."""

import pytest

from app.ai import SummaryService, TemplateSummarizer
from app.errors import ApiError
from app.models import Principal
from app.posts import PostService
from app.repository import InMemoryStore


@pytest.fixture
def author(store: InMemoryStore, clock) -> Principal:
    return Principal(store.insert_user("ada@devblog.test", "x", "Ada", "AUTHOR", clock()).id, "AUTHOR")


@pytest.fixture
def posts(store, clock) -> PostService:
    return PostService(store, clock, SummaryService(TemplateSummarizer()))


def publish(posts, author, clock, n, **fields):
    created = []
    for i in range(n):
        clock.advance(minutes=1)
        body = {"title": f"Post {i}", "body": f"Body number {i}.", "status": "published", **fields}
        created.append(posts.create(author, body))
    return created


def test_feed_is_newest_first_and_skips_drafts(posts, author, clock):
    publish(posts, author, clock, 3)
    posts.create(author, {"title": "Secret draft", "body": "Not yet."})
    page, cursor = posts.feed(None, None, None, None)
    assert [p.title for p in page] == ["Post 2", "Post 1", "Post 0"] and cursor is None


def test_keyset_pagination_walks_every_post_exactly_once(posts, author, clock):
    publish(posts, author, clock, 7)
    seen, cursor, pages = [], None, 0
    while True:
        page, cursor = posts.feed("3", cursor, None, None)
        seen += [p.title for p in page]
        pages += 1
        if cursor is None:
            break
    assert pages == 3 and seen == [f"Post {i}" for i in range(6, -1, -1)]


def test_posts_published_in_the_same_millisecond_are_not_skipped(posts, author, clock):
    # Same published_at for all five: the id breaks the tie, so the cursor needs both.
    for i in range(5):
        posts.create(author, {"title": f"Same time {i}", "body": "x.", "status": "published"})
    first, cursor = posts.feed("2", None, None, None)
    second, cursor = posts.feed("2", cursor, None, None)
    third, cursor = posts.feed("2", cursor, None, None)
    titles = [p.title for p in first + second + third]
    assert sorted(titles) == [f"Same time {i}" for i in range(5)] and len(set(titles)) == 5 and cursor is None


def test_a_new_post_does_not_shift_the_next_page(posts, author, clock):
    publish(posts, author, clock, 4)
    first, cursor = posts.feed("2", None, None, None)
    publish(posts, author, clock, 1, title="Breaking news")  # with OFFSET this would repeat "Post 2"
    second, _ = posts.feed("2", cursor, None, None)
    assert [p.title for p in first] == ["Post 3", "Post 2"] and [p.title for p in second] == ["Post 1", "Post 0"]


def test_tag_filter(posts, author, clock):
    publish(posts, author, clock, 2, tags=["java"])
    publish(posts, author, clock, 1, tags=["python"])
    page, _ = posts.feed(None, None, "Java", None)
    assert len(page) == 2 and all(p.tags == ["java"] for p in page)


def test_search_matches_title_and_body_words(posts, author, clock):
    posts.create(author, {"title": "Docker for beginners", "body": "Containers explained.", "status": "published"})
    posts.create(author, {"title": "Kubernetes", "body": "Orchestrate many docker containers.", "status": "published"})
    posts.create(author, {"title": "Java records", "body": "Immutable data.", "status": "published"})
    assert {p.title for p in posts.feed(None, None, None, "docker")[0]} == {"Docker for beginners", "Kubernetes"}
    assert [p.title for p in posts.feed(None, None, None, "docker containers kubernetes")[0]] == ["Kubernetes"]
    assert posts.feed(None, None, None, "rust")[0] == []


def test_feed_validates_its_parameters(posts):
    for args in [("0", None, None, None), (None, "garbage!", None, None), (None, None, "not a tag", None)]:
        with pytest.raises(ApiError) as exc:
            posts.feed(*args)
        assert exc.value.status == 400


def test_deleted_posts_leave_the_feed_and_the_tag_counts(posts, author, clock):
    a, b = publish(posts, author, clock, 2, tags=["java"])
    publish(posts, author, clock, 1, tags=["python", "java"])
    posts.create(author, {"title": "Draft", "body": "x", "tags": ["rust"]})
    assert [(t.name, t.posts) for t in posts.tags()] == [("java", 3), ("python", 1)]
    posts.delete(author, a.slug)
    assert [(t.name, t.posts) for t in posts.tags()] == [("java", 2), ("python", 1)]
    assert a.slug not in [p.slug for p in posts.feed(None, None, None, None)[0]]


# ---- over HTTP ------------------------------------------------------------------------------------
def test_feed_endpoint_shape_and_cursor(api, client, clock):
    author = api.user("AUTHOR", name="Ada")
    for i in range(3):
        clock.advance(minutes=1)
        api.post(author, title=f"Post {i}", body="word " * 50, tags=["java"])
    first = client.get("/api/v1/posts?limit=2").json()
    assert [p["title"] for p in first["items"]] == ["Post 2", "Post 1"] and first["nextCursor"]
    item = first["items"][0]
    assert set(item) == {"id", "slug", "title", "excerpt", "tags", "author", "publishedAt", "coverImageUrl"}
    assert item["author"] == {"id": author["user"]["id"], "displayName": "Ada"}
    assert len(item["excerpt"]) <= 160 and item["excerpt"].endswith("...")
    second = client.get(f"/api/v1/posts?limit=2&cursor={first['nextCursor']}").json()
    assert [p["title"] for p in second["items"]] == ["Post 0"] and second["nextCursor"] is None
    assert client.get("/api/v1/posts?limit=500").status_code == 400
    assert client.get("/api/v1/posts?cursor=%%%").status_code == 400


def test_tags_endpoint(api, client):
    author = api.user("AUTHOR")
    api.post(author, tags=["java", "spring"])
    api.post(author, tags=["java"])
    assert client.get("/api/v1/tags").json() == {
        "items": [{"name": "java", "posts": 2}, {"name": "spring", "posts": 1}]
    }
    assert client.get("/api/v1/posts?tag=spring").json()["items"][0]["tags"] == ["java", "spring"]
    assert client.get("/api/v1/posts?q=sentences").json()["items"] != []

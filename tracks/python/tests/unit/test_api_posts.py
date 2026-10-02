"""M3: the post endpoints over HTTP."""

POST = {"title": "Hello DevBlog", "body": "First post. It has two sentences.", "tags": ["Java"]}


def test_create_post_returns_201_location_and_the_full_post(api, client):
    author = api.user("AUTHOR", name="Ada")
    r = client.post("/api/v1/posts", json=POST, headers=api.h(author))
    assert r.status_code == 201 and r.headers["location"] == "/api/v1/posts/hello-devblog"
    assert r.json() == {
        "id": r.json()["id"],
        "slug": "hello-devblog",
        "title": "Hello DevBlog",
        "body": "First post. It has two sentences.",
        "status": "draft",
        "tags": ["java"],
        "author": {"id": author["user"]["id"], "displayName": "Ada"},
        "createdAt": "2026-10-02T09:30:00.000Z",
        "updatedAt": "2026-10-02T09:30:00.000Z",
        "publishedAt": None,
        "coverImageUrl": None,
        "version": 1,
    }


def test_post_writes_need_a_token_and_the_right_role(api, client):
    r = client.post("/api/v1/posts", json=POST)
    assert r.status_code == 401 and r.headers["www-authenticate"] == "Bearer"
    assert client.post("/api/v1/posts", json=POST, headers=api.h(api.user())).status_code == 403


def test_drafts_are_404_for_everyone_else(api, client):
    author = api.user("AUTHOR")
    draft = api.post(author, status="draft")
    assert client.get(f"/api/v1/posts/{draft['slug']}").status_code == 404
    assert client.get(f"/api/v1/posts/{draft['slug']}", headers=api.h(api.user("AUTHOR"))).status_code == 404
    assert client.get(f"/api/v1/posts/{draft['slug']}", headers=api.h(author)).status_code == 200
    assert client.get(f"/api/v1/posts/{draft['slug']}", headers=api.h(api.admin())).status_code == 200
    # a broken token is a 401, even on a public endpoint: never silently anonymous
    assert client.get(f"/api/v1/posts/{draft['slug']}", headers={"Authorization": "Bearer x"}).status_code == 401


def test_patch_with_optimistic_locking(api, client):
    author = api.user("AUTHOR")
    post = api.post(author)
    url = f"/api/v1/posts/{post['slug']}"
    ok = client.patch(url, json={"version": 1, "title": "Edited"}, headers=api.h(author))
    assert ok.status_code == 200 and ok.json()["version"] == 2 and ok.json()["slug"] == post["slug"]
    stale = client.patch(url, json={"version": 1, "title": "Lost update"}, headers=api.h(author))
    assert stale.status_code == 409 and stale.json()["type"].endswith("/version-conflict")
    assert "current version 2" in stale.json()["detail"]
    other = client.patch(url, json={"version": 2, "title": "Not mine"}, headers=api.h(api.user("AUTHOR")))
    assert other.status_code == 403


def test_delete_is_204_then_404(api, client):
    author = api.user("AUTHOR")
    post = api.post(author)
    url = f"/api/v1/posts/{post['slug']}"
    assert client.delete(url).status_code == 401
    assert client.delete(url, headers=api.h(author)).status_code == 204
    assert client.get(url).status_code == 404
    assert client.delete(url, headers=api.h(author)).status_code == 404


def test_summary_endpoint(api, client):
    post = api.post(api.user("AUTHOR"), body="Keyset pagination is fast. OFFSET is slow. Here is why.")
    r = client.get(f"/api/v1/posts/{post['slug']}/summary")
    assert r.json() == {
        "slug": post["slug"],
        "provider": "template",
        "fallback": False,
        "summary": "Keyset pagination is fast. OFFSET is slow.",
        "generatedAt": "2026-10-02T09:30:00.000Z",
    }
    assert client.get("/api/v1/posts/missing/summary").status_code == 404

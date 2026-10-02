"""M5: comments and the moderation queue over HTTP."""


def test_comment_flow_with_moderation(api, client):
    author, reader, admin = api.user("AUTHOR"), api.user(name="Rita"), api.admin()
    post = api.post(author)
    url = f"/api/v1/posts/{post['slug']}/comments"
    assert client.post(url, json={"body": "hi"}).status_code == 401
    ok = client.post(url, json={"body": "Clear and useful."}, headers=api.h(reader))
    assert ok.status_code == 201
    assert ok.json() == {
        "id": ok.json()["id"],
        "postSlug": post["slug"],
        "parentId": None,
        "author": {"id": reader["user"]["id"], "displayName": "Rita"},
        "body": "Clear and useful.",
        "status": "visible",
        "moderation": {
            "label": "ok",
            "confidence": 0.6,
            "provider": "rules",
            "fallback": False,
            "reason": "no rule matched",
        },
        "createdAt": "2026-10-02T09:30:00.000Z",
    }
    held = client.post(url, json={"body": "BUY NOW at www.cheap.example"}, headers=api.h(reader)).json()
    assert held["status"] == "held" and held["moderation"]["label"] == "spam"
    assert [c["id"] for c in client.get(url).json()["items"]] == [ok.json()["id"]]

    queue = client.get("/api/v1/admin/comments", headers=api.h(admin)).json()["items"]
    assert [c["id"] for c in queue] == [held["id"]]
    assert client.get("/api/v1/admin/comments", headers=api.h(reader)).status_code == 403
    decided = client.post(
        f"/api/v1/admin/comments/{held['id']}/decision", json={"decision": "approve"}, headers=api.h(admin)
    )
    assert decided.status_code == 200 and decided.json()["status"] == "visible"
    assert len(client.get(url).json()["items"]) == 2
    assert client.get("/api/v1/admin/comments?status=nope", headers=api.h(admin)).status_code == 400


def test_replies_and_deleting(api, client):
    author, reader = api.user("AUTHOR"), api.user()
    post = api.post(author)
    url = f"/api/v1/posts/{post['slug']}/comments"
    top = client.post(url, json={"body": "Question?"}, headers=api.h(reader)).json()
    reply = client.post(url, json={"body": "Answer.", "parentId": top["id"]}, headers=api.h(author))
    assert reply.status_code == 201 and reply.json()["parentId"] == top["id"]
    assert client.post(url, json={"body": "x", "parentId": "1"}, headers=api.h(reader)).status_code == 400
    assert client.delete(f"/api/v1/comments/{top['id']}", headers=api.h(author)).status_code == 403
    assert client.delete(f"/api/v1/comments/{top['id']}", headers=api.h(reader)).status_code == 204
    assert client.delete("/api/v1/comments/abc", headers=api.h(reader)).status_code == 404
    assert client.get("/api/v1/posts/missing/comments").status_code == 404

"""v1.1 (Phase 7): optional cover image URL on posts (V2 migration)."""

import pytest

from app import validation as v
from app.errors import ApiError


@pytest.mark.parametrize(
    "url", ["https://img.example/cover.png", "http://localhost:8080/a.jpg?w=800#top", "HTTPS://CDN.EXAMPLE"]
)
def test_valid_cover_urls(url):
    assert v.cover_image_url(url) == url


@pytest.mark.parametrize(
    "url",
    ["", "javascript:alert(1)", "data:image/png;base64,AAAA", "ftp://x.example/a.png", "/relative.png",
     "https://exa mple.com", "https://", 42, "https://x.example/" + "a" * 2040],
)  # fmt: skip
def test_invalid_cover_urls_are_400(url):
    with pytest.raises(ApiError) as e:
        v.cover_image_url(url)
    assert e.value.status == 400 and "coverImageUrl" in e.value.detail


def test_null_means_no_cover():
    assert v.cover_image_url(None) is None


def test_cover_on_create_patch_clear_and_in_the_feed(api, client):
    author = api.user("AUTHOR")
    post = api.post(author, coverImageUrl="https://img.example/a.png")
    assert post["coverImageUrl"] == "https://img.example/a.png"
    assert client.get("/api/v1/posts").json()["items"][0]["coverImageUrl"] == "https://img.example/a.png"
    url = f"/api/v1/posts/{post['slug']}"
    r = client.patch(url, json={"version": 1, "coverImageUrl": "https://img.example/b.png"}, headers=api.h(author))
    assert r.status_code == 200 and r.json()["coverImageUrl"] == "https://img.example/b.png"
    r = client.patch(url, json={"version": 2, "title": "Only the title"}, headers=api.h(author))
    assert r.json()["coverImageUrl"] == "https://img.example/b.png"  # untouched when not sent
    r = client.patch(url, json={"version": 3, "coverImageUrl": None}, headers=api.h(author))
    assert r.status_code == 200 and r.json()["coverImageUrl"] is None
    r = client.patch(url, json={"version": 4, "coverImageUrl": "javascript:alert(1)"}, headers=api.h(author))
    assert r.status_code == 400


def test_patch_with_nothing_to_update_mentions_the_cover(api, client):
    author = api.user("AUTHOR")
    post = api.post(author)
    r = client.patch(f"/api/v1/posts/{post['slug']}", json={"version": 1}, headers=api.h(author))
    assert r.json()["detail"] == "nothing to update: send title, body, tags, status or coverImageUrl"

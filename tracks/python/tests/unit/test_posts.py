"""M3: posts - roles, slugs, drafts, ownership, optimistic locking, soft delete (FR-4, FR-5)."""

import pytest

from app.ai import SummaryService, TemplateSummarizer
from app.errors import ApiError
from app.models import Principal
from app.posts import PostService
from app.repository import InMemoryStore

POST = {"title": "Hello DevBlog", "body": "First post. It has two sentences."}


@pytest.fixture
def people(store: InMemoryStore, clock):
    ids = {}
    for name, role in [("ada", "AUTHOR"), ("bob", "AUTHOR"), ("rita", "READER"), ("root", "ADMIN")]:
        ids[name] = Principal(store.insert_user(f"{name}@devblog.test", "x", name.title(), role, clock()).id, role)
    return ids


@pytest.fixture
def posts(store, clock) -> PostService:
    return PostService(store, clock, SummaryService(TemplateSummarizer()))


def status_of(fn, *args):
    with pytest.raises(ApiError) as exc:
        fn(*args)
    return exc.value.status, exc.value.slug


def test_author_creates_a_draft_by_default(posts: PostService, people, clock):
    post = posts.create(people["ada"], {**POST, "tags": ["Python", "fastapi"]})
    assert post.slug == "hello-devblog" and post.status == "draft" and post.version == 1
    assert post.tags == ["fastapi", "python"] and post.author_name == "Ada"
    assert post.published_at is None and post.created_at == post.updated_at == clock()


def test_publishing_on_create_sets_published_at(posts: PostService, people, clock):
    assert posts.create(people["ada"], {**POST, "status": "published"}).published_at == clock()


def test_readers_cannot_write_posts(posts: PostService, people):
    assert status_of(posts.create, people["rita"], POST) == (403, "forbidden")
    assert posts.create(people["root"], POST).author_id == people["root"].user_id  # ADMIN may


def test_slug_collisions_get_a_suffix(posts: PostService, people):
    slugs = [posts.create(people["ada"], POST).slug for _ in range(3)]
    assert slugs == ["hello-devblog", "hello-devblog-2", "hello-devblog-3"]


def test_create_validates(posts: PostService, people):
    assert status_of(posts.create, people["ada"], {"title": "", "body": "x"})[0] == 400
    assert status_of(posts.create, people["ada"], {**POST, "status": "archived"})[0] == 400


def test_drafts_are_visible_only_to_the_author_and_admins(posts: PostService, people):
    draft = posts.create(people["ada"], POST)
    assert posts.get(people["ada"], draft.slug).id == draft.id
    assert posts.get(people["root"], draft.slug).id == draft.id
    # Not 403: a stranger must not even learn that the draft exists.
    for stranger in [None, people["bob"], people["rita"]]:
        assert status_of(posts.get, stranger, draft.slug) == (404, "not-found")


def test_published_posts_are_public(posts: PostService, people):
    post = posts.create(people["ada"], {**POST, "status": "published"})
    assert posts.get(None, post.slug).title == "Hello DevBlog"


def test_author_edits_own_post_and_version_increments(posts: PostService, people, clock):
    post = posts.create(people["ada"], POST)
    clock.advance(minutes=5)
    updated = posts.update(people["ada"], post.slug, {"version": 1, "title": "Hello again", "tags": ["news"]})
    assert updated.title == "Hello again" and updated.tags == ["news"] and updated.version == 2
    assert updated.slug == post.slug  # slugs never change: links keep working
    assert updated.updated_at == clock() and updated.created_at == post.created_at


def test_other_authors_get_403_but_admins_may_edit(posts: PostService, people):
    post = posts.create(people["ada"], {**POST, "status": "published"})
    assert status_of(posts.update, people["bob"], post.slug, {"version": 1, "title": "Mine now"}) == (403, "forbidden")
    assert posts.update(people["root"], post.slug, {"version": 1, "title": "Moderated"}).title == "Moderated"


def test_stale_version_is_a_409_conflict(posts: PostService, people):
    post = posts.create(people["ada"], POST)
    posts.update(people["ada"], post.slug, {"version": 1, "body": "Edited in tab one."})
    assert status_of(posts.update, people["ada"], post.slug, {"version": 1, "body": "Edited in tab two."}) == (
        409,
        "version-conflict",
    )


def test_update_needs_a_version_and_a_change(posts: PostService, people):
    post = posts.create(people["ada"], POST)
    assert status_of(posts.update, people["ada"], post.slug, {"title": "No version"})[0] == 400
    assert status_of(posts.update, people["ada"], post.slug, {"version": 1})[0] == 400
    assert status_of(posts.update, people["ada"], post.slug, {"version": 1, "status": None})[0] == 400
    assert status_of(posts.update, people["ada"], "no-such-post", {"version": 1, "title": "x"})[0] == 404


def test_first_publication_date_is_kept_when_republishing(posts: PostService, people, clock):
    post = posts.create(people["ada"], POST)
    clock.advance(hours=1)
    published = posts.update(people["ada"], post.slug, {"version": 1, "status": "published"})
    first = published.published_at
    assert first == clock()
    clock.advance(hours=1)
    back = posts.update(people["ada"], post.slug, {"version": 2, "status": "draft"})
    again = posts.update(people["ada"], post.slug, {"version": 3, "status": "published"})
    assert back.published_at == first and again.published_at == first


def test_soft_delete_hides_the_post(posts: PostService, people, store: InMemoryStore):
    post = posts.create(people["ada"], {**POST, "status": "published"})
    assert status_of(posts.delete, people["bob"], post.slug) == (403, "forbidden")
    posts.delete(people["ada"], post.slug)
    assert status_of(posts.get, people["ada"], post.slug)[0] == 404
    assert post.id in store.posts  # the row is kept (soft delete)
    assert status_of(posts.delete, people["ada"], post.slug)[0] == 404


def test_admin_can_delete_any_post(posts: PostService, people):
    post = posts.create(people["ada"], {**POST, "status": "published"})
    posts.delete(people["root"], post.slug)
    assert status_of(posts.get, None, post.slug)[0] == 404


def test_summary_uses_the_template_offline(posts: PostService, people):
    post = posts.create(people["ada"], {**POST, "status": "published"})
    _, result = posts.summary(None, post.slug)
    assert (result.provider, result.fallback, result.summary) == (
        "template",
        False,
        "First post. It has two sentences.",
    )
    draft = posts.create(people["ada"], POST)
    assert status_of(posts.summary, None, draft.slug)[0] == 404

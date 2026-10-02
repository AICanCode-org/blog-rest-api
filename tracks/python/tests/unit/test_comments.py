"""M5: comments, moderation hold and the human moderation queue (FR-7, FR-9)."""

import pytest

from app.ai import ModerationService, RuleModerator, SummaryService, TemplateSummarizer
from app.comments import CommentService
from app.errors import ApiError
from app.models import Principal
from app.posts import PostService
from app.repository import InMemoryStore


@pytest.fixture
def people(store: InMemoryStore, clock):
    ids = {}
    for name, role in [("ada", "AUTHOR"), ("rita", "READER"), ("sam", "READER"), ("root", "ADMIN")]:
        ids[name] = Principal(store.insert_user(f"{name}@devblog.test", "x", name.title(), role, clock()).id, role)
    return ids


@pytest.fixture
def setup(store, clock, people):
    posts = PostService(store, clock, SummaryService(TemplateSummarizer()))
    comments = CommentService(store, store, ModerationService(RuleModerator()), clock)
    post = posts.create(people["ada"], {"title": "Hello", "body": "Body.", "status": "published"})
    return posts, comments, post


def status_of(fn, *args):
    with pytest.raises(ApiError) as exc:
        fn(*args)
    return exc.value.status, exc.value.slug


def test_a_clean_comment_is_visible(setup, people, clock):
    _, comments, post = setup
    c = comments.create(people["rita"], post.slug, {"body": "  Great post!  "})
    assert (c.status, c.body, c.post_slug, c.author_name, c.parent_id) == (
        "visible",
        "Great post!",
        "hello",
        "Rita",
        None,
    )
    assert (c.moderation.label, c.moderation.provider) == ("ok", "rules") and c.created_at == clock()
    assert [x.id for x in comments.list(post.slug)] == [c.id]


def test_spam_and_toxic_comments_are_held_not_deleted(setup, people):
    _, comments, post = setup
    spam = comments.create(people["rita"], post.slug, {"body": "Free money, click here"})
    toxic = comments.create(people["sam"], post.slug, {"body": "you idiot"})
    assert spam.status == toxic.status == "held"
    assert comments.list(post.slug) == []
    assert [c.id for c in comments.queue(people["root"], None)] == [spam.id, toxic.id]


def test_admin_decisions_override_the_ai(setup, people, clock):
    _, comments, post = setup
    held = comments.create(people["rita"], post.slug, {"body": "giveaway at https://a.example"})
    clock.advance(minutes=3)
    approved = comments.decide(people["root"], held.id, {"decision": "approve"})
    assert approved.status == "visible" and approved.moderated_by == people["root"].user_id
    assert approved.moderated_at == clock() and approved.moderation.label == "spam"  # the AI verdict is kept
    assert [c.id for c in comments.list(post.slug)] == [held.id]
    rejected = comments.decide(people["root"], held.id, {"decision": "reject"})
    assert rejected.status == "rejected" and comments.list(post.slug) == []
    assert [c.id for c in comments.queue(people["root"], "rejected")] == [held.id]


def test_only_admins_moderate(setup, people):
    _, comments, post = setup
    held = comments.create(people["rita"], post.slug, {"body": "click here"})
    assert status_of(comments.queue, people["ada"], None) == (403, "forbidden")
    assert status_of(comments.decide, people["ada"], held.id, {"decision": "approve"}) == (403, "forbidden")
    assert status_of(comments.decide, people["root"], 999, {"decision": "approve"})[0] == 404
    assert status_of(comments.decide, people["root"], held.id, {"decision": "delete"})[0] == 400
    assert status_of(comments.queue, people["root"], "all")[0] == 400


def test_replies_are_one_level_deep_on_the_same_post(setup, people):
    posts, comments, post = setup
    top = comments.create(people["rita"], post.slug, {"body": "Question?"})
    reply = comments.create(people["ada"], post.slug, {"body": "Answer.", "parentId": top.id})
    assert reply.parent_id == top.id
    assert status_of(comments.create, people["sam"], post.slug, {"body": "deeper", "parentId": reply.id})[0] == 400
    other = posts.create(people["ada"], {"title": "Other", "body": "B.", "status": "published"})
    assert status_of(comments.create, people["sam"], other.slug, {"body": "wrong post", "parentId": top.id})[0] == 400
    held = comments.create(people["sam"], post.slug, {"body": "click here"})
    assert status_of(comments.create, people["rita"], post.slug, {"body": "re", "parentId": held.id})[0] == 400
    assert status_of(comments.create, people["rita"], post.slug, {"body": "re", "parentId": 999})[0] == 400


def test_comments_need_a_published_post(setup, people):
    posts, comments, _ = setup
    draft = posts.create(people["ada"], {"title": "Draft", "body": "B."})
    assert status_of(comments.create, people["ada"], draft.slug, {"body": "hi"}) == (404, "not-found")
    assert status_of(comments.list, draft.slug)[0] == 404
    assert status_of(comments.create, people["ada"], "missing", {"body": "hi"})[0] == 404
    assert status_of(comments.create, people["ada"], "hello", {"body": ""})[0] == 400


def test_delete_own_comment_or_as_admin(setup, people):
    _, comments, post = setup
    mine = comments.create(people["rita"], post.slug, {"body": "first"})
    theirs = comments.create(people["sam"], post.slug, {"body": "second"})
    assert status_of(comments.delete, people["rita"], theirs.id) == (403, "forbidden")
    comments.delete(people["rita"], mine.id)
    comments.delete(people["root"], theirs.id)
    assert comments.list(post.slug) == []
    assert status_of(comments.delete, people["rita"], mine.id)[0] == 404


def test_comments_of_a_deleted_post_leave_the_queue(setup, people):
    posts, comments, post = setup
    comments.create(people["rita"], post.slug, {"body": "click here"})
    posts.delete(people["ada"], post.slug)
    assert comments.queue(people["root"], None) == []

"""Posts: drafts, publishing, ownership, optimistic locking, soft delete, the feed and tags."""

from __future__ import annotations

from dataclasses import replace

from . import validation as v
from .ai import SummaryResult, SummaryService
from .auth import require_role
from .clock import Clock
from .errors import DuplicateError, forbidden, not_found, validation_error, version_conflict
from .models import FeedQuery, Post, Principal, TagCount
from .repository import PostRepository
from .text import decode_cursor, encode_cursor, slugify

MAX_SLUG_ATTEMPTS = 50


def can_see(principal: Principal | None, post: Post) -> bool:
    if post.status == "published":
        return True
    return principal is not None and (principal.is_admin or principal.user_id == post.author_id)


def can_edit(principal: Principal, post: Post) -> bool:
    return principal.is_admin or principal.user_id == post.author_id


class PostService:
    def __init__(self, posts: PostRepository, clock: Clock, summaries: SummaryService):
        self.posts = posts
        self._clock = clock
        self._summaries = summaries

    def create(self, principal: Principal, payload: object) -> Post:
        require_role(principal, "AUTHOR", "ADMIN")
        data = v.require_object(payload)
        title, body = v.title(data.get("title")), v.body(data.get("body"))
        tags, status = v.tags(data.get("tags")), v.status(data.get("status"))
        now = self._clock()
        base = slugify(title)
        for attempt in range(1, MAX_SLUG_ATTEMPTS + 1):
            slug = base if attempt == 1 else f"{base}-{attempt}"
            draft = Post(0, slug, principal.user_id, "", title, body, status, tags, 1, now, now,
                         now if status == "published" else None)  # fmt: skip
            try:
                # Insert and let the unique constraint decide: "check, then insert" would race.
                return self.posts.insert_post(draft)
            except DuplicateError:
                continue
        raise validation_error("too many posts with this title")

    def get(self, principal: Principal | None, slug: str) -> Post:
        post = self.posts.find_post(slug)
        # A draft you may not see is "not found", not "forbidden": don't confirm that it exists.
        if post is None or not can_see(principal, post):
            raise not_found("post")
        return post

    def update(self, principal: Principal, slug: str, payload: object) -> Post:
        data = v.require_object(payload)
        expected = v.version(data.get("version"))
        if not any(k in data for k in ("title", "body", "tags", "status")):
            raise validation_error("nothing to update: send title, body, tags or status")
        post = self.get(principal, slug)
        if not can_edit(principal, post):
            raise forbidden("only the author or an ADMIN can edit this post")
        changed = replace(
            post,
            title=v.title(data["title"]) if "title" in data else post.title,
            body=v.body(data["body"]) if "body" in data else post.body,
            tags=v.tags(data["tags"]) if "tags" in data else post.tags,
            status=v.status(data["status"], default=None) if "status" in data else post.status,
            updated_at=self._clock(),
        )
        if changed.status == "published" and changed.published_at is None:
            changed.published_at = changed.updated_at  # first publication; re-publishing keeps the date
        saved = self.posts.update_post(changed, expected)
        if saved is None:
            raise version_conflict(post.version)
        return saved

    def delete(self, principal: Principal, slug: str) -> None:
        post = self.get(principal, slug)
        if not can_edit(principal, post):
            raise forbidden("only the author or an ADMIN can delete this post")
        self.posts.soft_delete_post(post.id, self._clock())

    def feed(
        self, limit: str | None, cursor: str | None, tag: str | None, q: str | None
    ) -> tuple[list[Post], str | None]:
        size = v.limit(limit)
        query = FeedQuery(limit=size + 1, tag=v.tag_filter(tag), q=v.search_query(q), after=decode_cursor(cursor))
        rows = self.posts.feed(query)
        # Ask for one extra row: if it exists there is a next page, and the cursor is the last row shown.
        page = rows[:size]
        next_cursor = encode_cursor(page[-1].published_at, page[-1].id) if len(rows) > size else None  # type: ignore[arg-type]
        return page, next_cursor

    def tags(self) -> list[TagCount]:
        return self.posts.tag_counts()

    def summary(self, principal: Principal | None, slug: str) -> tuple[Post, SummaryResult]:
        post = self.get(principal, slug)
        return post, self._summaries.summarize(post.title, post.body)

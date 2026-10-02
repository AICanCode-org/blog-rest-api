"""Posts: drafts, publishing, ownership, optimistic locking, soft delete, the feed and tags."""

from __future__ import annotations

from .ai import SummaryResult, SummaryService
from .clock import Clock
from .models import Post, Principal, TagCount
from .repository import PostRepository

MAX_SLUG_ATTEMPTS = 50


def can_see(principal: Principal | None, post: Post) -> bool:
    # TODO(phase-3 M3): published posts: everyone; drafts: only the author and ADMINs.
    raise NotImplementedError("TODO(phase-3 M3): can_see")


def can_edit(principal: Principal, post: Post) -> bool:
    # TODO(phase-3 M3): the author or an ADMIN.
    raise NotImplementedError("TODO(phase-3 M3): can_edit")


class PostService:
    def __init__(self, posts: PostRepository, clock: Clock, summaries: SummaryService):
        self.posts = posts
        self._clock = clock
        self._summaries = summaries

    def create(self, principal: Principal, payload: object) -> Post:
        # TODO(phase-3 M3): AUTHOR/ADMIN only; validate title, body, tags, status; slug = slugify(title);
        # INSERT and on DuplicateError retry with -2, -3 ... (MAX_SLUG_ATTEMPTS); publishedAt = now when
        # created as published.
        raise NotImplementedError("TODO(phase-3 M3): PostService.create")

    def get(self, principal: Principal | None, slug: str) -> Post:
        # TODO(phase-3 M3): a missing post OR a draft you may not see -> 404 (never 403: don't confirm it
        # exists).
        raise NotImplementedError("TODO(phase-3 M3): PostService.get")

    def update(self, principal: Principal, slug: str, payload: object) -> Post:
        # TODO(phase-3 M3): order matters (the tests check it): object, version, nothing-to-update (400), get
        # (404), can_edit (403), validate title/body/tags/status, save with optimistic locking (409
        # version-conflict). First publish sets publishedAt; the slug never changes.
        raise NotImplementedError("TODO(phase-3 M3): PostService.update")

    def delete(self, principal: Principal, slug: str) -> None:
        # TODO(phase-3 M3): get + can_edit, then a SOFT delete (deleted_at). Deleted posts are 404 from then
        # on.
        raise NotImplementedError("TODO(phase-3 M3): PostService.delete")

    def feed(
        self, limit: str | None, cursor: str | None, tag: str | None, q: str | None
    ) -> tuple[list[Post], str | None]:
        # TODO(phase-3 M4): validate limit/cursor/tag/q; ask the repository for limit + 1 rows after the
        # cursor (published, not deleted, newest first, id breaks ties); if there is an extra row, nextCursor
        # = cursor of the last row SHOWN. ADR-0002.
        raise NotImplementedError("TODO(phase-3 M4): PostService.feed")

    def tags(self) -> list[TagCount]:
        # TODO(phase-3 M4): tag counts over published, non-deleted posts, sorted by name.
        raise NotImplementedError("TODO(phase-3 M4): PostService.tags")

    def summary(self, principal: Principal | None, slug: str) -> tuple[Post, SummaryResult]:
        # TODO(phase-3 M5): get the post (same visibility rules) and summarise title + body with the
        # SummaryService.
        raise NotImplementedError("TODO(phase-3 M5): PostService.summary")

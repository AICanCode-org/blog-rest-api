"""Comments with AI moderation and a human moderation queue."""

from __future__ import annotations

from .ai import ModerationService
from .clock import Clock
from .models import Comment, Post, Principal
from .repository import CommentRepository, PostRepository


class CommentService:
    def __init__(self, comments: CommentRepository, posts: PostRepository, moderation: ModerationService, clock: Clock):
        self.comments = comments
        self._posts = posts
        self._moderation = moderation
        self._clock = clock

    def _published_post(self, slug: str) -> Post:
        # TODO(phase-3 M5): find the post by slug; missing, deleted or draft -> 404 not-found
        #   'post'.
        raise NotImplementedError("TODO(phase-3 M5): CommentService._published_post")

    def create(self, principal: Principal, slug: str, payload: object) -> Comment:
        # TODO(phase-3 M5): validate body and parentId; the post must be PUBLISHED (404); a parent
        #   must be a visible top-level comment of the same post (400); moderate the body and store it
        #   as 'held' when should_hold, else 'visible'.
        raise NotImplementedError("TODO(phase-3 M5): CommentService.create")

    def list(self, slug: str) -> list[Comment]:
        # TODO(phase-3 M5): visible comments of a PUBLISHED post (404 otherwise), oldest first.
        raise NotImplementedError("TODO(phase-3 M5): CommentService.list")

    def delete(self, principal: Principal, comment_id: int) -> None:
        # TODO(phase-3 M5): unknown comment -> 404; only its author or an ADMIN (403); SOFT delete
        #   (deleted_at).
        raise NotImplementedError("TODO(phase-3 M5): CommentService.delete")

    def queue(self, principal: Principal, status: str | None) -> list[Comment]:
        # TODO(phase-3 M5): ADMIN only; comments with the given status (default held), excluding comments of
        # deleted posts.
        raise NotImplementedError("TODO(phase-3 M5): CommentService.queue")

    def decide(self, principal: Principal, comment_id: int, payload: object) -> Comment:
        # TODO(phase-3 M5): ADMIN only; approve -> visible, reject -> rejected; record moderated_by/at; keep
        # the AI verdict; unknown id -> 404.
        raise NotImplementedError("TODO(phase-3 M5): CommentService.decide")

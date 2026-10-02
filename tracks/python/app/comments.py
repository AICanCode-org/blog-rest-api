"""Comments with AI moderation and a human moderation queue."""

from __future__ import annotations

from . import validation as v
from .ai import ModerationService, should_hold
from .auth import require_role
from .clock import Clock
from .errors import forbidden, not_found, validation_error
from .metrics import Metrics
from .models import Comment, Post, Principal
from .repository import CommentRepository, PostRepository


class CommentService:
    def __init__(
        self,
        comments: CommentRepository,
        posts: PostRepository,
        moderation: ModerationService,
        clock: Clock,
        metrics: Metrics | None = None,
    ):
        self._metrics = metrics or Metrics()
        self.comments = comments
        self._posts = posts
        self._moderation = moderation
        self._clock = clock

    def _published_post(self, slug: str) -> Post:
        post = self._posts.find_post(slug)
        if post is None or post.status != "published":
            raise not_found("post")
        return post

    def create(self, principal: Principal, slug: str, payload: object) -> Comment:
        data = v.require_object(payload)
        body = v.comment_body(data.get("body"))
        parent_id = v.parent_id(data.get("parentId"))
        post = self._published_post(slug)
        if parent_id is not None:
            parent = self.comments.find_comment(parent_id)
            if (
                parent is None
                or parent.post_id != post.id
                or parent.parent_id is not None
                or parent.status != "visible"
            ):
                raise validation_error("parentId must be a visible top-level comment of this post")
        moderation = self._moderation.moderate(body)
        status = "held" if should_hold(moderation) else "visible"
        comment = Comment(0, post.id, post.slug, principal.user_id, "", parent_id, body, status, moderation,
                          self._clock())  # fmt: skip
        saved = self.comments.insert_comment(comment)
        self._metrics.inc("devblog_comments_created_total")
        if status == "held":
            self._metrics.inc("devblog_comments_held_total")
        if moderation.fallback:
            self._metrics.inc("devblog_ai_fallback_total")
        return saved

    def list(self, slug: str) -> list[Comment]:
        return self.comments.visible_comments(self._published_post(slug).id)

    def delete(self, principal: Principal, comment_id: int) -> None:
        comment = self.comments.find_comment(comment_id)
        if comment is None:
            raise not_found("comment")
        if not (principal.is_admin or principal.user_id == comment.author_id):
            raise forbidden("only the author or an ADMIN can delete this comment")
        self.comments.soft_delete_comment(comment_id, self._clock())

    def queue(self, principal: Principal, status: str | None) -> list[Comment]:
        require_role(principal, "ADMIN")
        return self.comments.comments_by_status(v.comment_status(status))

    def decide(self, principal: Principal, comment_id: int, payload: object) -> Comment:
        require_role(principal, "ADMIN")
        decision = v.decision(v.require_object(payload).get("decision"))
        status = "visible" if decision == "approve" else "rejected"
        updated = self.comments.set_comment_status(comment_id, status, principal.user_id, self._clock())
        if updated is None:
            raise not_found("comment")
        return updated

package org.aicancode.devblog.service;

import java.time.Clock;
import java.util.List;
import org.aicancode.devblog.ai.ModerationService;
import org.aicancode.devblog.domain.Models.Comment;
import org.aicancode.devblog.domain.Models.Post;
import org.aicancode.devblog.domain.Models.Principal;
import org.aicancode.devblog.repo.CommentRepository;
import org.aicancode.devblog.repo.PostRepository;

/** Comments with AI moderation and a human moderation queue. */
public class CommentService {

    private final CommentRepository comments;
    private final PostRepository posts;
    private final ModerationService moderation;
    private final Clock clock;

    public CommentService(CommentRepository comments, PostRepository posts, ModerationService moderation, Clock clock) {
        this.comments = comments;
        this.posts = posts;
        this.moderation = moderation;
        this.clock = clock;
    }

    private Post publishedPost(String slug) {
        // TODO(phase-3 M5): find the post by slug; missing, deleted or draft -> 404 not-found 'post'.
        throw new UnsupportedOperationException("TODO(phase-3 M5): CommentService.publishedPost");
    }

    public Comment create(Principal principal, String slug, Object payload) {
        // TODO(phase-3 M5): validate body and parentId; the post must be PUBLISHED (404); a parent must be a
        //   visible top-level comment of the same post (400); moderate the body and store it as 'held' when
        //   should_hold, else 'visible'.
        throw new UnsupportedOperationException("TODO(phase-3 M5): CommentService.create");
    }

    public List<Comment> list(String slug) {
        // TODO(phase-3 M5): visible comments of a PUBLISHED post (404 otherwise), oldest first.
        throw new UnsupportedOperationException("TODO(phase-3 M5): CommentService.list");
    }

    public void delete(Principal principal, long commentId) {
        // TODO(phase-3 M5): unknown comment -> 404; only its author or an ADMIN (403); SOFT delete
        //   (deleted_at).
        throw new UnsupportedOperationException("TODO(phase-3 M5): CommentService.delete");
    }

    public List<Comment> queue(Principal principal, String status) {
        // TODO(phase-3 M5): ADMIN only; comments with the given status (default held), excluding comments of
        // deleted posts.
        throw new UnsupportedOperationException("TODO(phase-3 M5): CommentService.queue");
    }

    public Comment decide(Principal principal, long commentId, Object payload) {
        // TODO(phase-3 M5): ADMIN only; approve -> visible, reject -> rejected; record moderated_by/at; keep the AI
        // verdict; unknown id -> 404.
        throw new UnsupportedOperationException("TODO(phase-3 M5): CommentService.decide");
    }
}

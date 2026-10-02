package org.aicancode.devblog.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.aicancode.devblog.ai.ModerationService;
import org.aicancode.devblog.domain.ApiException;
import org.aicancode.devblog.domain.Models.Comment;
import org.aicancode.devblog.domain.Models.Moderation;
import org.aicancode.devblog.domain.Models.Post;
import org.aicancode.devblog.domain.Models.Principal;
import org.aicancode.devblog.domain.Times;
import org.aicancode.devblog.domain.Validation;
import org.aicancode.devblog.metrics.Metrics;
import org.aicancode.devblog.repo.CommentRepository;
import org.aicancode.devblog.repo.PostRepository;

/** Comments with AI moderation and a human moderation queue. */
public class CommentService {

    private final CommentRepository comments;
    private final PostRepository posts;
    private final ModerationService moderation;
    private final Clock clock;
    private final Metrics metrics;

    public CommentService(CommentRepository comments, PostRepository posts, ModerationService moderation, Clock clock) {
        this(comments, posts, moderation, clock, new Metrics());
    }

    public CommentService(CommentRepository comments, PostRepository posts, ModerationService moderation, Clock clock,
                          Metrics metrics) {
        this.metrics = metrics;
        this.comments = comments;
        this.posts = posts;
        this.moderation = moderation;
        this.clock = clock;
    }

    private Post publishedPost(String slug) {
        return posts.findPost(slug).filter(p -> "published".equals(p.status()))
                .orElseThrow(() -> ApiException.notFound("post"));
    }

    public Comment create(Principal principal, String slug, Object payload) {
        Map<String, Object> data = Validation.requireObject(payload);
        String body = Validation.commentBody(data.get("body"));
        Long parentId = Validation.parentId(data.get("parentId"));
        Post post = publishedPost(slug);
        if (parentId != null) {
            Comment parent = comments.findComment(parentId).orElse(null);
            if (parent == null || parent.postId() != post.id() || parent.parentId() != null
                    || !"visible".equals(parent.status())) {
                throw ApiException.validation("parentId must be a visible top-level comment of this post");
            }
        }
        Moderation verdict = moderation.moderate(body);
        String status = ModerationService.shouldHold(verdict) ? "held" : "visible";
        Comment saved = comments.insertComment(new Comment(0, post.id(), post.slug(), principal.userId(), "", parentId,
                body, status, verdict, Times.now(clock), null, null));
        metrics.inc(Metrics.COMMENTS_CREATED);
        if ("held".equals(status)) {
            metrics.inc(Metrics.COMMENTS_HELD);
        }
        if (verdict.fallback()) {
            metrics.inc(Metrics.AI_FALLBACK);
        }
        return saved;
    }

    public List<Comment> list(String slug) {
        return comments.visibleComments(publishedPost(slug).id());
    }

    public void delete(Principal principal, long commentId) {
        Comment comment = comments.findComment(commentId).orElseThrow(() -> ApiException.notFound("comment"));
        if (!(principal.isAdmin() || principal.userId() == comment.authorId())) {
            throw ApiException.forbidden("only the author or an ADMIN can delete this comment");
        }
        comments.softDeleteComment(commentId, Times.now(clock));
    }

    public List<Comment> queue(Principal principal, String status) {
        AuthService.requireRole(principal, "ADMIN");
        return comments.commentsByStatus(Validation.commentStatus(status));
    }

    public Comment decide(Principal principal, long commentId, Object payload) {
        AuthService.requireRole(principal, "ADMIN");
        String decision = Validation.decision(Validation.requireObject(payload).get("decision"));
        String status = "approve".equals(decision) ? "visible" : "rejected";
        return comments.setCommentStatus(commentId, status, principal.userId(), Times.now(clock))
                .orElseThrow(() -> ApiException.notFound("comment"));
    }
}

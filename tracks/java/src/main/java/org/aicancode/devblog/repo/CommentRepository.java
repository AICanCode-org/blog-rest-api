package org.aicancode.devblog.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.aicancode.devblog.domain.Models.Comment;

/** Persistence port for comments. */
public interface CommentRepository {

    Comment insertComment(Comment comment);

    Optional<Comment> findComment(long id);

    List<Comment> visibleComments(long postId);

    List<Comment> commentsByStatus(String status);

    Optional<Comment> setCommentStatus(long id, String status, long by, Instant at);

    void softDeleteComment(long id, Instant at);
}

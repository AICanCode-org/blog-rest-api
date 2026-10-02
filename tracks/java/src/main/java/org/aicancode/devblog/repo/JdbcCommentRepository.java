package org.aicancode.devblog.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.aicancode.devblog.domain.Models.Comment;
import org.aicancode.devblog.domain.Models.Moderation;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL adapter for comments. */
public class JdbcCommentRepository implements CommentRepository {

    private static final String COMMENT_SELECT = """
            SELECT c.id, c.post_id, p.slug AS post_slug, c.author_id, u.display_name AS author_name, c.parent_id,
                   c.body, c.status, c.moderation_label, c.moderation_confidence, c.moderation_provider,
                   c.moderation_fallback, c.moderation_reason, c.moderated_by, c.moderated_at, c.created_at
            FROM comments c JOIN users u ON u.id = c.author_id JOIN posts p ON p.id = c.post_id
            """;

    private final JdbcClient jdbc;

    public JdbcCommentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Comment comment(ResultSet rs, int row) throws SQLException {
        Moderation m = new Moderation(rs.getString("moderation_label"),
                rs.getBigDecimal("moderation_confidence").doubleValue(), // NUMERIC(3,2)
                rs.getString("moderation_provider"), rs.getBoolean("moderation_fallback"),
                rs.getString("moderation_reason"));
        return new Comment(rs.getLong("id"), rs.getLong("post_id"), rs.getString("post_slug"), rs.getLong("author_id"),
                rs.getString("author_name"), nullableLong(rs, "parent_id"), rs.getString("body"), rs.getString("status"),
                m, JdbcUserRepository.instant(rs, "created_at"), nullableLong(rs, "moderated_by"),
                JdbcUserRepository.instant(rs, "moderated_at"));
    }

    private List<Comment> query(String where, Object... params) {
        return jdbc.sql(COMMENT_SELECT + where).params(params).query(JdbcCommentRepository::comment).list();
    }

    @Override
    public Comment insertComment(Comment c) {
        // TODO(phase-3 M5): INSERT with the moderation columns, read it back with author name and post slug.
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcCommentRepository.insertComment");
    }

    @Override
    public Optional<Comment> findComment(long id) {
        // TODO(phase-3 M5): by id, not deleted.
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcCommentRepository.findComment");
    }

    @Override
    public List<Comment> visibleComments(long postId) {
        // TODO(phase-3 M5): status 'visible', not deleted, ORDER BY created_at, id.
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcCommentRepository.visibleComments");
    }

    @Override
    public List<Comment> commentsByStatus(String status) {
        // TODO(phase-3 M5): by status, not deleted, post not deleted, ORDER BY created_at, id.
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcCommentRepository.commentsByStatus");
    }

    @Override
    public Optional<Comment> setCommentStatus(long id, String status, long by, Instant at) {
        // TODO(phase-3 M5): UPDATE status, moderated_by, moderated_at RETURNING; null if missing.
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcCommentRepository.setCommentStatus");
    }

    @Override
    public void softDeleteComment(long id, Instant at) {
        // TODO(phase-3 M5): set deleted_at.
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcCommentRepository.softDeleteComment");
    }
}

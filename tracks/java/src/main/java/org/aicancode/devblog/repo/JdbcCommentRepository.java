package org.aicancode.devblog.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
        Moderation m = c.moderation();
        long id = jdbc.sql("INSERT INTO comments (post_id, author_id, parent_id, body, status, moderation_label, "
                        + "moderation_confidence, moderation_provider, moderation_fallback, moderation_reason, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")
                .params(c.postId(), c.authorId(), c.parentId(), c.body(), c.status(), m.label(), m.confidence(),
                        m.provider(), m.fallback(), m.reason(), Timestamp.from(c.createdAt()))
                .query(Long.class).single();
        return findComment(id).orElseThrow();
    }

    @Override
    public Optional<Comment> findComment(long id) {
        return query("WHERE c.id = ? AND c.deleted_at IS NULL", id).stream().findFirst();
    }

    @Override
    public List<Comment> visibleComments(long postId) {
        return query("WHERE c.post_id = ? AND c.status = 'visible' AND c.deleted_at IS NULL ORDER BY c.created_at, c.id",
                postId);
    }

    @Override
    public List<Comment> commentsByStatus(String status) {
        return query("WHERE c.status = ? AND c.deleted_at IS NULL AND p.deleted_at IS NULL ORDER BY c.created_at, c.id",
                status);
    }

    @Override
    public Optional<Comment> setCommentStatus(long id, String status, long by, Instant at) {
        jdbc.sql("UPDATE comments SET status = ?, moderated_by = ?, moderated_at = ? WHERE id = ? AND deleted_at IS NULL")
                .params(status, by, Timestamp.from(at), id).update();
        return findComment(id);
    }

    @Override
    public void softDeleteComment(long id, Instant at) {
        jdbc.sql("UPDATE comments SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL").params(Timestamp.from(at), id)
                .update();
    }
}

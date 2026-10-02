package org.aicancode.devblog.repo;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.aicancode.devblog.domain.Models.FeedQuery;
import org.aicancode.devblog.domain.Models.Post;
import org.aicancode.devblog.domain.Models.TagCount;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL adapter for posts and tags. */
public class JdbcPostRepository implements PostRepository {

    static final String POST_SELECT = """
            SELECT p.id, p.slug, p.author_id, u.display_name AS author_name, p.title, p.body, p.status, p.version,
                   p.created_at, p.updated_at, p.published_at,
                   ARRAY(SELECT t.name FROM post_tags pt JOIN tags t ON t.id = pt.tag_id
                         WHERE pt.post_id = p.id ORDER BY t.name) AS tags
            FROM posts p JOIN users u ON u.id = p.author_id
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcPostRepository(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Post post(ResultSet rs, int row) throws SQLException {
        Array tags = rs.getArray("tags");
        List<String> tagList = tags == null ? List.of() : Arrays.asList((String[]) tags.getArray());
        return new Post(rs.getLong("id"), rs.getString("slug"), rs.getLong("author_id"), rs.getString("author_name"),
                rs.getString("title"), rs.getString("body"), rs.getString("status"), List.copyOf(tagList),
                rs.getInt("version"), JdbcUserRepository.instant(rs, "created_at"),
                JdbcUserRepository.instant(rs, "updated_at"), JdbcUserRepository.instant(rs, "published_at"));
    }

    private Optional<Post> byId(long id) {
        return jdbc.sql(POST_SELECT + "WHERE p.id = ?").param(id).query(JdbcPostRepository::post).optional();
    }

    private void replaceTags(long postId, List<String> tags) {
        jdbc.sql("DELETE FROM post_tags WHERE post_id = ?").param(postId).update();
        if (!tags.isEmpty()) {
            String[] names = tags.toArray(String[]::new);
            jdbc.sql("INSERT INTO tags (name) SELECT unnest(?::text[]) ON CONFLICT (name) DO NOTHING").param(names).update();
            jdbc.sql("INSERT INTO post_tags (post_id, tag_id) SELECT ?, id FROM tags WHERE name = ANY(?)")
                    .params(postId, names).update();
        }
    }

    @Override
    public Post insertPost(Post post) {
        // TODO(phase-3 M3): one transaction: INSERT the post (unique violation on slug -> DuplicateError), upsert
        // the tags, link them, return the post read back with author name and tags.
        throw new UnsupportedOperationException("TODO(phase-3 M3): JdbcPostRepository.insertPost");
    }

    @Override
    public Optional<Post> findPost(String slug) {
        // TODO(phase-3 M3): by slug, not deleted, with author name and tags.
        throw new UnsupportedOperationException("TODO(phase-3 M3): JdbcPostRepository.findPost");
    }

    @Override
    public Optional<Post> updatePost(Post post, int expectedVersion) {
        // TODO(phase-3 M3): UPDATE ... WHERE id = ? AND version = ? AND deleted_at IS NULL, version = version + 1;
        // 0 rows -> null (conflict). Replace the tags in the same transaction.
        throw new UnsupportedOperationException("TODO(phase-3 M3): JdbcPostRepository.updatePost");
    }

    @Override
    public void softDeletePost(long id, Instant at) {
        // TODO(phase-3 M3): set deleted_at; never DELETE the row.
        throw new UnsupportedOperationException("TODO(phase-3 M3): JdbcPostRepository.softDeletePost");
    }

    @Override
    public List<Post> feed(FeedQuery query) {
        // TODO(phase-3 M4): WHERE published AND not deleted [AND tag] [AND search_vector @@
        // websearch_to_tsquery('english', q)] [AND (published_at, id) < (cursor)] ORDER BY published_at DESC, id
        // DESC LIMIT n. Uses the index from V1.
        throw new UnsupportedOperationException("TODO(phase-3 M4): JdbcPostRepository.feed");
    }

    @Override
    public List<TagCount> tagCounts() {
        // TODO(phase-3 M4): SELECT name, COUNT(*) over published, non-deleted posts GROUP BY name ORDER BY name.
        throw new UnsupportedOperationException("TODO(phase-3 M4): JdbcPostRepository.tagCounts");
    }
}

package org.aicancode.devblog.repo;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.aicancode.devblog.domain.DuplicateException;
import org.aicancode.devblog.domain.Models.FeedQuery;
import org.aicancode.devblog.domain.Models.Post;
import org.aicancode.devblog.domain.Models.TagCount;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL adapter for posts and tags. */
public class JdbcPostRepository implements PostRepository {

    static final String POST_SELECT = """
            SELECT p.id, p.slug, p.author_id, u.display_name AS author_name, p.title, p.body, p.status, p.version,
                   p.created_at, p.updated_at, p.published_at, p.cover_image_url,
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
                JdbcUserRepository.instant(rs, "updated_at"), JdbcUserRepository.instant(rs, "published_at"),
                rs.getString("cover_image_url"));
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
        try {
            return tx.execute(status -> {
                long id = jdbc.sql("INSERT INTO posts (slug, author_id, title, body, status, created_at, updated_at, "
                                + "published_at, cover_image_url) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")
                        .params(post.slug(), post.authorId(), post.title(), post.body(), post.status(),
                                ts(post.createdAt()), ts(post.updatedAt()), ts(post.publishedAt()), post.coverImageUrl())
                        .query(Long.class).single();
                replaceTags(id, post.tags());
                return byId(id).orElseThrow();
            });
        } catch (DuplicateKeyException e) {
            throw new DuplicateException("slug");
        }
    }

    @Override
    public Optional<Post> findPost(String slug) {
        return jdbc.sql(POST_SELECT + "WHERE p.slug = ? AND p.deleted_at IS NULL").param(slug)
                .query(JdbcPostRepository::post).optional();
    }

    @Override
    public Optional<Post> updatePost(Post post, int expectedVersion) {
        return tx.execute(status -> {
            // Optimistic locking: the WHERE clause only matches if nobody saved in between.
            int rows = jdbc.sql("UPDATE posts SET title = ?, body = ?, status = ?, published_at = ?, updated_at = ?, "
                            + "cover_image_url = ?, version = version + 1 WHERE id = ? AND version = ? AND deleted_at IS NULL")
                    .params(post.title(), post.body(), post.status(), ts(post.publishedAt()), ts(post.updatedAt()),
                            post.coverImageUrl(), post.id(), expectedVersion)
                    .update();
            if (rows != 1) {
                return Optional.<Post>empty();
            }
            replaceTags(post.id(), post.tags());
            return byId(post.id());
        });
    }

    @Override
    public void softDeletePost(long id, Instant at) {
        jdbc.sql("UPDATE posts SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL").params(ts(at), id).update();
    }

    @Override
    public List<Post> feed(FeedQuery query) {
        List<String> where = new ArrayList<>(List.of("p.status = 'published'", "p.deleted_at IS NULL"));
        List<Object> params = new ArrayList<>();
        if (query.tag() != null) {
            where.add("EXISTS (SELECT 1 FROM post_tags pt JOIN tags t ON t.id = pt.tag_id "
                    + "WHERE pt.post_id = p.id AND t.name = ?)");
            params.add(query.tag());
        }
        if (query.q() != null) {
            where.add("p.search_vector @@ websearch_to_tsquery('english', ?)");
            params.add(query.q());
        }
        if (query.after() != null) {
            // Keyset pagination: "everything older than the last row I saw" - no OFFSET scan.
            where.add("(p.published_at, p.id) < (?, ?)");
            params.add(ts(query.after().publishedAt()));
            params.add(query.after().id());
        }
        params.add(query.limit());
        String sql = POST_SELECT + "WHERE " + String.join(" AND ", where)
                + " ORDER BY p.published_at DESC, p.id DESC LIMIT ?";
        return jdbc.sql(sql).params(params).query(JdbcPostRepository::post).list();
    }

    @Override
    public List<TagCount> tagCounts() {
        return jdbc.sql("SELECT t.name, COUNT(*) AS posts FROM tags t JOIN post_tags pt ON pt.tag_id = t.id "
                        + "JOIN posts p ON p.id = pt.post_id WHERE p.status = 'published' AND p.deleted_at IS NULL "
                        + "GROUP BY t.name ORDER BY COUNT(*) DESC, t.name")
                .query((rs, row) -> new TagCount(rs.getString("name"), rs.getLong("posts"))).list();
    }
}

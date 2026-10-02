package org.aicancode.devblog.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Plain data objects shared by services and repositories (no framework imports). */
public final class Models {

    public static final Set<String> ROLES = Set.of("READER", "AUTHOR", "ADMIN");

    private Models() {
    }

    public record User(long id, String email, String passwordHash, String displayName, String role, Instant createdAt) {
        public User withRole(String newRole) {
            return new User(id, email, passwordHash, displayName, newRole, createdAt);
        }
    }

    /** Who is calling, as proven by a verified access token. */
    public record Principal(long userId, String role) {
        public boolean isAdmin() {
            return "ADMIN".equals(role);
        }
    }

    public record RefreshToken(long id, long userId, String tokenHash, Instant createdAt, Instant expiresAt,
                               Instant revokedAt) {
    }

    public record TokenPair(String accessToken, String refreshToken, User user) {
    }

    public record Post(long id, String slug, long authorId, String authorName, String title, String body, String status,
                       List<String> tags, int version, Instant createdAt, Instant updatedAt, Instant publishedAt) {
    }

    public record Moderation(String label, double confidence, String provider, boolean fallback, String reason) {
    }

    public record Comment(long id, long postId, String postSlug, long authorId, String authorName, Long parentId,
                          String body, String status, Moderation moderation, Instant createdAt, Long moderatedBy,
                          Instant moderatedAt) {
    }

    public record TagCount(String name, long posts) {
    }

    /** Keyset position: (publishedAt, id) of the last item seen. */
    public record Cursor(Instant publishedAt, long id) {
    }

    public record FeedQuery(int limit, String tag, String q, Cursor after) {
    }
}

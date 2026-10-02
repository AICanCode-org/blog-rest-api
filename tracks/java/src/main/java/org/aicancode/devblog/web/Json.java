package org.aicancode.devblog.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.aicancode.devblog.domain.Models.Comment;
import org.aicancode.devblog.domain.Models.Post;
import org.aicancode.devblog.domain.Models.TagCount;
import org.aicancode.devblog.domain.Models.TokenPair;
import org.aicancode.devblog.domain.Models.User;
import org.aicancode.devblog.domain.Text;
import org.aicancode.devblog.domain.Times;
import org.aicancode.devblog.security.TokenService;

/** JSON shapes (identical in every track, see spec/openapi.yaml). LinkedHashMap keeps the documented order. */
final class Json {

    private Json() {
    }

    private static Map<String, Object> author(long id, String displayName) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", id);
        a.put("displayName", displayName);
        return a;
    }

    static Map<String, Object> user(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("email", u.email());
        m.put("displayName", u.displayName());
        m.put("role", u.role());
        m.put("createdAt", Times.iso(u.createdAt()));
        return m;
    }

    static Map<String, Object> tokens(TokenPair t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accessToken", t.accessToken());
        m.put("refreshToken", t.refreshToken());
        m.put("tokenType", "Bearer");
        m.put("expiresIn", TokenService.ACCESS_TOKEN_TTL.toSeconds());
        m.put("user", user(t.user()));
        return m;
    }

    static Map<String, Object> post(Post p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("slug", p.slug());
        m.put("title", p.title());
        m.put("body", p.body());
        m.put("status", p.status());
        m.put("tags", p.tags());
        m.put("author", author(p.authorId(), p.authorName()));
        m.put("createdAt", Times.iso(p.createdAt()));
        m.put("updatedAt", Times.iso(p.updatedAt()));
        m.put("publishedAt", Times.iso(p.publishedAt()));
        m.put("coverImageUrl", p.coverImageUrl());
        m.put("version", p.version());
        return m;
    }

    static Map<String, Object> postItem(Post p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("slug", p.slug());
        m.put("title", p.title());
        m.put("excerpt", Text.excerpt(p.body()));
        m.put("tags", p.tags());
        m.put("author", author(p.authorId(), p.authorName()));
        m.put("publishedAt", Times.iso(p.publishedAt()));
        m.put("coverImageUrl", p.coverImageUrl());
        return m;
    }

    static Map<String, Object> tag(TagCount t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", t.name());
        m.put("posts", t.posts());
        return m;
    }

    static Map<String, Object> health(boolean dbUp) {
        String db = dbUp ? "UP" : "DOWN";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", db);
        m.put("checks", Map.of("database", db));
        return m;
    }

    static Map<String, Object> comment(Comment c) {
        Map<String, Object> moderation = new LinkedHashMap<>();
        moderation.put("label", c.moderation().label());
        moderation.put("confidence", c.moderation().confidence());
        moderation.put("provider", c.moderation().provider());
        moderation.put("fallback", c.moderation().fallback());
        moderation.put("reason", c.moderation().reason());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("postSlug", c.postSlug());
        m.put("parentId", c.parentId());
        m.put("author", author(c.authorId(), c.authorName()));
        m.put("body", c.body());
        m.put("status", c.status());
        m.put("moderation", moderation);
        m.put("createdAt", Times.iso(c.createdAt()));
        return m;
    }
}

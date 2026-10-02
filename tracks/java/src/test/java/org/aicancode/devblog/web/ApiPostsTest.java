package org.aicancode.devblog.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.aicancode.devblog.support.TestApp;
import org.aicancode.devblog.support.TestApp.Res;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M3: the post endpoints over HTTP. */
class ApiPostsTest {

    private static final Map<String, Object> POST =
            Map.of("title", "Hello DevBlog", "body", "First post. It has two sentences.", "tags", List.of("Java"));
    private final TestApp app = new TestApp();

    @Test
    void createPostReturns201LocationAndTheFullPost() {
        JsonNode ada = app.user("AUTHOR", "Ada");
        Res r = app.call("POST", "/api/v1/posts", POST, ada);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.header("Location")).isEqualTo("/api/v1/posts/hello-devblog");
        assertThat(r.json().toString()).isEqualTo("{\"id\":" + TestApp.id(r.json()) + ",\"slug\":\"hello-devblog\","
                + "\"title\":\"Hello DevBlog\",\"body\":\"First post. It has two sentences.\",\"status\":\"draft\","
                + "\"tags\":[\"java\"],\"author\":{\"id\":" + ada.get("user").get("id").asLong() + ",\"displayName\":\"Ada\"},"
                + "\"createdAt\":\"2026-10-02T09:30:00.000Z\",\"updatedAt\":\"2026-10-02T09:30:00.000Z\","
                + "\"publishedAt\":null,\"coverImageUrl\":null,\"version\":1}");
    }

    @Test
    void postWritesNeedATokenAndTheRightRole() {
        Res r = app.call("POST", "/api/v1/posts", POST, (String) null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.header("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(app.call("POST", "/api/v1/posts", POST, app.user()).status()).isEqualTo(403);
    }

    @Test
    void draftsAre404ForEveryoneElse() {
        JsonNode author = app.user("AUTHOR");
        String url = "/api/v1/posts/" + app.post(author, Map.of("status", "draft")).get("slug").asString();
        assertThat(app.get(url).status()).isEqualTo(404);
        assertThat(app.get(url, app.user("AUTHOR")).status()).isEqualTo(404);
        assertThat(app.get(url, author).status()).isEqualTo(200);
        assertThat(app.get(url, app.admin()).status()).isEqualTo(200);
        // a broken token is a 401, even on a public endpoint: never silently anonymous
        assertThat(app.call("GET", url, null, "Bearer x").status()).isEqualTo(401);
    }

    @Test
    void patchWithOptimisticLocking() {
        JsonNode author = app.user("AUTHOR");
        JsonNode post = app.post(author);
        String url = "/api/v1/posts/" + post.get("slug").asString();
        Res ok = app.call("PATCH", url, Map.of("version", 1, "title", "Edited"), author);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.json().get("version").asInt()).isEqualTo(2);
        assertThat(ok.json().get("slug").asString()).isEqualTo(post.get("slug").asString());
        Res stale = app.call("PATCH", url, Map.of("version", 1, "title", "Lost update"), author);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.json().get("type").asString()).endsWith("/version-conflict");
        assertThat(stale.json().get("detail").asString()).contains("current version 2");
        assertThat(app.call("PATCH", url, Map.of("version", 2, "title", "Not mine"), app.user("AUTHOR")).status()).isEqualTo(403);
    }

    @Test
    void deleteIs204Then404() {
        JsonNode author = app.user("AUTHOR");
        String url = "/api/v1/posts/" + app.post(author).get("slug").asString();
        assertThat(app.call("DELETE", url, null, (String) null).status()).isEqualTo(401);
        assertThat(app.call("DELETE", url, null, author).status()).isEqualTo(204);
        assertThat(app.get(url).status()).isEqualTo(404);
        assertThat(app.call("DELETE", url, null, author).status()).isEqualTo(404);
    }

    @Test
    void summaryEndpoint() {
        JsonNode post = app.post(app.user("AUTHOR"), Map.of("body", "Keyset pagination is fast. OFFSET is slow. Here is why."));
        String slug = post.get("slug").asString();
        assertThat(app.get("/api/v1/posts/" + slug + "/summary").json().toString()).isEqualTo("{\"slug\":\"" + slug + "\","
                + "\"provider\":\"template\",\"fallback\":false,\"summary\":\"Keyset pagination is fast. OFFSET is slow.\","
                + "\"generatedAt\":\"2026-10-02T09:30:00.000Z\"}");
        assertThat(app.get("/api/v1/posts/missing/summary").status()).isEqualTo(404);
    }
}

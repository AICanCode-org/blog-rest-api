package org.aicancode.devblog.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.aicancode.devblog.support.TestApp;
import org.aicancode.devblog.support.TestApp.Res;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M5: comments and the moderation queue over HTTP. */
class ApiCommentsTest {

    private final TestApp app = new TestApp();

    @Test
    void commentFlowWithModeration() {
        JsonNode author = app.user("AUTHOR");
        JsonNode reader = app.user("READER", "Rita");
        JsonNode admin = app.admin();
        String slug = app.post(author).get("slug").asString();
        String url = "/api/v1/posts/" + slug + "/comments";
        assertThat(app.call("POST", url, Map.of("body", "hi"), (String) null).status()).isEqualTo(401);
        Res ok = app.call("POST", url, Map.of("body", "Clear and useful."), reader);
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.json().toString()).isEqualTo("{\"id\":" + TestApp.id(ok.json()) + ",\"postSlug\":\"" + slug + "\","
                + "\"parentId\":null,\"author\":{\"id\":" + reader.get("user").get("id").asLong() + ",\"displayName\":\"Rita\"},"
                + "\"body\":\"Clear and useful.\",\"status\":\"visible\",\"moderation\":{\"label\":\"ok\",\"confidence\":0.6,"
                + "\"provider\":\"rules\",\"fallback\":false,\"reason\":\"no rule matched\"},"
                + "\"createdAt\":\"2026-10-02T09:30:00.000Z\"}");
        JsonNode held = app.call("POST", url, Map.of("body", "BUY NOW at www.cheap.example"), reader).json();
        assertThat(held.get("status").asString()).isEqualTo("held");
        assertThat(held.get("moderation").get("label").asString()).isEqualTo("spam");
        assertThat(app.get(url).json().get("items")).hasSize(1);

        JsonNode queue = app.get("/api/v1/admin/comments", admin).json().get("items");
        assertThat(queue).hasSize(1);
        assertThat(TestApp.id(queue.get(0))).isEqualTo(TestApp.id(held));
        assertThat(app.get("/api/v1/admin/comments", reader).status()).isEqualTo(403);
        Res decided = app.call("POST", "/api/v1/admin/comments/" + TestApp.id(held) + "/decision",
                Map.of("decision", "approve"), admin);
        assertThat(decided.status()).isEqualTo(200);
        assertThat(decided.json().get("status").asString()).isEqualTo("visible");
        assertThat(app.get(url).json().get("items")).hasSize(2);
        assertThat(app.get("/api/v1/admin/comments?status=nope", admin).status()).isEqualTo(400);
    }

    @Test
    void repliesAndDeleting() {
        JsonNode author = app.user("AUTHOR");
        JsonNode reader = app.user();
        String url = "/api/v1/posts/" + app.post(author).get("slug").asString() + "/comments";
        JsonNode top = app.call("POST", url, Map.of("body", "Question?"), reader).json();
        Res reply = app.call("POST", url, Map.of("body", "Answer.", "parentId", TestApp.id(top)), author);
        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.json().get("parentId").asLong()).isEqualTo(TestApp.id(top));
        assertThat(app.call("POST", url, Map.of("body", "x", "parentId", "1"), reader).status()).isEqualTo(400);
        assertThat(app.call("DELETE", "/api/v1/comments/" + TestApp.id(top), null, author).status()).isEqualTo(403);
        assertThat(app.call("DELETE", "/api/v1/comments/" + TestApp.id(top), null, reader).status()).isEqualTo(204);
        assertThat(app.call("DELETE", "/api/v1/comments/abc", null, reader).status()).isEqualTo(404);
        assertThat(app.get("/api/v1/posts/missing/comments").status()).isEqualTo(404);
    }
}

package org.aicancode.devblog.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.aicancode.devblog.support.TestApp;
import org.aicancode.devblog.support.TestApp.Res;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M2: the auth endpoints over HTTP - status codes, headers and JSON shapes from spec/openapi.yaml. */
class ApiAuthTest {

    private static final String PROBLEM = "application/problem+json";
    private final TestApp app = new TestApp();

    private Res post(String url, Object body) {
        return app.call("POST", url, body, (String) null);
    }

    @Test
    void registerLoginMe() {
        Res r = post("/api/v1/auth/register",
                Map.of("email", "Asha@DevBlog.test", "password", "password-123", "displayName", "Asha", "role", "ADMIN"));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json().toString()).isEqualTo("{\"id\":" + TestApp.id(r.json()) + ",\"email\":\"asha@devblog.test\","
                + "\"displayName\":\"Asha\",\"role\":\"READER\",\"createdAt\":\"2026-10-02T09:30:00.000Z\"}");
        JsonNode login = post("/api/v1/auth/login", Map.of("email", "asha@devblog.test", "password", "password-123")).json();
        assertThat(login.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(login.get("expiresIn").asInt()).isEqualTo(900);
        assertThat(login.get("user").get("role").asString()).isEqualTo("READER");
        assertThat(login.get("accessToken").asString().split("\\.")).hasSize(3);
        assertThat(login.get("refreshToken").asString()).isNotBlank();
        Res me = app.get("/api/v1/me", login);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().get("email").asString()).isEqualTo("asha@devblog.test");
    }

    @Test
    void errorsAreProblemJson() {
        Res r = post("/api/v1/auth/register", Map.of("email", "nope", "password", "password-123", "displayName", "A"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw().getContentType()).startsWith(PROBLEM);
        assertThat(r.json().get("type").asString()).isEqualTo("https://devblog.example/problems/validation-error");
        assertThat(r.json().get("detail").asString()).isEqualTo("email must be a valid e-mail address");
    }

    @Test
    void bodiesThatAreNotJsonObjectsAre400() {
        assertThat(post("/api/v1/auth/login", "not json").status()).isEqualTo(400);
        assertThat(post("/api/v1/auth/login", List.of("a", "b")).status()).isEqualTo(400);
    }

    @Test
    void duplicateEmailIs409() {
        app.user();
        Res r = post("/api/v1/auth/register", Map.of("email", "USER1@devblog.test", "password", "password-123",
                "displayName", "X"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.json().get("type").asString()).endsWith("/email-taken");
    }

    @Test
    void badLoginIs401InvalidCredentials() {
        app.user();
        Res r = post("/api/v1/auth/login", Map.of("email", "user1@devblog.test", "password", "wrong-password"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.json().get("type").asString()).endsWith("/invalid-credentials");
    }

    @Test
    void meWithoutOrWithABadTokenIs401WithWwwAuthenticate() {
        for (String header : Arrays.asList(null, "Bearer nope", "Basic dXNlcjpwYXNz")) {
            Res r = app.call("GET", "/api/v1/me", null, header);
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.header("WWW-Authenticate")).isEqualTo("Bearer");
            assertThat(r.json().get("type").asString()).endsWith("/unauthorized");
        }
    }

    @Test
    void expiredAccessTokenIs401() {
        JsonNode session = app.user();
        app.clock.advance(Duration.ofMinutes(16));
        assertThat(app.get("/api/v1/me", session).status()).isEqualTo(401);
    }

    @Test
    void refreshAndLogout() {
        JsonNode session = app.user();
        Map<String, Object> body = Map.of("refreshToken", session.get("refreshToken").asString());
        Res renewed = post("/api/v1/auth/refresh", body);
        assertThat(renewed.status()).isEqualTo(200);
        assertThat(renewed.json().get("refreshToken").asString()).isNotEqualTo(session.get("refreshToken").asString());
        Res reused = post("/api/v1/auth/refresh", body);
        assertThat(reused.status()).isEqualTo(401);
        assertThat(reused.json().get("type").asString()).endsWith("/invalid-refresh-token");
        assertThat(post("/api/v1/auth/logout", Map.of("refreshToken", "whatever")).status()).isEqualTo(204);
        assertThat(post("/api/v1/auth/logout", Map.of()).status()).isEqualTo(400);
    }

    @Test
    void adminPromotesAReaderWhoThenRefreshesIntoTheNewRole() {
        JsonNode reader = app.user();
        JsonNode admin = app.admin();
        String url = "/api/v1/admin/users/" + reader.get("user").get("id").asLong() + "/role";
        assertThat(app.call("PATCH", url, Map.of("role", "AUTHOR"), reader).status()).isEqualTo(403);
        Res r = app.call("PATCH", url, Map.of("role", "AUTHOR"), admin);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("role").asString()).isEqualTo("AUTHOR");
        JsonNode renewed = post("/api/v1/auth/refresh", Map.of("refreshToken", reader.get("refreshToken").asString())).json();
        assertThat(renewed.get("user").get("role").asString()).isEqualTo("AUTHOR");
        assertThat(app.call("PATCH", "/api/v1/admin/users/abc/role", Map.of("role", "AUTHOR"), admin).status()).isEqualTo(404);
        assertThat(app.call("PATCH", "/api/v1/admin/users/999/role", Map.of("role", "AUTHOR"), admin).status()).isEqualTo(404);
    }
}

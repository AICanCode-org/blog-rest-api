package org.aicancode.devblog.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.aicancode.devblog.db.DatabaseUrl;
import org.aicancode.devblog.repo.JdbcUserRepository;
import org.aicancode.devblog.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration tests: the real app on a random port against a real PostgreSQL (docker compose up -d postgres).
 * Run with: mvn verify -Pintegration. Uses TEST_DATABASE_URL so your dev data is never touched.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {

    static final String DB_URL = env("TEST_DATABASE_URL", "postgresql://devblog:devblog@localhost:5432/devblog_test");
    static final String ADMIN_EMAIL = "admin@devblog.test";
    static final String ADMIN_PASSWORD = "admin-password";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", () -> DB_URL);
        registry.add("JWT_SECRET", () -> "integration-test-secret-that-is-long-enough-123");
        registry.add("BCRYPT_COST", () -> "4");
        registry.add("ADMIN_EMAIL", () -> ADMIN_EMAIL);
        registry.add("ADMIN_PASSWORD", () -> ADMIN_PASSWORD);
        registry.add("AI_PROVIDER", () -> "offline");
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    AuthService auth;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbcClient.sql("TRUNCATE users, refresh_tokens, posts, tags, post_tags, comments RESTART IDENTITY CASCADE").update();
        auth.ensureAdmin(ADMIN_EMAIL, ADMIN_PASSWORD); // what the app does at startup
    }

    record Res(int status, JsonNode json) {
    }

    private Res send(String method, String path, Object body, JsonNode session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (session != null) {
            b.header("Authorization", "Bearer " + session.get("accessToken").asString());
        }
        if (body != null) {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body().isEmpty() ? null : JSON.readTree(r.body()));
    }

    private JsonNode login(String email, String password) throws Exception {
        Res r = send("POST", "/api/v1/auth/login", Map.of("email", email, "password", password), null);
        assertThat(r.status()).isEqualTo(200);
        return r.json();
    }

    private JsonNode makeUser(String email, String role) throws Exception {
        Res r = send("POST", "/api/v1/auth/register",
                Map.of("email", email, "password", "password-123", "displayName", email.substring(0, 5)), null);
        assertThat(r.status()).isEqualTo(201);
        if (!"READER".equals(role)) {
            send("PATCH", "/api/v1/admin/users/" + r.json().get("id").asLong() + "/role", Map.of("role", role),
                    login(ADMIN_EMAIL, ADMIN_PASSWORD));
        }
        return login(email, "password-123");
    }

    private String createPost(JsonNode author, Map<String, Object> body) throws Exception {
        Res r = send("POST", "/api/v1/posts", body, author);
        assertThat(r.status()).isEqualTo(201);
        return r.json().get("slug").asString();
    }

    private static List<Long> ids(JsonNode items) {
        List<Long> out = new ArrayList<>();
        items.forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    @Test
    void healthAndMigrations() throws Exception {
        assertThat(send("GET", "/health", null, null).json().toString())
                .isEqualTo("{\"status\":\"UP\",\"checks\":{\"database\":\"UP\"}}");
        assertThat(jdbcClient.sql("SELECT version FROM schema_migrations ORDER BY version").query(Integer.class).list())
                .startsWith(1, 2);
    }

    @Test
    void passwordsAndRefreshTokensAreNeverStoredInClear() throws Exception {
        JsonNode session = makeUser("asha@devblog.test", "READER");
        String hash = jdbcClient.sql("SELECT password_hash FROM users WHERE email = 'asha@devblog.test'")
                .query(String.class).single();
        assertThat(hash).startsWith("$2").doesNotContain("password-123");
        List<String> hashes = jdbcClient.sql("SELECT token_hash FROM refresh_tokens").query(String.class).list();
        assertThat(hashes).doesNotContain(session.get("refreshToken").asString()).allMatch(h -> h.length() == 64);
    }

    @Test
    void uniqueEmailIsEnforcedByTheDatabase() throws Exception {
        makeUser("dup@devblog.test", "READER");
        assertThat(send("POST", "/api/v1/auth/register",
                Map.of("email", "DUP@devblog.test", "password", "password-123", "displayName", "D"), null).status())
                .isEqualTo(409);
    }

    @Test
    void concurrentRefreshesWithOneTokenLetExactlyOneWin() throws Exception {
        JsonNode session = makeUser("race@devblog.test", "READER");
        Map<String, Object> body = Map.of("refreshToken", session.get("refreshToken").asString());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(() -> send("POST", "/api/v1/auth/refresh", body, null).status());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 401, 401, 401);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void slugUniquenessAndTagsInTheDatabase() throws Exception {
        JsonNode author = makeUser("ada@devblog.test", "AUTHOR");
        Map<String, Object> body = Map.of("title", "Same Title", "body", "Body.", "status", "published",
                "tags", List.of("java", "sql"));
        List<String> slugs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            slugs.add(createPost(author, body));
        }
        assertThat(slugs).containsExactly("same-title", "same-title-2", "same-title-3");
        assertThat(jdbcClient.sql("SELECT count(*) FROM tags").query(Long.class).single()).isEqualTo(2L);
        assertThat(send("GET", "/api/v1/tags", null, null).json().get("items").toString())
                .isEqualTo("[{\"name\":\"java\",\"posts\":3},{\"name\":\"sql\",\"posts\":3}]");
    }

    @Test
    void fullTextSearchUsesEnglishStemming() throws Exception {
        JsonNode author = makeUser("ada@devblog.test", "AUTHOR");
        createPost(author, Map.of("title", "Testing with containers", "body", "We run tests against PostgreSQL.",
                "status", "published"));
        createPost(author, Map.of("title", "Cooking", "body", "Pasta.", "status", "published"));
        JsonNode found = send("GET", "/api/v1/posts?q=test%20container", null, null).json().get("items");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("title").asString()).isEqualTo("Testing with containers");
        String q = URLEncoder.encode("\"exact phrase\" -pasta", StandardCharsets.UTF_8);
        assertThat(send("GET", "/api/v1/posts?q=" + q, null, null).status()).isEqualTo(200);
    }

    @Test
    void keysetPaginationOverTheRealIndex() throws Exception {
        JsonNode author = makeUser("ada@devblog.test", "AUTHOR");
        for (int i = 0; i < 25; i++) {
            createPost(author, Map.of("title", "P" + i, "body", "x.", "status", "published"));
        }
        jdbcClient.sql("UPDATE posts SET published_at = '2026-10-02T09:30:00Z'").update(); // the id must break ties
        List<Long> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = send("GET", "/api/v1/posts?limit=10" + (cursor == null ? "" : "&cursor=" + cursor), null, null)
                    .json();
            seen.addAll(ids(page.get("items")));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
        } while (cursor != null);
        assertThat(seen).hasSize(25);
        assertThat(new HashSet<>(seen)).hasSize(25);
        assertThat(seen).isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }

    @Test
    void versionConflictIsDetectedByTheUpdateStatement() throws Exception {
        JsonNode author = makeUser("ada@devblog.test", "AUTHOR");
        String slug = createPost(author, Map.of("title", "Race", "body", "v1"));
        assertThat(send("PATCH", "/api/v1/posts/" + slug, Map.of("version", 1, "body", "v2"), author).status()).isEqualTo(200);
        assertThat(send("PATCH", "/api/v1/posts/" + slug, Map.of("version", 1, "body", "v3"), author).status()).isEqualTo(409);
        Map<String, Object> row = jdbcClient.sql("SELECT body, version FROM posts WHERE slug = ?").param(slug)
                .query().singleRow();
        assertThat(row).containsEntry("body", "v2").containsEntry("version", 2);
    }

    @Test
    void softDeleteKeepsTheRow() throws Exception {
        JsonNode author = makeUser("ada@devblog.test", "AUTHOR");
        String slug = createPost(author, Map.of("title", "Gone", "body", "b", "status", "published"));
        assertThat(send("DELETE", "/api/v1/posts/" + slug, null, author).status()).isEqualTo(204);
        assertThat(send("GET", "/api/v1/posts/" + slug, null, null).status()).isEqualTo(404);
        assertThat(jdbcClient.sql("SELECT deleted_at IS NOT NULL FROM posts WHERE slug = ?").param(slug)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void commentsAndModerationArePersisted() throws Exception {
        JsonNode author = makeUser("ada@devblog.test", "AUTHOR");
        JsonNode reader = makeUser("rita@devblog.test", "READER");
        String url = "/api/v1/posts/" + createPost(author, Map.of("title", "Talk", "body", "b", "status", "published"))
                + "/comments";
        JsonNode ok = send("POST", url, Map.of("body", "Nice"), reader).json();
        JsonNode held = send("POST", url, Map.of("body", "see https://a.example https://b.example"), reader).json();
        assertThat(held.get("moderation").toString()).isEqualTo(
                "{\"label\":\"spam\",\"confidence\":0.75,\"provider\":\"rules\",\"fallback\":false,\"reason\":\"2 links\"}");
        Res reply = send("POST", url, Map.of("body", "Thanks", "parentId", ok.get("id").asLong()), author);
        assertThat(reply.status()).isEqualTo(201);
        assertThat(ids(send("GET", url, null, null).json().get("items")))
                .containsExactly(ok.get("id").asLong(), reply.json().get("id").asLong());
        JsonNode admin = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        assertThat(ids(send("GET", "/api/v1/admin/comments", null, admin).json().get("items")))
                .containsExactly(held.get("id").asLong());
        send("POST", "/api/v1/admin/comments/" + held.get("id").asLong() + "/decision", Map.of("decision", "reject"), admin);
        Map<String, Object> row = jdbcClient
                .sql("SELECT status, moderated_by IS NOT NULL AS moderated FROM comments WHERE id = ?")
                .param(held.get("id").asLong()).query().singleRow();
        assertThat(row).containsEntry("status", "rejected").containsEntry("moderated", true);
    }

    @Test
    void coverImageUrlIsStoredByTheV2Migration() throws Exception {
        JsonNode author = makeUser("ada@devblog.test", "AUTHOR");
        String cover = "https://img.example/c.png";
        String slug = createPost(author, Map.of("title", "Cover", "body", "b", "status", "published", "coverImageUrl", cover));
        assertThat(jdbcClient.sql("SELECT cover_image_url FROM posts WHERE slug = ?").param(slug).query(String.class).single())
                .isEqualTo(cover);
        assertThat(send("GET", "/api/v1/posts", null, null).json().get("items").get(0).get("coverImageUrl").asString())
                .isEqualTo(cover);
    }

    @Test
    void adminIsBootstrappedFromTheEnvironment() {
        assertThat(jdbcClient.sql("SELECT role FROM users WHERE email = ?").param(ADMIN_EMAIL).query(String.class).single())
                .isEqualTo("ADMIN");
    }

    @Test
    void healthReportsDownWhenTheDatabaseIsUnreachable() {
        DatabaseUrl url = DatabaseUrl.parse(DB_URL);
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url.jdbcUrl());
        ds.setUsername(url.username());
        ds.setPassword(url.password());
        ds.setConnectionTimeout(1000);
        JdbcUserRepository repo = new JdbcUserRepository(JdbcClient.create(ds));
        assertThat(repo.ping()).isTrue();
        ds.close(); // simulate the database going away: every new connection now fails
        assertThat(repo.ping()).isFalse();
    }
}

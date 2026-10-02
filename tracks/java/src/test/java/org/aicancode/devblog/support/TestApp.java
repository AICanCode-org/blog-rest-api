package org.aicancode.devblog.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aicancode.devblog.ai.ModerationService;
import org.aicancode.devblog.ai.RuleModerator;
import org.aicancode.devblog.ai.SummaryService;
import org.aicancode.devblog.ai.TemplateSummarizer;
import org.aicancode.devblog.security.PasswordHasher;
import org.aicancode.devblog.security.TokenService;
import org.aicancode.devblog.service.AuthService;
import org.aicancode.devblog.service.CommentService;
import org.aicancode.devblog.service.PostService;
import org.aicancode.devblog.web.AuthController;
import org.aicancode.devblog.web.CommentController;
import org.aicancode.devblog.web.HealthController;
import org.aicancode.devblog.web.PostController;
import org.aicancode.devblog.web.ProblemHandler;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real controllers on in-memory adapters (no database, no network, no Spring context), plus helpers that
 * read like the user stories. The same helpers exist in the Python (conftest.py) and TypeScript (helpers.ts) tracks.
 */
public class TestApp {

    public static final String TEST_SECRET = "test-secret-that-is-at-least-32-bytes-long";
    public static final JsonMapper JSON = JsonMapper.builder().build();

    public final InMemoryStore store = new InMemoryStore();
    public final MutableClock clock = new MutableClock();
    public final MockMvc mvc;
    private int n;

    public TestApp() {
        AuthService auth = auth(store, clock);
        auth.ensureAdmin("admin@devblog.test", "admin-password");
        PostService posts = new PostService(store, clock, new SummaryService(new TemplateSummarizer(), null));
        CommentService comments = new CommentService(store, store, new ModerationService(new RuleModerator(), null), clock);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth), new PostController(auth, posts, clock),
                        new CommentController(auth, comments), new HealthController(auth))
                .setControllerAdvice(new ProblemHandler())
                .build();
    }

    public static AuthService auth(InMemoryStore store, Clock clock) {
        return new AuthService(store, new PasswordHasher(4), new TokenService(TEST_SECRET, clock), clock);
    }

    /** A response: status, headers and the parsed JSON body (null when there is no body). */
    public record Res(int status, MockHttpServletResponse raw, JsonNode json) {
        public String header(String name) {
            return raw.getHeader(name);
        }
    }

    public Res call(String method, String url, Object body, String authorization) {
        try {
            MockHttpServletRequestBuilder b = request(HttpMethod.valueOf(method), url);
            if (body instanceof String raw) {
                b.contentType(MediaType.APPLICATION_JSON).content(raw);
            } else if (body != null) {
                b.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
            }
            if (authorization != null) {
                b.header("Authorization", authorization);
            }
            MockHttpServletResponse r = mvc.perform(b).andReturn().getResponse();
            String text = r.getContentAsString();
            return new Res(r.getStatus(), r, text.isEmpty() ? null : JSON.readTree(text));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Res call(String method, String url, Object body, JsonNode session) {
        return call(method, url, body, session == null ? null : "Bearer " + session.get("accessToken").asString());
    }

    public Res get(String url) {
        return call("GET", url, null, (String) null);
    }

    public Res get(String url, JsonNode session) {
        return call("GET", url, null, session);
    }

    public JsonNode user(String role, String name) {
        n++;
        String email = "user" + n + "@devblog.test";
        Res r = call("POST", "/api/v1/auth/register",
                Map.of("email", email, "password", "password-123", "displayName", name == null ? "User " + n : name),
                (String) null);
        assertThat(r.status()).as(r.raw().toString()).isEqualTo(201);
        if (!"READER".equals(role)) {
            store.setRole(r.json().get("id").asLong(), role);
        }
        return login(email, "password-123");
    }

    public JsonNode user(String role) {
        return user(role, null);
    }

    public JsonNode user() {
        return user("READER", null);
    }

    public JsonNode admin() {
        return login("admin@devblog.test", "admin-password");
    }

    public JsonNode login(String email, String password) {
        Res r = call("POST", "/api/v1/auth/login", Map.of("email", email, "password", password), (String) null);
        assertThat(r.status()).isEqualTo(200);
        return r.json();
    }

    public JsonNode post(JsonNode session, Map<String, Object> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Hello DevBlog");
        body.put("body", "First post. It has two sentences.");
        body.put("status", "published");
        body.putAll(fields);
        Res r = call("POST", "/api/v1/posts", body, session);
        assertThat(r.status()).isEqualTo(201);
        return r.json();
    }

    public JsonNode post(JsonNode session) {
        return post(session, Map.of());
    }

    public static long id(JsonNode node) {
        return node.get("id").asLong();
    }
}

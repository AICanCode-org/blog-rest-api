package org.aicancode.devblog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.aicancode.devblog.ai.CircuitBreaker;
import org.aicancode.devblog.ai.LlmClient;
import org.aicancode.devblog.ai.LlmModerator;
import org.aicancode.devblog.ai.ModerationService;
import org.aicancode.devblog.ai.RuleModerator;
import org.aicancode.devblog.ai.SummaryService;
import org.aicancode.devblog.ai.TemplateSummarizer;
import org.aicancode.devblog.metrics.Metrics;
import org.aicancode.devblog.support.TestApp;
import org.aicancode.devblog.support.TestApp.Res;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** v1.1 (Phase 7): request IDs, /metrics and the LLM circuit breaker. */
class ObservabilityTest {

    private static long metric(TestApp app, String name) {
        return rawBody(app).lines().filter(l -> l.startsWith(name + " ")).map(l -> Long.parseLong(l.split(" ")[1])).findFirst()
                .orElseThrow();
    }

    private static String rawBody(TestApp app) {
        try {
            return app.get("/metrics").raw().getContentAsString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aSafeRequestIdIsEchoedAndAMissingOneIsMinted() throws Exception {
        TestApp app = new TestApp();
        var r = app.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/health")
                .header("X-Request-ID", "abc-123")).andReturn().getResponse();
        assertThat(r.getHeader("X-Request-ID")).isEqualTo("abc-123");
        assertThat(app.get("/health").header("X-Request-ID")).matches("[0-9a-f-]{36}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "evil\u00e9", "a;b",
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void unsafeRequestIdsAreReplaced(String value) {
        assertThat(RequestIdFilter.incomingOrNewId(value)).isNotEqualTo(value);
    }

    @Test
    void metricsListEveryCounter() {
        TestApp app = new TestApp();
        Res r = app.get("/metrics");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("Content-Type")).startsWith("text/plain");
        String body = rawBody(app);
        for (String name : Metrics.HELP.keySet()) {
            assertThat(body).contains("# TYPE " + name + " counter").contains("\n" + name + " 0");
        }
    }

    @Test
    void businessCounters() {
        TestApp app = new TestApp();
        JsonNode author = app.user("AUTHOR", null);
        JsonNode reader = app.user();
        app.call("POST", "/api/v1/auth/login", Map.of("email", "user1@devblog.test", "password", "wrong-password"),
                (String) null);
        JsonNode published = app.post(author);
        JsonNode draft = app.post(author, Map.of("status", "draft", "title", "Draft"));
        String url = "/api/v1/posts/" + draft.get("slug").asString();
        app.call("PATCH", url, Map.of("version", 1, "status", "published"), author);
        app.call("PATCH", url, Map.of("version", 2, "status", "draft"), author);
        app.call("PATCH", url, Map.of("version", 3, "status", "published"), author); // re-publish: not new
        String comments = "/api/v1/posts/" + published.get("slug").asString() + "/comments";
        app.call("POST", comments, Map.of("body", "Nice"), reader);
        app.call("POST", comments, Map.of("body", "click here"), reader);
        assertThat(metric(app, Metrics.USERS_REGISTERED)).isEqualTo(2);
        assertThat(metric(app, Metrics.LOGINS_FAILED)).isEqualTo(1);
        assertThat(metric(app, Metrics.POSTS_PUBLISHED)).isEqualTo(2);
        assertThat(metric(app, Metrics.COMMENTS_CREATED)).isEqualTo(2);
        assertThat(metric(app, Metrics.COMMENTS_HELD)).isEqualTo(1);
        assertThat(metric(app, Metrics.AI_FALLBACK)).isZero();
    }

    @Test
    void fallbacksAreCounted() {
        LlmClient broken = (s, u, n) -> {
            throw new java.net.http.HttpTimeoutException("LLM timed out");
        };
        TestApp app = new TestApp(new ModerationService(new RuleModerator(), new LlmModerator(broken)),
                new SummaryService(new TemplateSummarizer(), new org.aicancode.devblog.ai.LlmSummarizer(broken)),
                new Metrics());
        JsonNode post = app.post(app.user("AUTHOR", null));
        String slug = post.get("slug").asString();
        app.call("POST", "/api/v1/posts/" + slug + "/comments", Map.of("body", "Nice"), app.user());
        assertThat(app.get("/api/v1/posts/" + slug + "/summary").json().get("fallback").asBoolean()).isTrue();
        assertThat(app.metrics.value(Metrics.AI_FALLBACK)).isEqualTo(2);
    }

    @Test
    void unknownMetricNamesAreRejected() {
        assertThatThrownBy(() -> new Metrics().inc("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- circuit breaker ----
    private static final class FakeLlm implements LlmClient {
        int calls;
        boolean fail = true;

        @Override
        public String chat(String system, String user, int maxTokens) throws Exception {
            calls++;
            if (fail) {
                throw new java.net.http.HttpTimeoutException("timed out");
            }
            return "ok";
        }
    }

    private final FakeLlm llm = new FakeLlm();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AtomicInteger skipped = new AtomicInteger();
    private final CircuitBreaker breaker = new CircuitBreaker(llm, 3, 30_000, now::get, skipped::incrementAndGet);

    private String call() throws Exception {
        return breaker.chat("s", "u", 10);
    }

    @Test
    void breakerOpensAfterThreeFailuresInARowAndSkipsCalls() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(this::call).hasMessage("timed out");
        }
        assertThat(breaker.state()).isEqualTo("open");
        assertThatThrownBy(this::call).isInstanceOf(CircuitBreaker.OpenException.class);
        assertThat(llm.calls).isEqualTo(3);
        assertThat(skipped.get()).isEqualTo(1);
    }

    @Test
    void aSuccessResetsTheFailureCount() throws Exception {
        List<String> answers = new ArrayList<>();
        for (boolean fail : new boolean[] {true, true, false, true, true}) {
            llm.fail = fail;
            try {
                answers.add(call());
            } catch (java.net.http.HttpTimeoutException expected) {
                answers.add("failed");
            }
        }
        assertThat(answers).containsExactly("failed", "failed", "ok", "failed", "failed");
        assertThat(breaker.state()).isEqualTo("closed");
    }

    @Test
    void halfOpenTrialSuccessClosesAndFailureReopens() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(this::call).hasMessage("timed out");
        }
        now.addAndGet(30_000);
        assertThat(breaker.state()).isEqualTo("half-open");
        assertThatThrownBy(this::call).hasMessage("timed out");
        assertThat(breaker.state()).isEqualTo("open");
        assertThat(llm.calls).isEqualTo(4);
        now.addAndGet(30_000);
        llm.fail = false;
        assertThat(call()).isEqualTo("ok");
        assertThat(breaker.state()).isEqualTo("closed");
    }

    @Test
    void theRealWiringShortCircuitsAnUnreachableLlm() {
        Metrics metrics = new Metrics();
        LlmClient http = new org.aicancode.devblog.ai.HttpLlmClient("http://127.0.0.1:9/v1", "m", "", 500);
        LlmClient guarded = new CircuitBreaker(http, () -> metrics.inc(Metrics.LLM_SHORT_CIRCUITS));
        TestApp app = new TestApp(new ModerationService(new RuleModerator(), new LlmModerator(guarded)),
                new SummaryService(new TemplateSummarizer(), null), metrics);
        JsonNode post = app.post(app.user("AUTHOR", null));
        JsonNode reader = app.user();
        for (int i = 0; i < 4; i++) {
            Res r = app.call("POST", "/api/v1/posts/" + post.get("slug").asString() + "/comments", Map.of("body", "c" + i),
                    reader);
            assertThat(r.status()).isEqualTo(201);
            assertThat(r.json().get("moderation").get("fallback").asBoolean()).isTrue();
        }
        assertThat(metrics.value(Metrics.LLM_SHORT_CIRCUITS)).isEqualTo(1);
        assertThat(metrics.value(Metrics.AI_FALLBACK)).isEqualTo(4);
    }
}

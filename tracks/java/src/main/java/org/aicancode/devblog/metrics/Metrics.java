package org.aicancode.devblog.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v1.1: hand-rolled Prometheus counters (text exposition format 0.0.4).
 * No client library on purpose: /metrics is just text. Names are identical in all three tracks.
 */
public class Metrics {

    public static final String USERS_REGISTERED = "devblog_users_registered_total";
    public static final String LOGINS_FAILED = "devblog_logins_failed_total";
    public static final String POSTS_PUBLISHED = "devblog_posts_published_total";
    public static final String COMMENTS_CREATED = "devblog_comments_created_total";
    public static final String COMMENTS_HELD = "devblog_comments_held_total";
    public static final String AI_FALLBACK = "devblog_ai_fallback_total";
    public static final String LLM_SHORT_CIRCUITS = "devblog_llm_short_circuits_total";

    public static final Map<String, String> HELP = new LinkedHashMap<>();

    static {
        HELP.put(USERS_REGISTERED, "Accounts created with POST /auth/register.");
        HELP.put(LOGINS_FAILED, "Login attempts answered with 401.");
        HELP.put(POSTS_PUBLISHED, "Posts published for the first time.");
        HELP.put(COMMENTS_CREATED, "Comments created (visible or held).");
        HELP.put(COMMENTS_HELD, "Comments the moderator put on hold for a human.");
        HELP.put(AI_FALLBACK, "Moderation or summary calls that fell back to the offline path.");
        HELP.put(LLM_SHORT_CIRCUITS, "LLM calls skipped because the circuit breaker was open.");
    }

    private final Map<String, AtomicLong> values = new LinkedHashMap<>();

    public Metrics() {
        HELP.keySet().forEach(name -> values.put(name, new AtomicLong()));
    }

    public void inc(String name) {
        inc(name, 1);
    }

    public void inc(String name, long amount) {
        AtomicLong counter = values.get(name);
        if (counter == null) {
            throw new IllegalArgumentException("unknown metric " + name);
        }
        counter.addAndGet(amount);
    }

    public long value(String name) {
        return values.get(name).get();
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        HELP.forEach((name, help) -> sb.append("# HELP ").append(name).append(' ').append(help).append('\n')
                .append("# TYPE ").append(name).append(" counter\n")
                .append(name).append(' ').append(values.get(name).get()).append('\n'));
        return sb.toString();
    }
}

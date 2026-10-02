package org.aicancode.devblog.support;

import java.util.Map;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * PENDING test classes describe behaviour you have not built yet, so they are skipped
 * (Maven reports them as "Skipped"). When you start a milestone, delete its line from
 * {@link #PENDING}, watch the tests fail (red), implement until they pass (green).
 * Registered for every test automatically (see src/test/resources/junit-platform.properties).
 */
public class Pending implements ExecutionCondition {

    /** Test class simple name -> milestone. */
    static final Map<String, String> PENDING = Map.ofEntries(
            Map.entry("ValidationTest", "Phase 3 M1 - validation"),
            Map.entry("TextTest", "Phase 3 M1 - slugs, excerpts, cursors"),
            Map.entry("SecurityTest", "Phase 3 M2 - passwords and tokens"),
            Map.entry("AuthServiceTest", "Phase 3 M2 - AuthService"),
            Map.entry("ApiAuthTest", "Phase 3 M2 - auth endpoints"),
            Map.entry("PostServiceTest", "Phase 3 M3 - PostService"),
            Map.entry("ApiPostsTest", "Phase 3 M3 - post endpoints"),
            Map.entry("FeedTest", "Phase 3 M4 - feed, search, tags"),
            Map.entry("ModerationTest", "Phase 3 M5 - AI moderation"),
            Map.entry("SummaryTest", "Phase 3 M5 - AI summaries"),
            Map.entry("CommentServiceTest", "Phase 3 M5 - CommentService"),
            Map.entry("ApiCommentsTest", "Phase 3 M5 - comment endpoints"),
            Map.entry("ApiIntegrationTest", "Phase 4 - integration tests"));

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return context.getTestClass()
                .map(c -> PENDING.get(c.getSimpleName()))
                .map(reason -> ConditionEvaluationResult.disabled("PENDING (" + reason + ") - see support/Pending.java"))
                .orElse(ConditionEvaluationResult.enabled("not pending"));
    }
}

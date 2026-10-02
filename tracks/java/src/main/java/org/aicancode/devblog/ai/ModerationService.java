package org.aicancode.devblog.ai;

import org.aicancode.devblog.domain.Models.Moderation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comment moderation: the rules by default (offline), or an LLM with the rules as fallback (fallback=true).
 * The AI can only put a comment on hold; a human decides.
 */
public class ModerationService {

    public static final double HOLD_THRESHOLD = 0.7;
    private static final Logger LOG = LoggerFactory.getLogger(ModerationService.class);

    private final RuleModerator rules;
    private final Moderator llm;

    public ModerationService(RuleModerator rules, Moderator llm) {
        this.rules = rules;
        this.llm = llm;
    }

    public Moderation moderate(String text) {
        if (llm != null) {
            try {
                Verdict v = llm.classify(text);
                return new Moderation(v.label(), v.confidence(), "llm", false, v.reason());
            } catch (Exception e) { // any LLM failure -> graceful fallback
                LOG.warn("LLM moderation failed, using rules: {}", e.toString());
                Verdict v = rules.classify(text);
                return new Moderation(v.label(), v.confidence(), "rules", true, v.reason());
            }
        }
        Verdict v = rules.classify(text);
        return new Moderation(v.label(), v.confidence(), "rules", false, v.reason());
    }

    public static boolean shouldHold(Moderation m) {
        return !"ok".equals(m.label()) && m.confidence() >= HOLD_THRESHOLD;
    }
}

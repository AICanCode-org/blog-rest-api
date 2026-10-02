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
        // TODO(phase-3 M5): LLM first if configured; ANY exception -> rules with fallback=true (log a warning). No
        // LLM -> rules with fallback=false.
        throw new UnsupportedOperationException("TODO(phase-3 M5): ModerationService.moderate");
    }

    public static boolean shouldHold(Moderation m) {
        // TODO(phase-3 M5): label != ok AND confidence >= HOLD_THRESHOLD (0.7).
        throw new UnsupportedOperationException("TODO(phase-3 M5): ModerationService.shouldHold");
    }
}

package org.aicancode.devblog.ai;

import java.util.List;
import java.util.regex.Pattern;

/** Deterministic and offline. The term lists and the order of the checks are part of the spec. */
public class RuleModerator implements Moderator {

    public static final List<String> TOXIC_TERMS =
            List.of("idiot", "stupid", "moron", "dumb", "loser", "shut up", "trash", "hate you");
    public static final List<String> SPAM_PHRASES =
            List.of("buy now", "free money", "click here", "limited offer", "work from home", "giveaway");
    private static final Pattern LINK = Pattern.compile("https?://|www\\.");

    @Override
    public Verdict classify(String text) {
        // TODO(phase-3 M5): see the moderation tests: toxic words (whole words) 0.9, spam phrases 0.9, 2+ links
        // spam 0.75 'N links', otherwise ok 0.6 'no rule matched'.
        throw new UnsupportedOperationException("TODO(phase-3 M5): RuleModerator.classify");
    }
}

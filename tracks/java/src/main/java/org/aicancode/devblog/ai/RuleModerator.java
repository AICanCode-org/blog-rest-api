package org.aicancode.devblog.ai;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
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
        String lower = text.toLowerCase(Locale.ROOT);
        for (String term : TOXIC_TERMS) {
            if (Pattern.compile("\\b" + Pattern.quote(term) + "\\b").matcher(lower).find()) {
                return new Verdict("toxic", 0.9, "toxic language: " + term);
            }
        }
        for (String phrase : SPAM_PHRASES) {
            if (lower.contains(phrase)) {
                return new Verdict("spam", 0.9, "spam phrase: " + phrase);
            }
        }
        Matcher m = LINK.matcher(lower);
        int links = 0;
        while (m.find()) {
            links++;
        }
        if (links >= 2) {
            return new Verdict("spam", 0.75, links + " links");
        }
        return new Verdict("ok", 0.6, "no rule matched");
    }
}

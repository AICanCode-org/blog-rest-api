package org.aicancode.devblog.ai;

import java.util.Arrays;
import java.util.regex.Pattern;
import org.aicancode.devblog.domain.Text;

/** The first two sentences of the post, at most 280 characters. Deterministic and offline. */
public class TemplateSummarizer implements Summarizer {

    public static final int SUMMARY_MAX = 280;
    private static final Pattern SENTENCE_END = Pattern.compile("(?U)(?<=[.!?])\\s+");

    @Override
    public String summarize(String title, String body) {
        String text = Text.plainText(body);
        if (text.isEmpty()) {
            return Text.shorten(title, SUMMARY_MAX);
        }
        String[] sentences = SENTENCE_END.split(text);
        return Text.shorten(String.join(" ", Arrays.copyOf(sentences, Math.min(2, sentences.length))), SUMMARY_MAX);
    }
}

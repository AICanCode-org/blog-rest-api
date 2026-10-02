package org.aicancode.devblog.ai;

import java.util.regex.Pattern;

/** The first two sentences of the post, at most 280 characters. Deterministic and offline. */
public class TemplateSummarizer implements Summarizer {

    public static final int SUMMARY_MAX = 280;
    private static final Pattern SENTENCE_END = Pattern.compile("(?U)(?<=[.!?])\\s+");

    @Override
    public String summarize(String title, String body) {
        // TODO(phase-3 M5): deterministic: the first two sentences of plain_text(body), at most 280
        //   characters; an empty body -> the title. Exact cases: see the summary tests.
        throw new UnsupportedOperationException("TODO(phase-3 M5): TemplateSummarizer.summarize");
    }
}

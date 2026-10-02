package org.aicancode.devblog.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Post TL;DR: the template by default (offline), or an LLM with the template as fallback (fallback=true). */
public class SummaryService {

    private static final Logger LOG = LoggerFactory.getLogger(SummaryService.class);

    private final TemplateSummarizer template;
    private final Summarizer llm;

    public SummaryService(TemplateSummarizer template, Summarizer llm) {
        this.template = template;
        this.llm = llm;
    }

    public record Result(String provider, boolean fallback, String summary) {
    }

    public Result summarize(String title, String body) {
        // TODO(phase-3 M5): LLM if configured, ANY exception -> template with fallback=true (log a warning);
        //   no LLM -> template with fallback=false.
        throw new UnsupportedOperationException("TODO(phase-3 M5): SummaryService.summarize");
    }
}

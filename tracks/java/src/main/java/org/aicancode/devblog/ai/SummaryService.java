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
        if (llm != null) {
            try {
                return new Result("llm", false, llm.summarize(title, body));
            } catch (Exception e) { // any LLM failure -> graceful fallback
                LOG.warn("LLM summary failed, using template: {}", e.toString());
                return new Result("template", true, template.summarize(title, body));
            }
        }
        return new Result("template", false, template.summarize(title, body));
    }
}

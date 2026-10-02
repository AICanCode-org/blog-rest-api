package org.aicancode.devblog.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** M5: the TL;DR summary with an offline template (FR-8, NFR-8). */
class SummaryTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "One. Two! Three? Four.|One. Two!",
        "Just one sentence.|Just one sentence.",
        "Version 1.2 is out. It fixes bugs. More later.|Version 1.2 is out. It fixes bugs.",
        "###|My title"}) // nothing left after removing markdown -> the title
    void templateSummary(String body, String summary) {
        assertThat(new TemplateSummarizer().summarize("My title", body)).isEqualTo(summary);
    }

    @Test
    void templateDropsMarkdownAndIsAtMost280Characters() {
        assertThat(new TemplateSummarizer().summarize("t", "# Heading\n\nNo full stop at all"))
                .isEqualTo("Heading No full stop at all");
        assertThat(new TemplateSummarizer().summarize("t", "word ".repeat(200))).hasSizeLessThanOrEqualTo(280).endsWith("...");
    }

    @Test
    void llmSummaryIsCleanedAndCapped() {
        String[] sent = new String[2];
        LlmClient client = (system, user, maxTokens) -> {
            sent[0] = user;
            sent[1] = Integer.toString(maxTokens);
            return "  A   short\nsummary. " + "x".repeat(400);
        };
        SummaryService.Result result = new SummaryService(new TemplateSummarizer(), new LlmSummarizer(client))
                .summarize("Title", "B".repeat(10_000));
        assertThat(List.of(result.provider(), result.fallback())).containsExactly("llm", false);
        assertThat(result.summary()).startsWith("A short summary.").hasSize(280);
        // cost cap: never more than LLM_INPUT_MAX characters of the body are sent
        assertThat(sent[0].chars().filter(c -> c == 'B').count()).isEqualTo(LlmSummarizer.LLM_INPUT_MAX);
        assertThat(sent[1]).isEqualTo("120");
    }

    @Test
    void llmFailureFallsBackToTheTemplate() {
        List<LlmClient> failing = List.of(
                (s, u, m) -> {
                    throw new java.net.http.HttpTimeoutException("timed out");
                },
                (s, u, m) -> "   ");
        for (LlmClient client : failing) {
            SummaryService.Result r = new SummaryService(new TemplateSummarizer(), new LlmSummarizer(client))
                    .summarize("T", "First. Second. Third.");
            assertThat(r).isEqualTo(new SummaryService.Result("template", true, "First. Second."));
        }
    }
}

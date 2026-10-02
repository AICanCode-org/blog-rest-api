"""M5: the TL;DR summary with an offline template (FR-8, NFR-8)."""

import json

import httpx
import pytest

from app.ai import LLM_INPUT_MAX, LlmClient, LlmSummarizer, SummaryService, TemplateSummarizer


@pytest.mark.parametrize(
    ("body", "summary"),
    [
        ("One. Two! Three? Four.", "One. Two!"),
        ("# Heading\n\nNo full stop at all", "Heading No full stop at all"),
        ("Just one sentence.", "Just one sentence."),
        ("Version 1.2 is out. It fixes bugs. More later.", "Version 1.2 is out. It fixes bugs."),
        ("###", "My title"),  # nothing left after removing markdown -> the title
    ],
)
def test_template_summary(body, summary):
    assert TemplateSummarizer().summarize("My title", body) == summary


def test_template_summary_is_at_most_280_characters():
    text = TemplateSummarizer().summarize("t", "word " * 200)
    assert len(text) <= 280 and text.endswith("...")


def llm(content: str | None, seen: list | None = None) -> LlmClient:
    def handler(request: httpx.Request) -> httpx.Response:
        if seen is not None:
            seen.append(json.loads(request.content))
        if content is None:
            raise httpx.ReadTimeout("timed out")
        return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})

    return LlmClient("http://llm.test/v1", "m", None, 1000, transport=httpx.MockTransport(handler))


def test_llm_summary_is_cleaned_and_capped():
    seen: list = []
    service = SummaryService(TemplateSummarizer(), LlmSummarizer(llm("  A   short\nsummary. " + "x" * 400, seen)))
    result = service.summarize("Title", "B" * 10_000)
    assert result.provider == "llm" and not result.fallback
    assert result.summary.startswith("A short summary.") and len(result.summary) == 280
    # cost cap: never more than LLM_INPUT_MAX characters of the body are sent
    assert seen[0]["messages"][1]["content"].count("B") == LLM_INPUT_MAX
    assert seen[0]["max_tokens"] == 120


@pytest.mark.parametrize("content", [None, "   "])
def test_llm_failure_falls_back_to_the_template(content):
    result = SummaryService(TemplateSummarizer(), LlmSummarizer(llm(content))).summarize("T", "First. Second. Third.")
    assert (result.provider, result.fallback, result.summary) == ("template", True, "First. Second.")

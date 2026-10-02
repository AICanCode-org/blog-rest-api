// M5: the TL;DR summary with an offline template (FR-8, NFR-8).
import { expect, it } from "vitest";
import { LLM_INPUT_MAX, LlmClient, LlmSummarizer, SummaryService, TemplateSummarizer } from "../../src/ai.js";
import { silentLog } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("summaries");

function llm(content: string | null, seen: { messages: { content: string }[]; max_tokens: number }[] = []) {
  const fetchFn = (async (_url: string, init: RequestInit) => {
    seen.push(JSON.parse(init.body as string));
    if (content === null) throw new DOMException("The operation timed out.", "TimeoutError");
    return new Response(JSON.stringify({ choices: [{ message: { content } }] }));
  }) as unknown as typeof fetch;
  return new LlmClient("http://llm.test/v1", "m", null, 1000, fetchFn);
}

describe("summaries", () => {
  it.each([
    ["One. Two! Three? Four.", "One. Two!"],
    ["# Heading\n\nNo full stop at all", "Heading No full stop at all"],
    ["Just one sentence.", "Just one sentence."],
    ["Version 1.2 is out. It fixes bugs. More later.", "Version 1.2 is out. It fixes bugs."],
    ["###", "My title"], // nothing left after removing markdown -> the title
  ])("template: %j -> %j", (body, summary) => {
    expect(new TemplateSummarizer().summarizeSync("My title", body)).toBe(summary);
  });

  it("keeps the template summary at most 280 characters", () => {
    const text = new TemplateSummarizer().summarizeSync("t", "word ".repeat(200));
    expect(text.length).toBeLessThanOrEqual(280);
    expect(text.endsWith("...")).toBe(true);
  });

  it("cleans and caps the LLM summary and the input", async () => {
    const seen: { messages: { content: string }[]; max_tokens: number }[] = [];
    const service = new SummaryService(
      new TemplateSummarizer(),
      new LlmSummarizer(llm("  A   short\nsummary. " + "x".repeat(400), seen)),
    );
    const result = await service.summarize("Title", "B".repeat(10_000));
    expect([result.provider, result.fallback]).toEqual(["llm", false]);
    expect(result.summary.startsWith("A short summary.")).toBe(true);
    expect(result.summary).toHaveLength(280);
    // cost cap: never more than LLM_INPUT_MAX characters of the body are sent
    expect(seen[0]!.messages[1]!.content.split("B").length - 1).toBe(LLM_INPUT_MAX);
    expect(seen[0]!.max_tokens).toBe(120);
  });

  it.each([null, "   "])("falls back to the template when the LLM answers %j", async (content) => {
    const service = new SummaryService(new TemplateSummarizer(), new LlmSummarizer(llm(content)), silentLog);
    expect(await service.summarize("T", "First. Second. Third.")).toEqual({
      provider: "template",
      fallback: true,
      summary: "First. Second.",
    });
  });
});

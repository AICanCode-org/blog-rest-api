// v1.1 (Phase 7): request IDs, /metrics and the LLM circuit breaker.
import { describe, expect, it } from "vitest";
import { CircuitBreaker, CircuitOpenError, ModerationService, RuleModerator } from "../../src/ai.js";
import { SummaryService, TemplateSummarizer } from "../../src/ai.js";
import { COUNTERS, Metrics } from "../../src/metrics.js";
import { incomingOrNewId } from "../../src/observability.js";
import { Api } from "../helpers.js";

async function metric(api: Api, name: string): Promise<number> {
  const body = (await api.request("GET", "/metrics")).body;
  const line = body.split("\n").find((l) => l.startsWith(`${name} `));
  return Number(line!.split(" ")[1]);
}

describe("request IDs", () => {
  it("echoes a safe ID and mints one when missing", async () => {
    const api = await Api.create();
    const r = await api.app.inject({ method: "GET", url: "/health", headers: { "x-request-id": "abc-123" } });
    expect(r.headers["x-request-id"]).toBe("abc-123");
    const minted = (await api.request("GET", "/health")).headers["x-request-id"] as string;
    expect(minted).toMatch(/^[0-9a-f-]{36}$/);
  });

  it.each(["", "has space", "x".repeat(65), "evilé", "a;b"])("replaces the unsafe ID %j", (value) => {
    expect(incomingOrNewId(value)).not.toBe(value);
  });
});

describe("/metrics", () => {
  it("lists every counter as Prometheus text", async () => {
    const api = await Api.create();
    const r = await api.request("GET", "/metrics");
    expect(r.statusCode).toBe(200);
    expect(r.headers["content-type"]).toMatch(/^text\/plain/);
    for (const name of Object.keys(COUNTERS)) {
      expect(r.body).toContain(`# TYPE ${name} counter`);
      expect(r.body).toContain(`\n${name} 0`);
    }
  });

  it("counts the business events", async () => {
    const api = await Api.create();
    const author = await api.user("AUTHOR");
    const reader = await api.user();
    await api.request("POST", "/api/v1/auth/login", {
      email: "user1@devblog.test",
      password: "wrong-password",
    });
    const published = await api.post(author);
    const draft = await api.post(author, { status: "draft", title: "Draft" });
    const url = `/api/v1/posts/${draft.slug}`;
    await api.request("PATCH", url, { version: 1, status: "published" }, author);
    await api.request("PATCH", url, { version: 2, status: "draft" }, author);
    await api.request("PATCH", url, { version: 3, status: "published" }, author); // re-publish: not new
    const comments = `/api/v1/posts/${published.slug}/comments`;
    await api.request("POST", comments, { body: "Nice" }, reader);
    await api.request("POST", comments, { body: "click here" }, reader);
    expect(await metric(api, "devblog_users_registered_total")).toBe(2);
    expect(await metric(api, "devblog_logins_failed_total")).toBe(1);
    expect(await metric(api, "devblog_posts_published_total")).toBe(2);
    expect(await metric(api, "devblog_comments_created_total")).toBe(2);
    expect(await metric(api, "devblog_comments_held_total")).toBe(1);
    expect(await metric(api, "devblog_ai_fallback_total")).toBe(0);
  });

  it("counts fallbacks", async () => {
    const broken = {
      classify: async () => Promise.reject(new Error("LLM timed out")),
      summarize: async () => Promise.reject(new Error("LLM timed out")),
    };
    const log = { warn: () => {} };
    const api = await Api.create({
      ai: {
        moderation: new ModerationService(new RuleModerator(), broken, log),
        summaries: new SummaryService(new TemplateSummarizer(), broken, log),
      },
    });
    const post = await api.post(await api.user("AUTHOR"));
    await api.request("POST", `/api/v1/posts/${post.slug}/comments`, { body: "Nice" }, await api.user());
    expect((await api.request("GET", `/api/v1/posts/${post.slug}/summary`)).json().fallback).toBe(true);
    expect(await metric(api, "devblog_ai_fallback_total")).toBe(2);
  });

  it("renders the text format", () => {
    const m = new Metrics();
    m.inc("devblog_comments_held_total", 3);
    expect(m.value("devblog_comments_held_total")).toBe(3);
    expect(m.render()).toContain("devblog_comments_held_total 3\n");
  });
});

describe("circuit breaker", () => {
  function setup() {
    const llm = {
      calls: 0,
      fail: true,
      async chat() {
        this.calls += 1;
        if (this.fail) throw new Error("timed out");
        return "ok";
      },
    };
    const clock = { t: 1_000_000 };
    let skipped = 0;
    const breaker = new CircuitBreaker(llm, {
      threshold: 3,
      cooldownMs: 30_000,
      now: () => clock.t,
      onShortCircuit: () => (skipped += 1),
      log: { warn: () => {} },
    });
    const call = () => breaker.chat("s", "u", 10);
    return { llm, clock, breaker, call, skipped: () => skipped };
  }

  it("opens after three failures in a row and skips calls", async () => {
    const { llm, breaker, call, skipped } = setup();
    for (let i = 0; i < 3; i++) await expect(call()).rejects.toThrow("timed out");
    expect(breaker.state).toBe("open");
    await expect(call()).rejects.toBeInstanceOf(CircuitOpenError);
    expect(llm.calls).toBe(3);
    expect(skipped()).toBe(1);
  });

  it("a success resets the failure count", async () => {
    const { llm, breaker, call } = setup();
    for (const fail of [true, true, false, true, true]) {
      llm.fail = fail;
      if (fail) await expect(call()).rejects.toThrow();
      else await call();
    }
    expect(breaker.state).toBe("closed");
  });

  it("half-open: a trial success closes, a trial failure re-opens", async () => {
    const { llm, clock, breaker, call } = setup();
    for (let i = 0; i < 3; i++) await expect(call()).rejects.toThrow();
    clock.t += 30_000;
    expect(breaker.state).toBe("half-open");
    await expect(call()).rejects.toThrow("timed out");
    expect(breaker.state).toBe("open");
    expect(llm.calls).toBe(4);
    clock.t += 30_000;
    llm.fail = false;
    expect(await call()).toBe("ok");
    expect(breaker.state).toBe("closed");
  });

  it("the real wiring short-circuits an unreachable LLM", async () => {
    const metrics = new Metrics();
    const api = await Api.create({
      settings: { aiProvider: "llm", llmBaseUrl: "http://127.0.0.1:9/v1", llmTimeoutMs: 500 },
      metrics,
    });
    const post = await api.post(await api.user("AUTHOR"));
    const reader = await api.user();
    for (let i = 0; i < 4; i++) {
      const r = await api.request("POST", `/api/v1/posts/${post.slug}/comments`, { body: `c${i}` }, reader);
      expect(r.statusCode).toBe(201);
      expect(r.json().moderation.fallback).toBe(true);
    }
    expect(metrics.value("devblog_llm_short_circuits_total")).toBe(1);
    expect(metrics.value("devblog_ai_fallback_total")).toBe(4);
  });
});

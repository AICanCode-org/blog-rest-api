// M5: comments and the moderation queue over HTTP.
import { beforeEach, expect, it } from "vitest";
import { Api } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("api-comments");
const ids = (r: { json: () => { items: { id: number }[] } }) => r.json().items.map((c) => c.id);

describe("comments API", () => {
  let api: Api;
  beforeEach(async () => {
    api = await Api.create();
  });

  it("creates, moderates and lists comments", async () => {
    const author = await api.user("AUTHOR");
    const reader = await api.user("READER", "Rita");
    const admin = await api.admin();
    const post = await api.post(author);
    const url = `/api/v1/posts/${post.slug}/comments`;
    expect((await api.request("POST", url, { body: "hi" })).statusCode).toBe(401);
    const ok = await api.request("POST", url, { body: "Clear and useful." }, reader);
    expect(ok.statusCode).toBe(201);
    expect(ok.json()).toEqual({
      id: ok.json().id,
      postSlug: post.slug,
      parentId: null,
      author: { id: reader.user.id, displayName: "Rita" },
      body: "Clear and useful.",
      status: "visible",
      moderation: {
        label: "ok",
        confidence: 0.6,
        provider: "rules",
        fallback: false,
        reason: "no rule matched",
      },
      createdAt: "2026-10-02T09:30:00.000Z",
    });
    const held = (await api.request("POST", url, { body: "BUY NOW at www.cheap.example" }, reader)).json();
    expect([held.status, held.moderation.label]).toEqual(["held", "spam"]);
    expect(ids(await api.request("GET", url))).toEqual([ok.json().id]);

    expect(ids(await api.request("GET", "/api/v1/admin/comments", undefined, admin))).toEqual([held.id]);
    expect((await api.request("GET", "/api/v1/admin/comments", undefined, reader)).statusCode).toBe(403);
    const decided = await api.request(
      "POST",
      `/api/v1/admin/comments/${held.id}/decision`,
      { decision: "approve" },
      admin,
    );
    expect(decided.statusCode).toBe(200);
    expect(decided.json().status).toBe("visible");
    expect(ids(await api.request("GET", url))).toHaveLength(2);
    expect(
      (await api.request("GET", "/api/v1/admin/comments?status=nope", undefined, admin)).statusCode,
    ).toBe(400);
  });

  it("handles replies and deleting", async () => {
    const author = await api.user("AUTHOR");
    const reader = await api.user();
    const post = await api.post(author);
    const url = `/api/v1/posts/${post.slug}/comments`;
    const top = (await api.request("POST", url, { body: "Question?" }, reader)).json();
    const reply = await api.request("POST", url, { body: "Answer.", parentId: top.id }, author);
    expect(reply.statusCode).toBe(201);
    expect(reply.json().parentId).toBe(top.id);
    expect((await api.request("POST", url, { body: "x", parentId: "1" }, reader)).statusCode).toBe(400);
    expect((await api.request("DELETE", `/api/v1/comments/${top.id}`, undefined, author)).statusCode).toBe(
      403,
    );
    expect((await api.request("DELETE", `/api/v1/comments/${top.id}`, undefined, reader)).statusCode).toBe(
      204,
    );
    expect((await api.request("DELETE", "/api/v1/comments/abc", undefined, reader)).statusCode).toBe(404);
    expect((await api.request("GET", "/api/v1/posts/missing/comments")).statusCode).toBe(404);
  });
});

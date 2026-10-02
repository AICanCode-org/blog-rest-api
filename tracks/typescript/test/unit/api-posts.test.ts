// M3: the post endpoints over HTTP.
import { beforeEach, expect, it } from "vitest";
import { Api } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("api-posts");
const POST = { title: "Hello DevBlog", body: "First post. It has two sentences.", tags: ["Java"] };

describe("posts API", () => {
  let api: Api;
  beforeEach(async () => {
    api = await Api.create();
  });

  it("creates a post: 201, Location and the full post", async () => {
    const author = await api.user("AUTHOR", "Ada");
    const r = await api.request("POST", "/api/v1/posts", POST, author);
    expect(r.statusCode).toBe(201);
    expect(r.headers.location).toBe("/api/v1/posts/hello-devblog");
    expect(r.json()).toEqual({
      id: r.json().id,
      slug: "hello-devblog",
      title: "Hello DevBlog",
      body: "First post. It has two sentences.",
      status: "draft",
      tags: ["java"],
      author: { id: author.user.id, displayName: "Ada" },
      createdAt: "2026-10-02T09:30:00.000Z",
      updatedAt: "2026-10-02T09:30:00.000Z",
      publishedAt: null,
      version: 1,
    });
  });

  it("needs a token and the right role to write", async () => {
    const r = await api.request("POST", "/api/v1/posts", POST);
    expect(r.statusCode).toBe(401);
    expect(r.headers["www-authenticate"]).toBe("Bearer");
    expect((await api.request("POST", "/api/v1/posts", POST, await api.user())).statusCode).toBe(403);
  });

  it("answers 404 for someone else's draft", async () => {
    const author = await api.user("AUTHOR");
    const draft = await api.post(author, { status: "draft" });
    const url = `/api/v1/posts/${draft.slug}`;
    expect((await api.request("GET", url)).statusCode).toBe(404);
    expect((await api.request("GET", url, undefined, await api.user("AUTHOR"))).statusCode).toBe(404);
    expect((await api.request("GET", url, undefined, author)).statusCode).toBe(200);
    expect((await api.request("GET", url, undefined, await api.admin())).statusCode).toBe(200);
    // a broken token is a 401, even on a public endpoint: never silently anonymous
    expect((await api.request("GET", url, undefined, "Bearer x")).statusCode).toBe(401);
  });

  it("patches with optimistic locking", async () => {
    const author = await api.user("AUTHOR");
    const post = await api.post(author);
    const url = `/api/v1/posts/${post.slug}`;
    const ok = await api.request("PATCH", url, { version: 1, title: "Edited" }, author);
    expect(ok.statusCode).toBe(200);
    expect([ok.json().version, ok.json().slug]).toEqual([2, post.slug]);
    const stale = await api.request("PATCH", url, { version: 1, title: "Lost update" }, author);
    expect(stale.statusCode).toBe(409);
    expect(stale.json().type).toMatch(/\/version-conflict$/);
    expect(stale.json().detail).toContain("current version 2");
    const other = await api.request(
      "PATCH",
      url,
      { version: 2, title: "Not mine" },
      await api.user("AUTHOR"),
    );
    expect(other.statusCode).toBe(403);
  });

  it("deletes: 204, then 404", async () => {
    const author = await api.user("AUTHOR");
    const post = await api.post(author);
    const url = `/api/v1/posts/${post.slug}`;
    expect((await api.request("DELETE", url)).statusCode).toBe(401);
    expect((await api.request("DELETE", url, undefined, author)).statusCode).toBe(204);
    expect((await api.request("GET", url)).statusCode).toBe(404);
    expect((await api.request("DELETE", url, undefined, author)).statusCode).toBe(404);
  });

  it("serves the summary", async () => {
    const post = await api.post(await api.user("AUTHOR"), {
      body: "Keyset pagination is fast. OFFSET is slow. Here is why.",
    });
    expect((await api.request("GET", `/api/v1/posts/${post.slug}/summary`)).json()).toEqual({
      slug: post.slug,
      provider: "template",
      fallback: false,
      summary: "Keyset pagination is fast. OFFSET is slow.",
      generatedAt: "2026-10-02T09:30:00.000Z",
    });
    expect((await api.request("GET", "/api/v1/posts/missing/summary")).statusCode).toBe(404);
  });
});

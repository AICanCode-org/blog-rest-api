// M4: the public feed - keyset pagination, tag filter, search, tag counts (FR-6).
import { beforeEach, expect, it } from "vitest";
import { SummaryService, TemplateSummarizer } from "../../src/ai.js";
import type { Principal } from "../../src/models.js";
import { PostService } from "../../src/posts.js";
import { InMemoryStore } from "../../src/repository.js";
import { Api, FakeClock } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("feed");

describe("feed", () => {
  let store: InMemoryStore;
  let clock: FakeClock;
  let posts: PostService;
  let author: Principal;
  beforeEach(async () => {
    store = new InMemoryStore();
    clock = new FakeClock();
    posts = new PostService(store, clock.fn, new SummaryService(new TemplateSummarizer()));
    author = {
      userId: (await store.insertUser("ada@devblog.test", "x", "Ada", "AUTHOR", clock.now)).id,
      role: "AUTHOR",
    };
  });

  async function publish(n: number, fields: Record<string, unknown> = {}) {
    const created = [];
    for (let i = 0; i < n; i++) {
      clock.advance({ minutes: 1 });
      created.push(
        await posts.create(author, {
          title: `Post ${i}`,
          body: `Body number ${i}.`,
          status: "published",
          ...fields,
        }),
      );
    }
    return created;
  }
  const titles = (items: { title: string }[]) => items.map((p) => p.title);

  it("is newest first and skips drafts", async () => {
    await publish(3);
    await posts.create(author, { title: "Secret draft", body: "Not yet." });
    const page = await posts.feed({});
    expect(titles(page.items)).toEqual(["Post 2", "Post 1", "Post 0"]);
    expect(page.nextCursor).toBeNull();
  });

  it("walks every post exactly once with keyset pagination", async () => {
    await publish(7);
    const seen: string[] = [];
    let cursor: string | undefined;
    let pages = 0;
    for (;;) {
      const page = await posts.feed({ limit: "3", cursor });
      seen.push(...titles(page.items));
      pages += 1;
      if (!page.nextCursor) break;
      cursor = page.nextCursor;
    }
    expect(pages).toBe(3);
    expect(seen).toEqual([6, 5, 4, 3, 2, 1, 0].map((i) => `Post ${i}`));
  });

  it("does not skip posts published in the same millisecond", async () => {
    // Same publishedAt for all five: the id breaks the tie, so the cursor needs both.
    for (let i = 0; i < 5; i++)
      await posts.create(author, { title: `Same time ${i}`, body: "x.", status: "published" });
    const first = await posts.feed({ limit: "2" });
    const second = await posts.feed({ limit: "2", cursor: first.nextCursor! });
    const third = await posts.feed({ limit: "2", cursor: second.nextCursor! });
    const all = titles([...first.items, ...second.items, ...third.items]);
    expect([...all].sort()).toEqual([0, 1, 2, 3, 4].map((i) => `Same time ${i}`));
    expect(new Set(all).size).toBe(5);
    expect(third.nextCursor).toBeNull();
  });

  it("is not shifted by a new post", async () => {
    await publish(4);
    const first = await posts.feed({ limit: "2" });
    await publish(1, { title: "Breaking news" }); // with OFFSET this would repeat "Post 2"
    const second = await posts.feed({ limit: "2", cursor: first.nextCursor! });
    expect(titles(first.items)).toEqual(["Post 3", "Post 2"]);
    expect(titles(second.items)).toEqual(["Post 1", "Post 0"]);
  });

  it("filters by tag", async () => {
    await publish(2, { tags: ["java"] });
    await publish(1, { tags: ["python"] });
    const page = await posts.feed({ tag: "Java" });
    expect(page.items).toHaveLength(2);
    expect(page.items.every((p) => p.tags.join() === "java")).toBe(true);
  });

  it("searches title and body words", async () => {
    await posts.create(author, {
      title: "Docker for beginners",
      body: "Containers explained.",
      status: "published",
    });
    await posts.create(author, {
      title: "Kubernetes",
      body: "Orchestrate many docker containers.",
      status: "published",
    });
    await posts.create(author, { title: "Java records", body: "Immutable data.", status: "published" });
    expect(new Set(titles((await posts.feed({ q: "docker" })).items))).toEqual(
      new Set(["Docker for beginners", "Kubernetes"]),
    );
    expect(titles((await posts.feed({ q: "docker containers kubernetes" })).items)).toEqual(["Kubernetes"]);
    expect((await posts.feed({ q: "rust" })).items).toEqual([]);
  });

  it("validates its parameters", async () => {
    for (const params of [{ limit: "0" }, { cursor: "garbage!" }, { tag: "not a tag" }]) {
      await expect(posts.feed(params)).rejects.toMatchObject({ status: 400 });
    }
  });

  it("drops deleted posts from the feed and the tag counts", async () => {
    const [a] = await publish(2, { tags: ["java"] });
    await publish(1, { tags: ["python", "java"] });
    await posts.create(author, { title: "Draft", body: "x", tags: ["rust"] });
    expect(await posts.tags()).toEqual([
      { name: "java", posts: 3 },
      { name: "python", posts: 1 },
    ]);
    await posts.delete(author, a!.slug);
    expect(await posts.tags()).toEqual([
      { name: "java", posts: 2 },
      { name: "python", posts: 1 },
    ]);
    expect((await posts.feed({})).items.map((p) => p.slug)).not.toContain(a!.slug);
  });
});

describe("feed over HTTP", () => {
  it("has the documented shape and cursor", async () => {
    const api = await Api.create();
    const author = await api.user("AUTHOR", "Ada");
    for (let i = 0; i < 3; i++) {
      api.clock.advance({ minutes: 1 });
      await api.post(author, { title: `Post ${i}`, body: "word ".repeat(50), tags: ["java"] });
    }
    const first = (await api.request("GET", "/api/v1/posts?limit=2")).json();
    expect(first.items.map((p: { title: string }) => p.title)).toEqual(["Post 2", "Post 1"]);
    expect(first.nextCursor).toBeTruthy();
    const item = first.items[0];
    expect(Object.keys(item).sort()).toEqual([
      "author",
      "coverImageUrl",
      "excerpt",
      "id",
      "publishedAt",
      "slug",
      "tags",
      "title",
    ]);
    expect(item.author).toEqual({ id: author.user.id, displayName: "Ada" });
    expect(item.excerpt.length).toBeLessThanOrEqual(160);
    expect(item.excerpt.endsWith("...")).toBe(true);
    const second = (await api.request("GET", `/api/v1/posts?limit=2&cursor=${first.nextCursor}`)).json();
    expect(second.items.map((p: { title: string }) => p.title)).toEqual(["Post 0"]);
    expect(second.nextCursor).toBeNull();
    expect((await api.request("GET", "/api/v1/posts?limit=500")).statusCode).toBe(400);
    expect((await api.request("GET", "/api/v1/posts?cursor=%25%25%25")).statusCode).toBe(400);
  });

  it("serves tag counts, the tag filter and search", async () => {
    const api = await Api.create();
    const author = await api.user("AUTHOR");
    await api.post(author, { tags: ["java", "spring"] });
    await api.post(author, { tags: ["java"] });
    expect((await api.request("GET", "/api/v1/tags")).json()).toEqual({
      items: [
        { name: "java", posts: 2 },
        { name: "spring", posts: 1 },
      ],
    });
    expect((await api.request("GET", "/api/v1/posts?tag=spring")).json().items[0].tags).toEqual([
      "java",
      "spring",
    ]);
    expect((await api.request("GET", "/api/v1/posts?q=sentences")).json().items).not.toEqual([]);
  });
});

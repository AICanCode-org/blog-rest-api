// M3: posts - roles, slugs, drafts, ownership, optimistic locking, soft delete (FR-4, FR-5).
import { beforeEach, expect, it } from "vitest";
import { SummaryService, TemplateSummarizer } from "../../src/ai.js";
import type { Principal } from "../../src/models.js";
import { PostService } from "../../src/posts.js";
import { InMemoryStore } from "../../src/repository.js";
import { FakeClock, makePeople, statusOf } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("posts");
const POST = { title: "Hello DevBlog", body: "First post. It has two sentences." };

describe("post service", () => {
  let store: InMemoryStore;
  let clock: FakeClock;
  let posts: PostService;
  let p: Record<string, Principal>;
  beforeEach(async () => {
    store = new InMemoryStore();
    clock = new FakeClock();
    posts = new PostService(store, clock.fn, new SummaryService(new TemplateSummarizer()));
    p = await makePeople(store, clock, [
      ["ada", "AUTHOR"],
      ["bob", "AUTHOR"],
      ["rita", "READER"],
      ["root", "ADMIN"],
    ]);
  });

  it("creates a draft by default", async () => {
    const post = await posts.create(p.ada!, { ...POST, tags: ["Python", "fastapi"] });
    expect([post.slug, post.status, post.version]).toEqual(["hello-devblog", "draft", 1]);
    expect(post.tags).toEqual(["fastapi", "python"]);
    expect(post.authorName).toBe("Ada");
    expect(post.publishedAt).toBeNull();
    expect(post.createdAt).toEqual(clock.now);
    expect(post.updatedAt).toEqual(clock.now);
  });

  it("sets publishedAt when publishing on create", async () => {
    expect((await posts.create(p.ada!, { ...POST, status: "published" })).publishedAt).toEqual(clock.now);
  });

  it("does not let readers write posts", async () => {
    expect(await statusOf(posts.create(p.rita!, POST))).toEqual([403, "forbidden"]);
    expect((await posts.create(p.root!, POST)).authorId).toBe(p.root!.userId); // ADMIN may
  });

  it("adds a suffix on slug collisions", async () => {
    const slugs = [];
    for (let i = 0; i < 3; i++) slugs.push((await posts.create(p.ada!, POST)).slug);
    expect(slugs).toEqual(["hello-devblog", "hello-devblog-2", "hello-devblog-3"]);
  });

  it("validates new posts", async () => {
    expect((await statusOf(posts.create(p.ada!, { title: "", body: "x" })))[0]).toBe(400);
    expect((await statusOf(posts.create(p.ada!, { ...POST, status: "archived" })))[0]).toBe(400);
  });

  it("shows drafts only to the author and admins", async () => {
    const draft = await posts.create(p.ada!, POST);
    expect((await posts.get(p.ada!, draft.slug)).id).toBe(draft.id);
    expect((await posts.get(p.root!, draft.slug)).id).toBe(draft.id);
    // Not 403: a stranger must not even learn that the draft exists.
    for (const stranger of [null, p.bob!, p.rita!]) {
      expect(await statusOf(posts.get(stranger, draft.slug))).toEqual([404, "not-found"]);
    }
  });

  it("makes published posts public", async () => {
    const post = await posts.create(p.ada!, { ...POST, status: "published" });
    expect((await posts.get(null, post.slug)).title).toBe("Hello DevBlog");
  });

  it("lets the author edit and increments the version", async () => {
    const post = await posts.create(p.ada!, POST);
    clock.advance({ minutes: 5 });
    const updated = await posts.update(p.ada!, post.slug, {
      version: 1,
      title: "Hello again",
      tags: ["news"],
    });
    expect([updated.title, updated.version]).toEqual(["Hello again", 2]);
    expect(updated.tags).toEqual(["news"]);
    expect(updated.slug).toBe(post.slug); // slugs never change: links keep working
    expect(updated.updatedAt).toEqual(clock.now);
    expect(updated.createdAt).toEqual(post.createdAt);
  });

  it("gives other authors 403 but lets admins edit", async () => {
    const post = await posts.create(p.ada!, { ...POST, status: "published" });
    expect(await statusOf(posts.update(p.bob!, post.slug, { version: 1, title: "Mine now" }))).toEqual([
      403,
      "forbidden",
    ]);
    expect((await posts.update(p.root!, post.slug, { version: 1, title: "Moderated" })).title).toBe(
      "Moderated",
    );
  });

  it("answers a stale version with 409", async () => {
    const post = await posts.create(p.ada!, POST);
    await posts.update(p.ada!, post.slug, { version: 1, body: "Edited in tab one." });
    expect(
      await statusOf(posts.update(p.ada!, post.slug, { version: 1, body: "Edited in tab two." })),
    ).toEqual([409, "version-conflict"]);
  });

  it("needs a version and a change", async () => {
    const post = await posts.create(p.ada!, POST);
    expect((await statusOf(posts.update(p.ada!, post.slug, { title: "No version" })))[0]).toBe(400);
    expect((await statusOf(posts.update(p.ada!, post.slug, { version: 1 })))[0]).toBe(400);
    expect((await statusOf(posts.update(p.ada!, post.slug, { version: 1, status: null })))[0]).toBe(400);
    expect((await statusOf(posts.update(p.ada!, "no-such-post", { version: 1, title: "x" })))[0]).toBe(404);
  });

  it("keeps the first publication date when re-publishing", async () => {
    const post = await posts.create(p.ada!, POST);
    clock.advance({ hours: 1 });
    const published = await posts.update(p.ada!, post.slug, { version: 1, status: "published" });
    const first = published.publishedAt;
    expect(first).toEqual(clock.now);
    clock.advance({ hours: 1 });
    const back = await posts.update(p.ada!, post.slug, { version: 2, status: "draft" });
    const again = await posts.update(p.ada!, post.slug, { version: 3, status: "published" });
    expect(back.publishedAt).toEqual(first);
    expect(again.publishedAt).toEqual(first);
  });

  it("soft-deletes", async () => {
    const post = await posts.create(p.ada!, { ...POST, status: "published" });
    expect(await statusOf(posts.delete(p.bob!, post.slug))).toEqual([403, "forbidden"]);
    await posts.delete(p.ada!, post.slug);
    expect((await statusOf(posts.get(p.ada!, post.slug)))[0]).toBe(404);
    expect(store.posts.has(post.id)).toBe(true); // the row is kept (soft delete)
    expect((await statusOf(posts.delete(p.ada!, post.slug)))[0]).toBe(404);
  });

  it("lets an admin delete any post", async () => {
    const post = await posts.create(p.ada!, { ...POST, status: "published" });
    await posts.delete(p.root!, post.slug);
    expect((await statusOf(posts.get(null, post.slug)))[0]).toBe(404);
  });

  it("summarises with the offline template", async () => {
    const post = await posts.create(p.ada!, { ...POST, status: "published" });
    const { result } = await posts.summary(null, post.slug);
    expect(result).toEqual({
      provider: "template",
      fallback: false,
      summary: "First post. It has two sentences.",
    });
    const draft = await posts.create(p.ada!, POST);
    expect((await statusOf(posts.summary(null, draft.slug)))[0]).toBe(404);
  });
});

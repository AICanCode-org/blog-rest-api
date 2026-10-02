// M5: comments, moderation hold and the human moderation queue (FR-7, FR-9).
import { beforeEach, expect, it } from "vitest";
import { ModerationService, RuleModerator, SummaryService, TemplateSummarizer } from "../../src/ai.js";
import { CommentService } from "../../src/comments.js";
import type { Post, Principal } from "../../src/models.js";
import { PostService } from "../../src/posts.js";
import { InMemoryStore } from "../../src/repository.js";
import { FakeClock, makePeople, statusOf } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("comments");
const ids = (list: { id: number }[]) => list.map((c) => c.id);

describe("comment service", () => {
  let clock: FakeClock;
  let posts: PostService;
  let comments: CommentService;
  let post: Post;
  let p: Record<string, Principal>;
  beforeEach(async () => {
    const store = new InMemoryStore();
    clock = new FakeClock();
    p = await makePeople(store, clock, [
      ["ada", "AUTHOR"],
      ["rita", "READER"],
      ["sam", "READER"],
      ["root", "ADMIN"],
    ]);
    posts = new PostService(store, clock.fn, new SummaryService(new TemplateSummarizer()));
    comments = new CommentService(store, store, new ModerationService(new RuleModerator()), clock.fn);
    post = await posts.create(p.ada!, { title: "Hello", body: "Body.", status: "published" });
  });

  it("makes a clean comment visible", async () => {
    const c = await comments.create(p.rita!, post.slug, { body: "  Great post!  " });
    expect([c.status, c.body, c.postSlug, c.authorName, c.parentId]).toEqual([
      "visible",
      "Great post!",
      "hello",
      "Rita",
      null,
    ]);
    expect([c.moderation.label, c.moderation.provider]).toEqual(["ok", "rules"]);
    expect(c.createdAt).toEqual(clock.now);
    expect(ids(await comments.list(post.slug))).toEqual([c.id]);
  });

  it("holds spam and toxic comments instead of deleting them", async () => {
    const spam = await comments.create(p.rita!, post.slug, { body: "Free money, click here" });
    const toxic = await comments.create(p.sam!, post.slug, { body: "you idiot" });
    expect([spam.status, toxic.status]).toEqual(["held", "held"]);
    expect(await comments.list(post.slug)).toEqual([]);
    expect(ids(await comments.queue(p.root!, undefined))).toEqual([spam.id, toxic.id]);
  });

  it("lets admin decisions override the AI", async () => {
    const held = await comments.create(p.rita!, post.slug, { body: "giveaway at https://a.example" });
    clock.advance({ minutes: 3 });
    const approved = await comments.decide(p.root!, held.id, { decision: "approve" });
    expect([approved.status, approved.moderatedBy]).toEqual(["visible", p.root!.userId]);
    expect(approved.moderatedAt).toEqual(clock.now);
    expect(approved.moderation.label).toBe("spam"); // the AI verdict is kept
    expect(ids(await comments.list(post.slug))).toEqual([held.id]);
    const rejected = await comments.decide(p.root!, held.id, { decision: "reject" });
    expect(rejected.status).toBe("rejected");
    expect(await comments.list(post.slug)).toEqual([]);
    expect(ids(await comments.queue(p.root!, "rejected"))).toEqual([held.id]);
  });

  it("lets only admins moderate", async () => {
    const held = await comments.create(p.rita!, post.slug, { body: "click here" });
    expect(await statusOf(comments.queue(p.ada!, undefined))).toEqual([403, "forbidden"]);
    expect(await statusOf(comments.decide(p.ada!, held.id, { decision: "approve" }))).toEqual([
      403,
      "forbidden",
    ]);
    expect((await statusOf(comments.decide(p.root!, 999, { decision: "approve" })))[0]).toBe(404);
    expect((await statusOf(comments.decide(p.root!, held.id, { decision: "delete" })))[0]).toBe(400);
    expect((await statusOf(comments.queue(p.root!, "all")))[0]).toBe(400);
  });

  it("keeps replies one level deep on the same post", async () => {
    const top = await comments.create(p.rita!, post.slug, { body: "Question?" });
    const reply = await comments.create(p.ada!, post.slug, { body: "Answer.", parentId: top.id });
    expect(reply.parentId).toBe(top.id);
    expect(
      (await statusOf(comments.create(p.sam!, post.slug, { body: "deeper", parentId: reply.id })))[0],
    ).toBe(400);
    const other = await posts.create(p.ada!, { title: "Other", body: "B.", status: "published" });
    expect(
      (await statusOf(comments.create(p.sam!, other.slug, { body: "wrong post", parentId: top.id })))[0],
    ).toBe(400);
    const held = await comments.create(p.sam!, post.slug, { body: "click here" });
    expect((await statusOf(comments.create(p.rita!, post.slug, { body: "re", parentId: held.id })))[0]).toBe(
      400,
    );
    expect((await statusOf(comments.create(p.rita!, post.slug, { body: "re", parentId: 999 })))[0]).toBe(400);
  });

  it("needs a published post", async () => {
    const draft = await posts.create(p.ada!, { title: "Draft", body: "B." });
    expect(await statusOf(comments.create(p.ada!, draft.slug, { body: "hi" }))).toEqual([404, "not-found"]);
    expect((await statusOf(comments.list(draft.slug)))[0]).toBe(404);
    expect((await statusOf(comments.create(p.ada!, "missing", { body: "hi" })))[0]).toBe(404);
    expect((await statusOf(comments.create(p.ada!, "hello", { body: "" })))[0]).toBe(400);
  });

  it("deletes your own comment, or any as an admin", async () => {
    const mine = await comments.create(p.rita!, post.slug, { body: "first" });
    const theirs = await comments.create(p.sam!, post.slug, { body: "second" });
    expect(await statusOf(comments.delete(p.rita!, theirs.id))).toEqual([403, "forbidden"]);
    await comments.delete(p.rita!, mine.id);
    await comments.delete(p.root!, theirs.id);
    expect(await comments.list(post.slug)).toEqual([]);
    expect((await statusOf(comments.delete(p.rita!, mine.id)))[0]).toBe(404);
  });

  it("drops comments of a deleted post from the queue", async () => {
    await comments.create(p.rita!, post.slug, { body: "click here" });
    await posts.delete(p.ada!, post.slug);
    expect(await comments.queue(p.root!, undefined)).toEqual([]);
  });
});

// Posts: drafts, publishing, ownership, optimistic locking, soft delete, the feed and tags.
import type { SummaryResult, SummaryService } from "./ai.js";
import { Metrics } from "./metrics.js";
import { requireRole } from "./auth.js";
import type { Clock } from "./clock.js";
import { DuplicateError, forbidden, notFound, validationError, versionConflict } from "./errors.js";
import { isAdmin, type Post, type Principal, type TagCount } from "./models.js";
import type { PostRepository } from "./repository.js";
import { decodeCursor, encodeCursor, slugify } from "./text.js";
import * as v from "./validation.js";

export const MAX_SLUG_ATTEMPTS = 50;

export const canSee = (principal: Principal | null, post: Post) =>
  post.status === "published" ||
  (principal !== null && (isAdmin(principal) || principal.userId === post.authorId));

export const canEdit = (principal: Principal, post: Post) =>
  isAdmin(principal) || principal.userId === post.authorId;

export interface FeedParams {
  limit?: string;
  cursor?: string;
  tag?: string;
  q?: string;
}

export class PostService {
  constructor(
    readonly posts: PostRepository,
    private readonly clock: Clock,
    private readonly summaries: SummaryService,
    private readonly metrics: Metrics = new Metrics(),
  ) {}

  async create(principal: Principal, payload: unknown): Promise<Post> {
    requireRole(principal, "AUTHOR", "ADMIN");
    const data = v.requireObject(payload);
    const title = v.title(data.title);
    const body = v.body(data.body);
    const tags = v.tags(data.tags);
    const status = v.status(data.status);
    const coverImageUrl = v.coverImageUrl(data.coverImageUrl);
    const now = this.clock();
    const base = slugify(title);
    for (let attempt = 1; attempt <= MAX_SLUG_ATTEMPTS; attempt++) {
      const slug = attempt === 1 ? base : `${base}-${attempt}`;
      const draft: Post = {
        id: 0,
        slug,
        authorId: principal.userId,
        authorName: "",
        title,
        body,
        status,
        tags,
        version: 1,
        createdAt: now,
        updatedAt: now,
        publishedAt: status === "published" ? now : null,
        coverImageUrl,
      };
      try {
        // Insert and let the unique constraint decide: "check, then insert" would race.
        const created = await this.posts.insertPost(draft);
        if (created.status === "published") this.metrics.inc("devblog_posts_published_total");
        return created;
      } catch (err) {
        if (!(err instanceof DuplicateError)) throw err;
      }
    }
    throw validationError("too many posts with this title");
  }

  async get(principal: Principal | null, slug: string): Promise<Post> {
    const post = await this.posts.findPost(slug);
    // A draft you may not see is "not found", not "forbidden": don't confirm that it exists.
    if (!post || !canSee(principal, post)) throw notFound("post");
    return post;
  }

  async update(principal: Principal, slug: string, payload: unknown): Promise<Post> {
    const data = v.requireObject(payload);
    const expected = v.version(data.version);
    const has = (k: string) => Object.prototype.hasOwnProperty.call(data, k);
    if (!["title", "body", "tags", "status", "coverImageUrl"].some(has)) {
      throw validationError("nothing to update: send title, body, tags, status or coverImageUrl");
    }
    const post = await this.get(principal, slug);
    if (!canEdit(principal, post)) throw forbidden("only the author or an ADMIN can edit this post");
    const changed: Post = {
      ...post,
      title: has("title") ? v.title(data.title) : post.title,
      body: has("body") ? v.body(data.body) : post.body,
      tags: has("tags") ? v.tags(data.tags) : post.tags,
      status: has("status") ? v.status(data.status, null) : post.status,
      coverImageUrl: has("coverImageUrl") ? v.coverImageUrl(data.coverImageUrl) : post.coverImageUrl,
      updatedAt: this.clock(),
    };
    if (changed.status === "published" && changed.publishedAt === null) {
      changed.publishedAt = changed.updatedAt; // first publication; re-publishing keeps the date
    }
    const saved = await this.posts.updatePost(changed, expected);
    if (!saved) throw versionConflict(post.version);
    if (post.publishedAt === null && saved.publishedAt !== null)
      this.metrics.inc("devblog_posts_published_total");
    return saved;
  }

  async delete(principal: Principal, slug: string): Promise<void> {
    const post = await this.get(principal, slug);
    if (!canEdit(principal, post)) throw forbidden("only the author or an ADMIN can delete this post");
    await this.posts.softDeletePost(post.id, this.clock());
  }

  async feed(params: FeedParams): Promise<{ items: Post[]; nextCursor: string | null }> {
    const size = v.limit(params.limit);
    const rows = await this.posts.feed({
      limit: size + 1,
      tag: v.tagFilter(params.tag),
      q: v.searchQuery(params.q),
      after: decodeCursor(params.cursor),
    });
    // Ask for one extra row: if it exists there is a next page, and the cursor is the last row shown.
    const items = rows.slice(0, size);
    const last = items[items.length - 1];
    const nextCursor = rows.length > size && last ? encodeCursor(last.publishedAt!, last.id) : null;
    return { items, nextCursor };
  }

  tags(): Promise<TagCount[]> {
    return this.posts.tagCounts();
  }

  async summary(principal: Principal | null, slug: string): Promise<{ post: Post; result: SummaryResult }> {
    const post = await this.get(principal, slug);
    return { post, result: await this.summaries.summarize(post.title, post.body) };
  }
}

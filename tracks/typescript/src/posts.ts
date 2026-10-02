// Posts: drafts, publishing, ownership, optimistic locking, soft delete, the feed and tags.
import type { SummaryResult, SummaryService } from "./ai.js";
import type { Clock } from "./clock.js";
import { type Post, type Principal, type TagCount } from "./models.js";
import type { PostRepository } from "./repository.js";

export const MAX_SLUG_ATTEMPTS = 50;

export function canSee(principal: Principal | null, post: Post): boolean {
  // TODO(phase-3 M3): published posts: everyone; drafts: only the author and ADMINs.
  throw new Error("TODO(phase-3 M3): canSee");
}

export function canEdit(principal: Principal, post: Post): boolean {
  // TODO(phase-3 M3): the author or an ADMIN.
  throw new Error("TODO(phase-3 M3): canEdit");
}

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
  ) {}

  async create(principal: Principal, payload: unknown): Promise<Post> {
    // TODO(phase-3 M3): AUTHOR/ADMIN only; validate title, body, tags, status; slug = slugify(title);
    // INSERT and on DuplicateError retry with -2, -3 ... (MAX_SLUG_ATTEMPTS); publishedAt = now when
    // created as published.
    throw new Error("TODO(phase-3 M3): PostService.create");
  }

  async get(principal: Principal | null, slug: string): Promise<Post> {
    // TODO(phase-3 M3): a missing post OR a draft you may not see -> 404 (never 403: don't confirm it
    // exists).
    throw new Error("TODO(phase-3 M3): PostService.get");
  }

  async update(principal: Principal, slug: string, payload: unknown): Promise<Post> {
    // TODO(phase-3 M3): order matters (the tests check it): object, version, nothing-to-update (400), get
    // (404), can_edit (403), validate title/body/tags/status, save with optimistic locking (409
    // version-conflict). First publish sets publishedAt; the slug never changes.
    throw new Error("TODO(phase-3 M3): PostService.update");
  }

  async delete(principal: Principal, slug: string): Promise<void> {
    // TODO(phase-3 M3): get + can_edit, then a SOFT delete (deleted_at). Deleted posts are 404 from then
    // on.
    throw new Error("TODO(phase-3 M3): PostService.delete");
  }

  async feed(params: FeedParams): Promise<{ items: Post[]; nextCursor: string | null }> {
    // TODO(phase-3 M4): validate limit/cursor/tag/q; ask the repository for limit + 1 rows after the
    // cursor (published, not deleted, newest first, id breaks ties); if there is an extra row, nextCursor
    // = cursor of the last row SHOWN. ADR-0002.
    throw new Error("TODO(phase-3 M4): PostService.feed");
  }

  tags(): Promise<TagCount[]> {
    // TODO(phase-3 M4): tag counts over published, non-deleted posts, sorted by name.
    throw new Error("TODO(phase-3 M4): PostService.tags");
  }

  async summary(principal: Principal | null, slug: string): Promise<{ post: Post; result: SummaryResult }> {
    // TODO(phase-3 M5): get the post (same visibility rules) and summarise title + body with the
    // SummaryService.
    throw new Error("TODO(phase-3 M5): PostService.summary");
  }
}

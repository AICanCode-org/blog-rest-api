// Comments with AI moderation and a human moderation queue.
import { type ModerationService } from "./ai.js";
import type { Clock } from "./clock.js";
import { type Comment, type Post, type Principal } from "./models.js";
import type { CommentRepository, PostRepository } from "./repository.js";

export class CommentService {
  constructor(
    readonly comments: CommentRepository,
    private readonly posts: PostRepository,
    private readonly moderation: ModerationService,
    private readonly clock: Clock,
  ) {}

  private async publishedPost(slug: string): Promise<Post> {
    // TODO(phase-3 M5): find the post by slug; missing, deleted or draft -> 404 not-found 'post'.
    throw new Error("TODO(phase-3 M5): CommentService.publishedPost");
  }

  async create(principal: Principal, slug: string, payload: unknown): Promise<Comment> {
    // TODO(phase-3 M5): validate body and parentId; the post must be PUBLISHED (404); a parent must be a
    //   visible top-level comment of the same post (400); moderate the body and store it as 'held' when
    //   should_hold, else 'visible'.
    throw new Error("TODO(phase-3 M5): CommentService.create");
  }

  async list(slug: string): Promise<Comment[]> {
    // TODO(phase-3 M5): visible comments of a PUBLISHED post (404 otherwise), oldest first.
    throw new Error("TODO(phase-3 M5): CommentService.list");
  }

  async delete(principal: Principal, commentId: number): Promise<void> {
    // TODO(phase-3 M5): unknown comment -> 404; only its author or an ADMIN (403); SOFT delete (deleted_at).
    throw new Error("TODO(phase-3 M5): CommentService.delete");
  }

  async queue(principal: Principal, status: string | undefined): Promise<Comment[]> {
    // TODO(phase-3 M5): ADMIN only; comments with the given status (default held), excluding comments of
    // deleted posts.
    throw new Error("TODO(phase-3 M5): CommentService.queue");
  }

  async decide(principal: Principal, commentId: number, payload: unknown): Promise<Comment> {
    // TODO(phase-3 M5): ADMIN only; approve -> visible, reject -> rejected; record moderated_by/at; keep
    // the AI verdict; unknown id -> 404.
    throw new Error("TODO(phase-3 M5): CommentService.decide");
  }
}

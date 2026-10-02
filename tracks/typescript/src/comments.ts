// Comments with AI moderation and a human moderation queue.
import { shouldHold, type ModerationService } from "./ai.js";
import { requireRole } from "./auth.js";
import { Metrics } from "./metrics.js";
import type { Clock } from "./clock.js";
import { forbidden, notFound, validationError } from "./errors.js";
import { isAdmin, type Comment, type Post, type Principal } from "./models.js";
import type { CommentRepository, PostRepository } from "./repository.js";
import * as v from "./validation.js";

export class CommentService {
  constructor(
    readonly comments: CommentRepository,
    private readonly posts: PostRepository,
    private readonly moderation: ModerationService,
    private readonly clock: Clock,
    private readonly metrics: Metrics = new Metrics(),
  ) {}

  private async publishedPost(slug: string): Promise<Post> {
    const post = await this.posts.findPost(slug);
    if (!post || post.status !== "published") throw notFound("post");
    return post;
  }

  async create(principal: Principal, slug: string, payload: unknown): Promise<Comment> {
    const data = v.requireObject(payload);
    const body = v.commentBody(data.body);
    const parentId = v.parentId(data.parentId);
    const post = await this.publishedPost(slug);
    if (parentId !== null) {
      const parent = await this.comments.findComment(parentId);
      if (!parent || parent.postId !== post.id || parent.parentId !== null || parent.status !== "visible") {
        throw validationError("parentId must be a visible top-level comment of this post");
      }
    }
    const moderation = await this.moderation.moderate(body);
    const status = shouldHold(moderation) ? "held" : "visible";
    const saved = await this.comments.insertComment({
      id: 0,
      postId: post.id,
      postSlug: post.slug,
      authorId: principal.userId,
      authorName: "",
      parentId,
      body,
      status,
      moderation,
      createdAt: this.clock(),
      moderatedBy: null,
      moderatedAt: null,
    });
    this.metrics.inc("devblog_comments_created_total");
    if (status === "held") this.metrics.inc("devblog_comments_held_total");
    if (moderation.fallback) this.metrics.inc("devblog_ai_fallback_total");
    return saved;
  }

  async list(slug: string): Promise<Comment[]> {
    return this.comments.visibleComments((await this.publishedPost(slug)).id);
  }

  async delete(principal: Principal, commentId: number): Promise<void> {
    const comment = await this.comments.findComment(commentId);
    if (!comment) throw notFound("comment");
    if (!(isAdmin(principal) || principal.userId === comment.authorId)) {
      throw forbidden("only the author or an ADMIN can delete this comment");
    }
    await this.comments.softDeleteComment(commentId, this.clock());
  }

  async queue(principal: Principal, status: string | undefined): Promise<Comment[]> {
    requireRole(principal, "ADMIN");
    return this.comments.commentsByStatus(v.commentStatus(status));
  }

  async decide(principal: Principal, commentId: number, payload: unknown): Promise<Comment> {
    requireRole(principal, "ADMIN");
    const decision = v.decision(v.requireObject(payload).decision);
    const updated = await this.comments.setCommentStatus(
      commentId,
      decision === "approve" ? "visible" : "rejected",
      principal.userId,
      this.clock(),
    );
    if (!updated) throw notFound("comment");
    return updated;
  }
}

// Persistence ports (interfaces) with two adapters each: PostgreSQL and in-memory (for unit tests).
//
// The in-memory adapters behave like the database where it matters (unique e-mail/slug, version
// check, soft delete). Full-text search is the exception: in memory it is plain word matching,
// while PostgreSQL also stems ("testing" finds "tests"). The integration tests cover the real thing.
import type pg from "pg";
import { DuplicateError } from "./errors.js";
import type {
  Comment,
  CommentStatus,
  FeedQuery,
  Label,
  Post,
  PostStatus,
  RefreshToken,
  Role,
  TagCount,
  User,
} from "./models.js";

/** Starter helper: marks code you still have to write (returns `never`, so types still check). */
const todo = (what: string): never => {
  throw new Error(what);
};

// ---------------------------------------------------------------------------------------------
// Ports
// ---------------------------------------------------------------------------------------------
export interface UserRepository {
  insertUser(email: string, passwordHash: string, displayName: string, role: Role, at: Date): Promise<User>;
  findUserByEmail(email: string): Promise<User | null>;
  findUser(id: number): Promise<User | null>;
  updateRole(id: number, role: Role): Promise<User | null>;
  insertRefreshToken(userId: number, tokenHash: string, at: Date, expiresAt: Date): Promise<void>;
  findRefreshToken(tokenHash: string): Promise<RefreshToken | null>;
  /** Compare-and-set: true only for the caller that actually revoked it. */
  revokeRefreshToken(id: number, at: Date): Promise<boolean>;
  revokeAllRefreshTokens(userId: number, at: Date): Promise<void>;
  ping(): Promise<boolean>;
}

export interface PostRepository {
  insertPost(post: Post): Promise<Post>;
  findPost(slug: string): Promise<Post | null>;
  updatePost(post: Post, expectedVersion: number): Promise<Post | null>;
  softDeletePost(id: number, at: Date): Promise<void>;
  feed(query: FeedQuery): Promise<Post[]>;
  tagCounts(): Promise<TagCount[]>;
}

export interface CommentRepository {
  insertComment(comment: Comment): Promise<Comment>;
  findComment(id: number): Promise<Comment | null>;
  visibleComments(postId: number): Promise<Comment[]>;
  commentsByStatus(status: CommentStatus): Promise<Comment[]>;
  setCommentStatus(id: number, status: CommentStatus, by: number, at: Date): Promise<Comment | null>;
  softDeleteComment(id: number, at: Date): Promise<void>;
}

// ---------------------------------------------------------------------------------------------
// PostgreSQL
// ---------------------------------------------------------------------------------------------
const UNIQUE_VIOLATION = "23505";
export const isUniqueViolation = (err: unknown) => (err as { code?: string }).code === UNIQUE_VIOLATION;

export const USER_COLS = "id, email, password_hash, display_name, role, created_at";
const POST_SELECT = `
SELECT p.id, p.slug, p.author_id, u.display_name AS author_name, p.title, p.body, p.status, p.version,
       p.created_at, p.updated_at, p.published_at,
       ARRAY(SELECT t.name FROM post_tags pt JOIN tags t ON t.id = pt.tag_id
             WHERE pt.post_id = p.id ORDER BY t.name) AS tags
FROM posts p JOIN users u ON u.id = p.author_id
`;
const COMMENT_SELECT = `
SELECT c.id, c.post_id, p.slug AS post_slug, c.author_id, u.display_name AS author_name, c.parent_id, c.body,
       c.status, c.moderation_label, c.moderation_confidence, c.moderation_provider, c.moderation_fallback,
       c.moderation_reason, c.moderated_by, c.moderated_at, c.created_at
FROM comments c JOIN users u ON u.id = c.author_id JOIN posts p ON p.id = c.post_id
`;

type Row = Record<string, unknown>;
// BIGSERIAL ids arrive as strings from node-postgres (they may exceed 2^53); ours never do.
const num = (v: unknown) => Number(v);
const numOrNull = (v: unknown) => (v === null || v === undefined ? null : Number(v));

export const toUser = (r: Row): User => ({
  id: num(r.id),
  email: r.email as string,
  passwordHash: r.password_hash as string,
  displayName: r.display_name as string,
  role: r.role as Role,
  createdAt: r.created_at as Date,
});

const toPost = (r: Row): Post => ({
  id: num(r.id),
  slug: r.slug as string,
  authorId: num(r.author_id),
  authorName: r.author_name as string,
  title: r.title as string,
  body: r.body as string,
  status: r.status as PostStatus,
  tags: [...(r.tags as string[])],
  version: r.version as number,
  createdAt: r.created_at as Date,
  updatedAt: r.updated_at as Date,
  publishedAt: (r.published_at as Date | null) ?? null,
});

const toComment = (r: Row): Comment => ({
  id: num(r.id),
  postId: num(r.post_id),
  postSlug: r.post_slug as string,
  authorId: num(r.author_id),
  authorName: r.author_name as string,
  parentId: numOrNull(r.parent_id),
  body: r.body as string,
  status: r.status as CommentStatus,
  moderation: {
    label: r.moderation_label as Label,
    confidence: Number(r.moderation_confidence), // NUMERIC(3,2) arrives as the string "0.75"
    provider: r.moderation_provider as "rules" | "llm",
    fallback: r.moderation_fallback as boolean,
    reason: r.moderation_reason as string,
  },
  createdAt: r.created_at as Date,
  moderatedBy: numOrNull(r.moderated_by),
  moderatedAt: (r.moderated_at as Date | null) ?? null,
});

type Queryable = Pick<pg.PoolClient, "query">;

export async function replaceTags(db: Queryable, postId: number, tags: string[]): Promise<void> {
  await db.query("DELETE FROM post_tags WHERE post_id = $1", [postId]);
  if (tags.length) {
    await db.query("INSERT INTO tags (name) SELECT unnest($1::text[]) ON CONFLICT (name) DO NOTHING", [tags]);
    await db.query("INSERT INTO post_tags (post_id, tag_id) SELECT $1, id FROM tags WHERE name = ANY($2)", [
      postId,
      tags,
    ]);
  }
}

export async function inTransaction<T>(
  pool: pg.Pool,
  work: (client: pg.PoolClient) => Promise<T>,
): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const result = await work(client);
    await client.query("COMMIT");
    return result;
  } catch (err) {
    await client.query("ROLLBACK").catch(() => {});
    throw err;
  } finally {
    client.release();
  }
}

export class PgUserRepository implements UserRepository {
  constructor(private readonly pool: pg.Pool) {}

  private async one(sql: string, params: unknown[]): Promise<Row | null> {
    const { rows } = await this.pool.query(sql, params);
    return rows[0] ?? null;
  }

  async insertUser(email: string, passwordHash: string, displayName: string, role: Role, at: Date) {
    // TODO(phase-3 M2): INSERT ... RETURNING; unique violation on email -> DuplicateError.
    return todo("TODO(phase-3 M2): PgUserRepository.insertUser");
  }

  async findUserByEmail(email: string) {
    // TODO(phase-3 M2): SELECT by email (already lower-cased).
    return todo("TODO(phase-3 M2): PgUserRepository.findUserByEmail");
  }

  async findUser(id: number) {
    // TODO(phase-3 M2): SELECT by id.
    return todo("TODO(phase-3 M2): PgUserRepository.findUser");
  }

  async updateRole(id: number, role: Role) {
    // TODO(phase-3 M2): UPDATE ... RETURNING; null when the user does not exist.
    return todo("TODO(phase-3 M2): PgUserRepository.updateRole");
  }

  async insertRefreshToken(userId: number, tokenHash: string, at: Date, expiresAt: Date) {
    // TODO(phase-3 M2): store user id, token HASH, created_at, expires_at.
    return todo("TODO(phase-3 M2): PgUserRepository.insertRefreshToken");
  }

  async findRefreshToken(tokenHash: string) {
    // TODO(phase-3 M2): SELECT by token hash.
    return todo("TODO(phase-3 M2): PgUserRepository.findRefreshToken");
  }

  async revokeRefreshToken(id: number, at: Date) {
    // TODO(phase-3 M2): compare-and-set: UPDATE ... SET revoked_at WHERE id = ? AND revoked_at IS NULL;
    // return true only if a row changed (this is what makes concurrent refreshes safe).
    return todo("TODO(phase-3 M2): PgUserRepository.revokeRefreshToken");
  }

  async revokeAllRefreshTokens(userId: number, at: Date) {
    // TODO(phase-3 M2): revoke every not-yet-revoked token of the user.
    return todo("TODO(phase-3 M2): PgUserRepository.revokeAllRefreshTokens");
  }

  async ping() {
    try {
      await this.pool.query("SELECT 1");
      return true;
    } catch {
      return false; // health checks must never throw
    }
  }
}

export class PgPostRepository implements PostRepository {
  constructor(private readonly pool: pg.Pool) {}

  private async selectOne(db: Queryable, where: string, params: unknown[]): Promise<Post | null> {
    const { rows } = await db.query(POST_SELECT + where, params);
    return rows[0] ? toPost(rows[0]) : null;
  }

  async insertPost(post: Post) {
    // TODO(phase-3 M3): one transaction: INSERT the post (unique violation on slug -> DuplicateError),
    // upsert the tags, link them, return the post read back with author name and tags.
    return todo("TODO(phase-3 M3): PgPostRepository.insertPost");
  }

  findPost(slug: string) {
    // TODO(phase-3 M3): by slug, not deleted, with author name and tags.
    return todo("TODO(phase-3 M3): PgPostRepository.findPost");
  }

  updatePost(post: Post, expectedVersion: number) {
    // TODO(phase-3 M3): UPDATE ... WHERE id = ? AND version = ? AND deleted_at IS NULL, version = version
    // + 1; 0 rows -> null (conflict). Replace the tags in the same transaction.
    return todo("TODO(phase-3 M3): PgPostRepository.updatePost");
  }

  async softDeletePost(id: number, at: Date) {
    // TODO(phase-3 M3): set deleted_at; never DELETE the row.
    return todo("TODO(phase-3 M3): PgPostRepository.softDeletePost");
  }

  async feed(query: FeedQuery) {
    // TODO(phase-3 M4): WHERE published AND not deleted [AND tag] [AND search_vector @@
    // websearch_to_tsquery('english', q)] [AND (published_at, id) < (cursor)] ORDER BY published_at DESC,
    // id DESC LIMIT n. Uses the index from V1.
    return todo("TODO(phase-3 M4): PgPostRepository.feed");
  }

  async tagCounts() {
    // TODO(phase-3 M4): SELECT name, COUNT(*) over published, non-deleted posts GROUP BY name ORDER BY
    // name.
    return todo("TODO(phase-3 M4): PgPostRepository.tagCounts");
  }
}

export class PgCommentRepository implements CommentRepository {
  constructor(private readonly pool: pg.Pool) {}

  private async query(where: string, params: unknown[]): Promise<Comment[]> {
    const { rows } = await this.pool.query(COMMENT_SELECT + where, params);
    return rows.map(toComment);
  }

  async insertComment(c: Comment) {
    // TODO(phase-3 M5): INSERT with the moderation columns, read it back with author name and post slug.
    return todo("TODO(phase-3 M5): PgCommentRepository.insertComment");
  }

  async findComment(id: number) {
    // TODO(phase-3 M5): by id, not deleted.
    return todo("TODO(phase-3 M5): PgCommentRepository.findComment");
  }

  visibleComments(postId: number) {
    // TODO(phase-3 M5): status 'visible', not deleted, ORDER BY created_at, id.
    return todo("TODO(phase-3 M5): PgCommentRepository.visibleComments");
  }

  commentsByStatus(status: CommentStatus) {
    // TODO(phase-3 M5): by status, not deleted, post not deleted, ORDER BY created_at, id.
    return todo("TODO(phase-3 M5): PgCommentRepository.commentsByStatus");
  }

  async setCommentStatus(id: number, status: CommentStatus, by: number, at: Date) {
    // TODO(phase-3 M5): UPDATE status, moderated_by, moderated_at RETURNING; null if missing.
    return todo("TODO(phase-3 M5): PgCommentRepository.setCommentStatus");
  }

  async softDeleteComment(id: number, at: Date) {
    // TODO(phase-3 M5): set deleted_at.
    return todo("TODO(phase-3 M5): PgCommentRepository.softDeleteComment");
  }
}

// ---------------------------------------------------------------------------------------------
// In-memory (unit tests, and a database-free demo)
// ---------------------------------------------------------------------------------------------
const WORD = /[a-z0-9]+/g;
const words = (s: string) => s.toLowerCase().match(WORD) ?? [];
const byTimeThenId = (a: { t: number; id: number }, b: { t: number; id: number }) => a.t - b.t || a.id - b.id;

/** One object implements all three ports, like one database holds all tables. */
export class InMemoryStore implements UserRepository, PostRepository, CommentRepository {
  readonly users = new Map<number, User>();
  readonly tokens = new Map<number, RefreshToken>();
  readonly posts = new Map<number, Post>();
  readonly deletedPosts = new Set<number>();
  readonly comments = new Map<number, Comment>();
  readonly deletedComments = new Set<number>();
  private readonly ids = { user: 0, token: 0, post: 0, comment: 0 };
  healthy = true;

  private next(kind: keyof InMemoryStore["ids"]): number {
    return ++this.ids[kind];
  }

  // ---- users + refresh tokens ----
  async insertUser(email: string, passwordHash: string, displayName: string, role: Role, at: Date) {
    if ([...this.users.values()].some((u) => u.email === email)) throw new DuplicateError("email");
    const user: User = { id: this.next("user"), email, passwordHash, displayName, role, createdAt: at };
    this.users.set(user.id, user);
    return { ...user };
  }

  async findUserByEmail(email: string) {
    const found = [...this.users.values()].find((u) => u.email === email);
    return found ? { ...found } : null;
  }

  async findUser(id: number) {
    const found = this.users.get(id);
    return found ? { ...found } : null;
  }

  async updateRole(id: number, role: Role) {
    const user = this.users.get(id);
    if (!user) return null;
    user.role = role;
    return { ...user };
  }

  async insertRefreshToken(userId: number, tokenHash: string, at: Date, expiresAt: Date) {
    if ([...this.tokens.values()].some((t) => t.tokenHash === tokenHash)) throw new DuplicateError("token");
    const id = this.next("token");
    this.tokens.set(id, { id, userId, tokenHash, createdAt: at, expiresAt, revokedAt: null });
  }

  async findRefreshToken(tokenHash: string) {
    const found = [...this.tokens.values()].find((t) => t.tokenHash === tokenHash);
    return found ? { ...found } : null;
  }

  async revokeRefreshToken(id: number, at: Date) {
    const token = this.tokens.get(id);
    if (!token || token.revokedAt) return false;
    token.revokedAt = at;
    return true;
  }

  async revokeAllRefreshTokens(userId: number, at: Date) {
    for (const t of this.tokens.values()) if (t.userId === userId && !t.revokedAt) t.revokedAt = at;
  }

  async ping() {
    return this.healthy;
  }

  // ---- posts ----
  private withAuthor(post: Post): Post {
    return { ...post, authorName: this.users.get(post.authorId)!.displayName, tags: [...post.tags] };
  }

  async insertPost(post: Post) {
    if ([...this.posts.values()].some((p) => p.slug === post.slug)) throw new DuplicateError("slug");
    const stored = { ...post, id: this.next("post"), version: 1, tags: [...post.tags].sort() };
    this.posts.set(stored.id, stored);
    return this.withAuthor(stored);
  }

  async findPost(slug: string) {
    const found = [...this.posts.values()].find((p) => p.slug === slug && !this.deletedPosts.has(p.id));
    return found ? this.withAuthor(found) : null;
  }

  async updatePost(post: Post, expectedVersion: number) {
    const current = this.posts.get(post.id);
    if (!current || this.deletedPosts.has(post.id) || current.version !== expectedVersion) return null;
    const stored = {
      ...post,
      version: current.version + 1,
      tags: [...post.tags].sort(),
      createdAt: current.createdAt,
    };
    this.posts.set(post.id, stored);
    return this.withAuthor(stored);
  }

  async softDeletePost(id: number) {
    this.deletedPosts.add(id);
  }

  async feed(query: FeedQuery) {
    const wanted = query.q ? words(query.q) : [];
    const after = query.after;
    const result = [...this.posts.values()].filter((p) => {
      if (p.status !== "published" || this.deletedPosts.has(p.id)) return false;
      if (query.tag && !p.tags.includes(query.tag)) return false;
      if (wanted.length) {
        const have = new Set(words(`${p.title} ${p.body}`));
        if (!wanted.every((w) => have.has(w))) return false;
      }
      if (after) {
        const t = p.publishedAt!.getTime();
        const a = after.publishedAt.getTime();
        if (t > a || (t === a && p.id >= after.id)) return false;
      }
      return true;
    });
    result.sort((a, b) => b.publishedAt!.getTime() - a.publishedAt!.getTime() || b.id - a.id);
    return result.slice(0, query.limit).map((p) => this.withAuthor(p));
  }

  async tagCounts() {
    const counts = new Map<string, number>();
    for (const p of this.posts.values()) {
      if (p.status === "published" && !this.deletedPosts.has(p.id)) {
        for (const t of p.tags) counts.set(t, (counts.get(t) ?? 0) + 1);
      }
    }
    return [...counts.entries()]
      .sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))
      .map(([name, posts]) => ({ name, posts }));
  }

  // ---- comments ----
  private commentView(c: Comment): Comment {
    return {
      ...c,
      authorName: this.users.get(c.authorId)!.displayName,
      postSlug: this.posts.get(c.postId)!.slug,
    };
  }

  async insertComment(comment: Comment) {
    const stored = { ...comment, id: this.next("comment") };
    this.comments.set(stored.id, stored);
    return this.commentView(stored);
  }

  async findComment(id: number) {
    const c = this.comments.get(id);
    return c && !this.deletedComments.has(id) ? this.commentView(c) : null;
  }

  private live(): Comment[] {
    return [...this.comments.values()]
      .filter((c) => !this.deletedComments.has(c.id))
      .sort((a, b) =>
        byTimeThenId({ t: a.createdAt.getTime(), id: a.id }, { t: b.createdAt.getTime(), id: b.id }),
      );
  }

  async visibleComments(postId: number) {
    return this.live()
      .filter((c) => c.postId === postId && c.status === "visible")
      .map((c) => this.commentView(c));
  }

  async commentsByStatus(status: CommentStatus) {
    return this.live()
      .filter((c) => c.status === status && !this.deletedPosts.has(c.postId))
      .map((c) => this.commentView(c));
  }

  async setCommentStatus(id: number, status: CommentStatus, by: number, at: Date) {
    const c = this.comments.get(id);
    if (!c || this.deletedComments.has(id)) return null;
    this.comments.set(id, { ...c, status, moderatedBy: by, moderatedAt: at });
    return this.findComment(id);
  }

  async softDeleteComment(id: number) {
    this.deletedComments.add(id);
  }
}

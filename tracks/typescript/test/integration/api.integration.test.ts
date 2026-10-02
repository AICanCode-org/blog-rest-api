// Integration tests: the real app against a real PostgreSQL (docker compose up -d postgres).
// Run with:  make test-integration
// Uses TEST_DATABASE_URL so your dev data is never touched.
import type { FastifyInstance } from "fastify";
import pg from "pg";
import { afterAll, beforeAll, beforeEach, expect, it } from "vitest";
import { buildApp } from "../../src/app.js";
import { loadSettings } from "../../src/config.js";
import { createPool, runMigrations } from "../../src/db.js";
import { PgCommentRepository, PgPostRepository, PgUserRepository } from "../../src/repository.js";
import { wire } from "../../src/wiring.js";
import { offlineAi, testSettings, type Session } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("integration");
const DB_URL = process.env.TEST_DATABASE_URL ?? "postgresql://devblog:devblog@localhost:5432/devblog_test";
const ADMIN = { email: "admin@devblog.test", password: "admin-password" };

describe("API against PostgreSQL", () => {
  let pool: pg.Pool;
  let app: FastifyInstance;
  const sql = async (text: string, params: unknown[] = []) => (await pool.query(text, params)).rows;

  beforeAll(async () => {
    pool = createPool(DB_URL);
    await runMigrations(pool, loadSettings({}).migrationsDir);
  });
  afterAll(async () => {
    await app?.close();
    await pool?.end();
  });
  beforeEach(async () => {
    await sql("TRUNCATE users, refresh_tokens, posts, tags, post_tags, comments RESTART IDENTITY CASCADE");
    const repos = {
      users: new PgUserRepository(pool),
      posts: new PgPostRepository(pool),
      comments: new PgCommentRepository(pool),
    };
    const settings = testSettings({ adminEmail: ADMIN.email, adminPassword: ADMIN.password });
    await app?.close();
    app = buildApp(await wire(settings, repos, { ai: offlineAi(), warn: () => {} }));
  });

  const req = (method: "GET" | "POST" | "PATCH" | "DELETE", url: string, payload?: unknown, s?: Session) =>
    app.inject({
      method,
      url,
      payload: payload as never,
      headers: s ? { authorization: `Bearer ${s.accessToken}` } : {},
    });

  async function login(email: string, password = "password-123"): Promise<Session> {
    const r = await req("POST", "/api/v1/auth/login", { email, password });
    expect(r.statusCode, r.body).toBe(200);
    return r.json();
  }

  async function makeUser(email: string, role = "READER"): Promise<Session> {
    const r = await req("POST", "/api/v1/auth/register", {
      email,
      password: "password-123",
      displayName: email.slice(0, 5),
    });
    expect(r.statusCode, r.body).toBe(201);
    if (role !== "READER") {
      const admin = await login(ADMIN.email, ADMIN.password);
      await req("PATCH", `/api/v1/admin/users/${r.json().id}/role`, { role }, admin);
    }
    return login(email);
  }

  const create = async (s: Session, body: Record<string, unknown>) =>
    (await req("POST", "/api/v1/posts", body, s)).json();

  it("is healthy and records the migrations", async () => {
    expect((await req("GET", "/health")).json()).toEqual({ status: "UP", checks: { database: "UP" } });
    const versions = (await sql("SELECT version FROM schema_migrations ORDER BY version")).map(
      (r) => r.version,
    );
    expect(versions.slice(0, 2)).toEqual([1, 2]);
  });

  it("never stores passwords or refresh tokens in clear", async () => {
    const session = await makeUser("asha@devblog.test");
    const [{ password_hash }] = await sql(
      "SELECT password_hash FROM users WHERE email = 'asha@devblog.test'",
    );
    expect(password_hash.startsWith("$2")).toBe(true);
    const hashes = (await sql("SELECT token_hash FROM refresh_tokens")).map((r) => r.token_hash);
    expect(hashes).not.toContain(session.refreshToken);
    expect(hashes.every((h) => h.length === 64)).toBe(true);
  });

  it("enforces unique e-mails in the database", async () => {
    await makeUser("dup@devblog.test");
    const r = await req("POST", "/api/v1/auth/register", {
      email: "DUP@devblog.test",
      password: "password-123",
      displayName: "D",
    });
    expect(r.statusCode).toBe(409);
  });

  it("lets exactly one of several concurrent refreshes with one token win", async () => {
    const session = await makeUser("race@devblog.test");
    const results = await Promise.all(
      [1, 2, 3, 4].map(() => req("POST", "/api/v1/auth/refresh", { refreshToken: session.refreshToken })),
    );
    const statuses = results.map((r) => r.statusCode).sort();
    expect(statuses).toEqual([200, 401, 401, 401]);
  });

  it("keeps slugs unique and shares tags", async () => {
    const author = await makeUser("ada@devblog.test", "AUTHOR");
    const body = { title: "Same Title", body: "Body.", status: "published", tags: ["java", "sql"] };
    const slugs = [];
    for (let i = 0; i < 3; i++) slugs.push((await create(author, body)).slug);
    expect(slugs).toEqual(["same-title", "same-title-2", "same-title-3"]);
    expect((await sql("SELECT count(*)::int AS n FROM tags"))[0].n).toBe(2);
    expect((await req("GET", "/api/v1/tags")).json().items).toEqual([
      { name: "java", posts: 3 },
      { name: "sql", posts: 3 },
    ]);
  });

  it("uses English stemming for full-text search", async () => {
    const author = await makeUser("ada@devblog.test", "AUTHOR");
    await create(author, {
      title: "Testing with containers",
      body: "We run tests against PostgreSQL.",
      status: "published",
    });
    await create(author, { title: "Cooking", body: "Pasta.", status: "published" });
    const found = (await req("GET", "/api/v1/posts?q=test%20container")).json().items;
    expect(found.map((p: { title: string }) => p.title)).toEqual(["Testing with containers"]);
    expect(
      (await req("GET", `/api/v1/posts?q=${encodeURIComponent('"exact phrase" -pasta')}`)).statusCode,
    ).toBe(200);
  });

  it("paginates over the real index, using the id to break ties", async () => {
    const author = await makeUser("ada@devblog.test", "AUTHOR");
    for (let i = 0; i < 25; i++) await create(author, { title: `P${i}`, body: "x.", status: "published" });
    await sql("UPDATE posts SET published_at = '2026-10-02T09:30:00Z'");
    const seen: number[] = [];
    let cursor: string | null = null;
    do {
      const page: { items: { id: number }[]; nextCursor: string | null } = (
        await req("GET", `/api/v1/posts?limit=10${cursor ? `&cursor=${cursor}` : ""}`)
      ).json();
      seen.push(...page.items.map((p) => p.id));
      cursor = page.nextCursor;
    } while (cursor);
    expect(seen).toHaveLength(25);
    expect(new Set(seen).size).toBe(25);
    expect(seen).toEqual([...seen].sort((a, b) => b - a));
  });

  it("detects a version conflict in the UPDATE statement", async () => {
    const author = await makeUser("ada@devblog.test", "AUTHOR");
    const { slug } = await create(author, { title: "Race", body: "v1" });
    expect((await req("PATCH", `/api/v1/posts/${slug}`, { version: 1, body: "v2" }, author)).statusCode).toBe(
      200,
    );
    expect((await req("PATCH", `/api/v1/posts/${slug}`, { version: 1, body: "v3" }, author)).statusCode).toBe(
      409,
    );
    expect(await sql("SELECT body, version FROM posts WHERE slug = $1", [slug])).toEqual([
      { body: "v2", version: 2 },
    ]);
  });

  it("keeps the row on soft delete", async () => {
    const author = await makeUser("ada@devblog.test", "AUTHOR");
    const { slug } = await create(author, { title: "Gone", body: "b", status: "published" });
    expect((await req("DELETE", `/api/v1/posts/${slug}`, undefined, author)).statusCode).toBe(204);
    expect((await req("GET", `/api/v1/posts/${slug}`)).statusCode).toBe(404);
    expect(await sql("SELECT deleted_at IS NOT NULL AS gone FROM posts WHERE slug = $1", [slug])).toEqual([
      { gone: true },
    ]);
  });

  it("persists comments and moderation", async () => {
    const author = await makeUser("ada@devblog.test", "AUTHOR");
    const reader = await makeUser("rita@devblog.test");
    const { slug } = await create(author, { title: "Talk", body: "b", status: "published" });
    const url = `/api/v1/posts/${slug}/comments`;
    const ok = (await req("POST", url, { body: "Nice" }, reader)).json();
    const held = (await req("POST", url, { body: "see https://a.example https://b.example" }, reader)).json();
    expect(held.moderation).toEqual({
      label: "spam",
      confidence: 0.75,
      provider: "rules",
      fallback: false,
      reason: "2 links",
    });
    const reply = await req("POST", url, { body: "Thanks", parentId: ok.id }, author);
    expect(reply.statusCode).toBe(201);
    expect((await req("GET", url)).json().items.map((c: { id: number }) => c.id)).toEqual([
      ok.id,
      reply.json().id,
    ]);
    const admin = await login(ADMIN.email, ADMIN.password);
    expect(
      (await req("GET", "/api/v1/admin/comments", undefined, admin))
        .json()
        .items.map((c: { id: number }) => c.id),
    ).toEqual([held.id]);
    await req("POST", `/api/v1/admin/comments/${held.id}/decision`, { decision: "reject" }, admin);
    expect(
      await sql("SELECT status, moderated_by IS NOT NULL AS by FROM comments WHERE id = $1", [held.id]),
    ).toEqual([{ status: "rejected", by: true }]);
  });

  it("stores the cover image URL (V2 migration)", async () => {
    const author = await makeUser("ada@devblog.test", "AUTHOR");
    const cover = "https://img.example/c.png";
    const { slug } = await create(author, {
      title: "Cover",
      body: "b",
      status: "published",
      coverImageUrl: cover,
    });
    expect(await sql("SELECT cover_image_url FROM posts WHERE slug = $1", [slug])).toEqual([
      { cover_image_url: cover },
    ]);
    expect((await req("GET", "/api/v1/posts")).json().items[0].coverImageUrl).toBe(cover);
  });

  it("bootstraps the admin from the environment", async () => {
    expect(await sql("SELECT role FROM users WHERE email = $1", [ADMIN.email])).toEqual([{ role: "ADMIN" }]);
  });

  it("reports the database as down when it is unreachable", async () => {
    const other = createPool(DB_URL);
    const repo = new PgUserRepository(other);
    expect(await repo.ping()).toBe(true);
    await other.end(); // simulate the database going away: every new query now fails
    expect(await repo.ping()).toBe(false);
  });
});

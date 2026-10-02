import { expect } from "vitest";
import type { FastifyInstance } from "fastify";
import { ModerationService, RuleModerator, SummaryService, TemplateSummarizer } from "../src/ai.js";
import { buildApp } from "../src/app.js";
import type { Clock } from "../src/clock.js";
import type { Principal, Role } from "../src/models.js";
import { loadSettings, type Settings } from "../src/config.js";
import { ApiError } from "../src/errors.js";
import { InMemoryStore } from "../src/repository.js";
import type { Metrics } from "../src/metrics.js";
import { wire, type buildAi } from "../src/wiring.js";

export const TEST_SECRET = "test-secret-that-is-at-least-32-bytes-long";

/** A clock you can move. Tests should never depend on the real time. */
export class FakeClock {
  now = new Date("2026-10-02T09:30:00.000Z");
  readonly fn: Clock = () => this.now;
  advance({ days = 0, hours = 0, minutes = 0, seconds = 0 }) {
    this.now = new Date(this.now.getTime() + ((days * 24 + hours) * 60 + minutes) * 60_000 + seconds * 1000);
  }
}

export const silentLog = { warn: () => {} };

export function testSettings(overrides: Partial<Settings> = {}): Settings {
  return {
    ...loadSettings({}),
    jwtSecret: TEST_SECRET,
    bcryptCost: 4,
    adminEmail: null,
    adminPassword: null,
    aiProvider: "offline",
    ...overrides,
  };
}

export const offlineAi = () => ({
  moderation: new ModerationService(new RuleModerator()),
  summaries: new SummaryService(new TemplateSummarizer()),
});

/** Expects `promise` to reject with an ApiError and returns [status, slug]. */
export async function statusOf(promise: Promise<unknown>): Promise<[number, string]> {
  try {
    await promise;
  } catch (err) {
    expect(err).toBeInstanceOf(ApiError);
    return [(err as ApiError).status, (err as ApiError).slug];
  }
  throw new Error("expected an ApiError");
}

export interface Session {
  accessToken: string;
  refreshToken: string;
  user: { id: number; email: string; role: string; displayName: string };
}

/** The real Fastify app on in-memory adapters (no database, no network) + helpers that read like user stories. */
export class Api {
  private n = 0;
  constructor(
    readonly app: FastifyInstance,
    readonly store: InMemoryStore,
    readonly clock: FakeClock,
  ) {}

  static async create(
    opts: { settings?: Partial<Settings>; ai?: ReturnType<typeof buildAi>; metrics?: Metrics } = {},
  ): Promise<Api> {
    const store = new InMemoryStore();
    const clock = new FakeClock();
    const settings = testSettings({
      adminEmail: "admin@devblog.test",
      adminPassword: "admin-password",
      ...opts.settings,
    });
    const ai = opts.ai ?? (settings.aiProvider === "llm" ? undefined : offlineAi());
    const deps = await wire(
      settings,
      { users: store, posts: store, comments: store },
      { clock: clock.fn, ai, warn: () => {}, metrics: opts.metrics },
    );
    return new Api(buildApp(deps), store, clock);
  }

  request(
    method: "GET" | "POST" | "PATCH" | "DELETE",
    url: string,
    payload?: unknown,
    session?: Session | string,
  ) {
    const headers: Record<string, string> = {};
    if (typeof session === "string") headers.authorization = session;
    else if (session) headers.authorization = `Bearer ${session.accessToken}`;
    return this.app.inject({ method, url, payload: payload as never, headers });
  }

  async user(role = "READER", name?: string): Promise<Session> {
    this.n += 1;
    const email = `user${this.n}@devblog.test`;
    const r = await this.request("POST", "/api/v1/auth/register", {
      email,
      password: "password-123",
      displayName: name ?? `User ${this.n}`,
    });
    expect(r.statusCode, r.body).toBe(201);
    if (role !== "READER") this.store.users.get(r.json().id)!.role = role as never;
    return this.login(email, "password-123");
  }

  admin(): Promise<Session> {
    return this.login("admin@devblog.test", "admin-password");
  }

  async login(email: string, password: string): Promise<Session> {
    const r = await this.request("POST", "/api/v1/auth/login", { email, password });
    expect(r.statusCode, r.body).toBe(200);
    return r.json();
  }

  async post(session: Session, fields: Record<string, unknown> = {}) {
    const body = {
      title: "Hello DevBlog",
      body: "First post. It has two sentences.",
      status: "published",
      ...fields,
    };
    const r = await this.request("POST", "/api/v1/posts", body, session);
    expect(r.statusCode, r.body).toBe(201);
    return r.json();
  }
}

/** Users created directly in the store (service-level tests): {ada: {userId, role}, ...}. */
export async function makePeople(store: InMemoryStore, clock: FakeClock, list: [string, Role][]) {
  const people: Record<string, Principal> = {};
  for (const [name, role] of list) {
    const title = name[0]!.toUpperCase() + name.slice(1);
    const user = await store.insertUser(`${name}@devblog.test`, "x", title, role, clock.now);
    people[name] = { userId: user.id, role };
  }
  return people;
}

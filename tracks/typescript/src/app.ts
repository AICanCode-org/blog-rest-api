// HTTP layer (Fastify). Thin: read the request, call a service, shape the JSON, map errors.
import Fastify, {
  LogController,
  type FastifyInstance,
  type FastifyReply,
  type FastifyRequest,
  type FastifyServerOptions,
} from "fastify";
import type { AuthService } from "./auth.js";
import { iso, type Clock } from "./clock.js";
import type { CommentService } from "./comments.js";
import { ApiError, notFound, validationError } from "./errors.js";
import type { Metrics } from "./metrics.js";
import type { Comment, Post, TokenPair, User } from "./models.js";
import { incomingOrNewId, REQUEST_ID_HEADER } from "./observability.js";
import type { PostService } from "./posts.js";
import { ACCESS_TOKEN_TTL_SECONDS } from "./security.js";
import { excerpt } from "./text.js";
import { pathId } from "./validation.js";

const PROBLEM_JSON = "application/problem+json";

export interface AppDeps {
  auth: AuthService;
  posts: PostService;
  comments: CommentService;
  clock: Clock;
  metrics: Metrics;
}

// ---- JSON shapes (identical in every track, see spec/openapi.yaml) ------------------------------
export const userJson = (u: User) => ({
  id: u.id,
  email: u.email,
  displayName: u.displayName,
  role: u.role,
  createdAt: iso(u.createdAt),
});

export const tokensJson = (t: TokenPair) => ({
  accessToken: t.accessToken,
  refreshToken: t.refreshToken,
  tokenType: "Bearer",
  expiresIn: ACCESS_TOKEN_TTL_SECONDS,
  user: userJson(t.user),
});

export const postJson = (p: Post) => ({
  id: p.id,
  slug: p.slug,
  title: p.title,
  body: p.body,
  status: p.status,
  tags: p.tags,
  author: { id: p.authorId, displayName: p.authorName },
  createdAt: iso(p.createdAt),
  updatedAt: iso(p.updatedAt),
  publishedAt: iso(p.publishedAt),
  coverImageUrl: p.coverImageUrl,
  version: p.version,
});

export const postItemJson = (p: Post) => ({
  id: p.id,
  slug: p.slug,
  title: p.title,
  excerpt: excerpt(p.body),
  tags: p.tags,
  author: { id: p.authorId, displayName: p.authorName },
  publishedAt: iso(p.publishedAt),
  coverImageUrl: p.coverImageUrl,
});

export const commentJson = (c: Comment) => ({
  id: c.id,
  postSlug: c.postSlug,
  parentId: c.parentId,
  author: { id: c.authorId, displayName: c.authorName },
  body: c.body,
  status: c.status,
  moderation: {
    label: c.moderation.label,
    confidence: c.moderation.confidence,
    provider: c.moderation.provider,
    fallback: c.moderation.fallback,
    reason: c.moderation.reason,
  },
  createdAt: iso(c.createdAt),
});

type Params = Record<string, string>;
/** A repeated query parameter (?limit=1&limit=2) counts as its last value, like in the other tracks. */
function queryParam(req: FastifyRequest, name: string): string | undefined {
  const value = (req.query as Record<string, string | string[] | undefined>)[name];
  return Array.isArray(value) ? value[value.length - 1] : value;
}

function sendProblem(reply: FastifyReply, err: ApiError) {
  if (err.slug === "unauthorized") reply.header("www-authenticate", "Bearer");
  return reply.code(err.status).type(PROBLEM_JSON).send(err.toProblem());
}

export function buildApp(deps: AppDeps, opts: FastifyServerOptions = {}): FastifyInstance {
  const app = Fastify({
    // Request IDs (v1.1): one ID per request, echoed back and attached to every log line.
    requestIdHeader: false,
    genReqId: (req) => incomingOrNewId(req.headers[REQUEST_ID_HEADER]),
    logController: new LogController({ requestIdLogLabel: "requestId" }),
    ...opts,
  });
  app.addHook("onSend", async (request, reply) => {
    reply.header(REQUEST_ID_HEADER, request.id);
  });
  const { auth, posts, comments, clock, metrics } = deps;
  const authHeader = (req: FastifyRequest) => req.headers.authorization;

  // ---- error mapping ---------------------------------------------------
  app.setErrorHandler((err, request, reply) => {
    if (err instanceof ApiError) return sendProblem(reply, err);
    const status = (err as { statusCode?: number }).statusCode;
    if (status && status >= 400 && status < 500) {
      // Malformed JSON, empty body, unsupported media type...
      return sendProblem(reply, validationError("request body must be a JSON object"));
    }
    request.log.error(err);
    return sendProblem(reply, new ApiError(500, "internal-error", "Internal server error"));
  });
  app.setNotFoundHandler((_req, reply) => sendProblem(reply, notFound("route")));

  // ---- ops -----------------------------------------------------------------
  app.get("/health", async (_req, reply) => {
    const dbUp = await auth.users.ping();
    return reply
      .code(dbUp ? 200 : 503)
      .send({ status: dbUp ? "UP" : "DOWN", checks: { database: dbUp ? "UP" : "DOWN" } });
  });
  app.get("/metrics", async (_req, reply) => reply.type("text/plain; version=0.0.4").send(metrics.render()));

  // ---- auth ----------------------------------------------------------------
  app.post("/api/v1/auth/register", async (req, reply) => {
    const user = await auth.register(req.body);
    metrics.inc("devblog_users_registered_total");
    return reply.code(201).send(userJson(user));
  });
  app.post("/api/v1/auth/login", async (req) => {
    try {
      return tokensJson(await auth.login(req.body));
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) metrics.inc("devblog_logins_failed_total");
      throw err;
    }
  });
  app.post("/api/v1/auth/refresh", async (req) => tokensJson(await auth.refresh(req.body)));
  app.post("/api/v1/auth/logout", async (req, reply) => {
    await auth.logout(req.body);
    return reply.code(204).send();
  });
  app.get("/api/v1/me", async (req) => userJson(await auth.me(await auth.authenticate(authHeader(req)))));

  // ---- posts ---------------------------------------------------------------
  app.get("/api/v1/posts", async (req) => {
    const page = await posts.feed({
      limit: queryParam(req, "limit"),
      cursor: queryParam(req, "cursor"),
      tag: queryParam(req, "tag"),
      q: queryParam(req, "q"),
    });
    return { items: page.items.map(postItemJson), nextCursor: page.nextCursor };
  });

  app.post("/api/v1/posts", async (req, reply) => {
    const post = await posts.create(await auth.authenticate(authHeader(req)), req.body);
    return reply.code(201).header("location", `/api/v1/posts/${post.slug}`).send(postJson(post));
  });

  app.get("/api/v1/posts/:slug", async (req) => {
    const { slug } = req.params as Params;
    return postJson(await posts.get(await auth.optional(authHeader(req)), slug!));
  });

  app.patch("/api/v1/posts/:slug", async (req) => {
    const { slug } = req.params as Params;
    return postJson(await posts.update(await auth.authenticate(authHeader(req)), slug!, req.body));
  });

  app.delete("/api/v1/posts/:slug", async (req, reply) => {
    const { slug } = req.params as Params;
    await posts.delete(await auth.authenticate(authHeader(req)), slug!);
    return reply.code(204).send();
  });

  app.get("/api/v1/posts/:slug/summary", async (req) => {
    const { slug } = req.params as Params;
    const { post, result } = await posts.summary(await auth.optional(authHeader(req)), slug!);
    if (result.fallback) metrics.inc("devblog_ai_fallback_total");
    return { slug: post.slug, ...result, generatedAt: iso(clock()) };
  });

  app.get("/api/v1/tags", async () => ({ items: await posts.tags() }));

  // ---- comments --------------------------------------------------------------
  app.get("/api/v1/posts/:slug/comments", async (req) => {
    const { slug } = req.params as Params;
    return { items: (await comments.list(slug!)).map(commentJson) };
  });

  app.post("/api/v1/posts/:slug/comments", async (req, reply) => {
    const { slug } = req.params as Params;
    const comment = await comments.create(await auth.authenticate(authHeader(req)), slug!, req.body);
    return reply.code(201).send(commentJson(comment));
  });

  app.delete("/api/v1/comments/:id", async (req, reply) => {
    const principal = await auth.authenticate(authHeader(req));
    await comments.delete(principal, pathId((req.params as Params).id!, "comment"));
    return reply.code(204).send();
  });

  // ---- admin -----------------------------------------------------------------
  app.patch("/api/v1/admin/users/:id/role", async (req) => {
    const principal = await auth.authenticate(authHeader(req));
    return userJson(await auth.changeRole(principal, pathId((req.params as Params).id!, "user"), req.body));
  });

  app.get("/api/v1/admin/comments", async (req) => {
    const principal = await auth.authenticate(authHeader(req));
    return { items: (await comments.queue(principal, queryParam(req, "status"))).map(commentJson) };
  });

  app.post("/api/v1/admin/comments/:id/decision", async (req) => {
    const principal = await auth.authenticate(authHeader(req));
    return commentJson(
      await comments.decide(principal, pathId((req.params as Params).id!, "comment"), req.body),
    );
  });

  return app;
}

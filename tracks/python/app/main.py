"""HTTP layer (FastAPI). Thin: read the request, call a service, shape the JSON, map errors."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Body, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, PlainTextResponse, Response
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import validation as v
from .ai import (
    CircuitBreaker,
    LlmClient,
    LlmModerator,
    LlmSummarizer,
    ModerationService,
    RuleModerator,
    SummaryService,
    TemplateSummarizer,
)
from .auth import AuthService
from .clock import Clock, iso, utc_now
from .comments import CommentService
from .config import Settings
from .db import create_pool, run_migrations
from .errors import ApiError, not_found, validation_error
from .metrics import Metrics
from .models import Comment, Post, TokenPair, User
from .observability import RequestIdMiddleware, configure_logging
from .posts import PostService
from .repository import (
    CommentRepository,
    PostgresCommentRepository,
    PostgresPostRepository,
    PostgresUserRepository,
    PostRepository,
    UserRepository,
)
from .security import ACCESS_TOKEN_TTL, PasswordHasher, TokenService, resolve_secret
from .text import excerpt

log = logging.getLogger("devblog")
PROBLEM_JSON = "application/problem+json"
Auth = Annotated[str | None, Header(alias="Authorization")]
JsonBody = Annotated[object, Body()]
configure_logging()


def build_ai(settings: Settings, metrics: Metrics | None = None) -> tuple[ModerationService, SummaryService]:
    if settings.ai_provider == "llm":
        counters = metrics or Metrics()
        client = CircuitBreaker(
            LlmClient(settings.llm_base_url, settings.llm_model, settings.llm_api_key, settings.llm_timeout_ms),
            on_short_circuit=lambda: counters.inc("devblog_llm_short_circuits_total"),
        )
        return (
            ModerationService(RuleModerator(), LlmModerator(client)),
            SummaryService(TemplateSummarizer(), LlmSummarizer(client)),
        )
    return ModerationService(RuleModerator()), SummaryService(TemplateSummarizer())


# ---- JSON shapes (identical in every track, see spec/openapi.yaml) ------------------------------
def user_json(u: User) -> dict:
    return {"id": u.id, "email": u.email, "displayName": u.display_name, "role": u.role, "createdAt": iso(u.created_at)}


def tokens_json(t: TokenPair) -> dict:
    return {
        "accessToken": t.access_token,
        "refreshToken": t.refresh_token,
        "tokenType": "Bearer",
        "expiresIn": int(ACCESS_TOKEN_TTL.total_seconds()),
        "user": user_json(t.user),
    }


def post_json(p: Post) -> dict:
    return {
        "id": p.id,
        "slug": p.slug,
        "title": p.title,
        "body": p.body,
        "status": p.status,
        "tags": p.tags,
        "author": {"id": p.author_id, "displayName": p.author_name},
        "createdAt": iso(p.created_at),
        "updatedAt": iso(p.updated_at),
        "publishedAt": iso(p.published_at),
        "coverImageUrl": p.cover_image_url,
        "version": p.version,
    }


def post_item_json(p: Post) -> dict:
    return {
        "id": p.id,
        "slug": p.slug,
        "title": p.title,
        "excerpt": excerpt(p.body),
        "tags": p.tags,
        "author": {"id": p.author_id, "displayName": p.author_name},
        "publishedAt": iso(p.published_at),
        "coverImageUrl": p.cover_image_url,
    }


def comment_json(c: Comment) -> dict:
    m = c.moderation
    return {
        "id": c.id,
        "postSlug": c.post_slug,
        "parentId": c.parent_id,
        "author": {"id": c.author_id, "displayName": c.author_name},
        "body": c.body,
        "status": c.status,
        "moderation": {
            "label": m.label,
            "confidence": m.confidence,
            "provider": m.provider,
            "fallback": m.fallback,
            "reason": m.reason,
        },
        "createdAt": iso(c.created_at),
    }


def create_app(
    settings: Settings | None = None,
    *,
    users: UserRepository | None = None,
    posts: PostRepository | None = None,
    comments: CommentRepository | None = None,
    clock: Clock = utc_now,
    ai: tuple[ModerationService, SummaryService] | None = None,
    metrics: Metrics | None = None,
) -> FastAPI:
    """Real adapters by default; tests pass in-memory repositories, a fake clock and fake AI."""
    settings = settings or Settings.from_env()
    metrics = metrics or Metrics()
    state: dict = {}

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        pool = None
        user_repo, post_repo, comment_repo = users, posts, comments
        if user_repo is None or post_repo is None or comment_repo is None:
            pool = create_pool(settings.database_url)
            applied = run_migrations(pool, settings.migrations_dir)
            log.info("migrations applied this start: %s", applied or "none")
            user_repo = PostgresUserRepository(pool)
            post_repo = PostgresPostRepository(pool)
            comment_repo = PostgresCommentRepository(pool)
        moderation, summaries = ai or build_ai(settings, metrics)
        tokens = TokenService(resolve_secret(settings.jwt_secret), clock)
        auth = AuthService(user_repo, PasswordHasher(settings.bcrypt_cost), tokens, clock)
        if settings.admin_email and settings.admin_password:
            auth.ensure_admin(settings.admin_email, settings.admin_password)
            log.info("admin account ensured for %s", settings.admin_email)
        state["auth"] = auth
        state["posts"] = PostService(post_repo, clock, summaries, metrics)
        state["comments"] = CommentService(comment_repo, post_repo, moderation, clock, metrics)
        state["clock"] = clock
        yield
        if pool is not None:
            pool.close()

    app = FastAPI(title="DevBlog (Python track)", version="1.1.0", lifespan=lifespan)
    app.add_middleware(RequestIdMiddleware)

    def auth() -> AuthService:
        return state["auth"]

    def post_service() -> PostService:
        return state["posts"]

    def comment_service() -> CommentService:
        return state["comments"]

    # ---- error mapping ---------------------------------------------------
    @app.exception_handler(ApiError)
    async def api_error_handler(_req: Request, exc: ApiError):
        headers = {"WWW-Authenticate": "Bearer"} if exc.slug == "unauthorized" else None
        return JSONResponse(exc.to_problem(), status_code=exc.status, media_type=PROBLEM_JSON, headers=headers)

    @app.exception_handler(RequestValidationError)
    async def request_validation_handler(_req: Request, _exc: RequestValidationError):
        problem = validation_error("request body must be a JSON object").to_problem()
        return JSONResponse(problem, status_code=400, media_type=PROBLEM_JSON)

    @app.exception_handler(StarletteHTTPException)
    async def http_error_handler(_req: Request, exc: StarletteHTTPException):
        # Unknown routes (and wrong methods) answer with problem+json too, never FastAPI's {"detail": ...}.
        error = (
            not_found("route") if exc.status_code == 404 else ApiError(exc.status_code, "http-error", str(exc.detail))
        )
        return JSONResponse(error.to_problem(), status_code=error.status, media_type=PROBLEM_JSON)

    @app.exception_handler(Exception)
    async def unexpected_handler(_req: Request, exc: Exception):
        log.exception("unhandled error", exc_info=exc)
        problem = ApiError(500, "internal-error", "Internal server error").to_problem()
        return JSONResponse(problem, status_code=500, media_type=PROBLEM_JSON)

    # ---- ops -----------------------------------------------------------------
    @app.get("/health")
    def health():
        db_up = auth().users.ping()
        body = {"status": "UP" if db_up else "DOWN", "checks": {"database": "UP" if db_up else "DOWN"}}
        return JSONResponse(body, status_code=200 if db_up else 503)

    @app.get("/metrics")
    def get_metrics():
        return PlainTextResponse(metrics.render(), media_type="text/plain; version=0.0.4")

    # ---- auth ----------------------------------------------------------------
    @app.post("/api/v1/auth/register", status_code=201)
    def register(payload: JsonBody):
        user = auth().register(payload)
        metrics.inc("devblog_users_registered_total")
        return JSONResponse(user_json(user), status_code=201)

    @app.post("/api/v1/auth/login")
    def login(payload: JsonBody):
        try:
            return tokens_json(auth().login(payload))
        except ApiError as exc:
            if exc.status == 401:
                metrics.inc("devblog_logins_failed_total")
            raise

    @app.post("/api/v1/auth/refresh")
    def refresh(payload: JsonBody):
        return tokens_json(auth().refresh(payload))

    @app.post("/api/v1/auth/logout", status_code=204)
    def logout(payload: JsonBody):
        auth().logout(payload)
        return Response(status_code=204)

    @app.get("/api/v1/me")
    def me(authorization: Auth = None):
        return user_json(auth().me(auth().authenticate(authorization)))

    # ---- posts ---------------------------------------------------------------
    @app.get("/api/v1/posts")
    def list_posts(limit: str | None = None, cursor: str | None = None, tag: str | None = None, q: str | None = None):
        page, next_cursor = post_service().feed(limit, cursor, tag, q)
        return {"items": [post_item_json(p) for p in page], "nextCursor": next_cursor}

    @app.post("/api/v1/posts", status_code=201)
    def create_post(payload: JsonBody, authorization: Auth = None):
        post = post_service().create(auth().authenticate(authorization), payload)
        return JSONResponse(post_json(post), status_code=201, headers={"Location": f"/api/v1/posts/{post.slug}"})

    @app.get("/api/v1/posts/{slug}")
    def get_post(slug: str, authorization: Auth = None):
        return post_json(post_service().get(auth().optional(authorization), slug))

    @app.patch("/api/v1/posts/{slug}")
    def update_post(slug: str, payload: JsonBody, authorization: Auth = None):
        return post_json(post_service().update(auth().authenticate(authorization), slug, payload))

    @app.delete("/api/v1/posts/{slug}", status_code=204)
    def delete_post(slug: str, authorization: Auth = None):
        post_service().delete(auth().authenticate(authorization), slug)
        return Response(status_code=204)

    @app.get("/api/v1/posts/{slug}/summary")
    def post_summary(slug: str, authorization: Auth = None):
        post, result = post_service().summary(auth().optional(authorization), slug)
        if result.fallback:
            metrics.inc("devblog_ai_fallback_total")
        return {
            "slug": post.slug,
            "provider": result.provider,
            "fallback": result.fallback,
            "summary": result.summary,
            "generatedAt": iso(state["clock"]()),
        }

    @app.get("/api/v1/tags")
    def list_tags():
        return {"items": [{"name": t.name, "posts": t.posts} for t in post_service().tags()]}

    # ---- comments --------------------------------------------------------------
    @app.get("/api/v1/posts/{slug}/comments")
    def list_comments(slug: str):
        return {"items": [comment_json(c) for c in comment_service().list(slug)]}

    @app.post("/api/v1/posts/{slug}/comments", status_code=201)
    def create_comment(slug: str, payload: JsonBody, authorization: Auth = None):
        comment = comment_service().create(auth().authenticate(authorization), slug, payload)
        return JSONResponse(comment_json(comment), status_code=201)

    @app.delete("/api/v1/comments/{comment_id}", status_code=204)
    def delete_comment(comment_id: str, authorization: Auth = None):
        principal = auth().authenticate(authorization)
        comment_service().delete(principal, v.path_id(comment_id, "comment"))
        return Response(status_code=204)

    # ---- admin -----------------------------------------------------------------
    @app.patch("/api/v1/admin/users/{user_id}/role")
    def change_role(user_id: str, payload: JsonBody, authorization: Auth = None):
        principal = auth().authenticate(authorization)
        return user_json(auth().change_role(principal, v.path_id(user_id, "user"), payload))

    @app.get("/api/v1/admin/comments")
    def moderation_queue(status: str | None = None, authorization: Auth = None):
        items = comment_service().queue(auth().authenticate(authorization), status)
        return {"items": [comment_json(c) for c in items]}

    @app.post("/api/v1/admin/comments/{comment_id}/decision")
    def decide(comment_id: str, payload: JsonBody, authorization: Auth = None):
        principal = auth().authenticate(authorization)
        return comment_json(comment_service().decide(principal, v.path_id(comment_id, "comment"), payload))

    return app

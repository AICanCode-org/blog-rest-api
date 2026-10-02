"""Persistence ports (Protocols) with two adapters each: PostgreSQL and in-memory (for unit tests).

The in-memory adapters behave like the database where it matters (unique e-mail/slug, version
check, soft delete). Full-text search is the exception: in memory it is plain word matching,
while PostgreSQL also stems ("testing" finds "tests"). The integration tests cover the real thing.
"""

from __future__ import annotations

import re
import threading
from dataclasses import replace
from datetime import datetime
from typing import Protocol

from psycopg import errors as pg_errors
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from .errors import DuplicateError
from .models import Comment, FeedQuery, Moderation, Post, RefreshToken, TagCount, User


# ---------------------------------------------------------------------------------------------
# Ports
# ---------------------------------------------------------------------------------------------
class UserRepository(Protocol):
    def insert_user(self, email: str, password_hash: str, display_name: str, role: str, at: datetime) -> User: ...
    def find_user_by_email(self, email: str) -> User | None: ...
    def find_user(self, user_id: int) -> User | None: ...
    def update_role(self, user_id: int, role: str) -> User | None: ...
    def insert_refresh_token(self, user_id: int, token_hash: str, at: datetime, expires_at: datetime) -> None: ...
    def find_refresh_token(self, token_hash: str) -> RefreshToken | None: ...
    def revoke_refresh_token(self, token_id: int, at: datetime) -> bool: ...
    def revoke_all_refresh_tokens(self, user_id: int, at: datetime) -> None: ...
    def ping(self) -> bool: ...


class PostRepository(Protocol):
    def insert_post(self, post: Post) -> Post: ...
    def find_post(self, slug: str) -> Post | None: ...
    def update_post(self, post: Post, expected_version: int) -> Post | None: ...
    def soft_delete_post(self, post_id: int, at: datetime) -> None: ...
    def feed(self, query: FeedQuery) -> list[Post]: ...
    def tag_counts(self) -> list[TagCount]: ...


class CommentRepository(Protocol):
    def insert_comment(self, comment: Comment) -> Comment: ...
    def find_comment(self, comment_id: int) -> Comment | None: ...
    def visible_comments(self, post_id: int) -> list[Comment]: ...
    def comments_by_status(self, status: str) -> list[Comment]: ...
    def set_comment_status(self, comment_id: int, status: str, by: int, at: datetime) -> Comment | None: ...
    def soft_delete_comment(self, comment_id: int, at: datetime) -> None: ...


# ---------------------------------------------------------------------------------------------
# PostgreSQL
# ---------------------------------------------------------------------------------------------
_USER_COLS = "id, email, password_hash, display_name, role, created_at"
_POST_SELECT = """
SELECT p.id, p.slug, p.author_id, u.display_name AS author_name, p.title, p.body, p.status, p.version,
       p.created_at, p.updated_at, p.published_at, p.cover_image_url,
       ARRAY(SELECT t.name FROM post_tags pt JOIN tags t ON t.id = pt.tag_id
             WHERE pt.post_id = p.id ORDER BY t.name) AS tags
FROM posts p JOIN users u ON u.id = p.author_id
"""
_COMMENT_SELECT = """
SELECT c.id, c.post_id, p.slug AS post_slug, c.author_id, u.display_name AS author_name, c.parent_id, c.body,
       c.status, c.moderation_label, c.moderation_confidence, c.moderation_provider, c.moderation_fallback,
       c.moderation_reason, c.moderated_by, c.moderated_at, c.created_at
FROM comments c JOIN users u ON u.id = c.author_id JOIN posts p ON p.id = c.post_id
"""


def _user(row: dict) -> User:
    return User(**row)


def _post(row: dict) -> Post:
    return Post(**{**row, "tags": list(row["tags"])})


def _comment(row: dict) -> Comment:
    moderation = Moderation(
        label=row.pop("moderation_label"),
        confidence=float(row.pop("moderation_confidence")),
        provider=row.pop("moderation_provider"),
        fallback=row.pop("moderation_fallback"),
        reason=row.pop("moderation_reason"),
    )
    return Comment(**row, moderation=moderation)


def _replace_tags(cur, post_id: int, tags: list[str]) -> None:
    cur.execute("DELETE FROM post_tags WHERE post_id = %s", (post_id,))
    if tags:
        cur.execute("INSERT INTO tags (name) SELECT unnest(%s::text[]) ON CONFLICT (name) DO NOTHING", (tags,))
        cur.execute(
            "INSERT INTO post_tags (post_id, tag_id) SELECT %s, id FROM tags WHERE name = ANY(%s)", (post_id, tags)
        )


class PostgresUserRepository:
    def __init__(self, pool: ConnectionPool):
        self._pool = pool

    def _one(self, sql: str, params: tuple) -> dict | None:
        with self._pool.connection() as conn, conn.cursor(row_factory=dict_row) as cur:
            cur.execute(sql, params)
            return cur.fetchone()

    def insert_user(self, email, password_hash, display_name, role, at) -> User:
        try:
            row = self._one(
                f"INSERT INTO users (email, password_hash, display_name, role, created_at) "
                f"VALUES (%s, %s, %s, %s, %s) RETURNING {_USER_COLS}",
                (email, password_hash, display_name, role, at),
            )
        except pg_errors.UniqueViolation as exc:
            raise DuplicateError("email") from exc
        return _user(row)

    def find_user_by_email(self, email) -> User | None:
        row = self._one(f"SELECT {_USER_COLS} FROM users WHERE email = %s", (email,))
        return _user(row) if row else None

    def find_user(self, user_id) -> User | None:
        row = self._one(f"SELECT {_USER_COLS} FROM users WHERE id = %s", (user_id,))
        return _user(row) if row else None

    def update_role(self, user_id, role) -> User | None:
        row = self._one(f"UPDATE users SET role = %s WHERE id = %s RETURNING {_USER_COLS}", (role, user_id))
        return _user(row) if row else None

    def insert_refresh_token(self, user_id, token_hash, at, expires_at) -> None:
        with self._pool.connection() as conn:
            conn.execute(
                "INSERT INTO refresh_tokens (user_id, token_hash, created_at, expires_at) VALUES (%s, %s, %s, %s)",
                (user_id, token_hash, at, expires_at),
            )

    def find_refresh_token(self, token_hash) -> RefreshToken | None:
        row = self._one(
            "SELECT id, user_id, token_hash, created_at, expires_at, revoked_at FROM refresh_tokens "
            "WHERE token_hash = %s",
            (token_hash,),
        )
        return RefreshToken(**row) if row else None

    def revoke_refresh_token(self, token_id, at) -> bool:
        # "AND revoked_at IS NULL" makes this a compare-and-set: of two concurrent refreshes with the
        # same token, exactly one sees rowcount 1. The other is treated as reuse.
        with self._pool.connection() as conn:
            cur = conn.execute(
                "UPDATE refresh_tokens SET revoked_at = %s WHERE id = %s AND revoked_at IS NULL", (at, token_id)
            )
            return cur.rowcount == 1

    def revoke_all_refresh_tokens(self, user_id, at) -> None:
        with self._pool.connection() as conn:
            conn.execute(
                "UPDATE refresh_tokens SET revoked_at = %s WHERE user_id = %s AND revoked_at IS NULL", (at, user_id)
            )

    def ping(self) -> bool:
        try:
            with self._pool.connection(timeout=2) as conn:
                conn.execute("SELECT 1")
            return True
        except Exception:  # noqa: BLE001 - health checks must never raise
            return False


class PostgresPostRepository:
    def __init__(self, pool: ConnectionPool):
        self._pool = pool

    def _select_one(self, cur, where: str, params: tuple) -> Post | None:
        cur.execute(_POST_SELECT + where, params)
        row = cur.fetchone()
        return _post(row) if row else None

    def insert_post(self, post: Post) -> Post:
        try:
            with self._pool.connection() as conn, conn.transaction(), conn.cursor(row_factory=dict_row) as cur:
                cur.execute(
                    "INSERT INTO posts (slug, author_id, title, body, status, created_at, updated_at, published_at, "
                    "cover_image_url) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s) RETURNING id",
                    (post.slug, post.author_id, post.title, post.body, post.status,
                     post.created_at, post.updated_at, post.published_at, post.cover_image_url),
                )  # fmt: skip
                post_id = cur.fetchone()["id"]
                _replace_tags(cur, post_id, post.tags)
                return self._select_one(cur, "WHERE p.id = %s", (post_id,))
        except pg_errors.UniqueViolation as exc:
            raise DuplicateError("slug") from exc

    def find_post(self, slug) -> Post | None:
        with self._pool.connection() as conn, conn.cursor(row_factory=dict_row) as cur:
            return self._select_one(cur, "WHERE p.slug = %s AND p.deleted_at IS NULL", (slug,))

    def update_post(self, post: Post, expected_version: int) -> Post | None:
        with self._pool.connection() as conn, conn.transaction(), conn.cursor(row_factory=dict_row) as cur:
            # Optimistic locking: the WHERE clause only matches if nobody saved in between.
            cur.execute(
                "UPDATE posts SET title = %s, body = %s, status = %s, published_at = %s, updated_at = %s, "
                "cover_image_url = %s, version = version + 1 "
                "WHERE id = %s AND version = %s AND deleted_at IS NULL RETURNING id",
                (
                    post.title,
                    post.body,
                    post.status,
                    post.published_at,
                    post.updated_at,
                    post.cover_image_url,
                    post.id,
                    expected_version,
                ),
            )
            if cur.fetchone() is None:
                return None
            _replace_tags(cur, post.id, post.tags)
            return self._select_one(cur, "WHERE p.id = %s", (post.id,))

    def soft_delete_post(self, post_id, at) -> None:
        with self._pool.connection() as conn:
            conn.execute("UPDATE posts SET deleted_at = %s WHERE id = %s AND deleted_at IS NULL", (at, post_id))

    def feed(self, query: FeedQuery) -> list[Post]:
        where = ["p.status = 'published'", "p.deleted_at IS NULL"]
        params: list = []
        if query.tag:
            where.append(
                "EXISTS (SELECT 1 FROM post_tags pt JOIN tags t ON t.id = pt.tag_id "
                "WHERE pt.post_id = p.id AND t.name = %s)"
            )
            params.append(query.tag)
        if query.q:
            where.append("p.search_vector @@ websearch_to_tsquery('english', %s)")
            params.append(query.q)
        if query.after:
            # Keyset pagination: "everything older than the last row I saw" - no OFFSET scan.
            where.append("(p.published_at, p.id) < (%s, %s)")
            params.extend(query.after)
        sql = _POST_SELECT + "WHERE " + " AND ".join(where) + " ORDER BY p.published_at DESC, p.id DESC LIMIT %s"
        params.append(query.limit)
        with self._pool.connection() as conn, conn.cursor(row_factory=dict_row) as cur:
            cur.execute(sql, params)
            return [_post(r) for r in cur.fetchall()]

    def tag_counts(self) -> list[TagCount]:
        with self._pool.connection() as conn:
            rows = conn.execute(
                "SELECT t.name, COUNT(*) FROM tags t JOIN post_tags pt ON pt.tag_id = t.id "
                "JOIN posts p ON p.id = pt.post_id WHERE p.status = 'published' AND p.deleted_at IS NULL "
                "GROUP BY t.name ORDER BY COUNT(*) DESC, t.name"
            ).fetchall()
            return [TagCount(name=r[0], posts=r[1]) for r in rows]


class PostgresCommentRepository:
    def __init__(self, pool: ConnectionPool):
        self._pool = pool

    def _query(self, where: str, params: tuple) -> list[Comment]:
        with self._pool.connection() as conn, conn.cursor(row_factory=dict_row) as cur:
            cur.execute(_COMMENT_SELECT + where, params)
            return [_comment(r) for r in cur.fetchall()]

    def insert_comment(self, comment: Comment) -> Comment:
        m = comment.moderation
        with self._pool.connection() as conn:
            row = conn.execute(
                "INSERT INTO comments (post_id, author_id, parent_id, body, status, moderation_label, "
                "moderation_confidence, moderation_provider, moderation_fallback, moderation_reason, created_at) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) RETURNING id",
                (comment.post_id, comment.author_id, comment.parent_id, comment.body, comment.status,
                 m.label, m.confidence, m.provider, m.fallback, m.reason, comment.created_at),
            ).fetchone()  # fmt: skip
        return self.find_comment(row[0])  # type: ignore[return-value]

    def find_comment(self, comment_id) -> Comment | None:
        rows = self._query("WHERE c.id = %s AND c.deleted_at IS NULL", (comment_id,))
        return rows[0] if rows else None

    def visible_comments(self, post_id) -> list[Comment]:
        return self._query(
            "WHERE c.post_id = %s AND c.status = 'visible' AND c.deleted_at IS NULL ORDER BY c.created_at, c.id",
            (post_id,),
        )

    def comments_by_status(self, status) -> list[Comment]:
        return self._query(
            "WHERE c.status = %s AND c.deleted_at IS NULL AND p.deleted_at IS NULL ORDER BY c.created_at, c.id",
            (status,),
        )

    def set_comment_status(self, comment_id, status, by, at) -> Comment | None:
        with self._pool.connection() as conn:
            conn.execute(
                "UPDATE comments SET status = %s, moderated_by = %s, moderated_at = %s "
                "WHERE id = %s AND deleted_at IS NULL",
                (status, by, at, comment_id),
            )
        return self.find_comment(comment_id)

    def soft_delete_comment(self, comment_id, at) -> None:
        with self._pool.connection() as conn:
            conn.execute("UPDATE comments SET deleted_at = %s WHERE id = %s AND deleted_at IS NULL", (at, comment_id))


# ---------------------------------------------------------------------------------------------
# In-memory (unit tests, and a database-free demo)
# ---------------------------------------------------------------------------------------------
_WORD = re.compile(r"[a-z0-9]+")


class InMemoryStore:
    """One object implements all three ports, like one database holds all tables."""

    def __init__(self):
        self._lock = threading.Lock()
        self.users: dict[int, User] = {}
        self.tokens: dict[int, RefreshToken] = {}
        self.posts: dict[int, Post] = {}
        self.deleted_posts: set[int] = set()
        self.comments: dict[int, Comment] = {}
        self.deleted_comments: set[int] = set()
        self._ids = {"user": 0, "token": 0, "post": 0, "comment": 0}
        self.healthy = True

    def _next(self, kind: str) -> int:
        self._ids[kind] += 1
        return self._ids[kind]

    # ---- users + refresh tokens ----
    def insert_user(self, email, password_hash, display_name, role, at) -> User:
        with self._lock:
            if any(u.email == email for u in self.users.values()):
                raise DuplicateError("email")
            user = User(self._next("user"), email, password_hash, display_name, role, at)
            self.users[user.id] = user
            return replace(user)

    def find_user_by_email(self, email) -> User | None:
        found = next((u for u in self.users.values() if u.email == email), None)
        return replace(found) if found else None

    def find_user(self, user_id) -> User | None:
        found = self.users.get(user_id)
        return replace(found) if found else None

    def update_role(self, user_id, role) -> User | None:
        with self._lock:
            if user_id not in self.users:
                return None
            self.users[user_id].role = role
            return replace(self.users[user_id])

    def insert_refresh_token(self, user_id, token_hash, at, expires_at) -> None:
        with self._lock:
            if any(t.token_hash == token_hash for t in self.tokens.values()):
                raise DuplicateError("token")
            token_id = self._next("token")
            self.tokens[token_id] = RefreshToken(token_id, user_id, token_hash, at, expires_at)

    def find_refresh_token(self, token_hash) -> RefreshToken | None:
        found = next((t for t in self.tokens.values() if t.token_hash == token_hash), None)
        return replace(found) if found else None

    def revoke_refresh_token(self, token_id, at) -> bool:
        with self._lock:
            token = self.tokens.get(token_id)
            if token is None or token.revoked_at is not None:
                return False
            token.revoked_at = at
            return True

    def revoke_all_refresh_tokens(self, user_id, at) -> None:
        with self._lock:
            for token in self.tokens.values():
                if token.user_id == user_id and token.revoked_at is None:
                    token.revoked_at = at

    def ping(self) -> bool:
        return self.healthy

    # ---- posts ----
    def _with_author(self, post: Post) -> Post:
        return replace(post, author_name=self.users[post.author_id].display_name, tags=list(post.tags))

    def insert_post(self, post: Post) -> Post:
        with self._lock:
            if any(p.slug == post.slug for p in self.posts.values()):
                raise DuplicateError("slug")
            stored = replace(post, id=self._next("post"), version=1, tags=sorted(post.tags))
            self.posts[stored.id] = stored
            return self._with_author(stored)

    def find_post(self, slug) -> Post | None:
        found = next((p for p in self.posts.values() if p.slug == slug and p.id not in self.deleted_posts), None)
        return self._with_author(found) if found else None

    def update_post(self, post: Post, expected_version: int) -> Post | None:
        with self._lock:
            current = self.posts.get(post.id)
            if current is None or post.id in self.deleted_posts or current.version != expected_version:
                return None
            stored = replace(post, version=current.version + 1, tags=sorted(post.tags), created_at=current.created_at)
            self.posts[post.id] = stored
            return self._with_author(stored)

    def soft_delete_post(self, post_id, at) -> None:
        self.deleted_posts.add(post_id)

    def feed(self, query: FeedQuery) -> list[Post]:
        words = _WORD.findall(query.q.lower()) if query.q else []
        result = []
        for p in self.posts.values():
            if p.status != "published" or p.id in self.deleted_posts:
                continue
            if query.tag and query.tag not in p.tags:
                continue
            if words:
                text_words = set(_WORD.findall(f"{p.title} {p.body}".lower()))
                if not all(w in text_words for w in words):
                    continue
            if query.after and (p.published_at, p.id) >= query.after:
                continue
            result.append(p)
        result.sort(key=lambda p: (p.published_at, p.id), reverse=True)
        return [self._with_author(p) for p in result[: query.limit]]

    def tag_counts(self) -> list[TagCount]:
        counts: dict[str, int] = {}
        for p in self.posts.values():
            if p.status == "published" and p.id not in self.deleted_posts:
                for t in p.tags:
                    counts[t] = counts.get(t, 0) + 1
        return [TagCount(n, c) for n, c in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))]

    # ---- comments ----
    def _comment_view(self, c: Comment) -> Comment:
        return replace(c, author_name=self.users[c.author_id].display_name, post_slug=self.posts[c.post_id].slug)

    def insert_comment(self, comment: Comment) -> Comment:
        with self._lock:
            stored = replace(comment, id=self._next("comment"))
            self.comments[stored.id] = stored
            return self._comment_view(stored)

    def find_comment(self, comment_id) -> Comment | None:
        c = self.comments.get(comment_id)
        return self._comment_view(c) if c and comment_id not in self.deleted_comments else None

    def _live(self) -> list[Comment]:
        return sorted(
            (c for c in self.comments.values() if c.id not in self.deleted_comments),
            key=lambda c: (c.created_at, c.id),
        )

    def visible_comments(self, post_id) -> list[Comment]:
        return [self._comment_view(c) for c in self._live() if c.post_id == post_id and c.status == "visible"]

    def comments_by_status(self, status) -> list[Comment]:
        return [
            self._comment_view(c) for c in self._live() if c.status == status and c.post_id not in self.deleted_posts
        ]

    def set_comment_status(self, comment_id, status, by, at) -> Comment | None:
        with self._lock:
            c = self.comments.get(comment_id)
            if c is None or comment_id in self.deleted_comments:
                return None
            self.comments[comment_id] = replace(c, status=status, moderated_by=by, moderated_at=at)
        return self.find_comment(comment_id)

    def soft_delete_comment(self, comment_id, at) -> None:
        self.deleted_comments.add(comment_id)

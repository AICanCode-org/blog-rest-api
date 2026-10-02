"""Plain data objects shared by services and repositories (no framework imports)."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

ROLES = ("READER", "AUTHOR", "ADMIN")


@dataclass
class User:
    id: int
    email: str
    password_hash: str
    display_name: str
    role: str
    created_at: datetime


@dataclass(frozen=True)
class Principal:
    """Who is calling, as proven by a verified access token."""

    user_id: int
    role: str

    @property
    def is_admin(self) -> bool:
        return self.role == "ADMIN"


@dataclass
class RefreshToken:
    id: int
    user_id: int
    token_hash: str
    created_at: datetime
    expires_at: datetime
    revoked_at: datetime | None = None


@dataclass
class TokenPair:
    access_token: str
    refresh_token: str
    user: User


@dataclass
class Post:
    id: int
    slug: str
    author_id: int
    author_name: str
    title: str
    body: str
    status: str
    tags: list[str]
    version: int
    created_at: datetime
    updated_at: datetime
    published_at: datetime | None = None
    cover_image_url: str | None = None  # v1.1


@dataclass(frozen=True)
class Moderation:
    label: str  # ok | spam | toxic
    confidence: float
    provider: str  # rules | llm
    fallback: bool
    reason: str


@dataclass
class Comment:
    id: int
    post_id: int
    post_slug: str
    author_id: int
    author_name: str
    parent_id: int | None
    body: str
    status: str  # visible | held | rejected
    moderation: Moderation
    created_at: datetime
    moderated_by: int | None = None
    moderated_at: datetime | None = None


@dataclass(frozen=True)
class TagCount:
    name: str
    posts: int


@dataclass(frozen=True)
class FeedQuery:
    limit: int
    tag: str | None = None
    q: str | None = None
    after: tuple[datetime, int] | None = None  # keyset: (published_at, id) of the last item seen

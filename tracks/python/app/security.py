"""Passwords (bcrypt), access tokens (JWT, HS256) and refresh tokens (opaque, hashed at rest)."""

from __future__ import annotations

import logging
import secrets
from datetime import timedelta

from .clock import Clock
from .models import Principal, User

log = logging.getLogger("devblog.security")
ISSUER = "devblog"
ACCESS_TOKEN_TTL = timedelta(minutes=15)
REFRESH_TOKEN_TTL = timedelta(days=7)
MIN_SECRET_BYTES = 32


class PasswordHasher:
    """bcrypt with a configurable cost (10 in production, 4 in tests to keep them fast)."""

    def __init__(self, cost: int = 10):
        if not 4 <= cost <= 15:
            raise ValueError("BCRYPT_COST must be 4-15")
        self._cost = cost
        # Used when the e-mail is unknown, so a login takes the same time either way.
        # TODO(phase-3 M2): self._dummy = self.hash("not-a-real-password") once hash() works.
        self._dummy: str | None = None

    def hash(self, password: str) -> str:
        # TODO(phase-3 M2): bcrypt with the configured cost (salt included in the hash).
        raise NotImplementedError("TODO(phase-3 M2): PasswordHasher.hash")

    def verify(self, password: str, password_hash: str | None) -> bool:
        # TODO(phase-3 M2): bcrypt check; never throw on a malformed hash, return false.
        raise NotImplementedError("TODO(phase-3 M2): PasswordHasher.verify")


def resolve_secret(configured: str | None) -> str:
    if configured is None:
        log.warning("JWT_SECRET is not set: using a random secret, tokens die with this process (dev only)")
        return secrets.token_urlsafe(48)
    if len(configured.encode("utf-8")) < MIN_SECRET_BYTES:
        raise ValueError(f"JWT_SECRET must be at least {MIN_SECRET_BYTES} bytes")
    return configured


class TokenService:
    def __init__(self, secret: str, clock: Clock):
        self._secret = secret
        self._clock = clock

    def issue_access_token(self, user: User) -> str:
        # TODO(phase-3 M2): HS256 JWT with sub (user id as string), role, iss 'devblog', iat, exp = now + 900
        # s, using the injected clock.
        raise NotImplementedError("TODO(phase-3 M2): TokenService.issue_access_token")

    def verify_access_token(self, token: str) -> Principal:
        # TODO(phase-3 M2): pin the algorithm to HS256, check signature, iss and exp against the injected
        # clock; any problem -> 401 unauthorized. Return Principal(user id, role).
        raise NotImplementedError("TODO(phase-3 M2): TokenService.verify_access_token")


def new_refresh_token() -> tuple[str, str]:
    """Returns (token for the client, SHA-256 hex digest for the database)."""
    # TODO(phase-3 M2): 32 random bytes from a CSPRNG, base64url without padding. Opaque: not a JWT.
    raise NotImplementedError("TODO(phase-3 M2): new_refresh_token")


def hash_refresh_token(token: str) -> str:
    # TODO(phase-3 M2): SHA-256 hex digest. Only the hash is stored (like a password, but fast is fine: it
    # has 256 bits of entropy).
    raise NotImplementedError("TODO(phase-3 M2): hash_refresh_token")

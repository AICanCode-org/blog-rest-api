"""Passwords (bcrypt), access tokens (JWT, HS256) and refresh tokens (opaque, hashed at rest)."""

from __future__ import annotations

import hashlib
import logging
import secrets
from datetime import timedelta

import bcrypt
import jwt

from .clock import Clock
from .errors import unauthorized
from .models import ROLES, Principal, User

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
        self._dummy = self.hash("not-a-real-password")

    def hash(self, password: str) -> str:
        return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt(self._cost)).decode("ascii")

    def verify(self, password: str, password_hash: str | None) -> bool:
        target = (password_hash or self._dummy).encode("ascii")
        ok = bcrypt.checkpw(password.encode("utf-8"), target)
        return ok and password_hash is not None


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
        now = self._clock()
        claims = {
            "sub": str(user.id),
            "role": user.role,
            "iss": ISSUER,
            "iat": int(now.timestamp()),
            "exp": int((now + ACCESS_TOKEN_TTL).timestamp()),
        }
        return jwt.encode(claims, self._secret, algorithm="HS256")

    def verify_access_token(self, token: str) -> Principal:
        try:
            # algorithms=["HS256"] pins the algorithm: "alg": "none" or RS256 tokens are rejected.
            # Expiry is checked below against the injected clock, so tests can travel in time.
            claims = jwt.decode(
                token,
                self._secret,
                algorithms=["HS256"],
                issuer=ISSUER,
                options={"verify_exp": False, "verify_iat": False},
            )
        except jwt.PyJWTError as exc:
            raise unauthorized("invalid or expired token") from exc
        exp, sub, role = claims.get("exp"), claims.get("sub"), claims.get("role")
        if not isinstance(exp, int) or exp <= self._clock().timestamp():
            raise unauthorized("invalid or expired token")
        if not isinstance(sub, str) or not sub.isascii() or not sub.isdigit() or role not in ROLES:
            raise unauthorized("invalid or expired token")
        return Principal(user_id=int(sub), role=role)


def new_refresh_token() -> tuple[str, str]:
    """Returns (token for the client, SHA-256 hex digest for the database)."""
    token = secrets.token_urlsafe(32)
    return token, hash_refresh_token(token)


def hash_refresh_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()

"""Accounts, login, token rotation and roles. Framework-free: the HTTP layer only parses and formats."""

from __future__ import annotations

import logging

from . import validation as v
from .clock import Clock
from .errors import (
    DuplicateError,
    email_taken,
    forbidden,
    invalid_credentials,
    invalid_refresh_token,
    not_found,
    unauthorized,
    validation_error,
)
from .models import Principal, TokenPair, User
from .repository import UserRepository
from .security import REFRESH_TOKEN_TTL, PasswordHasher, TokenService, hash_refresh_token, new_refresh_token

log = logging.getLogger("devblog.auth")


def require_role(principal: Principal, *roles: str) -> None:
    if principal.role not in roles:
        raise forbidden(f"requires role {' or '.join(roles)}")


class AuthService:
    def __init__(self, users: UserRepository, hasher: PasswordHasher, tokens: TokenService, clock: Clock):
        self.users = users
        self._hasher = hasher
        self._tokens = tokens
        self._clock = clock

    # ---- accounts ----
    def register(self, payload: object) -> User:
        data = v.require_object(payload)
        email = v.email(data.get("email"))
        password = v.password(data.get("password"))
        name = v.display_name(data.get("displayName"))
        # Never read "role" from the request: everyone starts as READER (mass-assignment defence).
        try:
            return self.users.insert_user(email, self._hasher.hash(password), name, "READER", self._clock())
        except DuplicateError as exc:
            raise email_taken() from exc

    def login(self, payload: object) -> TokenPair:
        data = v.require_object(payload)
        email, password = data.get("email"), data.get("password")
        if not isinstance(email, str) or not isinstance(password, str):
            raise validation_error("email and password are required")
        user = self.users.find_user_by_email(email.strip().lower())
        # verify() also runs for unknown e-mails (against a dummy hash): same answer, same timing.
        if len(password.encode("utf-8")) > 72 or not self._hasher.verify(
            password, user.password_hash if user else None
        ):
            raise invalid_credentials()
        assert user is not None
        return self._issue(user)

    def refresh(self, payload: object) -> TokenPair:
        token = self._refresh_token_from(payload)
        stored = self.users.find_refresh_token(hash_refresh_token(token))
        now = self._clock()
        if stored is None:
            raise invalid_refresh_token()
        if stored.revoked_at is not None or not self.users.revoke_refresh_token(stored.id, now):
            # A used token came back: someone has a copy. Log out every session of this user.
            log.warning("refresh token reuse detected for user %s: revoking all sessions", stored.user_id)
            self.users.revoke_all_refresh_tokens(stored.user_id, now)
            raise invalid_refresh_token()
        if stored.expires_at <= now:
            raise invalid_refresh_token()
        user = self.users.find_user(stored.user_id)
        if user is None:
            raise invalid_refresh_token()
        return self._issue(user)  # the role is re-read from the database here

    def logout(self, payload: object) -> None:
        stored = self.users.find_refresh_token(hash_refresh_token(self._refresh_token_from(payload)))
        if stored is not None:
            self.users.revoke_refresh_token(stored.id, self._clock())

    def me(self, principal: Principal) -> User:
        user = self.users.find_user(principal.user_id)
        if user is None:
            raise unauthorized("account no longer exists")
        return user

    def change_role(self, principal: Principal, user_id: int, payload: object) -> User:
        require_role(principal, "ADMIN")
        new_role = v.role(v.require_object(payload).get("role"))
        if user_id == principal.user_id:
            raise validation_error("you cannot change your own role")
        user = self.users.update_role(user_id, new_role)
        if user is None:
            raise not_found("user")
        return user

    def ensure_admin(self, email: str, password: str) -> User:
        """Start-up bootstrap from ADMIN_EMAIL / ADMIN_PASSWORD (there is no other way to get an ADMIN)."""
        email = v.email(email)
        existing = self.users.find_user_by_email(email)
        if existing is not None:
            return existing if existing.role == "ADMIN" else self.users.update_role(existing.id, "ADMIN")  # type: ignore[return-value]
        return self.users.insert_user(email, self._hasher.hash(v.password(password)), "Admin", "ADMIN", self._clock())

    # ---- tokens ----
    def authenticate(self, authorization: str | None) -> Principal:
        principal = self.optional(authorization)
        if principal is None:
            raise unauthorized()
        return principal

    def optional(self, authorization: str | None) -> Principal | None:
        """No header -> anonymous. A header that is present but wrong is always a 401, never anonymous."""
        if authorization is None:
            return None
        scheme, _, token = authorization.partition(" ")
        if scheme.lower() != "bearer" or not token.strip():
            raise unauthorized("use Authorization: Bearer <token>")
        return self._tokens.verify_access_token(token.strip())

    def _issue(self, user: User) -> TokenPair:
        refresh, refresh_hash = new_refresh_token()
        now = self._clock()
        self.users.insert_refresh_token(user.id, refresh_hash, now, now + REFRESH_TOKEN_TTL)
        return TokenPair(self._tokens.issue_access_token(user), refresh, user)

    @staticmethod
    def _refresh_token_from(payload: object) -> str:
        token = v.require_object(payload).get("refreshToken")
        if not isinstance(token, str) or not token:
            raise validation_error("refreshToken is required")
        return token

"""Accounts, login, token rotation and roles. Framework-free: the HTTP layer only parses and formats."""

from __future__ import annotations

import logging

from .clock import Clock
from .models import Principal, TokenPair, User
from .repository import UserRepository
from .security import PasswordHasher, TokenService

log = logging.getLogger("devblog.auth")


def require_role(principal: Principal, *roles: str) -> None:
    # TODO(phase-3 M2): 403 forbidden unless principal.role is one of the allowed roles.
    raise NotImplementedError("TODO(phase-3 M2): require_role")


class AuthService:
    def __init__(self, users: UserRepository, hasher: PasswordHasher, tokens: TokenService, clock: Clock):
        self.users = users
        self._hasher = hasher
        self._tokens = tokens
        self._clock = clock

    # ---- accounts ----
    def register(self, payload: object) -> User:
        # TODO(phase-3 M2): validate email, password, displayName; ALWAYS create a READER (ignore any 'role'
        # in the payload: mass assignment!); hash the password; DuplicateError -> 409 email-taken.
        raise NotImplementedError("TODO(phase-3 M2): AuthService.register")

    def login(self, payload: object) -> TokenPair:
        # TODO(phase-3 M2): same 401 invalid-credentials for unknown e-mail and wrong password; for an unknown
        # e-mail still run one bcrypt verify (timing). Return access + refresh token pair.
        raise NotImplementedError("TODO(phase-3 M2): AuthService.login")

    def refresh(self, payload: object) -> TokenPair:
        # TODO(phase-3 M2): hash the token, find it; unknown/expired -> 401 invalid-refresh-token. Revoke with
        # compare-and-set: if it was already revoked it is REUSE -> revoke all the user's tokens, 401. Re-read
        # the user's role, issue a new pair.
        raise NotImplementedError("TODO(phase-3 M2): AuthService.refresh")

    def logout(self, payload: object) -> None:
        # TODO(phase-3 M2): require a refreshToken string (400 otherwise); revoke it if it exists. Idempotent:
        # always succeeds.
        raise NotImplementedError("TODO(phase-3 M2): AuthService.logout")

    def me(self, principal: Principal) -> User:
        # TODO(phase-3 M2): the current user, 404 if it no longer exists.
        raise NotImplementedError("TODO(phase-3 M2): AuthService.me")

    def change_role(self, principal: Principal, user_id: int, payload: object) -> User:
        # TODO(phase-3 M2): ADMIN only (403); validate the role; changing your own role -> 400; unknown user
        # -> 404.
        raise NotImplementedError("TODO(phase-3 M2): AuthService.change_role")

    def ensure_admin(self, email: str, password: str) -> User:
        """Start-up bootstrap from ADMIN_EMAIL / ADMIN_PASSWORD (there is no other way to get an ADMIN)."""
        # TODO(phase-3 M2): create ADMIN_EMAIL as ADMIN, or promote it if it exists. Until you build it the
        # app starts without an admin.
        logging.getLogger("devblog").warning("TODO(phase-3 M2): ensure_admin - starting without an admin")
        return None  # type: ignore[return-value]

    # ---- tokens ----
    def authenticate(self, authorization: str | None) -> Principal:
        # TODO(phase-3 M2): header must be 'Bearer <jwt>'; missing or bad -> 401 unauthorized (the HTTP layer
        # adds WWW-Authenticate).
        raise NotImplementedError("TODO(phase-3 M2): AuthService.authenticate")

    def optional(self, authorization: str | None) -> Principal | None:
        """No header -> anonymous. A header that is present but wrong is always a 401, never anonymous."""
        # TODO(phase-3 M2): no header -> null (anonymous). A header that is present but bad is still a 401:
        # never silently anonymous.
        raise NotImplementedError("TODO(phase-3 M2): AuthService.optional")

    def _issue(self, user: User) -> TokenPair:
        # TODO(phase-3 M2): HS256 JWT with sub (user id as string), role, iss 'devblog', iat, exp = now + 900
        # s, using the injected clock.
        raise NotImplementedError("TODO(phase-3 M2): AuthService._issue")

    @staticmethod
    def _refresh_token_from(payload: object) -> str:
        # TODO(phase-3 M2): the payload must be an object with a non-empty refreshToken string, else
        #   400 'refreshToken is required'.
        raise NotImplementedError("TODO(phase-3 M2): AuthService._refresh_token_from")

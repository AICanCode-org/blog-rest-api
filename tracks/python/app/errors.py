"""Domain errors that map 1:1 to RFC 9457 problem responses (identical in every track)."""

from __future__ import annotations

PROBLEM_BASE = "https://devblog.example/problems/"


class ApiError(Exception):
    def __init__(self, status: int, slug: str, title: str, detail: str | None = None):
        super().__init__(detail or title)
        self.status = status
        self.slug = slug
        self.title = title
        self.detail = detail

    def to_problem(self) -> dict:
        body = {"type": PROBLEM_BASE + self.slug, "title": self.title, "status": self.status}
        if self.detail:
            body["detail"] = self.detail
        return body


def validation_error(detail: str) -> ApiError:
    return ApiError(400, "validation-error", "Validation failed", detail)


def unauthorized(detail: str = "authentication required") -> ApiError:
    return ApiError(401, "unauthorized", "Unauthorized", detail)


def invalid_credentials() -> ApiError:
    return ApiError(401, "invalid-credentials", "Invalid credentials", "wrong e-mail or password")


def invalid_refresh_token() -> ApiError:
    return ApiError(401, "invalid-refresh-token", "Invalid refresh token", "sign in again")


def forbidden(detail: str = "you are not allowed to do this") -> ApiError:
    return ApiError(403, "forbidden", "Forbidden", detail)


def not_found(what: str) -> ApiError:
    return ApiError(404, "not-found", "Not found", f"{what} not found")


def email_taken() -> ApiError:
    return ApiError(409, "email-taken", "E-mail already registered", "an account with this e-mail exists")


def version_conflict(current: int) -> ApiError:
    return ApiError(
        409, "version-conflict", "Version conflict", f"the post was changed by someone else (current version {current})"
    )


class DuplicateError(Exception):
    """Raised by a repository when a unique constraint (e-mail, slug) is violated."""

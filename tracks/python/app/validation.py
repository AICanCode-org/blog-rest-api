"""Input validation. Every function returns the normalised value or raises a 400 problem.

The limits are the ones in spec/requirements.md and spec/openapi.yaml; the Java and TypeScript
tracks implement the same rules with the same messages.
"""

from __future__ import annotations

import re

from .errors import validation_error

EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
TAG_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
DIGITS_RE = re.compile(r"^[0-9]+$")  # str.isdigit() would also accept "²" or Arabic-Indic digits
MAX_TAGS = 5
MAX_BODY = 50_000
MAX_COMMENT = 2_000
DEFAULT_LIMIT = 10
MAX_LIMIT = 50


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)  # True is an int in Python!


def require_object(payload: object) -> dict:
    if not isinstance(payload, dict):
        raise validation_error("request body must be a JSON object")
    return payload


def email(value: object) -> str:
    # TODO(phase-3 M1): string, trimmed and lower-cased, at most 254 characters, must match EMAIL_RE.
    # Exact messages: see the validation tests.
    raise NotImplementedError("TODO(phase-3 M1): email")


def password(value: object) -> str:
    # TODO(phase-3 M1): string of at least 8 characters and at most 72 BYTES in UTF-8 (bcrypt ignores the
    # rest). Do not trim.
    raise NotImplementedError("TODO(phase-3 M1): password")


def display_name(value: object) -> str:
    # TODO(phase-3 M1): string, trimmed, 1..50 characters (code points, not UTF-16 units).
    raise NotImplementedError("TODO(phase-3 M1): display_name")


def role(value: object) -> str:
    # TODO(phase-3 M1): one of READER, AUTHOR, ADMIN (exact case).
    raise NotImplementedError("TODO(phase-3 M1): role")


def title(value: object) -> str:
    # TODO(phase-3 M1): string, trimmed, 1..200 characters.
    raise NotImplementedError("TODO(phase-3 M1): title")


def body(value: object) -> str:
    # TODO(phase-3 M1): string, not blank, at most 50 000 characters. Stored as-is (Markdown): no
    # trimming.
    raise NotImplementedError("TODO(phase-3 M1): body")


def tags(value: object) -> list[str]:
    # TODO(phase-3 M1): missing/null -> []. A list of strings; trim + lower-case each, de-duplicate
    #   and sort, then at most 5 and each must match TAG_RE with at most 30 characters.
    raise NotImplementedError("TODO(phase-3 M1): tags")


def status(value: object, default: str | None = "draft") -> str:
    # TODO(phase-3 M1): missing/null -> the default ('draft' on create); otherwise 'draft' or 'published'.
    raise NotImplementedError("TODO(phase-3 M1): status")


def version(value: object) -> int:
    # TODO(phase-3 M1): a positive integer (booleans are not integers!). Required on PATCH.
    raise NotImplementedError("TODO(phase-3 M1): version")


def comment_body(value: object) -> str:
    # TODO(phase-3 M1): string, trimmed, 1..2000 characters.
    raise NotImplementedError("TODO(phase-3 M1): comment_body")


def parent_id(value: object) -> int | None:
    # TODO(phase-3 M1): missing/null -> null; otherwise a positive integer.
    raise NotImplementedError("TODO(phase-3 M1): parent_id")


def limit(value: str | None) -> int:
    # TODO(phase-3 M1): query string: missing -> 10; ASCII digits only ([0-9]+: isdigit() also accepts
    # other scripts), 1..50.
    raise NotImplementedError("TODO(phase-3 M1): limit")


def tag_filter(value: str | None) -> str | None:
    # TODO(phase-3 M1): query string: missing/empty -> null; otherwise lower-cased and must match TAG_RE.
    raise NotImplementedError("TODO(phase-3 M1): tag_filter")


def search_query(value: str | None) -> str | None:
    # TODO(phase-3 M1): query string: trimmed; empty -> null; at most 100 characters.
    raise NotImplementedError("TODO(phase-3 M1): search_query")


def comment_status(value: str | None) -> str:
    # TODO(phase-3 M1): query string: missing -> 'held'; otherwise held, visible or rejected.
    raise NotImplementedError("TODO(phase-3 M1): comment_status")


def decision(value: object) -> str:
    # TODO(phase-3 M1): 'approve' or 'reject'.
    raise NotImplementedError("TODO(phase-3 M1): decision")


def path_id(value: str, what: str) -> int:
    """A non-numeric id in the URL cannot exist, so it is a 404 rather than a 400."""
    # TODO(phase-3 M1): path segment: ASCII digits only (at most 18), otherwise a 404 not-found for
    # `what`: an id that cannot exist is not a 400.
    raise NotImplementedError("TODO(phase-3 M1): path_id")

"""Input validation. Every function returns the normalised value or raises a 400 problem.

The limits are the ones in spec/requirements.md and spec/openapi.yaml; the Java and TypeScript
tracks implement the same rules with the same messages.
"""

from __future__ import annotations

import re

from .errors import not_found, validation_error
from .models import ROLES

EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
TAG_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
COVER_URL_RE = re.compile(r"^[Hh][Tt][Tt][Pp][Ss]?://[A-Za-z0-9.-]+(:[0-9]{1,5})?([/?#]\S*)?$")
MAX_COVER_URL = 2048
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
    if not isinstance(value, str):
        raise validation_error("email is required")
    normalised = value.strip().lower()
    if len(normalised) > 254 or not EMAIL_RE.match(normalised):
        raise validation_error("email must be a valid e-mail address")
    return normalised


def password(value: object) -> str:
    if not isinstance(value, str) or len(value) < 8 or len(value.encode("utf-8")) > 72:
        raise validation_error("password must be 8 characters to 72 bytes")
    return value


def display_name(value: object) -> str:
    if not isinstance(value, str) or not 1 <= len(value.strip()) <= 50:
        raise validation_error("displayName must be 1-50 characters")
    return value.strip()


def role(value: object) -> str:
    if value not in ROLES:
        raise validation_error("role must be one of READER, AUTHOR, ADMIN")
    return value  # type: ignore[return-value]


def title(value: object) -> str:
    if not isinstance(value, str) or not 1 <= len(value.strip()) <= 200:
        raise validation_error("title must be 1-200 characters")
    return value.strip()


def body(value: object) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > MAX_BODY:
        raise validation_error("body must be 1-50000 characters")
    return value


def tags(value: object) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, list) or not all(isinstance(t, str) for t in value):
        raise validation_error("tags must be an array of strings")
    cleaned = sorted({t.strip().lower() for t in value})
    if len(cleaned) > MAX_TAGS:
        raise validation_error("at most 5 tags")
    for t in cleaned:
        if len(t) > 30 or not TAG_RE.match(t):
            raise validation_error("tags must be lower-case letters, digits and single hyphens (max 30)")
    return cleaned


def status(value: object, default: str | None = "draft") -> str:
    if value is None and default is not None:
        return default
    if value not in ("draft", "published"):
        raise validation_error("status must be draft or published")
    return value  # type: ignore[return-value]


def cover_image_url(value: object) -> str | None:
    """v1.1: optional. null clears it. Only absolute http(s) URLs: no javascript:, data: or file: tricks."""
    if value is None:
        return None
    if not isinstance(value, str) or len(value) > MAX_COVER_URL or not COVER_URL_RE.match(value):
        raise validation_error("coverImageUrl must be an http(s) URL of at most 2048 characters")
    return value


def version(value: object) -> int:
    if not _is_int(value) or value < 1:  # type: ignore[operator]
        raise validation_error("version is required (the version you read)")
    return value  # type: ignore[return-value]


def comment_body(value: object) -> str:
    if not isinstance(value, str) or not 1 <= len(value.strip()) <= MAX_COMMENT:
        raise validation_error("body must be 1-2000 characters")
    return value.strip()


def parent_id(value: object) -> int | None:
    if value is None:
        return None
    if not _is_int(value) or value < 1:  # type: ignore[operator]
        raise validation_error("parentId must be a positive integer")
    return value  # type: ignore[return-value]


def limit(value: str | None) -> int:
    if value is None:
        return DEFAULT_LIMIT
    if not DIGITS_RE.match(value) or not 1 <= int(value) <= MAX_LIMIT:
        raise validation_error("limit must be an integer 1-50")
    return int(value)


def tag_filter(value: str | None) -> str | None:
    if value is None or value == "":
        return None
    t = value.strip().lower()
    if len(t) > 30 or not TAG_RE.match(t):
        raise validation_error("tag must be lower-case letters, digits and single hyphens (max 30)")
    return t


def search_query(value: str | None) -> str | None:
    if value is None or not value.strip():
        return None
    if len(value.strip()) > 100:
        raise validation_error("q must be at most 100 characters")
    return value.strip()


def comment_status(value: str | None) -> str:
    if value is None:
        return "held"
    if value not in ("held", "visible", "rejected"):
        raise validation_error("status must be held, visible or rejected")
    return value


def decision(value: object) -> str:
    if value not in ("approve", "reject"):
        raise validation_error("decision must be approve or reject")
    return value  # type: ignore[return-value]


def path_id(value: str, what: str) -> int:
    """A non-numeric id in the URL cannot exist, so it is a 404 rather than a 400."""
    if not DIGITS_RE.match(value) or len(value) > 18:
        raise not_found(what)
    return int(value)

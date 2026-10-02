"""Slugs, excerpts and keyset cursors (pure functions, identical rules in every track)."""

from __future__ import annotations

import base64
import binascii
import re
import unicodedata
from datetime import datetime

from .clock import iso
from .errors import validation_error

SLUG_MAX = 80
EXCERPT_MAX = 160
_MARKUP = re.compile(r"[#*`>]")
_SPACES = re.compile(r"\s+")
_CURSOR = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3})Z\|([0-9]{1,18})$", re.ASCII)


def slugify(title: str) -> str:
    """'Café & Code: Part 2!' -> 'cafe-code-part-2'. Empty results become 'post'."""
    ascii_title = unicodedata.normalize("NFKD", title).encode("ascii", "ignore").decode("ascii")
    slug = re.sub(r"[^a-z0-9]+", "-", ascii_title.lower()).strip("-")
    slug = slug[:SLUG_MAX].rstrip("-")
    return slug or "post"


def plain_text(markdown: str) -> str:
    """Drop the most common markdown symbols and collapse whitespace."""
    return _SPACES.sub(" ", _MARKUP.sub("", markdown)).strip()


def shorten(text: str, max_len: int) -> str:
    if len(text) <= max_len:
        return text
    return text[: max_len - 3].rstrip() + "..."


def excerpt(body: str) -> str:
    return shorten(plain_text(body), EXCERPT_MAX)


def encode_cursor(published_at: datetime, post_id: int) -> str:
    raw = f"{iso(published_at)}|{post_id}".encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def decode_cursor(cursor: str | None) -> tuple[datetime, int] | None:
    if cursor is None or cursor == "":
        return None
    try:
        raw = base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)).decode()
        match = _CURSOR.match(raw)
        if match is None:
            raise ValueError(raw)
        return datetime.fromisoformat(match.group(1) + "+00:00"), int(match.group(2))
    except (ValueError, binascii.Error, UnicodeDecodeError) as exc:
        raise validation_error("cursor is invalid") from exc

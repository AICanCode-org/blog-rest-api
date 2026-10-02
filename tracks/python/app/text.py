"""Slugs, excerpts and keyset cursors (pure functions, identical rules in every track)."""

from __future__ import annotations

import re
from datetime import datetime

SLUG_MAX = 80
EXCERPT_MAX = 160
_MARKUP = re.compile(r"[#*`>]")
_SPACES = re.compile(r"\s+")
_CURSOR = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3})Z\|([0-9]{1,18})$", re.ASCII)


def slugify(title: str) -> str:
    """'Café & Code: Part 2!' -> 'cafe-code-part-2'. Empty results become 'post'."""
    # TODO(phase-3 M1): NFKD-normalise, drop non-ASCII, lower-case, runs of anything but [a-z0-9] -> one
    # '-', trim '-', cut to 80 and trim '-' again; empty -> 'post'.
    raise NotImplementedError("TODO(phase-3 M1): slugify")


def plain_text(markdown: str) -> str:
    """Drop the most common markdown symbols and collapse whitespace."""
    # TODO(phase-3 M1): drop the characters # * ` > and collapse whitespace (then trim). Used by excerpts
    # and summaries.
    raise NotImplementedError("TODO(phase-3 M1): plain_text")


def shorten(text: str, max_len: int) -> str:
    # TODO(phase-3 M1): at most `limit` characters (code points): if longer, cut to limit-3 (no trailing
    # space) and add '...'.
    raise NotImplementedError("TODO(phase-3 M1): shorten")


def excerpt(body: str) -> str:
    # TODO(phase-3 M1): shorten(plain_text(body), 160).
    raise NotImplementedError("TODO(phase-3 M1): excerpt")


def encode_cursor(published_at: datetime, post_id: int) -> str:
    # TODO(phase-3 M1): base64url WITHOUT padding of 'YYYY-MM-DDTHH:MM:SS.sssZ|id' (milliseconds, UTC).
    # ADR-0002.
    raise NotImplementedError("TODO(phase-3 M1): encode_cursor")


def decode_cursor(cursor: str | None) -> tuple[datetime, int] | None:
    # TODO(phase-3 M1): missing/empty -> null; anything that does not decode to exactly that format -> 400
    # 'cursor is invalid'.
    raise NotImplementedError("TODO(phase-3 M1): decode_cursor")

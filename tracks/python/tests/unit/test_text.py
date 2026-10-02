"""M1: slugs, excerpts and keyset cursors (FR-5, FR-6)."""

import base64
from datetime import UTC, datetime

import pytest

from app.errors import ApiError
from app.text import decode_cursor, encode_cursor, excerpt, plain_text, slugify


@pytest.mark.parametrize(
    ("title", "slug"),
    [
        ("Hello DevBlog", "hello-devblog"),
        ("Café & Code: Part 2!", "cafe-code-part-2"),
        ("  --Spaces   and---dashes--  ", "spaces-and-dashes"),
        ("!!!", "post"),
        ("数据库", "post"),
    ],
)
def test_slugify(title, slug):
    assert slugify(title) == slug


def test_slug_is_at_most_80_characters_and_never_ends_with_a_dash():
    slug = slugify("a" * 79 + " b")
    assert slug == "a" * 79
    assert len(slugify("word " * 40)) <= 80
    assert not slugify("word " * 40).endswith("-")


def test_plain_text_drops_markdown_symbols_and_collapses_whitespace():
    assert plain_text("# Title\n\n**Bold**  and `code`\n> quote") == "Title Bold and code quote"


def test_excerpt_is_at_most_160_characters():
    assert excerpt("Short body.") == "Short body."
    long = excerpt("word " * 100)
    assert len(long) <= 160 and long.endswith("...")
    assert excerpt("x" * 160) == "x" * 160
    assert excerpt("x" * 161) == "x" * 157 + "..."


def test_cursor_round_trip():
    at = datetime(2026, 10, 2, 9, 30, 0, 123000, tzinfo=UTC)
    cursor = encode_cursor(at, 42)
    assert "=" not in cursor  # URL-safe, no padding
    assert decode_cursor(cursor) == (at, 42)
    assert decode_cursor(None) is None
    assert decode_cursor("") is None


@pytest.mark.parametrize(
    "bad",
    [
        "not-base64!!",
        "aGVsbG8",
        "MjAyNi0wMS0wMVQwMDowMDowMC4wMDBa",  # a valid cursor with its last 3 characters cut off
        base64.urlsafe_b64encode(b"2026-10-02T09:30:00Z|1").decode(),  # no milliseconds
        base64.urlsafe_b64encode(b"2026-13-02T09:30:00.000Z|1").decode(),  # month 13
    ],
)
def test_bad_cursors_are_400(bad):
    with pytest.raises(ApiError) as exc:
        decode_cursor(bad)
    assert exc.value.status == 400

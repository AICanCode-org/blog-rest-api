# ADR-0002: Keyset pagination with a millisecond cursor

* **Status:** accepted
* **Date:** 2026-10-02

## Context
The feed lists published posts newest first. Readers scroll while authors publish. Pages must not
repeat or skip posts, and deep pages must stay fast.

## Options considered
1. **`LIMIT n OFFSET k`**: trivial, but every newly published post shifts all pages by one (duplicates),
   and the database still reads and discards `k` rows, so deep pages get slower.
2. **Page numbers with a snapshot table**: stable, but state per reader. Overkill.
3. **Keyset**: `WHERE (published_at, id) < (:ts, :id) ORDER BY published_at DESC, id DESC LIMIT n + 1`,
   reading the partial index `idx_posts_feed` in order.

## Decision
Option 3. The cursor is opaque to clients: base64url (no padding) of `YYYY-MM-DDTHH:MM:SS.sssZ|id`.
We fetch `limit + 1` rows; if the extra row exists, `nextCursor` encodes the **last row shown**.
Timestamps are truncated to **milliseconds** everywhere (stored and compared), because JavaScript and
JSON only carry milliseconds: a cursor built from a microsecond timestamp would never match exactly and
could skip a post. Malformed cursors are `400 cursor is invalid`.

## Consequences
* Stable pages and constant-time deep pages; `EXPLAIN` shows an index scan on `idx_posts_feed`.
* No "jump to page 7". Accepted for an infinite-scroll feed.
* The `id` tie-breaker makes ordering total; tests publish posts in the same millisecond to prove it.

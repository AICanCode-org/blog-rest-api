# DevBlog requirements

> Reference version (tag `phase-1-end`). Yours may differ in wording; it must not differ in what can be
> tested.

## 1. Problem statement
Student tech clubs want a blog where several members write tutorials and event reports, readers
comment, and an admin keeps the comments civil. Hosted platforms either lock the content in, show ads,
or offer no API the club can build its own front end on. DevBlog is a small, self-hostable REST API that
gets the security basics right (accounts, roles, tokens) and uses AI only as an assistant to a human
moderator, never as the final judge.

## 2. Users / personas and roles
| Persona | Role | Goal |
|---|---|---|
| **Ravi**, first-year student, reads on his phone | anonymous / READER | find posts by tag or keyword, comment after registering |
| **Meera**, second-year student, writes tutorials | AUTHOR | draft in peace, publish when ready, fix typos later without losing a co-author's edit |
| **Arjun**, club admin | ADMIN | promote members to authors, review flagged comments, remove anything inappropriate |

Permission matrix (✔ = allowed, 401/403/404 = what a refused request returns):

| Action | anonymous | READER | AUTHOR | ADMIN |
|---|---|---|---|---|
| Read published posts, feed, tags, search, summaries | ✔ | ✔ | ✔ | ✔ |
| Read a draft | 404 | 404 | own only (else 404) | ✔ |
| Create a post | 401 | 403 | ✔ | ✔ |
| Edit / delete a post | 401 | 403 | own only (else 403) | ✔ |
| Comment on a published post | 401 | ✔ | ✔ | ✔ |
| Delete a comment | 401 | own only (else 403) | own only (else 403) | ✔ |
| Moderation queue, approve/reject | 401 | 403 | 403 | ✔ |
| Change a user's role | 401 | 403 | 403 | ✔ (not their own) |

## 3. Functional requirements (FR)
- **FR-1 Register.** `POST /api/v1/auth/register` with `email` (trimmed, lower-cased, ≤ 254 chars,
  valid format), `password` (8–72 bytes in UTF-8) and `displayName` (1–50 chars) creates a **READER**
  and returns `201` with the user (never the password or its hash). A `role` field in the payload is
  ignored. An e-mail that is already registered returns `409 email-taken`.
- **FR-2 Login.** `POST /api/v1/auth/login` returns `200` with an access token (JWT, `expiresIn: 900`),
  a refresh token and the user. Unknown e-mail and wrong password return the **same**
  `401 invalid-credentials` (no account enumeration).
- **FR-3 Refresh with rotation.** `POST /api/v1/auth/refresh` exchanges a valid refresh token for a new
  pair and revokes the old one. Refresh tokens live 7 days. Presenting an already-used (revoked) token
  is treated as theft: **all** of that user's refresh tokens are revoked and the call returns
  `401 invalid-refresh-token`. The new access token carries the user's **current** role.
- **FR-4 Logout.** `POST /api/v1/auth/logout` revokes the given refresh token and returns `204`. It is
  idempotent (unknown or already revoked tokens also return `204`).
- **FR-5 Roles.** Every account is READER at registration. `PATCH /api/v1/admin/users/{id}/role`
  (ADMIN only) sets READER, AUTHOR or ADMIN; an admin cannot change their own role (`400`). The first
  admin is created (or promoted) at start-up from `ADMIN_EMAIL` / `ADMIN_PASSWORD`.
  `GET /api/v1/me` returns the current user.
- **FR-6 Create post.** AUTHOR or ADMIN. `title` 1–200 chars, `body` Markdown ≤ 50 000 chars, `tags`
  ≤ 5 (lower-cased, `^[a-z0-9]+(-[a-z0-9]+)*$`, ≤ 30 chars, de-duplicated), `status` `draft` (default) or
  `published`. The slug is derived from the title (`"Hello, World!"` → `hello-world`); if taken, `-2`,
  `-3`, … is appended. Returns `201` with a `Location` header.
- **FR-7 Visibility.** Published posts are public. A draft is visible only to its author and ADMINs;
  for everyone else it is `404`. Deleted posts are `404` for everyone.
- **FR-8 Edit with optimistic locking.** `PATCH /api/v1/posts/{slug}` (author or ADMIN, else `403`)
  requires the `version` the client last saw. A stale version returns `409 version-conflict` with the
  current version; nothing is overwritten. The first publish sets `publishedAt`; the slug never changes.
- **FR-9 Delete post.** `DELETE /api/v1/posts/{slug}` (author or ADMIN) soft-deletes the post (`204`).
- **FR-10 Feed.** `GET /api/v1/posts` lists published, non-deleted posts newest first with an excerpt
  (≤ 160 chars, Markdown stripped). `limit` 1–50 (default 10); `cursor` (opaque) returns the next page
  with no duplicates or gaps even while new posts are published; `tag` filters by tag; `q` (≤ 100 chars)
  is a full-text search over title and body.
- **FR-11 Tags.** `GET /api/v1/tags` lists tags with the number of published posts, sorted by name.
- **FR-12 Comments.** Any logged-in user may comment on a **published** post (`body` 1–2 000 chars,
  optional `parentId` of a visible top-level comment of the same post: one level of replies). Each
  comment is moderated on creation: it becomes `visible`, or `held` for a human if it looks like spam or
  abuse. `GET …/comments` shows only visible comments, oldest first. The comment's author or an ADMIN
  can delete it (soft delete).
- **FR-13 Moderation queue.** ADMINs list comments by status (`held` by default, `visible`, `rejected`)
  and approve or reject them; the decision records who made it and when, and the AI's original verdict
  is kept for audit.
- **FR-14 Summaries.** `GET /api/v1/posts/{slug}/summary` returns a TL;DR (≤ 280 chars) with
  `provider` and `fallback`, under the same visibility rules as the post.
- **FR-15 Health.** `GET /health` returns `200 UP` when the database answers, `503` otherwise.

## 4. Non-functional requirements (NFR)
- **NFR-1 Password storage.** bcrypt with a configurable cost (default 10, tests 4). Passwords and
  tokens never appear in logs or responses.
- **NFR-2 Tokens.** Access tokens: HS256 JWT, 15 minutes, algorithm pinned, issuer checked, secret
  ≥ 32 bytes from the environment. Refresh tokens: 256-bit random, stored only as SHA-256 hashes.
- **NFR-3 Errors.** Every error is RFC 9457 `application/problem+json` with a stable `type`; `401 unauthorized`
  responses carry `WWW-Authenticate: Bearer`; unexpected errors are a generic `500` with no stack trace.
- **NFR-4 AI is optional.** With no LLM configured, or an LLM that fails or exceeds `LLM_TIMEOUT_MS`
  (default 5 s), comments and summaries still work via rules/templates and say `fallback: true`. AI
  may hold a comment for review; it may never delete or reject one on its own.
- **NFR-5 Performance.** On a laptop with 10 000 posts: feed and search p95 < 100 ms; the feed query
  uses an index (no sequential scan, checked with `EXPLAIN`).
- **NFR-6 Privacy.** No IP addresses or user agents are stored. All sample and test data is synthetic.
- **NFR-7 Cost and portability.** Runs entirely on a laptop with Docker; no paid service is required.
  Java, Python and TypeScript implementations are interchangeable behind the same contract.
- **NFR-8 Quality.** ≥ 85 % line coverage per track (unit + integration); lint clean; CI green.

## 5. Threats (abuse cases)
| # | Threat (OWASP API Top 10 2023) | Countermeasure |
|---|---|---|
| T1 | Stolen refresh token reused by an attacker (API2 Broken Authentication) | rotation + reuse detection revokes the family (FR-3) |
| T2 | `"role":"ADMIN"` sent at registration (API3 Broken Object Property Level Authorization) | registration ignores `role` (FR-1) |
| T3 | Reading or editing someone else's draft by guessing slugs (API1 Broken Object Level Authorization) | ownership checks; drafts are `404` (FR-7, FR-8) |
| T4 | A READER calls admin endpoints (API5 Broken Function Level Authorization) | role check on every admin route (FR-5, FR-13) |
| T5 | Forged JWT with `alg: none` or a different algorithm | algorithm pinned to HS256, issuer + expiry checked (NFR-2) |
| T6 | Account enumeration through login messages or timing | identical `401` and a dummy bcrypt check for unknown e-mails (FR-2) |
| T7 | Lost update when two editors save the same post | optimistic locking (FR-8) |
| T8 | Spam / abusive comments; prompt injection inside a comment | moderation holds, never deletes; the LLM output is parsed strictly and falls back on anything odd (FR-12, NFR-4) |
| T9 | Huge payloads or pages (API4 Unrestricted Resource Consumption) | length limits on every field, `limit` ≤ 50, LLM input capped at 4 000 chars |

## 6. User stories + acceptance criteria
- **US-1 Register and log in** (M2). *As Ravi, I want an account, so that I can comment.*
  Given a new e-mail, when I register, then I get `201` and role READER. Given a registered e-mail, when I
  register again, then `409 email-taken`. Given a wrong password, when I log in, then `401
  invalid-credentials` with the same body as for an unknown e-mail.
- **US-2 Stay logged in safely** (M2). *As Ravi, I want my app to refresh my session, so that I don't log
  in every 15 minutes.* Given a refresh token, when I refresh, then I get a new pair and the old token no
  longer works. Given a token that was already used, when anyone presents it, then `401` and my other
  sessions are logged out too.
- **US-3 Drafts** (M3). *As Meera, I want to save a draft, so that nobody sees it before it is ready.*
  Given I create a post without a status, then it is a draft. Given a draft, when another user or an
  anonymous reader requests it, then `404`.
- **US-4 Publish and edit** (M3). *As Meera, I want to publish and later fix typos, so that readers see
  the final version.* Given a draft, when I PATCH `status: published`, then `publishedAt` is set. Given
  someone saved the post after I loaded it, when I save with my old version, then `409 version-conflict`.
- **US-5 Only authors write** (M2/M3). *As Arjun, I want only authors to post, so that the blog stays
  on topic.* Given a READER, when they create a post, then `403`. Given I promote them to AUTHOR, when they
  refresh their token and retry, then `201`.
- **US-6 Browse the feed** (M4). *As Ravi, I want the newest posts first and more on scroll.* Given 25
  published posts, when I page with `limit=10` following `nextCursor`, then I see each post exactly once
  and the last page has no `nextCursor`.
- **US-7 Find posts** (M4). *As Ravi, I want to filter by tag and search, so that I find the Docker
  tutorial.* Given posts tagged `docker`, when I call `?tag=docker`, then only those appear. Given a post
  whose body mentions "containers", when I search `q=container`, then it is found (stemming).
- **US-8 Comment** (M5). *As Ravi, I want to comment and reply, so that I can ask the author a question.*
  Given a published post, when I comment, then `201` and it is visible. Given a draft, when I comment,
  then `404`. Given a reply to a reply, then `400`.
- **US-9 Moderation with a human in the loop** (M5). *As Arjun, I want spam held for me, so that the
  blog stays clean without silently losing real comments.* Given a comment "click here for free money",
  then it is `held` and not listed. When I approve it, then it becomes visible and records me as
  moderator. Given the LLM is down, then the rules still decide and `fallback` is `true`.
- **US-10 TL;DR** (M5). *As Ravi, I want a two-sentence summary, so that I know if a long post is worth
  reading.* Given a published post, when I request the summary, then I get ≤ 280 chars, the provider
  and the fallback flag; for a draft I may not see, `404`.

## 7. Out of scope (v1)
Front end; image or file upload (v1.1 adds a cover-image **URL** only); e-mail verification and password
reset; OAuth / social login; likes, follows, notifications; rate limiting (documented as a known gap,
see the Phase 8 retrospective); multi-tenant blogs; editing comments.

## 8. Back-of-the-envelope estimates
- 30 authors, ~20 new posts/day at the busiest, ~200 comments/day, ~2 000 reads/day.
- Read:write ≈ 10:1. Average ≈ 0.03 requests/s; peak (× 10, an event day) ≈ 0.3 requests/s.
- Storage: a post averages ~8 KB (body + search vector + indexes), so 20/day × 365 ≈ 60 MB/year;
  comments ~1 KB × 200 × 365 ≈ 75 MB/year. Refresh tokens: a few rows per active user; expired rows can
  be purged with a nightly `DELETE` (out of scope for v1).
- bcrypt cost 10 ≈ 50–100 ms per hash on a laptop: fine for logins, and the reason tests use cost 4.

**What it means:** one PostgreSQL instance is far more than enough, and PostgreSQL's built-in full-text
search replaces a search engine. The engineering effort belongs to correctness: authorisation, token
handling, concurrency and graceful AI fallback.

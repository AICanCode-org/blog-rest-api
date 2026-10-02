---
phase: 2
slug: phase-2-design
title: Design (architecture, auth, DB schema, API contract)
duration: 3.5 h
start_tag: phase-1-end
end_tag: phase-2-end
---
# Phase 2 · Design: architecture, auth, database schema, API contract

## 1. Why it matters
Design is where you make the expensive decisions on purpose: how users prove who they are, how a
session survives a stolen token, how the feed pages through thousands of posts without skipping any,
what happens when two people edit the same post, and what an AI is allowed to decide. Writing them down
as diagrams and **Architecture Decision Records (ADRs)** lets a reviewer follow your reasoning in
minutes. The SQL schema is the one artefact that is shared by all three tracks, so a mistake here costs
three times, and a missing constraint is a security bug waiting to happen.

## 2. Objectives
- Draw context, container and sequence diagrams (login + refresh, comment + moderation) in Mermaid.
- Write five ADRs: auth design, keyset pagination, optimistic locking, AI moderation with a human in the
  loop, and why the Java track does not use the Spring Security filter chain.
- Design the PostgreSQL schema and write it as migration `spec/db/migrations/V1__init.sql`.
- Read the given OpenAPI contract and map each endpoint to a requirement.
- Watch your track's migrator apply V1 on start-up.

## 3. Step-by-step instructions

### Step 2.1 · Diagrams in `spec/architecture.md`
Mermaid is text, so it diffs and reviews like code. Start with a container diagram:
```text
flowchart TB
    client([HTTP clients: web, mobile, curl]) --> app["DevBlog app :8080"]
    app -- "SQL (source of truth)" --> pg[("PostgreSQL 16")]
    app -. "optional, timeout + fallback" .-> llm[("LLM endpoint")]
```
Wrap it in a code fence with the language `mermaid` and preview it on GitHub or in VS Code (extension
"Markdown Preview Mermaid Support"). Then draw two sequences:
1. **Login → authenticated request → refresh (rotation) → reuse of the old refresh token.**
2. **POST a comment** with the LLM answering, with the LLM down (rules fallback), and an admin approving
   a held comment.

### Step 2.2 · ADRs
Copy `spec/adr/0000-template.md` five times:
1. `0001-auth-jwt-and-rotating-refresh-tokens.md`: sessions vs JWT-only vs short JWT + opaque rotating
   refresh token. Where is each token stored, how long does it live, how do you log someone out?
2. `0002-keyset-pagination.md`: `OFFSET` vs keyset (`WHERE (published_at, id) < (…)`). What breaks when a
   post is published while someone is on page 2? Why millisecond timestamps in the cursor?
3. `0003-optimistic-locking.md`: last-write-wins vs a `version` column vs `SELECT … FOR UPDATE`.
4. `0004-ai-moderation-human-in-the-loop.md`: what may the AI decide, what happens when it is down or
   returns nonsense, how do you defend against prompt injection inside a comment?
5. `0005-no-spring-security-filter-chain.md` (all tracks read it; it explains a Java choice): use only
   `spring-security-crypto` and write the authorisation checks in the service layer.

Each ADR is one page: context, options with pros/cons, decision, consequences.

### Step 2.3 · Schema → `spec/db/migrations/V1__init.sql`
Requirements to satisfy:
- `users`: unique, lower-case `email` (enforce lower case with a `CHECK`), `password_hash` (never the
  password), `display_name`, `role` restricted by a `CHECK` to READER/AUTHOR/ADMIN.
- `refresh_tokens`: `token_hash CHAR(64)` (SHA-256 hex, unique), `user_id` with `ON DELETE CASCADE`,
  `expires_at`, nullable `revoked_at`.
- `posts`: unique `slug`, `status` draft/published, `version` for optimistic locking, `published_at`
  (required when published: a `CHECK`), `deleted_at` (soft delete), body ≤ 50 000 chars, and a
  **generated** `tsvector` column for full-text search with a GIN index.
- A **partial index** that matches the feed query exactly:
  `(published_at DESC, id DESC) WHERE status = 'published' AND deleted_at IS NULL`.
- `tags` + `post_tags` (many-to-many, cascade on post delete).
- `comments`: status visible/held/rejected, the AI verdict (label, confidence, provider, fallback,
  reason), `moderated_by`/`moderated_at` for the human decision, `parent_id` for replies.

Name every constraint (`uq_users_email`, `uq_posts_slug`, …) so error handling and tests can refer to
it. Never edit a migration after it is merged. Add `V2__…` instead (you will do that in Phase 7).

### Step 2.4 · Watch the migrator apply it
Every track runs the same tiny migrator at start-up (no Flyway/Alembic, so the three tracks can share
one SQL file). It takes a PostgreSQL advisory lock, so two instances never migrate at the same time,
and applies each file in its own transaction. Start your app (`make run`) and inspect the result:
```bash
docker compose exec postgres psql -U devblog -d devblog -c '\d posts' -c 'select * from schema_migrations'
```

<!-- track:java -->
Java: `tracks/java/src/main/java/org/aicancode/devblog/db/Migrator.java`, called from the `migrator`
bean in `config/AppConfig.java`. The repository beans depend on it, so Spring cannot serve traffic
against an old schema.
<!-- /track -->

<!-- track:python -->
Python: `tracks/python/app/db.py` → `run_migrations()`, called in the FastAPI `lifespan` in `app/main.py`.
<!-- /track -->

<!-- track:typescript -->
TypeScript: `tracks/typescript/src/db.ts` → `runMigrations()`, awaited in `src/index.ts` before `listen()`.
<!-- /track -->

Now prove the constraints work by breaking them in `psql` (each must fail):
```sql
INSERT INTO users (email, password_hash, display_name, created_at) VALUES ('Asha@Example.com', 'x', 'A', now());
INSERT INTO users (email, password_hash, display_name, role, created_at) VALUES ('a@example.com', 'x', 'A', 'ROOT', now());
```

### Step 2.5 · Map the contract
For every path in `spec/openapi.yaml`, write the FR number next to it (a table in `architecture.md`).
Any endpoint without a requirement, or any requirement without an endpoint, is a design bug: fix one of
the two. Check that every endpoint that changes data declares `security: bearerAuth`. Validate the file:
```bash
pipx run openapi-spec-validator spec/openapi.yaml       # or: npx @redocly/cli lint spec/openapi.yaml
```

### Step 2.6 · Commit
```bash
git switch -c docs/phase-2-design
git add spec/
git commit -m "docs(spec): architecture, ADRs 0001-0005, V1 schema"
```

## 4. AI-assist prompts

### Prompt 1: Mermaid sequence diagram for token rotation
```text
Write a Mermaid sequenceDiagram with participants Client, App, Postgres for: (1) login (bcrypt verify,
issue a 15-minute HS256 JWT and an opaque refresh token stored as a SHA-256 hash), (2) an authenticated
GET with the JWT, (3) refresh: look up the hash, revoke it with a compare-and-set UPDATE, issue a new
pair, (4) an attacker presents the OLD refresh token: detect reuse, revoke all of the user's refresh
tokens, return 401. Use alt blocks and keep messages short.
```
**Verify the output by:** pasting it into https://mermaid.live (it must render), then walking through
each branch against FR-2, FR-3 and NFR-2. Check that the refresh token itself is never stored and that
the reuse branch revokes *all* tokens, not only the presented one.

### Prompt 2: Review my schema
```text
Review this PostgreSQL 16 migration for a blog API: <paste V1__init.sql>. Requirements: <paste FR-1..15
and NFR-1..2>. Point out missing constraints, wrong types, missing or useless indexes, anything that
would make soft delete, keyset pagination, full-text search or optimistic locking hard, and anything
that stores a secret in a recoverable form. Do not add tables for out-of-scope features.
```
**Verify the output by:** running the migration (`make run`, then `\d posts`), trying the failure cases
by hand in `psql` (duplicate e-mail, upper-case e-mail, unknown role, published post without
`published_at`) and running `EXPLAIN` on the feed query: it must use `idx_posts_feed`, not a `Seq Scan`
(insert a few thousand rows first with `generate_series`).

### Prompt 3: Steel-man the option I rejected
```text
For a blog API, argue as convincingly as you can FOR server-side sessions in a database table (an
opaque session id in a cookie) instead of JWT access tokens plus rotating refresh tokens. Then list
when JWTs are genuinely better. Keep it under 250 words.
```
**Verify the output by:** checking each claim against your ADR-0001 (logout, revocation, horizontal
scaling, CSRF with cookies vs XSS with tokens in JavaScript), and recording the strongest
counter-argument in the ADR's *Consequences*. A good ADR admits what the rejected option does better.

## 5. Deliverables
- [ ] `spec/architecture.md` with context, container, auth + refresh, comment + moderation and ER
  diagrams that render on GitHub.
- [ ] `spec/adr/0001…0005` accepted.
- [ ] `spec/db/migrations/V1__init.sql`; the app applies it on start-up (`schema_migrations` has version 1).
- [ ] Every OpenAPI path mapped to a requirement; `EXPLAIN` of the feed query uses the partial index.
- [ ] Compare: `git diff phase-1-end phase-2-end --stat`.

## 6. Self-check quiz

### Q1. Why is the refresh token stored as a SHA-256 hash instead of in plain text?
- [ ] Hashes are shorter
- [x] A leaked database dump must not contain usable tokens
- [ ] PostgreSQL cannot store random strings
- [ ] So that it can be decoded later
> **Answer:** Like passwords, tokens are secrets. Because a refresh token has 256 bits of randomness, a fast hash is enough (no bcrypt needed): nobody can brute-force 2²⁵⁶ values.

### Q2. Why does keyset pagination beat `OFFSET` for the feed?
- [ ] It returns more rows per page
- [x] New posts published while you scroll do not shift the pages, and deep pages stay fast
- [ ] It works without an index
- [ ] It sorts alphabetically
> **Answer:** `OFFSET 20` after two new posts shows two posts again; keyset (`(published_at, id) < cursor`) continues exactly after the last row you saw, using the index.

### Q3. Why does the cursor contain the post `id` as well as `published_at`?
- [ ] To make the cursor longer
- [x] Two posts can share the same timestamp; the id breaks ties so no post is skipped or repeated
- [ ] The id is needed for authorisation
- [ ] PostgreSQL requires two columns in an index
> **Answer:** Ordering by `(published_at DESC, id DESC)` is total. With the timestamp alone, posts published in the same millisecond could fall between two pages.

### Q4. Two authors open the same post (version 3). Alice saves first. What happens when Bob saves?
- [ ] Bob's save overwrites Alice's silently
- [ ] The database locks the row until Bob gives up
- [x] Bob gets `409 version-conflict` with the current version, reloads and merges
- [ ] Both versions are stored
> **Answer:** `UPDATE … WHERE id = ? AND version = 3` matches nothing after Alice's save made it 4. No locks are held while people type.

### Q5. In ADR-0004, what is the AI allowed to do with a comment?
- [ ] Delete it if it is confident
- [ ] Reject it permanently
- [x] Hold it for a human; only an admin can reject it
- [ ] Rewrite it to be polite
> **Answer:** False positives are inevitable. Holding is reversible, so a model mistake costs a delay, not a lost comment. The admin's decision is recorded next to the AI verdict.

### Q6. What does a partial index `… WHERE status = 'published' AND deleted_at IS NULL` give the feed?
- [ ] It hides drafts from the API automatically
- [x] A smaller index that contains exactly the rows the feed query reads, in feed order
- [ ] It enforces that drafts have no `published_at`
- [ ] It speeds up inserts
> **Answer:** The planner can read the first N entries of the index and stop. Drafts and deleted posts never bloat it. The query's `WHERE` must match the index predicate for it to be used.

## 7. What you learned
- Diagrams-as-code with Mermaid: context, containers, sequences, ER.
- Token design: short-lived JWTs, opaque rotating refresh tokens, hashing secrets at rest, reuse detection.
- Keyset pagination and optimistic locking as answers to real concurrency problems.
- Bounding what an AI may decide, and designing the fallback before the feature.
- Schema design with named constraints, CHECKs, generated columns, partial and GIN indexes, soft delete.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git switch -c my-phase-3 phase-2-end
git diff phase-1-end phase-2-end -- spec/        # the reference design
git checkout phase-2-end -- spec/db/migrations/V1__init.sql   # take only the schema
```

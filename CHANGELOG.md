# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.1.0] - 2026-10-02
### Added
- Request IDs: `X-Request-ID` is validated (or generated), echoed on every response and included in every log line.
- Structured JSON logs (Logstash JSON in Java, a JSON formatter in Python, pino in TypeScript), one access line per request.
- `GET /metrics` (Prometheus text): users registered, failed logins, posts published, comments created and
  held, AI fallbacks, LLM short-circuits. Same names in every track.
- LLM circuit breaker shared by moderation and summaries: 3 failures in a row open it for 30 s, then one
  half-open trial. A hanging LLM now costs three slow comments instead of every comment.
- Optional `coverImageUrl` on posts (absolute http(s) URL, `null` clears it), OpenAPI 1.1.0, migration
  `V2__post_cover_image.sql` (nullable, so v1.0.0 keeps working against the new schema).
- `scripts/game-day.sh` (fake LLM that hangs) and `docs/postmortem-2026-10-02-llm-hang.md`.
- Smoke test: request-ID and `/metrics` checks (57 checks; 59 with `EXPECT_FALLBACK=true`).

## [1.0.0] - 2026-10-02
### Added
- Requirements (FR-1..15, NFR-1..8, threats T1..T9), architecture diagrams, ADRs 0001-0005,
  `V1__init.sql`, OpenAPI 1.0.0.
- Auth in all three tracks: registration (always READER), bcrypt, 15-minute HS256 JWTs, rotating refresh
  tokens stored as SHA-256 hashes with reuse detection, logout, roles and an admin bootstrapped from the
  environment.
- Posts: drafts (404 for others), slugs, optimistic locking (`409 version-conflict`), soft delete;
  keyset-paginated feed, tag filter, PostgreSQL full-text search, tag counts.
- Comments with one level of replies; AI moderation that can only hold a comment, rules fallback,
  admin queue and decisions; post summaries with a template fallback. Ollama/Gemini/Groq optional.
- Unit + integration tests per track (including a concurrent-refresh race test), 85 % line-coverage gate.
- CI: path filters, reusable per-track workflow with a PostgreSQL service container, CMS export check,
  Docker image smoke test, `ci-ok`.
- Dockerfiles (multi-stage, non-root, health check), Compose profiles per track, `scripts/smoke-test.sh`.
- `deploy.yml`: images to GHCR on version tags; optional Render deploy.
- `docs/runbook.md`.

## [0.1.0] - starter
### Added
- Starter: three track skeletons (Java, Python, TypeScript) with the complete (pending) test suite,
  shared OpenAPI contract, `/health`, Docker Compose with PostgreSQL, minimal CI.

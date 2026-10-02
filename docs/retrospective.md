# DevBlog retrospective (reference solution, v1.0.0 → v1.1.0)

Format: *What went well / What didn't / What I'd change*, plus one technical, one process and one AI
lesson, as asked in Phase 8. Written by the maintainers who built the reference solution. Yours will
(and should) look different.

## What went well
- **One contract, three implementations.** `spec/openapi.yaml`, the SQL migrations and
  `scripts/smoke-test.sh` are shared. All three tracks pass the same 57-check smoke test against their
  Docker images (53 at v1.0.0), including the security checks: `role` mass assignment ignored, drafts
  `404` for others, refresh-token reuse revoking the whole family, `403` for the wrong role.
- **Security as tests, not as prose.** Every threat in `spec/requirements.md` (T1–T9) points at a test.
  The race test sends several refreshes with the same token at once and asserts that exactly one
  wins; it passes because rotation is a compare-and-set (`UPDATE … WHERE id = ? AND revoked_at IS NULL`
  must affect exactly one row), not a read followed by a write.
- **Tests before code with a pending list.** Turning on one test group at a time kept every commit green
  and made "done" objective. Every phase tag builds and passes its tests in all three tracks
  (`scripts/verify-tags.sh`).
- **Offline first.** The rules moderator and the template summary mean nobody needs an API key to
  finish, and the AI may only *hold* a comment, so a model mistake costs a delay, never a lost comment.

## What didn't go well
- **Failure tests were too kind.** Every "LLM is down" test failed fast (connection refused). The Phase 7
  game day made the LLM *hang* instead: in v1.0.0 every comment waited the full 5 s timeout, and in
  the Python track 50 concurrent comments exhausted the worker thread pool so that even `/health` took
  4 s (`docs/postmortem-2026-10-02-llm-hang.md`). Correct behaviour, unacceptable latency.
- **One unexplained number.** A single Python comment took 15.2 s during the game day and did not
  reproduce. It is recorded as an open action item instead of being explained away.
- **Version churn.** Framework majors (Spring Boot 4 / Jackson 3, Fastify 5) moved APIs between "what
  the assistant remembered" and "what the docs say today". Several snippets had to be rewritten against
  current documentation.
- **Shared build machine.** One game-day run measured the wrong image because a mutable `:local` tag had
  been rebuilt in the meantime. The numbers looked plausible; a counter that v1.0.0 does not have gave it away.

## What I'd change next time
1. Write a **degraded-mode latency budget** next to NFR-4 on day one, and test it with a hanging
   dependency, not only a refused one.
2. Put the **game day into CI** (fake LLM in `MODE=hang` inside the Docker smoke job, assert latency).
3. Keep slow I/O off the request path in the Python track (async client or a bulkhead) so one slow
   dependency cannot starve unrelated endpoints.
4. Rate-limit `POST /auth/login` per account and IP. Today `devblog_logins_failed_total` only *shows*
   credential stuffing; it does not stop it.

## Three lessons
- **Technical:** a fallback is only graceful if the failure is fast. A circuit breaker turns "every request
  waits 5 s" into "three requests wait, then milliseconds", and it needs a half-open trial so it recovers
  on its own.
- **Process:** a pending-test list plus small milestone commits made progress visible and reviews small.
  The catch-up tags made it safe to fall behind.
- **AI:** assistants were excellent at boilerplate (Dockerfiles, workflow skeletons, test scaffolding,
  the fake LLM) and needed the strictest review exactly where it matters most: token handling,
  authorisation checks and anything version-specific. Every accepted suggestion is in `AI_LOG.md` with
  how it was verified; tests, `actionlint` and the smoke test caught what reading did not.

## Evidence
| | Java | Python | TypeScript |
|---|---|---|---|
| Unit + integration tests (v1.1.0) | 154 + 13 | 186 + 13 | 183 + 13 |
| Line coverage (gate ≥ 85 %) | 96.75 % (JaCoCo) | 99.09 % (coverage.py) | 97.91 % (V8) |
| Docker smoke test v1.1.0 (LLM unreachable) | 57/57 (59/59) | 57/57 (59/59) | 57/57 (59/59) |
| 10 comments while the LLM hangs, v1.0.0 → v1.1.0 (avg) | 5 026 → 1 519 ms | 6 036 → 1 572 ms | 5 014 → 1 509 ms |

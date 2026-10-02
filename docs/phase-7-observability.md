---
phase: 7
slug: phase-7-observability
title: Observability and maintenance
duration: 3 h
start_tag: phase-6-end
end_tag: phase-7-end
release: v1.1.0
---
# Phase 7 · Observability and maintenance (v1.1.0)

## 1. Why it matters
Shipping v1.0.0 is the start, not the end. In production you have to answer "is it working right now,
and if not, why?" without attaching a debugger. That needs three things: **logs** you can search,
**metrics** you can graph, and a **request ID** that ties a reader's complaint to log lines. DevBlog
also depends on something you do not control, an LLM, so you will run a **game day** where the LLM
hangs, measure what readers feel, and fix it with a **circuit breaker**. Finally you make your
first **maintenance change**, a schema migration plus a backward-compatible API field, and release it
safely as v1.1.0.

## 2. Objectives
- Give every request an `X-Request-ID` (reuse a safe incoming one, or generate a UUID) and put it in every log line.
- Emit structured **JSON logs**: one object per line, one access-log line per request.
- Expose `/metrics` in Prometheus text format with seven counters that are identical in every track.
- Run a **game day**: make the LLM hang, measure comment latency, write a short blameless post-mortem.
- Add an LLM **circuit breaker** (3 failures in a row → open for 30 s → one half-open trial).
- Ship a backward-compatible change: `V2__post_cover_image.sql` + `coverImageUrl` in the API, OpenAPI 1.1.0, CHANGELOG, tag `v1.1.0`.
- Review and merge one Dependabot PR.

## 3. Step-by-step instructions

### Step 7.1 · Request IDs
Rule (shared by all tracks): if the incoming `X-Request-ID` matches `^[A-Za-z0-9._-]{1,64}$`, echo it;
otherwise generate a UUID. Never echo arbitrary input: a header containing a newline could inject
fake log lines or headers.

<!-- track:java -->
A `OncePerRequestFilter` (`web/RequestIdFilter.java`) sets the response header, puts `requestId` in
the SLF4J **MDC** (so every log line in that request carries it) and logs one `devblog.access` line
with `method`, `path`, `status` and `durationMs` using the fluent API
`LOG.atInfo().addKeyValue("status", …).log()`. `@Order(Ordered.HIGHEST_PRECEDENCE)` makes it the first
filter, so even a `401` or a `404` gets an ID (there is no Spring Security filter chain, see ADR-0005).
<!-- /track -->

<!-- track:python -->
A Starlette middleware (`app/observability.py`) stores the ID in `request.state`, sets the response
header and logs one `devblog.access` record with `extra={"fields": {...}}`. The middleware wraps the
whole app, so error responses (`400`, `401`, `404`) carry the header too.
<!-- /track -->

<!-- track:typescript -->
Fastify has request IDs built in: `genReqId` (calling `incomingOrNewId()` from `src/observability.ts`)
with `requestIdHeader: false`, so we validate the incoming value ourselves, and a `LogController` with `requestIdLogLabel: "requestId"`.
An `onSend` hook copies `request.id` into the `X-Request-ID` response header.
<!-- /track -->

### Step 7.2 · JSON logs
<!-- track:java -->
One property: `logging.structured.format.console=logstash` in `application.properties`. MDC values
and key/value pairs become JSON fields.
<!-- /track -->

<!-- track:python -->
A small `logging.Formatter` subclass (`JsonFormatter`) that `json.dumps` `ts`, `level`, `logger`, `msg`
and the `fields`; installed by `configure_logging()` at startup.
<!-- /track -->

<!-- track:typescript -->
Nothing to add: Fastify's logger (pino) already writes JSON, one line per event (`incoming request`,
`request completed` with `responseTime`).
<!-- /track -->

**Never log secrets.** Passwords, access tokens, refresh tokens and the `Authorization` header must not
appear in any log line. Grep for them after the smoke test (`docker compose logs app-<track> | grep -i bearer`
must print nothing).

Check it:
```bash
docker compose --profile <track> up -d --build --wait
curl -s -H 'X-Request-ID: demo-123' localhost:8080/health -o /dev/null -D - | grep -i x-request-id
docker compose logs app-<track> | grep demo-123
```

### Step 7.3 · `/metrics`
Seven counters, same names in all tracks (see `GET /metrics` in `spec/openapi.yaml`):

| Counter | Incremented when |
|---|---|
| `devblog_users_registered_total` | a registration succeeds |
| `devblog_logins_failed_total` | a login is rejected (watch it for credential stuffing) |
| `devblog_posts_published_total` | a post becomes `published` |
| `devblog_comments_created_total` | a comment is stored |
| `devblog_comments_held_total` | moderation holds a comment for review |
| `devblog_ai_fallback_total` | an AI call fell back to the offline rules/template |
| `devblog_llm_short_circuits_total` | the circuit breaker skipped an LLM call (Step 7.5) |

They are hand-rolled on purpose: the Prometheus text format is just
```text
# HELP devblog_logins_failed_total Login attempts answered with 401.
# TYPE devblog_logins_failed_total counter
devblog_logins_failed_total 3
```
In a bigger service you would use Micrometer (Java), `prometheus-client` (Python) or `prom-client` (Node).
`/metrics` is public in this course project; in production you would restrict it to the scraper's network.

### Step 7.4 · Game day: the LLM hangs
A dead LLM is easy: the connection is refused and the fallback answers in milliseconds. A **hanging** LLM
is worse: it accepts the connection and never answers, so every request that needs it waits for the full
`LLM_TIMEOUT_MS` (5 s by default). That is what an overloaded hosted model or a stuck local Ollama looks like.
1. Start the stack at v1.0.0: `git switch --detach v1.0.0`, `docker compose --profile <track> up -d --build --wait`.
2. Get the script from the reference: `git show phase-7-end:scripts/game-day.sh > /tmp/game-day.sh && chmod +x /tmp/game-day.sh`,
   then `/tmp/game-day.sh <track>`. It creates a published post and a reader, then runs four phases of
   10 comments each: **offline** baseline, a **healthy** fake LLM, the fake LLM **hanging**, and the LLM
   **back**. The fake LLM is a 15-line Python HTTP server in a throw-away container on the Compose network
   (`MODE=ok` answers like `/v1/chat/completions`, `MODE=hang` never answers), so no model download and no API key.
3. Write down for each phase: status codes, average and maximum latency, the moderation `provider` and
   `fallback` flag, the warnings in the logs, and (from v1.1) the counters. Is any comment lost? Is any
   comment slow? What would 50 comments a minute feel like?
4. Write `docs/postmortem-<date>-llm-hang.md`: summary, impact, timeline, root cause, what went well,
   what to improve, action items. Blameless: describe systems, not people. Read the reference
   post-mortem **after** writing yours (`git show phase-7-end:docs/postmortem-2026-10-02-llm-hang.md`).

### Step 7.5 · Fix: an LLM circuit breaker
Wrap the one LLM client (shared by moderation and summaries) in a `CircuitBreaker`:
- **CLOSED:** calls go through. 3 failures **in a row** open the circuit.
- **OPEN:** calls fail immediately (the caller uses its offline fallback) for 30 s, and
  `devblog_llm_short_circuits_total` is incremented.
- **HALF-OPEN:** after the cooldown, exactly **one** trial call goes through. Success closes the circuit;
  failure re-opens it for another 30 s.

Test it with a fake clock and a fake client (no sleeping in tests): 3 failures → the 4th call does not reach
the client; advance the clock 30 s → one trial; a concurrent second call during the trial is short-circuited.

<!-- track:java -->
`ai/CircuitBreaker.java` implements `LlmClient`, so `AppConfig` wraps the HTTP client once and both
`LlmModerator` and `LlmSummarizer` receive the wrapped instance. State changes are `synchronized`;
the slow HTTP call happens **outside** the lock.
<!-- /track -->

<!-- track:python -->
`CircuitBreaker` in `app/ai.py` has the same `chat()` signature as the HTTP client, a `threading.Lock`
around state changes and `time.monotonic` as the default clock (wall-clock time can jump).
<!-- /track -->

<!-- track:typescript -->
`CircuitBreaker` in `src/ai.ts` wraps the client's `chat()`. Node is single-threaded, but `await` still
interleaves requests, so the `trialRunning` flag matters exactly as it does in Java and Python.
<!-- /track -->

Re-run the game day on your branch: in the *hang* phase the first 3 comments still wait for the timeout,
the rest answer in milliseconds with `fallback: true`. In the *back* phase the circuit stays open until the
30 s cooldown has passed, then one trial call closes it again.

### Step 7.6 · Maintenance change: `coverImageUrl` (v1.1.0)
1. `spec/db/migrations/V2__post_cover_image.sql`: `ALTER TABLE posts ADD COLUMN cover_image_url VARCHAR(2048);`
   Nullable, no default, so v1.0.0 code keeps working against the new schema.
2. `CreatePostRequest` / `UpdatePostRequest` accept an **optional** `coverImageUrl`: an absolute `http(s)` URL
   (validated with the regex in the OpenAPI spec; `javascript:` URLs are rejected), `null` clears it.
3. `Post` and `PostListItem` gain `coverImageUrl` (null when not set). Adding a field is backward compatible;
   removing or renaming one is not.
4. `spec/openapi.yaml` → `version: 1.1.0`; `CHANGELOG.md` → `## [1.1.0]`; bump the track's version.
5. Tests first: validation tests for the URL, an API test that sets and clears it, an integration test that
   the column round-trips.
6. Release: `git tag -a v1.1.0 -m "DevBlog v1.1.0" && git push origin main --follow-tags`.
   `deploy.yml` publishes `:1.1.0` and moves `:latest`.

### Step 7.7 · Merge a Dependabot PR
Pick one grouped update. Read its release notes, check that CI is green **including integration tests**,
run it locally if it is a major version, then merge. Write down in `AI_LOG.md` or the PR what you checked.

## 4. AI-assist prompts

### Prompt 1: Structured logging setup
```text
In <Spring Boot 4 | FastAPI | Fastify 5> I want one JSON log line per request with requestId, method,
path, status and durationMs, where requestId comes from a validated X-Request-ID header or a new UUID
and appears on every log line of that request, including 401 responses from the auth layer. Show the
minimal code with no new dependencies, and explain how the request ID is propagated (MDC / contextvars /
child logger). Passwords and tokens must never be logged.
```
**Verify the output by:** sending a request with `X-Request-ID: demo-123` and with a malicious value
(`$'bad\nInjected: 1'` in bash). The first must be echoed and logged, the second replaced by a UUID.
Check `docker compose logs` shows valid JSON (`| jq .`) and that `grep -i bearer` finds nothing.

### Prompt 2: Review my circuit breaker
```text
Here is my LLM circuit breaker in <language>: <paste>. Rules: 3 consecutive failures open it for 30 s,
then exactly one half-open trial; success closes, failure re-opens. Find race conditions (two requests
during the trial), places where the slow call happens while holding a lock, and missing tests. Suggest
tests that use a fake clock instead of sleeping.
```
**Verify the output by:** running the suggested tests and the game day. The short-circuit counter in
`/metrics` must grow by one for every comment after the third in the *hang* phase.

### Prompt 3: Is this migration backward compatible?
```text
I run several app instances and deploy one at a time. Is this migration safe to apply while the old
version is still serving traffic? <paste V2 SQL> What would make it unsafe (NOT NULL, defaults on big
tables, renames)? How would I roll it back?
```
**Verify the output by:** running the **v1.0.0** image against a database that already has V2 applied
(`git switch --detach v1.0.0`, `docker compose --profile <track> up -d --build`) and running the v1.0.0
smoke test: it must still pass.

## 5. Deliverables
- [ ] `X-Request-ID` echoed/generated; JSON access logs visible in `docker compose logs`, no tokens in them.
- [ ] `/metrics` with the seven counters; the v1.1 smoke test passes (57 checks).
- [ ] `docs/postmortem-…-llm-hang.md` with real numbers from your game day.
- [ ] Circuit breaker with fake-clock tests; the game day re-run shows fast fallbacks after 3 failures.
- [ ] `V2__post_cover_image.sql`, `coverImageUrl` in the API, OpenAPI 1.1.0, CHANGELOG, tag `v1.1.0`.
- [ ] One merged Dependabot PR.

## 6. Self-check quiz

### Q1. Why validate an incoming `X-Request-ID` before echoing it?
- [ ] To make IDs shorter
- [x] An unvalidated value could inject newlines or fake fields into logs and headers
- [ ] Browsers reject long headers
- [ ] UUIDs are faster to generate
> **Answer:** Treat every header as untrusted input. A strict allow-list regex is the simplest defence.

### Q2. During the game day every comment still returned 201. Why is a hanging LLM still an incident?
- [ ] Comments were lost
- [x] Every comment waited for the full LLM timeout, so writers felt seconds of latency and server threads/connections were tied up
- [ ] The database went down
- [ ] Moderation stopped working entirely
> **Answer:** Graceful degradation kept correctness (the rules fallback answered) but not latency. Availability is also about how long users wait.

### Q3. What does the circuit breaker do in the OPEN state?
- [ ] Retries the LLM three times
- [ ] Returns 503 to the client
- [x] Skips the LLM call immediately so the caller uses its offline fallback, until the cooldown ends
- [ ] Switches to a different LLM provider
> **Answer:** It turns a slow failure into a fast one. After the cooldown one half-open trial checks whether the LLM has recovered.

### Q4. Why is `ADD COLUMN cover_image_url VARCHAR(2048)` (nullable, no default) the safe choice?
- [ ] Postgres does not support `NOT NULL` columns
- [x] v1.0.0 code that does not know the column keeps working, and the change is instant
- [ ] It saves disk space
- [ ] It makes the column indexed
> **Answer:** Expand first, (optionally) contract later. A `NOT NULL` column would break inserts from v1.0.0 during a rolling deploy.

### Q5. Adding `coverImageUrl` to the response is a … change.
- [ ] Major (breaking)
- [x] Minor (backward-compatible feature), so 1.0.0 → 1.1.0
- [ ] Patch (bug fix)
- [ ] No version change needed
> **Answer:** SemVer: new optional fields are minor. Removing or renaming a field would be major (2.0.0).

### Q6. Which metric would you alert on to spot a credential-stuffing attack?
- [ ] `devblog_users_registered_total`
- [x] A sudden rise in `devblog_logins_failed_total`
- [ ] `devblog_comments_held_total`
- [ ] `devblog_llm_short_circuits_total`
> **Answer:** Many rejected logins in a short time is the classic signal. The fix (rate limiting per IP/account) is listed as a gap in Phase 8.

### Q7. What makes a post-mortem "blameless"?
- [ ] Nobody writes it
- [ ] It omits the root cause
- [x] It focuses on how the system and process allowed the failure, not on who made a mistake
- [ ] It is kept private
> **Answer:** People hide problems when they fear blame. Blameless reviews produce better action items.

## 7. What you learned
- Correlation IDs, MDC/context propagation and structured JSON logs without secrets.
- Prometheus counters and the text exposition format.
- Game days, blameless post-mortems, and why "hangs" is worse than "down".
- The circuit-breaker pattern, tested with a fake clock.
- Expand-style, backward-compatible migrations and SemVer minor releases.
- Dependency maintenance with Dependabot, and the role of CI as the safety net.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git switch -c my-phase-8 phase-7-end          # == v1.1.0
git diff v1.0.0 v1.1.0 --stat                 # the whole maintenance change
git diff v1.0.0 v1.1.0 -- spec/               # contract + migration only
```

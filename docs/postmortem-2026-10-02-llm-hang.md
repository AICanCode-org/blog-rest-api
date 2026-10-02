# Post-mortem: the LLM hangs (game day, 2026-10-02)

| | |
|---|---|
| Type | Planned game day (fault injection), local Docker Compose stack, v1.0.0 and the v1.1.0 release candidate |
| Fault | The LLM endpoint accepts connections but never answers (`scripts/game-day.sh`, fake LLM in `MODE=hang`) |
| Tracks | Python, TypeScript, Java (one at a time, same stack, `LLM_TIMEOUT_MS=5000`, the default) |
| Status | Resolved. Circuit breaker shipped in v1.1.0, four follow-ups open |
| Format | Blameless: we describe systems and decisions, not people |

All times are UTC. Numbers come from `scripts/game-day.sh <track>`: per phase 10 comments posted one after
the other on one published post, client-side `curl` timings, then 50 concurrent comments while timing
`/health`. Reproduce it yourself, but expect your own numbers.

## Summary
With the LLM hanging, every track kept accepting comments (`201`, nothing lost) and moderated them with
the offline rules (`provider: rules`, `fallback: true`), exactly as NFR-4 and ADR-0004 require. But in
v1.0.0 **every comment waited the full 5 s timeout**, because nothing remembered that the LLM had just
failed. In the **Python** track it was worse: 50 concurrent comments tied up the worker thread pool and
an unrelated `GET /health` took **4.0 s**. v1.1.0 adds a circuit breaker in front of the one LLM client:
after 3 failures in a row it skips the LLM for 30 s, so only the first three comments are slow and the
rest answer in milliseconds. After the cooldown one trial call found the LLM healthy and closed it again.

## Impact (v1.0.0, before the fix)
| | Python | TypeScript | Java |
|---|---|---|---|
| Baseline, `AI_PROVIDER=offline` (avg of 10) | 6 ms | 8 ms | 12 ms |
| Healthy fake LLM (avg of 10) | 10 ms | 18 ms | 30 ms |
| **LLM hangs** (avg / max of 10) | **6 036 ms / 15 221 ms** | **5 014 ms / 5 029 ms** | **5 026 ms / 5 053 ms** |
| `GET /health` while 50 comments are in flight | **4.03 s** | 0.003 s | 0.006 s |
| Status codes / moderation during the fault | 10 × `201`, rules, `fallback: true` | same | same |

No data loss and no wrong decisions: the rules held nothing that they would not have held anyway, and
the moderation queue kept working. For a reader, though, a 5-second "Post comment" button is broken, and
50 comments a minute would keep about four request threads permanently busy waiting for nothing.

## Timeline (Python track)
| Time (UTC) | Event | Evidence |
|---|---|---|
| 13:49:42 | Published post and reader created; baseline: 10 comments, avg 6 ms | script output |
| 13:50:00 | App restarted with `AI_PROVIDER=llm` against the fake LLM (`MODE=ok`): 10 comments, avg 10 ms, `provider: llm` | script output |
| 13:50:00 | **Fault injected:** fake LLM replaced by `MODE=hang` | |
| 13:50:00–13:51:05 | 10 comments take 65 s in total: nine ≈ 5.0 s, one **15.2 s**; all `201`, `fallback: true` | `curl -w %{time_total}` |
| 13:51:05 | 50 concurrent comments; `GET /health` answers `200` after **4.03 s** | script output |
| 13:51:05+ | Logs: one line per comment, `LLM moderation failed, using rules: timed out` (plain text, no request ID) | `docker compose logs app-python` |
| 13:51:15 | **Recovery:** fake LLM back to `MODE=ok`; the next comment uses the LLM again (11 ms) | script output |
| 13:52–13:57 | Same game day on TypeScript and Java: ≈ 5.0 s per comment, `/health` unaffected | script output |
| 13:58:14 | v1.1.0 (circuit breaker) Python: fault injected again | |
| 13:58:33 | 10 comments: avg **1 572 ms** (three ≈ 5 s, seven short-circuited in ms); `devblog_llm_short_circuits_total 7` | `/metrics` |
| 13:58:34 | 50 concurrent comments, `/health` in **0.003 s**; short-circuits 57; JSON log line `LLM circuit open: skipping the call` | `/metrics`, logs |
| 13:58:39 | LLM back: comments still use the rules for the rest of the 30 s cooldown (7 ms, `fallback: true`) | script output |
| 13:59:10 | After 31 s: the half-open trial succeeds, `provider: llm` again (avg 13 ms) | script output |

v1.1.0 on the other tracks: TypeScript avg 1 509 ms and Java avg 1 519 ms for the 10 comments during the
fault, both with 7 short-circuits and `/health` in a few milliseconds; both recovered after the cooldown
(Java's first calls after the cooldown took up to 3.3 s, then ≈ 20 ms).

## Root cause
1. **The LLM call is on the request path and has no memory.** Each comment calls the LLM synchronously
   with a 5 s timeout. v1.0.0 handled each failure correctly but independently, so a hanging provider
   cost every comment the full timeout. ADR-0004 had accepted this risk ("Revisit if that hurts").
2. **Our failure tests simulated the easy failure.** The unit tests make the LLM fail with *connection
   refused* (a fake client in Python and TypeScript, port 9 on `127.0.0.1` in Java), and the Phase 6
   fallback smoke run used an unreachable address with a 1.5 s timeout: all fast or bounded. That proves the fallback is *correct*, not that it is *fast*: a
   hanging endpoint is the realistic failure of an overloaded hosted model or a stuck local Ollama.
3. **Python: one shared thread pool.** FastAPI runs plain `def` endpoints in AnyIO's worker thread pool
   (40 threads by default). 50 comments each holding a thread for 5 s left `/health` (also a `def`
   endpoint) waiting for a free thread. Tomcat (200 threads) and Node's event loop did not hit this limit
   at 50 concurrent requests, but would at higher load.
4. **Unexplained outlier.** One Python comment took 15.2 s (three timeouts). A targeted re-run with a
   fresh and a stale connection pool (5 comments) did not reproduce it: all took 5.0 s. Not yet understood.

## What went well
- Graceful degradation worked in all tracks: no `5xx`, no lost comments, the human queue still worked.
- The same game day across three stacks quickly separated a shared design problem (no breaker) from a
  track-specific one (Python's thread pool).
- The fake LLM (15 lines of Python in a throw-away container) made the failure reproducible in seconds,
  with no model download and no API key.
- After the fix, `/metrics` made the breaker visible (`devblog_llm_short_circuits_total`), and the JSON
  logs (v1.1) gave one searchable line per short-circuit.

## What went wrong
- No latency budget for degraded mode was written down, so nothing tested for it.
- v1.0.0 had no metrics, so during the fault the only evidence was client-side timings and plain-text log
  lines without request IDs. The v1.1 observability work was needed to see the fix working.

## Action items
| # | Action | Type | Status |
|---|---|---|---|
| 1 | Circuit breaker around the shared LLM client (3 failures → open 30 s → one half-open trial), counter `devblog_llm_short_circuits_total`, fake-clock unit tests in every track | fix | **Done in v1.1.0** |
| 2 | Add NFR-9: *"With the LLM unavailable or hanging, p95 comment latency stays < 500 ms, excluding at most 3 requests per 30 s"* | requirement | Open |
| 3 | Add a hanging-LLM case (a server that never answers) to the integration tests and run `scripts/game-day.sh` in the CI Docker job with a latency assertion | test | Open |
| 4 | Python: keep slow I/O from starving unrelated endpoints (async HTTP client for the LLM, or a small bulkhead limiting concurrent LLM calls) | fix | Open |
| 5 | Find the cause of the single 15.2 s comment in the Python track | investigation | Open |
| 6 | Lower `LLM_TIMEOUT_MS` to 1–2 s. Rejected for now: a local Ollama on a laptop CPU often needs 2–4 s, and the breaker already bounds the cost | idea | Won't do |

## Lessons
- **"Down" is easy, "hangs" is hard.** A refused connection fails in milliseconds; a hanging one costs the full timeout, every time.
- **A fallback is only graceful if the failure is fast.** Correctness and latency are separate properties; test both.
- **Shared pools spread failures.** One slow dependency can slow down endpoints that never call it.

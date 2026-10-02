# AI_LOG: how I used AI on this project

Employers and reviewers increasingly ask *how* you used AI, not *whether*. This log is your evidence
that you stayed in control. Add one entry every time an AI assistant (ChatGPT, Gemini, Copilot,
Cursor, Claude, a local Ollama model, ...) produced something you kept, changed or rejected.

**Rules**
1. Never paste secrets, `.env` contents, JWT secrets, tokens, API keys or other people's personal data
   into a prompt.
2. Every AI output is a *draft*. Record how you **verified** it (test, docs, running it, a second source).
3. Be honest about rejections: they are the most valuable entries. Security code (hashing, tokens,
   authorisation) gets the strictest review.

## Entry template (copy for each entry)

### YYYY-MM-DD · Phase N · <short title>
* **Tool / model:** e.g. Gemini 2.0 Flash (free tier), Copilot in VS Code, Ollama llama3.2:1b
* **Prompt (summary or paste):**
  > ...
* **What it produced:** one or two lines, or a link to the commit
* **What I kept / changed / rejected, and why:**
* **How I verified it:** e.g. `make test` (60 passed), compared with the OpenAPI spec, read the official docs at ...
* **Time saved / lost (honest guess):**
* **What I learned:**

---

## My entries

<!-- Start below this line. Phase 0 asks you to write your first entry. -->

> The reference solution was written by the AiCanCode maintainers working with an AI coding agent.
> These entries record checks that really happened while building it. Your entries will be about *your* work.

### 2026-10-02 · Phase 0 · Port 5432 was already taken
* **Tool / model:** AI coding agent (chat + shell)
* **Prompt (summary):**
  > `docker compose up -d postgres` fails with "Bind for 0.0.0.0:5432 failed: port is already allocated". What is going on and what is the least invasive fix?
* **What it produced:** the explanation that another PostgreSQL (here: a different project's Compose
  stack) already published 5432, and three options: stop the other stack, change the published port
  with `POSTGRES_PORT` (already supported by our `docker-compose.yml`), or stop a system PostgreSQL.
* **What I kept / changed / rejected and why:** kept the `POSTGRES_PORT` override for local experiments
  (and `DATABASE_URL` / `TEST_DATABASE_URL` pointing at the new port), because stopping someone else's
  stack is not my call. Rejected the suggestion to `kill` whatever process owned the port.
* **How I verified it:** `docker compose ps` → `healthy`; the Python starter with
  `DATABASE_URL=…:55432/devblog make run`, then `curl localhost:8080/health` →
  `{"status":"UP","checks":{"database":"UP"}}`; `make lint test` in each track (only the always-on
  tests pass, the rest are skipped as pending).
* **Time saved / lost (honest guess):** saved ~10 min.
* **What I learned:** make ports configurable from day one; it costs one line in Compose.

### 2026-10-02 · Phase 3 · A test that called the code under test at import time
* **Tool / model:** AI coding agent
* **Prompt (summary):**
  > The starter's text tests are pending, so why does collecting them fail with "TODO(phase-3 M1)" instead of reporting them as skipped?
* **What it produced:** the diagnosis that one parametrised "bad cursor" case was built by calling
  `encode_cursor(...)` at module level, so the stub ran while the test file was being *collected*,
  before the pending marker could skip anything.
* **What I kept / changed / rejected and why:** kept the diagnosis, and replaced the call with the
  literal it produces (`MjAyNi0wMS0wMVQwMDowMDowMC4wMDBa`) in the Python and TypeScript suites.
  Rejected the alternative of wrapping the import in `try/except`, which would hide real errors.
* **How I verified it:** the starter collects cleanly (pending tests reported as skipped) and the
  solution's text tests still pass (14 in Python, 14 in TypeScript).
* **Time saved / lost (honest guess):** saved ~20 min.
* **What I learned:** test data should be data. Anything computed at import time runs before your skip logic.

### 2026-10-02 · Phase 3 · Reviewing the generated TODO hints
* **Tool / model:** AI coding agent
* **Prompt (summary):**
  > Generate the starter stubs from the reference code, with a one-line hint per method, keyed by method name.
* **What it produced:** stubs in all three tracks that compiled and kept the tests runnable.
* **What I kept / changed / rejected and why:** a line-by-line review found hints attached to the wrong
  method wherever two classes share a method name: `CommentService.create` got the hint for creating a
  *post*, `Validation.tags` got the hint for *listing* tags, and the LLM moderator got the rule-based
  moderator's hint. Fixed with class-qualified hints and a re-review of every stub.
* **How I verified it:** listed every `TODO(phase-3 Mx)` with its hint and compared each with the tests
  of that class; lint and the always-on tests pass in all three starters.
* **Time saved / lost (honest guess):** generation saved hours; the review took 20 minutes and was essential.
* **What I learned:** generated text keyed by a short name collides silently. Review the output, not the generator.

### 2026-10-02 · Phase 6 · Porting the smoke test from another project
* **Tool / model:** AI coding agent
* **Prompt (summary):**
  > Adapt our URL-shortener smoke test (bash + curl only) to DevBlog: registration, login, refresh rotation and reuse detection, roles, drafts, optimistic locking, search, comments, moderation queue.
* **What it produced:** a 53-check script. Reviewing the helpers it was about to copy turned up a bug
  that SnapLink never hit: `sed -n 's/.*"code":"\([^"]*\)".*/\1/p'` is greedy (`.*` matches as much as it
  can), so on a one-line JSON body it returns the **last** match of a key.
* **What I kept / changed / rejected and why:** in a DevBlog post the last `"id"` is `author.id`, not the
  post's id, so the moderation-queue check would have looked for the wrong comment. The helpers now use
  `grep -o … | head -1` (first occurrence). `shellcheck --severity=error` then flagged `$HELD_ID[,}]` in a
  regex (SC1087, parsed as an array); fixed with `${HELD_ID}`. Also checked that the script is safe to
  re-run: every user, tag and search word carries a unique run id.
* **How I verified it:** the script against the v1.0 images of all three tracks: 53/53 passed, and 55/55
  with `AI_PROVIDER=llm` pointing at an unreachable address and `EXPECT_FALLBACK=true`.
* **Time saved / lost (honest guess):** saved ~1 h; the greedy-regex bug would have cost more than that if it had shipped.
* **What I learned:** copying a helper copies its assumptions. SnapLink's JSON had no nested ids; DevBlog's does.

### 2026-10-02 · Phase 6 · Where should `deploy.yml` live?
* **Tool / model:** AI coding agent
* **Prompt (summary):**
  > Should the deploy workflow already be on `main` (the starter) so students see it early?
* **What it produced:** a version that lived on `main` and skipped tracks without a Dockerfile with a notice.
* **What I kept / changed / rejected and why:** rejected for the course. Students write `deploy.yml`
  themselves in Phase 6, the sibling project (SnapLink) keeps it on `solution` only, and a workflow that
  exists but does nothing on `main` is confusing. The file now arrives with the Dockerfiles at `phase-6-end`.
* **How I verified it:** `actionlint` (with shellcheck) on every workflow; `git ls-tree main .github/workflows`
  lists only `ci.yml`.
* **Time saved / lost (honest guess):** neutral.
* **What I learned:** consistency across sibling projects is a feature for learners, not just for maintainers.

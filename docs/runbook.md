# DevBlog runbook

Operational how-tos for DevBlog v1.x. Keep it short, keep it true: if a step here is wrong, fix it in
the same PR that taught you so.

| | |
|---|---|
| Service | DevBlog REST API (one track deployed: `DEPLOY_TRACK`, default `python`) |
| Images | `ghcr.io/aicancode-org/devblog-{java,python,typescript}:<version>` (`latest` = newest release) |
| Health | `GET /health` → `200 {"status":"UP","checks":{"database":"UP"}}`; `503` when the database is unreachable |
| Dependencies | PostgreSQL (required), LLM endpoint (optional; rules/template fallback) |
| Configuration | environment variables only: see `.env.example`. A deployment MUST set its own `JWT_SECRET` and `ADMIN_PASSWORD` |

## 1. Start and stop
**Laptop (Docker Compose):**
```bash
docker compose --profile python up -d --build --wait     # or --profile java / typescript
scripts/smoke-test.sh http://localhost:8080              # expect "... 0 failed"
docker compose --profile python logs -f app-python       # follow logs
docker compose --profile python down                     # stop (keeps data)
docker compose --profile python down -v                  # stop AND delete the database volume
```
**Without Docker for the app:** `docker compose up -d postgres`, then `cd tracks/<track> && make run`.
Port clash with another PostgreSQL or app? `POSTGRES_PORT=55432 APP_PORT=8081 docker compose …`.

**Render (optional):** Dashboard → service → **Manual Deploy** / **Suspend** / **Resume**. A free
instance sleeps when idle; the first request can take up to a minute.

## 2. Check that it works
1. `curl -s $URL/health`. `database` must be `UP`.
2. `SMOKE_ADMIN_EMAIL=… SMOKE_ADMIN_PASSWORD=… scripts/smoke-test.sh $URL`. The smoke test creates its
   own uniquely named users, a post and comments, and soft-deletes the post again. Without admin
   credentials add `SMOKE_ALLOW_NO_ADMIN=true`: the auth and read-only checks still run. The test users
   stay; they are clearly named `reader-…@smoke.devblog.test` / `author-…@smoke.devblog.test`.
3. From v1.1: `curl -s $URL/metrics | grep _total`.

## 3. Release
1. Make sure `solution`/`main` CI is green (`ci-ok`).
2. `CHANGELOG.md`: move *Unreleased* under `## [x.y.z] - YYYY-MM-DD`; bump the track versions.
3. `git tag -a vX.Y.Z -m "DevBlog vX.Y.Z" && git push origin vX.Y.Z`.
4. Watch **Actions → Deploy**: three `GHCR · <track>` jobs, then `Render (optional)`.

## 4. Roll back
The schema only ever changes in a backward-compatible way (additive migrations; see the Phase 7
migration rule), so the previous image runs fine against the newer schema.

**Render:** call the deploy hook with the previous image:
```bash
IMG="ghcr.io/aicancode-org/devblog-python:1.0.0"
curl -fsS -X POST "$RENDER_DEPLOY_HOOK_URL&imgURL=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$IMG")"
```
Or Dashboard → **Events** → pick the previous deploy → **Rollback**. Then run the smoke test.

**Laptop:** `git switch --detach v1.0.0 && docker compose --profile python up -d --build --wait`.

Never "roll back" by deleting a git tag or a GHCR version. Releases are immutable; fix forward with a new patch version.

## 5. Rotate a secret
| Secret | Where it lives | Rotate | Effect |
|---|---|---|---|
| `JWT_SECRET` | host env vars (Render) | generate `openssl rand -base64 48`, update, redeploy | every access token becomes invalid at once; clients refresh (refresh tokens are not JWTs and keep working) |
| `ADMIN_PASSWORD` | host env vars | change it, redeploy | applied at start-up only when the admin is created; for an existing admin, change the password in the database or create a new admin and demote the old one |
| `DATABASE_URL` password | host env vars | reset the role password at the DB provider, update, redeploy, smoke test | |
| `LLM_API_KEY` | host env vars | revoke in the provider console, create a new one, update, redeploy | until then moderation and summaries use rules/template (`fallback: true`) |
| `RENDER_DEPLOY_HOOK_URL` | GitHub environment `production` | Render → Settings → Deploy Hook → **Regenerate**, then `gh secret set RENDER_DEPLOY_HOOK_URL --env production` | |

**Suspected account takeover of one user:** revoke all their refresh tokens
(`UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = … AND revoked_at IS NULL`). Their access
tokens die within 15 minutes. **Suspected leak of `JWT_SECRET`:** rotate it immediately (anyone with it can
mint ADMIN tokens). If a secret leaked (committed, pasted into a prompt, printed in a log): **rotate
first**, then clean up. Removing it from git history does not un-leak it.

## 6. Common incidents
| Symptom | Likely cause | Action |
|---|---|---|
| `/health` → `503`, `database: DOWN` | Postgres unreachable / wrong `DATABASE_URL` | Check the DB provider status and the connection string (`sslmode=require` on hosted Postgres); app logs show the driver error. |
| All users get `401` after a deploy | `JWT_SECRET` changed or differs between instances | Expected once after a rotation (clients refresh). If it persists, make every instance use the same secret. |
| Users report being logged out everywhere | refresh-token reuse detection fired (log line "refresh token reuse detected") | Usually a client bug that refreshes twice in parallel, or a stolen token. Check the client; if theft is plausible, treat as an incident. |
| Comments are slow to post | LLM slow or down: every comment waits up to `LLM_TIMEOUT_MS` | Set `AI_PROVIDER=offline` and redeploy, or lower `LLM_TIMEOUT_MS`. (v1.1.0 adds a circuit breaker.) |
| Moderation / summaries always `fallback: true` | LLM unreachable, slow or key invalid | Expected without an LLM. Otherwise check `LLM_BASE_URL`, `LLM_MODEL` (model names change) and the key. |
| Many comments `held` | spam wave, or an over-eager model | Review the queue (`GET /api/v1/admin/comments`); nothing is lost while held. |
| Container restarts at boot | migration failed, or `JWT_SECRET` shorter than 32 bytes | Logs show the cause. Fix the migration in a new `V<n>__*.sql`; never edit an applied one. |
| First request after idle takes ~1 min | free-tier cold start | Expected. Upgrade or accept. |

## 7. Data
- Back up: `docker compose exec -T postgres pg_dump -U devblog devblog > backup.sql` (hosted Postgres: use the provider's backups / point-in-time restore).
- Restore: `docker compose exec -T postgres psql -U devblog devblog < backup.sql`.
- Backups contain bcrypt hashes and e-mail addresses: treat them as personal data (encrypt, limit access, delete old ones).
- Privacy: no IPs or user agents are stored (NFR-6). Deleted posts and comments are soft-deleted; a hard
  purge on request is a manual SQL step (documented gap).

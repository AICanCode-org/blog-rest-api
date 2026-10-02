#!/usr/bin/env bash
# DevBlog language-agnostic smoke test. Works against ANY track (Java, Python, TypeScript).
# Usage:  scripts/smoke-test.sh [BASE_URL]        (default http://localhost:8080)
# Needs only bash + curl (Linux, macOS, WSL, Git Bash). Creates its own users/posts/comments with
# unique names, so it is safe to run repeatedly and against a live deployment.
# Env:
#   SMOKE_ADMIN_EMAIL / SMOKE_ADMIN_PASSWORD  the bootstrap admin (default: the local Compose values)
#   SMOKE_ALLOW_NO_ADMIN=true   skip (instead of fail) the admin/author/moderation checks when the
#                               admin cannot log in, e.g. against a live URL without the admin secret
#   EXPECT_FALLBACK=true        the app runs with AI_PROVIDER=llm and an unreachable LLM: expect fallbacks
set -uo pipefail

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
BASE_URL="${BASE_URL%/}"
ADMIN_EMAIL="${SMOKE_ADMIN_EMAIL:-admin@devblog.local}"
ADMIN_PASSWORD="${SMOKE_ADMIN_PASSWORD:-admin-password-123}"
PASS=0
FAIL=0
SKIP=0
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

ok()   { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; [ -s "$TMP" ] && printf '       body: %s\n' "$(head -c 300 "$TMP")"; }
skip() { SKIP=$((SKIP + 1)); printf '  \033[33mSKIP\033[0m %s\n' "$1"; }

# request METHOD PATH [JSON_BODY] [TOKEN] -> sets STATUS, HEADERS; body in $TMP
request() {
  local method="$1" path="$2" body="${3:-}" token="${4:-}"
  local args=(-s -o "$TMP" -D - -X "$method" "$BASE_URL$path")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data "$body")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  HEADERS="$(curl "${args[@]}" | tr -d '\r')"
  STATUS="$(printf '%s\n' "$HEADERS" | awk 'NR==1{print $2}')"
}
header() { printf '%s\n' "$HEADERS" | awk -v h="$(echo "$1" | tr 'A-Z' 'a-z')" -F': ' 'tolower($1)==h{print $2}' | tail -1; }
# First occurrence of a key in the (single-line) JSON body.
json_str()  { grep -o "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$TMP" | head -1 | sed -E 's/.*:[[:space:]]*"([^"]*)"$/\1/'; }
json_num()  { grep -oE "\"$1\"[[:space:]]*:[[:space:]]*[0-9]+" "$TMP" | head -1 | sed -E 's/.*:[[:space:]]*//'; }
json_bool() { grep -oE "\"$1\"[[:space:]]*:[[:space:]]*(true|false)" "$TMP" | head -1 | sed -E 's/.*:[[:space:]]*//'; }
expect() { if [ "$STATUS" = "$1" ]; then ok "$2 ($STATUS)"; else bad "$2 (expected $1, got ${STATUS:-no response})"; fi; }
is_problem() { case "$(header Content-Type)" in application/problem+json*) ok "$1 uses application/problem+json" ;; *) bad "$1 uses application/problem+json" ;; esac; }

RUN="$(date +%s)$RANDOM"
PW="smoke-password-$RUN"
echo "DevBlog smoke test against $BASE_URL (run $RUN)"

# ---- ops ----
request GET /health
expect 200 "GET /health"
grep -q '"database":[[:space:]]*"UP"' "$TMP" && ok "database is UP" || bad "database is UP"

# ---- auth (FR-1..FR-5) ----
READER_EMAIL="reader-$RUN@smoke.devblog.test"
request POST /api/v1/auth/register "{\"email\":\"$READER_EMAIL\",\"password\":\"$PW\",\"displayName\":\"Smoke Reader\",\"role\":\"ADMIN\"}"
expect 201 "POST /auth/register"
[ "$(json_str role)" = "READER" ] && ok "a 'role' field in the request is ignored (role=READER)" || bad "role must be READER (got $(json_str role))"
request POST /api/v1/auth/register "{\"email\":\"$READER_EMAIL\",\"password\":\"$PW\",\"displayName\":\"Again\"}"
expect 409 "duplicate e-mail is rejected"
request POST /api/v1/auth/register "{\"email\":\"short-$RUN@smoke.devblog.test\",\"password\":\"short\",\"displayName\":\"X\"}"
expect 400 "password shorter than 8 characters is rejected"
is_problem "validation error"

request POST /api/v1/auth/login "{\"email\":\"$READER_EMAIL\",\"password\":\"wrong-password\"}"
expect 401 "wrong password is 401"
request POST /api/v1/auth/login "{\"email\":\"$READER_EMAIL\",\"password\":\"$PW\"}"
expect 200 "POST /auth/login"
READER_TOKEN="$(json_str accessToken)"
REFRESH1="$(json_str refreshToken)"
[ -n "$READER_TOKEN" ] && [ -n "$REFRESH1" ] && ok "login returns an access and a refresh token" || bad "login returns tokens"

request GET /api/v1/me "" "$READER_TOKEN"
expect 200 "GET /me with a token"
request GET /api/v1/me
expect 401 "GET /me without a token is 401"
[ -n "$(header WWW-Authenticate)" ] && ok "401 carries WWW-Authenticate" || bad "401 carries WWW-Authenticate"
request GET /api/v1/me "" "not.a.jwt"
expect 401 "a malformed token is 401"

request POST /api/v1/auth/refresh "{\"refreshToken\":\"$REFRESH1\"}"
expect 200 "POST /auth/refresh rotates the refresh token"
REFRESH2="$(json_str refreshToken)"
[ -n "$REFRESH2" ] && [ "$REFRESH2" != "$REFRESH1" ] && ok "a new refresh token was issued" || bad "a new refresh token was issued"
request POST /api/v1/auth/refresh "{\"refreshToken\":\"$REFRESH1\"}"
expect 401 "reusing a rotated refresh token is 401 (reuse detection)"
request POST /api/v1/auth/refresh "{\"refreshToken\":\"$REFRESH2\"}"
expect 401 "reuse revoked the whole token family"

# ---- public reads ----
request GET "/api/v1/posts?limit=5"
expect 200 "GET /posts (feed)"
request GET /api/v1/tags
expect 200 "GET /tags"
request GET "/api/v1/posts/no-such-post-$RUN"
expect 404 "unknown post is 404"
is_problem "404"

# ---- admin, author, posts, comments (needs the bootstrap admin) ----
request POST /api/v1/auth/login "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
if [ "$STATUS" != "200" ] && [ "${SMOKE_ALLOW_NO_ADMIN:-}" = "true" ]; then
  skip "admin login failed ($STATUS): skipping the author, post and moderation checks (set SMOKE_ADMIN_EMAIL/SMOKE_ADMIN_PASSWORD)"
else
  expect 200 "admin login ($ADMIN_EMAIL)"
  ADMIN_TOKEN="$(json_str accessToken)"

  AUTHOR_EMAIL="author-$RUN@smoke.devblog.test"
  request POST /api/v1/auth/register "{\"email\":\"$AUTHOR_EMAIL\",\"password\":\"$PW\",\"displayName\":\"Smoke Author\"}"
  AUTHOR_ID="$(json_num id)"
  request PATCH "/api/v1/admin/users/$AUTHOR_ID/role" '{"role":"AUTHOR"}' "$READER_TOKEN"
  expect 403 "a READER cannot change roles"
  request PATCH "/api/v1/admin/users/$AUTHOR_ID/role" '{"role":"AUTHOR"}' "$ADMIN_TOKEN"
  expect 200 "ADMIN promotes a user to AUTHOR"
  request POST /api/v1/auth/login "{\"email\":\"$AUTHOR_EMAIL\",\"password\":\"$PW\"}"
  AUTHOR_TOKEN="$(json_str accessToken)"

  TAG="smoke-$RUN"
  WORD="zyxsmoke$RUN"
  request POST /api/v1/posts "{\"title\":\"Smoke test $RUN\",\"body\":\"Hello from the smoke test. $WORD appears once.\",\"tags\":[\"$TAG\"]}" "$READER_TOKEN"
  expect 403 "a READER cannot create posts"
  request POST /api/v1/posts "{\"title\":\"Smoke test $RUN\",\"body\":\"Hello from the smoke test. $WORD appears once.\",\"tags\":[\"$TAG\"]}" "$AUTHOR_TOKEN"
  expect 201 "AUTHOR creates a draft"
  SLUG="$(json_str slug)"
  [ "$(json_str status)" = "draft" ] && ok "new posts are drafts" || bad "new posts are drafts"
  [ "$(header Location)" = "/api/v1/posts/$SLUG" ] && ok "Location header" || bad "Location header was '$(header Location)'"
  request GET "/api/v1/posts/$SLUG"
  expect 404 "a draft is 404 for anonymous readers (not 403)"

  request PATCH "/api/v1/posts/$SLUG" '{"version":1,"status":"published"}' "$AUTHOR_TOKEN"
  expect 200 "publish with version 1"
  [ "$(json_num version)" = "2" ] && ok "version is now 2" || bad "version is now 2 (got $(json_num version))"
  request PATCH "/api/v1/posts/$SLUG" '{"version":1,"title":"Stale edit"}' "$AUTHOR_TOKEN"
  expect 409 "a stale version is 409 (optimistic locking)"

  request GET "/api/v1/posts?tag=$TAG"
  expect 200 "feed filtered by tag"
  grep -q "\"slug\":[[:space:]]*\"$SLUG\"" "$TMP" && ok "the published post is in the tag feed" || bad "the published post is in the tag feed"
  request GET "/api/v1/posts?q=$WORD"
  expect 200 "full-text search"
  grep -q "\"slug\":[[:space:]]*\"$SLUG\"" "$TMP" && ok "search finds the post" || bad "search finds the post"

  request GET "/api/v1/posts/$SLUG/summary"
  expect 200 "GET summary (AI TL;DR)"
  PROVIDER="$(json_str provider)"
  if [ "$PROVIDER" = "template" ] || [ "$PROVIDER" = "llm" ]; then ok "summary provider=$PROVIDER fallback=$(json_bool fallback)"; else bad "summary provider"; fi
  if [ -n "${EXPECT_FALLBACK:-}" ]; then
    [ "$(json_bool fallback)" = "$EXPECT_FALLBACK" ] && ok "summary fallback=$EXPECT_FALLBACK as expected" || bad "summary fallback expected $EXPECT_FALLBACK"
  fi

  request POST /api/v1/auth/login "{\"email\":\"$READER_EMAIL\",\"password\":\"$PW\"}"
  READER_TOKEN="$(json_str accessToken)"
  request POST "/api/v1/posts/$SLUG/comments" '{"body":"Great post, thanks for writing it."}' "$READER_TOKEN"
  expect 201 "READER comments"
  [ "$(json_str status)" = "visible" ] && ok "a normal comment is visible" || bad "a normal comment is visible (got $(json_str status))"
  if [ -n "${EXPECT_FALLBACK:-}" ]; then
    [ "$(json_bool fallback)" = "$EXPECT_FALLBACK" ] && ok "moderation fallback=$EXPECT_FALLBACK as expected" || bad "moderation fallback expected $EXPECT_FALLBACK"
  fi
  request POST "/api/v1/posts/$SLUG/comments" '{"body":"Buy now! Free money at http://spam.example and http://spam2.example"}' "$READER_TOKEN"
  expect 201 "a spam comment is accepted for review"
  HELD_ID="$(json_num id)"
  [ "$(json_str status)" = "held" ] && ok "the spam comment is held (never deleted by the AI)" || bad "the spam comment is held (got $(json_str status))"
  request GET "/api/v1/posts/$SLUG/comments"
  expect 200 "GET comments"
  grep -q "Buy now" "$TMP" && bad "held comments are hidden from readers" || ok "held comments are hidden from readers"

  request GET /api/v1/admin/comments "" "$READER_TOKEN"
  expect 403 "a READER cannot see the moderation queue"
  request GET "/api/v1/admin/comments?status=held" "" "$ADMIN_TOKEN"
  expect 200 "ADMIN reads the moderation queue"
  grep -qE "\"id\":[[:space:]]*${HELD_ID}[,}]" "$TMP" && ok "the held comment is in the queue" || bad "the held comment is in the queue"
  request POST "/api/v1/admin/comments/$HELD_ID/decision" '{"decision":"reject"}' "$ADMIN_TOKEN"
  expect 200 "ADMIN rejects the held comment"
  [ "$(json_str status)" = "rejected" ] && ok "status=rejected" || bad "status=rejected"

  request DELETE "/api/v1/posts/$SLUG" "" "$READER_TOKEN"
  expect 403 "a READER cannot delete someone else's post"
  request DELETE "/api/v1/posts/$SLUG" "" "$AUTHOR_TOKEN"
  expect 204 "AUTHOR deletes the post (soft delete)"
  request GET "/api/v1/posts/$SLUG"
  expect 404 "a deleted post is 404"
fi

# ---- v1.1 observability (Phase 7) ----
HEADERS="$(curl -s -o /dev/null -D - -H 'X-Request-ID: smoke-req-1' "$BASE_URL/health" | tr -d '\r')"
[ "$(header X-Request-ID)" = "smoke-req-1" ] && ok "X-Request-ID is echoed (v1.1)" || bad "X-Request-ID is echoed (v1.1)"
HEADERS="$(curl -s -o /dev/null -D - -H 'X-Request-ID: bad id with spaces' "$BASE_URL/health" | tr -d '\r')"
RID="$(header X-Request-ID)"
[ -n "$RID" ] && [ "$RID" != "bad id with spaces" ] && ok "an unsafe X-Request-ID is replaced (v1.1)" || bad "an unsafe X-Request-ID is replaced (v1.1)"
request GET /metrics
expect 200 "GET /metrics (v1.1)"
REGISTERED="$(sed -n 's/^devblog_users_registered_total \([0-9][0-9]*\)$/\1/p' "$TMP")"
[ "${REGISTERED:-0}" -ge 1 ] 2>/dev/null && ok "devblog_users_registered_total=$REGISTERED" || bad "devblog_users_registered_total >= 1"
# ---- end v1.1 ----

echo
echo "Result: $PASS passed, $FAIL failed, $SKIP skipped"
[ "$FAIL" -eq 0 ]

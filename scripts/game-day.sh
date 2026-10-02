#!/usr/bin/env bash
# Game day (Phase 7): the LLM provider hangs while readers comment. Measure the impact on one track.
# Usage:  scripts/game-day.sh [python|typescript|java]      (needs Docker Compose; run from the repo root)
# It starts a tiny fake OpenAI-compatible LLM container ("llm-fake") on the Compose network that either
# answers instantly (MODE=ok) or accepts the connection and never answers (MODE=hang), and points the
# app at it with AI_PROVIDER=llm. Phases: offline baseline -> healthy LLM -> LLM hangs -> LLM back.
# Env:    N=10 (comments per phase)  N_PAR=50 (concurrent comments while probing /health)
#         LLM_TIMEOUT_MS=5000  COOLDOWN_WAIT=31 (seconds; the v1.1 circuit breaker cools down after 30 s)
#         BASE_URL=http://localhost:8080  DC="docker compose"  DOCKER=docker
#         SMOKE_ADMIN_EMAIL / SMOKE_ADMIN_PASSWORD (default: the local Compose admin)
# With sudo, keep the environment: DC="sudo -E docker compose" DOCKER="sudo docker".
set -u
TRACK=${1:-python}
N=${N:-10}
N_PAR=${N_PAR:-50}
B=${BASE_URL:-http://localhost:8080}
export LLM_TIMEOUT_MS=${LLM_TIMEOUT_MS:-5000}
read -r -a DC <<< "${DC:-docker compose}"
read -r -a DOCKER <<< "${DOCKER:-docker}"
ADMIN_EMAIL="${SMOKE_ADMIN_EMAIL:-admin@devblog.local}"
ADMIN_PASSWORD="${SMOKE_ADMIN_PASSWORD:-admin-password-123}"
FAKE=devblog-llm-fake
ts() { date -u '+%H:%M:%SZ'; }
post() { curl -s -X POST "$B$1" -H 'Content-Type: application/json' ${3:+-H "Authorization: Bearer $3"} -d "$2"; }
field() { grep -o "\"$1\":[[:space:]]*\"[^\"]*\"" | head -1 | sed -E 's/.*"([^"]*)"$/\1/'; }

# A fake LLM in 25 lines of Python: MODE=ok answers like /v1/chat/completions, MODE=hang never answers.
FAKE_LLM='
import json, os, socket, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
class H(BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if os.environ["MODE"] == "hang":
            time.sleep(3600)
        content = json.dumps({"label": "ok", "confidence": 0.95, "reason": "fake llm says ok"})
        body = json.dumps({"choices": [{"message": {"role": "assistant", "content": content}}]}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self, *args):
        pass
ThreadingHTTPServer(("0.0.0.0", 8000), H).serve_forever()
'

app_up() { # $1 = offline | llm
  if [ "$1" = llm ]; then
    AI_PROVIDER=llm LLM_BASE_URL=http://$FAKE:8000/v1 LLM_API_KEY='' "${DC[@]}" --profile "$TRACK" up -d --wait "app-$TRACK" >/dev/null 2>&1
  else
    AI_PROVIDER=offline "${DC[@]}" --profile "$TRACK" up -d --wait "app-$TRACK" >/dev/null 2>&1
  fi || { echo "could not start app-$TRACK"; exit 1; }
}
fake_llm() { # $1 = ok | hang | off
  "${DOCKER[@]}" rm -f "$FAKE" >/dev/null 2>&1
  [ "$1" = off ] && return 0
  "${DOCKER[@]}" run -d --name "$FAKE" --network "$NETWORK" -e MODE="$1" python:3.12-slim python -c "$FAKE_LLM" >/dev/null
  sleep 2
}
burst() { # $1 = label, $2 = number of comments
  local total=0 max=0 r code t statuses="" providers="" ms
  for i in $(seq "$2"); do
    r=$(curl -s -o /tmp/gd-comment.json -w '%{http_code} %{time_total}' -X POST "$B/api/v1/posts/$SLUG/comments" \
      -H 'Content-Type: application/json' -H "Authorization: Bearer $READER" -d "{\"body\":\"Nice write-up, part $i.\"}")
    code=${r% *}; t=${r#* }
    statuses="$statuses $code"
    providers="$providers $(field provider < /tmp/gd-comment.json)/fallback=$(grep -oE '"fallback":(true|false)' /tmp/gd-comment.json | cut -d: -f2)"
    ms=$(awk -v t="$t" 'BEGIN { printf "%d", t * 1000 }'); [ "$ms" -gt "$max" ] && max=$ms
    total=$(awk -v a="$total" -v b="$t" 'BEGIN { print a + b }')
  done
  echo "$(ts) $1: $2 comments, statuses:$(echo "$statuses" | tr ' ' '\n' | sed '/^$/d' | sort | uniq -c | tr -s ' \n' ' ')avg $(awk -v a="$total" -v n="$2" 'BEGIN { printf "%.0f", a / n * 1000 }') ms, max $max ms"
  echo "  moderation:$(echo "$providers" | tr ' ' '\n' | sed '/^$/d' | sort | uniq -c | tr -s ' \n' ' ')"
}
health_under_load() { # N_PAR concurrent comments, then time /health while they are in flight
  local pids=()
  for i in $(seq "$N_PAR"); do
    curl -s -o /dev/null -X POST "$B/api/v1/posts/$SLUG/comments" -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $READER" -d "{\"body\":\"Concurrent comment $i.\"}" & pids+=($!)
  done
  sleep 1
  echo "  /health while $N_PAR comments are in flight: $(curl -s -o /dev/null -w '%{http_code} in %{time_total}s' --max-time 60 "$B/health")"
  wait "${pids[@]}"
}
report() {
  local m; m=$(curl -s "$B/metrics" | grep -E '^devblog_(comments_created|comments_held|ai_fallback|llm_short_circuits)_total' | tr '\n' ' ')
  echo "  metrics: ${m:-(no /metrics before v1.1)}"
}

echo "$(ts) game day: LLM provider hangs, track=$TRACK, LLM_TIMEOUT_MS=$LLM_TIMEOUT_MS"
app_up offline
NETWORK=$("${DOCKER[@]}" inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$("${DC[@]}" ps -q "app-$TRACK")" | awk '{print $1}')
RUN="$(date +%s)$RANDOM"; PW="game-day-password-$RUN"
ADMIN=$(post /api/v1/auth/login "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | field accessToken)
[ -n "$ADMIN" ] || { echo "admin login failed (set SMOKE_ADMIN_EMAIL / SMOKE_ADMIN_PASSWORD)"; exit 1; }
AUTHOR_ID=$(post /api/v1/auth/register "{\"email\":\"gd-author-$RUN@devblog.test\",\"password\":\"$PW\",\"displayName\":\"GD Author\"}" | grep -oE '"id":[0-9]+' | head -1 | cut -d: -f2)
curl -s -o /dev/null -X PATCH "$B/api/v1/admin/users/$AUTHOR_ID/role" -H 'Content-Type: application/json' -H "Authorization: Bearer $ADMIN" -d '{"role":"AUTHOR"}'
AUTHOR=$(post /api/v1/auth/login "{\"email\":\"gd-author-$RUN@devblog.test\",\"password\":\"$PW\"}" | field accessToken)
SLUG=$(post /api/v1/posts "{\"title\":\"Game day $RUN\",\"body\":\"What happens when the model hangs?\",\"status\":\"published\"}" "$AUTHOR" | field slug)
post /api/v1/auth/register "{\"email\":\"gd-reader-$RUN@devblog.test\",\"password\":\"$PW\",\"displayName\":\"GD Reader\"}" >/dev/null
READER=$(post /api/v1/auth/login "{\"email\":\"gd-reader-$RUN@devblog.test\",\"password\":\"$PW\"}" | field accessToken)
echo "$(ts) post /$SLUG, network $NETWORK"

burst "baseline (AI_PROVIDER=offline)" "$N"; report
fake_llm ok; app_up llm
echo "$(ts) app restarted with AI_PROVIDER=llm -> $FAKE (MODE=ok)"
burst "healthy LLM" "$N"; report
echo "$(ts) FAULT: the LLM accepts connections but never answers (MODE=hang)"; fake_llm hang
burst "LLM hangs" "$N"; report
health_under_load; report
echo "  recent AI warnings:"
"${DC[@]}" logs --no-color --since 5m "app-$TRACK" 2>/dev/null | grep -iE 'llm|circuit|fallback' | tail -3 | cut -c1-240
echo "$(ts) RECOVERY: the LLM answers again (MODE=ok)"; fake_llm ok
burst "LLM back, immediately" 3; report
echo "$(ts) waiting ${COOLDOWN_WAIT:-31} s"; sleep "${COOLDOWN_WAIT:-31}"
burst "LLM back, after ${COOLDOWN_WAIT:-31} s" 3; report
curl -s -o /dev/null -X DELETE "$B/api/v1/posts/$SLUG" -H "Authorization: Bearer $AUTHOR"
fake_llm off; app_up offline
echo "$(ts) done (app back on AI_PROVIDER=offline). Write the numbers into docs/postmortem-<date>-llm-hang.md"

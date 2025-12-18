#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

mkdir -p .cpcache/tg

TOKEN_FILE="${TOKEN_FILE:-$ROOT/.secrets/telegram_bot_token}"
SECRET_FILE="${SECRET_FILE:-$ROOT/.secrets/telegram_webhook_secret}"
BASE_URL_FILE="${BASE_URL_FILE:-$ROOT/.secrets/telegram_webhook_base_url}"
WATCH_PID_FILE="$ROOT/.cpcache/tg/webhook-watch.pid"
WATCH_LOG_FILE="$ROOT/.cpcache/tg/webhook-watch.log"

if [[ ! -f "$TOKEN_FILE" ]]; then
  echo "Missing Telegram bot token file: $TOKEN_FILE"
  echo "Create it (in your editor) with the bot token as the only contents, then re-run:"
  echo "  $ROOT/scripts/tg-spinup.sh"
  exit 2
fi

BOT_TOKEN="$(tr -d '\r\n' <"$TOKEN_FILE")"
if [[ -z "$BOT_TOKEN" ]]; then
  echo "Token file is empty: $TOKEN_FILE"
  exit 2
fi

umask 077
mkdir -p "$(dirname "$SECRET_FILE")"
if [[ ! -f "$SECRET_FILE" ]]; then
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 16 >"$SECRET_FILE"
  else
    python3 - <<'PY' >"$SECRET_FILE"
import secrets
print(secrets.token_hex(16))
PY
  fi
fi
WEBHOOK_SECRET="$(tr -d '\r\n' <"$SECRET_FILE")"
if [[ -z "$WEBHOOK_SECRET" ]]; then
  echo "Webhook secret file is empty: $SECRET_FILE"
  exit 2
fi

export TELEGRAM_BOT_TOKEN="$BOT_TOKEN"
export TELEGRAM_WEBHOOK_SECRET="$WEBHOOK_SECRET"
export TELEGRAM_WEBHOOK_ENABLED=true
export TELEGRAM_COMMANDS_ENABLED=true
export TELEGRAM_NOTIFICATIONS_ENABLED=true

echo "Starting backend (if not already running)..."
FORCE_RESTART_BACKEND="${FORCE_RESTART_BACKEND:-false}"
if curl -sS "http://localhost:3000/health" >/dev/null 2>&1 && [[ "$FORCE_RESTART_BACKEND" != "true" ]]; then
  echo "Backend already responding on http://localhost:3000"
else
  if [[ -f .cpcache/tg/backend.pid ]]; then
    OLD_PID="$(cat .cpcache/tg/backend.pid || true)"
    if [[ -n "${OLD_PID:-}" ]] && kill -0 "$OLD_PID" >/dev/null 2>&1; then
      echo "Stopping existing backend pid=$OLD_PID"
      kill "$OLD_PID" >/dev/null 2>&1 || true
      sleep 0.5
    fi
  fi
  nohup clojure -M:dev > .cpcache/tg/backend.log 2>&1 &
  echo $! > .cpcache/tg/backend.pid
  for _ in {1..60}; do
    if curl -sS "http://localhost:3000/health" >/dev/null 2>&1; then
      echo "Backend is up."
      break
    fi
    sleep 0.5
  done
fi

echo "Starting tunnel via localhost.run (SSH reverse tunnel)..."
if [[ -f .cpcache/tg/ssh_tunnel.pid ]]; then
  OLD_TUN_PID="$(cat .cpcache/tg/ssh_tunnel.pid || true)"
  if [[ -n "${OLD_TUN_PID:-}" ]] && kill -0 "$OLD_TUN_PID" >/dev/null 2>&1; then
    echo "Stopping existing ssh tunnel pid=$OLD_TUN_PID"
    kill "$OLD_TUN_PID" >/dev/null 2>&1 || true
    sleep 0.5
  fi
fi

rm -f .cpcache/tg/tunnel.log
nohup ssh -tt -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=60 -o ServerAliveCountMax=3 \
  -R 80:localhost:3000 localhost.run > .cpcache/tg/tunnel.log 2>&1 &
echo $! > .cpcache/tg/ssh_tunnel.pid

TUNNEL_URL=""
for _ in {1..160}; do
  if rg -n "https://[a-z0-9]+\\.lhr\\.life" .cpcache/tg/tunnel.log >/dev/null 2>&1; then
    TUNNEL_URL="$(rg -o "https://[a-z0-9]+\\.lhr\\.life" .cpcache/tg/tunnel.log | tail -n 1)"
    break
  fi
  sleep 0.5
done

if [[ -z "$TUNNEL_URL" ]]; then
  echo "Failed to get tunnel URL. Check logs:"
  echo "  $ROOT/.cpcache/tg/tunnel.log"
  exit 1
fi

WEBHOOK_URL="${TUNNEL_URL}/api/telegram/webhook"
umask 077
mkdir -p "$(dirname "$BASE_URL_FILE")"
echo "$TUNNEL_URL" > "$BASE_URL_FILE"

echo "Registering webhook with Telegram..."
RESP="$(curl -sS -X POST "https://api.telegram.org/bot${BOT_TOKEN}/setWebhook" \
  --data-urlencode "url=${WEBHOOK_URL}" \
  --data-urlencode "secret_token=${WEBHOOK_SECRET}" \
  --data-urlencode "drop_pending_updates=true" \
  --data-urlencode 'allowed_updates=["message","callback_query"]')"

OK="$(RESP="$RESP" python3 - <<'PY'
import json, os
payload = json.loads(os.environ["RESP"])
print("true" if payload.get("ok") is True else "false")
PY
)"
if [[ "$OK" != "true" ]]; then
  echo "Failed to set Telegram webhook:"
  echo "$RESP"
  exit 1
fi

echo "Webhook set to: $WEBHOOK_URL"

echo "Starting webhook watcher (keeps webhook updated if tunnel reconnects)..."
if [[ -f "$WATCH_PID_FILE" ]]; then
  OLD_WATCH_PID="$(cat "$WATCH_PID_FILE" || true)"
  if [[ -n "${OLD_WATCH_PID:-}" ]] && kill -0 "$OLD_WATCH_PID" >/dev/null 2>&1; then
    kill "$OLD_WATCH_PID" >/dev/null 2>&1 || true
    sleep 0.2
  fi
fi
nohup "$ROOT/scripts/tg-watch-webhook.sh" > "$WATCH_LOG_FILE" 2>&1 &
echo $! > "$WATCH_PID_FILE"

echo "Generating link token for user 'damjan'..."
COOKIE_JAR="$ROOT/.cpcache/tg/cookies.txt"
rm -f "$COOKIE_JAR"

curl -sS -c "$COOKIE_JAR" -X POST "http://localhost:3000/api/login" \
  -H "content-type: application/json" \
  -d '{"user/username":"damjan","user/password":"Damjan1!"}' >/dev/null

LINK_TOKEN_JSON="$(curl -sS -b "$COOKIE_JAR" -X POST "http://localhost:3000/api/telegram/link-token" \
  -H "content-type: application/json" \
  -d '{}')"

LINK_TOKEN="$(LINK_TOKEN_JSON="$LINK_TOKEN_JSON" python3 - <<'PY'
import json, os, sys
payload = json.loads(os.environ["LINK_TOKEN_JSON"])
token = payload.get("token")
if not token:
    print("")
    sys.exit(0)
print(token)
PY
)"
if [[ -z "$LINK_TOKEN" ]]; then
  echo "Failed to generate link token. Check:"
  echo "  $ROOT/.cpcache/tg/backend.log"
  exit 1
fi

RECOGNIZE_FILE="${RECOGNIZE_FILE:-$ROOT/.secrets/telegram_user_id}"
if [[ -f "$RECOGNIZE_FILE" ]]; then
  TELEGRAM_USER_ID="$(tr -d '\r\n' <"$RECOGNIZE_FILE")"
  if [[ -n "$TELEGRAM_USER_ID" ]]; then
    curl -sS -b "$COOKIE_JAR" -X POST "http://localhost:3000/api/telegram/recognize" \
      -H "content-type: application/json" \
      -d "{\"telegram/user-id\":${TELEGRAM_USER_ID}}" >/dev/null || true
  fi
fi

echo ""
echo "Ready. In Telegram:"
echo "  1) Send /start ${LINK_TOKEN}"
echo "  2) Send /new Test from Telegram | hello"
echo "  3) Send /tasks"
echo ""
echo "Logs:"
echo "  backend: $ROOT/.cpcache/tg/backend.log"
echo "  tunnel:  $ROOT/.cpcache/tg/tunnel.log"
echo "  watch:   $WATCH_LOG_FILE"

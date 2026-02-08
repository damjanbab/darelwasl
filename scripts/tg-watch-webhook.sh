#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

mkdir -p .cpcache/tg

PROFILE="${TELEGRAM_PROFILE:-dev}"
PROFILE="$(echo "$PROFILE" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
if [[ "$PROFILE" != "dev" && "$PROFILE" != "prod" ]]; then
  echo "Invalid TELEGRAM_PROFILE: $PROFILE (expected dev|prod)" >&2
  exit 2
fi

if [[ "$PROFILE" == "dev" ]]; then
  TOKEN_FILE="${TOKEN_FILE:-$ROOT/.secrets/telegram_dev_bot_token}"
  SECRET_FILE="${SECRET_FILE:-$ROOT/.secrets/telegram_dev_webhook_secret}"
  BASE_URL_FILE="${BASE_URL_FILE:-$ROOT/.secrets/telegram_dev_webhook_base_url}"
else
  TOKEN_FILE="${TOKEN_FILE:-$ROOT/.secrets/telegram_prod_bot_token}"
  SECRET_FILE="${SECRET_FILE:-$ROOT/.secrets/telegram_prod_webhook_secret}"
  BASE_URL_FILE="${BASE_URL_FILE:-$ROOT/.secrets/telegram_prod_webhook_base_url}"
fi

# Back-compat fallbacks (older file names).
if [[ ! -f "$TOKEN_FILE" && -f "$ROOT/.secrets/telegram_bot_token" ]]; then
  TOKEN_FILE="$ROOT/.secrets/telegram_bot_token"
fi
if [[ ! -f "$SECRET_FILE" && -f "$ROOT/.secrets/telegram_webhook_secret" ]]; then
  SECRET_FILE="$ROOT/.secrets/telegram_webhook_secret"
fi
if [[ ! -f "$BASE_URL_FILE" && -f "$ROOT/.secrets/telegram_webhook_base_url" ]]; then
  BASE_URL_FILE="$ROOT/.secrets/telegram_webhook_base_url"
fi
TUNNEL_LOG="${TUNNEL_LOG:-$ROOT/.cpcache/tg/tunnel.log}"
TUNNEL_PID_FILE="${TUNNEL_PID_FILE:-$ROOT/.cpcache/tg/ssh_tunnel.pid}"

if [[ ! -f "$TOKEN_FILE" ]]; then
  echo "Missing Telegram bot token file: $TOKEN_FILE" >&2
  exit 2
fi
if [[ ! -f "$SECRET_FILE" ]]; then
  echo "Missing Telegram webhook secret file: $SECRET_FILE" >&2
  exit 2
fi

BOT_TOKEN="$(tr -d '\r\n' <"$TOKEN_FILE")"
WEBHOOK_SECRET="$(tr -d '\r\n' <"$SECRET_FILE")"

if [[ -z "$BOT_TOKEN" || -z "$WEBHOOK_SECRET" ]]; then
  echo "Missing BOT_TOKEN or WEBHOOK_SECRET contents." >&2
  exit 2
fi

expected="${TELEGRAM_EXPECTED_BOT_USERNAME:-mimi}"
expected="$(echo "$expected" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
if [[ "${SKIP_TELEGRAM_BOT_IDENTITY_CHECK:-}" != "1" && "$PROFILE" == "dev" ]]; then
  echo "Verifying bot identity via getMe..."
  ME_JSON="$(curl -sS "https://api.telegram.org/bot${BOT_TOKEN}/getMe" || true)"
  USERNAME="$(ME_JSON="$ME_JSON" python3 - <<'PY'
import json, os
raw = os.environ.get("ME_JSON","")
try:
  data = json.loads(raw)
  u = (data.get("result") or {}).get("username") or ""
  print(str(u))
except Exception:
  print("")
PY
)"
  uname="$(echo "$USERNAME" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
  if [[ -z "$uname" ]]; then
    echo "Unable to read bot username from getMe response. Set SKIP_TELEGRAM_BOT_IDENTITY_CHECK=1 to bypass." >&2
    exit 2
  fi
  if [[ "$uname" != "$expected" ]]; then
    echo "Refusing to run: dev bot username '$uname' != expected '$expected'." >&2
    echo "Set TELEGRAM_EXPECTED_BOT_USERNAME to the dev bot username, or SKIP_TELEGRAM_BOT_IDENTITY_CHECK=1 to bypass." >&2
    exit 2
  fi
fi

extract-latest-domain() {
  if [[ ! -f "$TUNNEL_LOG" ]]; then
    echo ""
    return 0
  fi
  grep -Eo "https://[a-z0-9]+\\.lhr\\.life" "$TUNNEL_LOG" | tail -n 1 || true
}

ensure-tunnel-running() {
  local pid=""
  if [[ -f "$TUNNEL_PID_FILE" ]]; then
    pid="$(cat "$TUNNEL_PID_FILE" || true)"
  fi

  if [[ -n "${pid:-}" ]] && kill -0 "$pid" >/dev/null 2>&1; then
    return 0
  fi

  echo "Tunnel not running; restarting localhost.run ssh tunnel..."
  nohup ssh -tt -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ExitOnForwardFailure=yes \
    -o ServerAliveInterval=60 -o ServerAliveCountMax=3 \
    -R 80:localhost:3000 localhost.run > "$TUNNEL_LOG" 2>&1 &
  echo $! > "$TUNNEL_PID_FILE"
}

set-webhook() {
  local base_url="$1"
  local webhook_url="${base_url}/api/telegram/webhook"

  local resp
  resp="$(curl -sS -X POST "https://api.telegram.org/bot${BOT_TOKEN}/setWebhook" \
    --data-urlencode "url=${webhook_url}" \
    --data-urlencode "secret_token=${WEBHOOK_SECRET}" \
    --data-urlencode "drop_pending_updates=true")"

  OK="$(RESP="$resp" python3 - <<'PY'
import json, os
raw = os.environ.get("RESP","")
try:
  data = json.loads(raw)
  print("true" if data.get("ok") is True else "false")
except Exception:
  print("false")
PY
)"
  if [[ "$OK" != "true" ]]; then
    echo "Failed to set webhook: $resp"
    return 1
  fi

  umask 077
  mkdir -p "$(dirname "$BASE_URL_FILE")"
  echo "$base_url" > "$BASE_URL_FILE"

  echo "Webhook updated -> ${webhook_url}"
}

echo "tg-watch-webhook running (pid $$)"
last=""

while true; do
  ensure-tunnel-running

  current="$(extract-latest-domain)"
  if [[ -n "$current" ]] && [[ "$current" != "$last" ]]; then
    if set-webhook "$current"; then
      last="$current"
    fi
  fi

  sleep 2
done

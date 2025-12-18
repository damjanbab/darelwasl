#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

mkdir -p .cpcache/tg

TOKEN_FILE="${TOKEN_FILE:-$ROOT/.secrets/telegram_bot_token}"
SECRET_FILE="${SECRET_FILE:-$ROOT/.secrets/telegram_webhook_secret}"
BASE_URL_FILE="${BASE_URL_FILE:-$ROOT/.secrets/telegram_webhook_base_url}"
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

extract-latest-domain() {
  if [[ ! -f "$TUNNEL_LOG" ]]; then
    echo ""
    return 0
  fi
  rg -o "https://[a-z0-9]+\\.lhr\\.life" "$TUNNEL_LOG" | tail -n 1 || true
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

  if command -v jq >/dev/null 2>&1; then
    local ok
    ok="$(echo "$resp" | jq -r '.ok')"
    if [[ "$ok" != "true" ]]; then
      echo "Failed to set webhook: $(echo "$resp" | jq -r '.description // .')"
      return 1
    fi
  else
    if ! echo "$resp" | rg -q "\"ok\"\\s*:\\s*true"; then
      echo "Failed to set webhook (no jq to parse response): $resp"
      return 1
    fi
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


#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

mkdir -p .cpcache/tg

TOKEN_FILE="${TOKEN_FILE:-$ROOT/.secrets/telegram_bot_token}"
SECRET_FILE="${SECRET_FILE:-$ROOT/.secrets/telegram_webhook_secret}"

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
if curl -sS "http://localhost:3000/health" >/dev/null 2>&1; then
  echo "Backend already responding on http://localhost:3000"
else
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

echo "Starting tunnel via localtunnel..."
nohup npx --yes localtunnel --port 3000 > .cpcache/tg/tunnel.log 2>&1 &
echo $! > .cpcache/tg/tunnel.pid

TUNNEL_URL=""
for _ in {1..80}; do
  if grep -Eo 'https://[^ ]+\.loca\.lt' .cpcache/tg/tunnel.log >/dev/null 2>&1; then
    TUNNEL_URL="$(grep -Eo 'https://[^ ]+\.loca\.lt' .cpcache/tg/tunnel.log | tail -n 1)"
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

echo "Registering webhook with Telegram..."
curl -sS -X POST "https://api.telegram.org/bot${BOT_TOKEN}/setWebhook" \
  --data-urlencode "url=${WEBHOOK_URL}" \
  --data-urlencode "secret_token=${WEBHOOK_SECRET}" \
  --data-urlencode "drop_pending_updates=true" >/dev/null

echo "Webhook set to: $WEBHOOK_URL"

echo "Generating link token for user 'damjan'..."
LINK_TOKEN="$(clojure -M:tg-dev link-token damjan | awk -F': ' '/^token:/{print $2}' | tr -d '\r\n')"
if [[ -z "$LINK_TOKEN" ]]; then
  echo "Failed to generate link token. Check:"
  echo "  $ROOT/.cpcache/tg/backend.log"
  exit 1
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


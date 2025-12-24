#!/usr/bin/env bash
set -euo pipefail

SESSION_ID="${TERMINAL_SESSION_ID:-}"
API_URL="${TERMINAL_API_URL:-http://127.0.0.1:4010}"

if [[ -z "$SESSION_ID" ]]; then
  echo "TERMINAL_SESSION_ID is not set" >&2
  exit 1
fi

curl -sS -X POST "${API_URL}/sessions/${SESSION_ID}/verify"

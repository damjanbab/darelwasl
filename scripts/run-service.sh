#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

cd "$ROOT"
if [[ -n "${TERMINAL_SESSION_ID:-}" && ! -f "public/js/main.js" ]]; then
  echo "Session build: installing JS dependencies and building frontend..."
  npm install --no-progress --no-audit
  npm run build
fi
exec clojure -M:dev

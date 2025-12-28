#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

cd "$ROOT"
if [[ -n "${TERMINAL_SESSION_ID:-}" && ! -f "public/js/main.js" ]]; then
  echo "Session build: installing JS dependencies and building frontend..."
  npm install --no-progress --no-audit
  npm run build
fi
APP_PORT="${APP_PORT:-3000}"
if command -v python3 >/dev/null 2>&1; then
  if python3 - <<'PY'
import os
import socket

port = int(os.environ.get("APP_PORT", "3000"))
sock = socket.socket()
sock.settimeout(0.4)
try:
    sock.connect(("127.0.0.1", port))
    raise SystemExit(0)
except Exception:
    raise SystemExit(1)
finally:
    sock.close()
PY
  then
    echo "APP_PORT ${APP_PORT} is already in use. Refusing to start a second app."
    exit 1
  fi
fi

DATOMIC_STORAGE_DIR="${DATOMIC_STORAGE_DIR:-data/datomic}"
DATOMIC_SYSTEM="${DATOMIC_SYSTEM:-darelwasl}"
DATOMIC_DB_NAME="${DATOMIC_DB_NAME:-darelwasl}"
if [[ "${DATOMIC_STORAGE_DIR}" != ":mem" && -n "${DATOMIC_STORAGE_DIR}" ]]; then
  LOCK_PATH="${DATOMIC_STORAGE_DIR}/${DATOMIC_SYSTEM}/${DATOMIC_DB_NAME}/.lock"
  if [[ -f "${LOCK_PATH}" ]]; then
    echo "Datomic lock detected at ${LOCK_PATH}. Another process is using this store."
    exit 1
  fi
fi
exec clojure -M:dev

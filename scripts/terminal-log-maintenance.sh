#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS_DIR="${TERMINAL_LOG_DIR:-$ROOT/data/terminal/logs}"
MAX_BYTES="${TERMINAL_TX_LOG_MAX_BYTES:-$((256 * 1024 * 1024))}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

if [[ ! -d "$LOGS_DIR" ]]; then
  exit 0
fi

for log_file in "$LOGS_DIR"/*/data-tx-log.edn; do
  [[ -f "$log_file" ]] || continue
  size="$(stat -c %s "$log_file" 2>/dev/null || echo 0)"
  if [[ "$size" -ge "$MAX_BYTES" ]]; then
    gzip -c "$log_file" > "${log_file}.${STAMP}.gz"
    : > "$log_file"
  fi
done

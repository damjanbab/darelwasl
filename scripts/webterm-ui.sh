#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

SRC="$ROOT/ops/webterm-ui/server.py"
DST="${DW_WEBTERM_UI_DST:-/usr/local/lib/dw-webterm-ui/server.py}"
SERVICE="${DW_WEBTERM_UI_SERVICE:-darelwasl-webterm-ui}"
LISTEN="${DW_WEBTERM_UI_LISTEN:-http://127.0.0.1:7682}"
LAB_STABLE_N="${DW_LAB_SESSION_STABLE:-${DW_LAB_SESSION:-7}}"
LAB_CANARY_N="${DW_LAB_SESSION_CANARY:-$((LAB_STABLE_N + 1))}"

usage() {
  cat <<EOF
Usage: scripts/webterm-ui.sh <cmd>

Commands:
  diff        Show diff between repo source and installed server.py
  install     Install repo source to ${DST}
  restart     Restart systemd service (${SERVICE})
  smoke       Curl a couple endpoints on ${LISTEN}

Env overrides:
  DW_WEBTERM_UI_DST=/path/to/server.py
  DW_WEBTERM_UI_SERVICE=service-name
  DW_WEBTERM_UI_LISTEN=http://host:port
EOF
}

require_src() {
  if [ ! -s "$SRC" ]; then
    echo "Missing source: $SRC" >&2
    exit 2
  fi
}

cmd="${1:-}"
case "$cmd" in
  diff)
    require_src
    if [ ! -f "$DST" ]; then
      echo "Installed file not found: $DST" >&2
      exit 1
    fi
    diff -u "$DST" "$SRC" || true
    ;;
  install)
    require_src
    mkdir -p "$(dirname "$DST")"
    install -m 0755 "$SRC" "$DST"
    echo "installed: $DST"
    ;;
  restart)
    systemctl restart "$SERVICE"
    systemctl status "$SERVICE" --no-pager -l || true
    ;;
  smoke)
    curl -fsS "$LISTEN/api/sessions" >/dev/null && echo "sessions ok"
    curl -fsS "$LISTEN/lab?session=$LAB_STABLE_N" >/dev/null && echo "lab stable ok"
    curl -fsS "$LISTEN/api/lab/outbox?session=$LAB_STABLE_N" >/dev/null && echo "outbox stable ok"
    curl -fsS "$LISTEN/api/lab/history?lines=200&session=$LAB_STABLE_N" >/dev/null && echo "history stable ok"
    curl -fsS "$LISTEN/lab?session=$LAB_CANARY_N" >/dev/null && echo "lab canary ok"
    curl -fsS "$LISTEN/api/lab/outbox?session=$LAB_CANARY_N" >/dev/null && echo "outbox canary ok"
    curl -fsS "$LISTEN/api/lab/history?lines=200&session=$LAB_CANARY_N" >/dev/null && echo "history canary ok"
    ;;
  ""|-h|--help|help)
    usage
    ;;
  *)
    echo "unknown cmd: $cmd" >&2
    usage >&2
    exit 2
    ;;
esac

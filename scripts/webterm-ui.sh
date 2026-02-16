#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

SRC="$ROOT/ops/webterm-ui/server.py"
TARGET="${DW_WEBTERM_UI_TARGET:-stable}" # stable|canary

STABLE_DST_DEFAULT="/usr/local/lib/dw-webterm-ui/server.py"
CANARY_DST_DEFAULT="/usr/local/lib/dw-webterm-ui-canary/server.py"
STABLE_SERVICE_DEFAULT="darelwasl-webterm-ui"
CANARY_SERVICE_DEFAULT="darelwasl-webterm-ui-canary"
STABLE_LISTEN_DEFAULT="http://127.0.0.1:7682"
CANARY_LISTEN_DEFAULT="http://127.0.0.1:7684"

if [[ "${1:-}" == "--target" ]]; then
  TARGET="${2:-}"
  shift 2 || true
fi
cmd="${1:-}"

dst_default="$STABLE_DST_DEFAULT"
service_default="$STABLE_SERVICE_DEFAULT"
listen_default="$STABLE_LISTEN_DEFAULT"
if [[ "$TARGET" == "canary" ]]; then
  dst_default="$CANARY_DST_DEFAULT"
  service_default="$CANARY_SERVICE_DEFAULT"
  listen_default="$CANARY_LISTEN_DEFAULT"
fi

DST="${DW_WEBTERM_UI_DST:-$dst_default}"
SERVICE="${DW_WEBTERM_UI_SERVICE:-$service_default}"
LISTEN="${DW_WEBTERM_UI_LISTEN:-$listen_default}"
LAB_STABLE_N="${DW_LAB_SESSION_STABLE:-${DW_LAB_SESSION:-7}}"
LAB_CANARY_N="${DW_LAB_SESSION_CANARY:-$((LAB_STABLE_N + 1))}"
PUBLIC_ORIGIN="${DW_WEBTERM_PUBLIC_ORIGIN:-https://code.haloeddepth.com}"

usage() {
  cat <<EOF
Usage: scripts/webterm-ui.sh [--target stable|canary] <cmd>

Commands:
  diff        Show diff between repo source and installed server.py
  install     Install repo source to ${DST}
  restart     Restart systemd service (${SERVICE})
  smoke       Curl a couple endpoints (stable + canary)
  deploy-canary  Install+restart canary; print proctor link

Env overrides:
  DW_WEBTERM_UI_TARGET=stable|canary
  DW_WEBTERM_UI_DST=/path/to/server.py
  DW_WEBTERM_UI_SERVICE=service-name
  DW_WEBTERM_UI_LISTEN=http://host:port
  DW_WEBTERM_PUBLIC_ORIGIN=https://code.haloeddepth.com
EOF
}

require_src() {
  if [ ! -s "$SRC" ]; then
    echo "Missing source: $SRC" >&2
    exit 2
  fi
}

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
    smoke_one() {
      local listen="$1" label="$2"
      curl -fsS "$listen/api/sessions" >/dev/null && echo "sessions ok (${label})"
      curl -fsS "$listen/lab?session=$LAB_STABLE_N" >/dev/null && echo "lab stable ok (${label})"
      curl -fsS "$listen/api/lab/outbox?session=$LAB_STABLE_N" >/dev/null && echo "outbox stable ok (${label})"
      curl -fsS "$listen/api/lab/history?lines=200&session=$LAB_STABLE_N" >/dev/null && echo "history stable ok (${label})"
      curl -fsS "$listen/lab?session=$LAB_CANARY_N" >/dev/null && echo "lab canary ok (${label})"
      curl -fsS "$listen/api/lab/outbox?session=$LAB_CANARY_N" >/dev/null && echo "outbox canary ok (${label})"
      curl -fsS "$listen/api/lab/history?lines=200&session=$LAB_CANARY_N" >/dev/null && echo "history canary ok (${label})"
    }

    smoke_one "$STABLE_LISTEN_DEFAULT" "stable-ui"
    smoke_one "$CANARY_LISTEN_DEFAULT" "canary-ui"
    ;;
  deploy-canary)
    "$0" --target canary install
    "$0" --target canary restart
    "$0" smoke
    echo "Proctor canary:"
    echo "  ${PUBLIC_ORIGIN}/canary/lab?session=${LAB_CANARY_N}"
    echo "Stable:"
    echo "  ${PUBLIC_ORIGIN}/lab?session=${LAB_STABLE_N}"
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

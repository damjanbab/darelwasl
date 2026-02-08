#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
  cat <<'EOF'
Usage:
  scripts/tgctl.sh dev preview-start <run-id> [--verify light|full]
  scripts/tgctl.sh dev preview-stop <run-id>
  scripts/tgctl.sh dev preview-status <run-id>

  scripts/tgctl.sh dev webhook-start
  scripts/tgctl.sh dev webhook-stop
  scripts/tgctl.sh dev webhook-status

  scripts/tgctl.sh prod promote

Notes:
  - Dev preview uses scripts/preview --mode telegram (polling; no webhook).
  - Webhook start uses scripts/tg-spinup.sh (tunnel + webhook).
  - Prod promote is gated (DEPLOY_APPROVED=1) and uses scripts/promote-live.sh.
EOF
}

die() { echo "tgctl: $*" >&2; exit 2; }

require_cmd() { command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"; }

telegram_active_file() {
  echo "$ROOT/target/previews/_telegram_active.json"
}

cmd="${1:-}"
sub="${2:-}"
shift || true
shift || true

case "$cmd:$sub" in
  dev:preview-start)
    run_id="${1:-}"
    [[ -n "${run_id:-}" ]] || die "missing <run-id>"
    shift || true
    verify="${VERIFY:-light}"
    if [[ "${1:-}" == "--verify" ]]; then
      verify="${2:-light}"
      shift 2 || true
    fi
    require_cmd python3
    scripts/preview start "$run_id" --mode telegram --verify "$verify"
    ;;

  dev:preview-stop)
    run_id="${1:-}"
    [[ -n "${run_id:-}" ]] || die "missing <run-id>"
    scripts/preview stop "$run_id"
    ;;

  dev:preview-status)
    run_id="${1:-}"
    [[ -n "${run_id:-}" ]] || die "missing <run-id>"
    scripts/preview status "$run_id"
    ;;

  dev:webhook-start)
    TELEGRAM_PROFILE=dev scripts/tg-spinup.sh
    ;;

  dev:webhook-stop)
    if [[ -f "$ROOT/.cpcache/tg/backend.pid" ]]; then
      pid="$(cat "$ROOT/.cpcache/tg/backend.pid" || true)"
      if [[ -n "${pid:-}" ]] && kill -0 "$pid" >/dev/null 2>&1; then
        kill "$pid" >/dev/null 2>&1 || true
      fi
    fi
    if [[ -f "$ROOT/.cpcache/tg/ssh_tunnel.pid" ]]; then
      pid="$(cat "$ROOT/.cpcache/tg/ssh_tunnel.pid" || true)"
      if [[ -n "${pid:-}" ]] && kill -0 "$pid" >/dev/null 2>&1; then
        kill "$pid" >/dev/null 2>&1 || true
      fi
    fi
    if [[ -f "$ROOT/.cpcache/tg/webhook-watch.pid" ]]; then
      pid="$(cat "$ROOT/.cpcache/tg/webhook-watch.pid" || true)"
      if [[ -n "${pid:-}" ]] && kill -0 "$pid" >/dev/null 2>&1; then
        kill "$pid" >/dev/null 2>&1 || true
      fi
    fi
    echo "stopped"
    ;;

  dev:webhook-status)
    for f in backend.pid ssh_tunnel.pid webhook-watch.pid; do
      p="$ROOT/.cpcache/tg/$f"
      if [[ -f "$p" ]]; then
        pid="$(cat "$p" || true)"
        if [[ -n "${pid:-}" ]] && kill -0 "$pid" >/dev/null 2>&1; then
          echo "$f=$pid (running)"
        else
          echo "$f=$pid (stale)"
        fi
      else
        echo "$f=missing"
      fi
    done
    ;;

  prod:promote)
    from_preview=""
    if [[ "${1:-}" == "--from-preview" ]]; then
      from_preview="${2:-}"
      shift 2 || true
    fi
    if [[ -n "$from_preview" ]]; then
      require_cmd python3
      manifest="$ROOT/target/previews/${from_preview}/preview.json"
      [[ -f "$manifest" ]] || die "preview manifest not found: $manifest"
      ok="$(python3 - "$manifest" <<'PY'
import json, sys
p=sys.argv[1]
data=json.load(open(p,"r",encoding="utf-8"))
print("true" if data.get("status") == "accepted" else "false")
PY
)"
      [[ "$ok" == "true" ]] || die "preview is not accepted: $from_preview (run: scripts/preview respond $from_preview accept)"
    fi
    if [[ "${DEPLOY_APPROVED:-}" != "1" ]]; then
      die "refusing to promote: set DEPLOY_APPROVED=1"
    fi
    scripts/promote-live.sh
    ;;

  "":*|*:help|help:*)
    usage
    ;;

  *)
    usage
    die "unknown command: $cmd $sub"
    ;;
esac

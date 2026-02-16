#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  cat <<'EOF'
Usage: scripts/hooks.sh <cmd>

Commands:
  install   Configure this repo to use versioned hooks in .githooks/
  status    Show current core.hooksPath
EOF
}

cmd="${1:-}"
case "$cmd" in
  install)
    if [ ! -x "$ROOT/.githooks/pre-push" ]; then
      echo "Missing or non-executable: $ROOT/.githooks/pre-push" >&2
      exit 2
    fi
    git -C "$ROOT" config core.hooksPath .githooks
    echo "installed: core.hooksPath=.githooks"
    ;;
  status)
    v="$(git -C "$ROOT" config --get core.hooksPath || true)"
    echo "core.hooksPath=${v:-<unset>}"
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


#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

PREFIX="${DW_TMUX_PREFIX:-codex}"
LAB_STABLE_N="${DW_LAB_SESSION_STABLE:-${DW_LAB_SESSION:-7}}"
LAB_CANARY_N="${DW_LAB_SESSION_CANARY:-$((LAB_STABLE_N + 1))}"
LAB_N="${DW_LAB_SESSION:-$LAB_STABLE_N}"
LAB_DIR="${DW_LAB_DIR:-$ROOT/tmp/lab}"

if [[ "${1:-}" == "--stable" ]]; then
  LAB_N="$LAB_STABLE_N"
  shift
elif [[ "${1:-}" == "--canary" ]]; then
  LAB_N="$LAB_CANARY_N"
  shift
elif [[ "${1:-}" == "--session" || "${1:-}" == "-s" ]]; then
  if [[ -z "${2:-}" ]]; then
    echo "missing session number after $1" >&2
    exit 2
  fi
  LAB_N="${2}"
  shift 2
fi

SESSION_NAME="${PREFIX}${LAB_N}"
ROOT_DIR="${LAB_DIR}/${SESSION_NAME}"
INBOX_DIR="${ROOT_DIR}/inbox"
OUTBOX_DIR="${ROOT_DIR}/outbox"

ensure_dirs() {
  mkdir -p "$INBOX_DIR" "$OUTBOX_DIR"
}

usage() {
  cat <<EOF
Usage: scripts/lab.sh <cmd> [args...]

Lab session: $SESSION_NAME (stable=${PREFIX}${LAB_STABLE_N}, canary=${PREFIX}${LAB_CANARY_N})

Session selection:
  --stable             Use stable lab session
  --canary             Use canary lab session
  --session N          Use explicit lab session number

Commands:
  paths                 Print inbox/outbox paths
  ls-inbox              List inbox files
  ls-outbox             List outbox files
  get-inbox <name>      Copy a file from inbox to cwd
  put-outbox <path>     Copy a local file into outbox (for download)
EOF
}

unique_outbox_path() {
  local base="$1"
  local name="${base}"
  local stem ext idx
  stem="${name%.*}"
  ext=""
  if [[ "$name" == *.* && "$stem" != "$name" ]]; then
    ext=".${name##*.}"
  else
    stem="$name"
  fi
  idx=1
  while [[ -e "$OUTBOX_DIR/$name" ]]; do
    name="${stem}-${idx}${ext}"
    idx=$((idx + 1))
  done
  printf "%s" "$OUTBOX_DIR/$name"
}

cmd="${1:-}"
shift || true

case "$cmd" in
  paths)
    ensure_dirs
    printf "session=%s\ninbox=%s\noutbox=%s\n" "$SESSION_NAME" "$INBOX_DIR" "$OUTBOX_DIR"
    ;;
  ls-inbox)
    ensure_dirs
    (cd "$INBOX_DIR" && ls -la)
    ;;
  ls-outbox)
    ensure_dirs
    (cd "$OUTBOX_DIR" && ls -la)
    ;;
  get-inbox)
    ensure_dirs
    name="${1:-}"
    if [[ -z "$name" ]]; then
      echo "missing name" >&2
      exit 2
    fi
    if [[ ! -f "$INBOX_DIR/$name" ]]; then
      echo "not found: $INBOX_DIR/$name" >&2
      exit 1
    fi
    cp -f "$INBOX_DIR/$name" "./$name"
    echo "copied: ./$name"
    ;;
  put-outbox)
    ensure_dirs
    src="${1:-}"
    if [[ -z "$src" ]]; then
      echo "missing path" >&2
      exit 2
    fi
    if [[ ! -f "$src" ]]; then
      echo "not found: $src" >&2
      exit 1
    fi
    base="$(basename "$src")"
    dest="$(unique_outbox_path "$base")"
    cp -f "$src" "$dest"
    echo "copied: $dest"
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

#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

DEFAULT_KEY_FILE="${DW_SECRETS_MASTER_KEY_FILE:-/etc/darelwasl/secrets.key}"

usage() {
  cat <<EOF
Usage: scripts/secrets.sh <cmd> [args...]

Commands:
  init-master-key [--path P]     Create a new 32-byte master key (base64) with 0600 perms
  set <key> [--description D]    Store secret (reads from stdin; prompts if TTY)
  get <key> [--show]             Read secret (metadata by default; --show prints value)
  materialize <key> <path>       Write secret to file (0600)
  list                           List secret keys (metadata only)

Notes:
  - Master key file is NOT stored in git. Default: $DEFAULT_KEY_FILE
  - The app reads secrets at runtime from Datomic (ciphertext) + master key file.
EOF
}

require_clojure() {
  if ! command -v clojure >/dev/null 2>&1; then
    echo "clojure not found" >&2
    exit 2
  fi
}

cmd_init_master_key() {
  local path="$DEFAULT_KEY_FILE"
  if [[ "${1:-}" == "--path" ]]; then
    path="${2:-}"
    shift 2
  fi
  if [[ -z "$path" ]]; then
    echo "missing --path value" >&2
    exit 2
  fi
  if [[ -e "$path" ]]; then
    echo "exists: $path" >&2
    exit 1
  fi
  mkdir -p "$(dirname "$path")"
  umask 077
  python3 - <<PY >"$path"
import base64, os
print(base64.b64encode(os.urandom(32)).decode("ascii"))
PY
  chmod 600 "$path"
  echo "created: $path"
}

cmd_set() {
  local key="${1:-}"
  shift || true
  if [[ -z "$key" ]]; then
    echo "missing key" >&2
    exit 2
  fi
  local desc=""
  if [[ "${1:-}" == "--description" ]]; then
    desc="${2:-}"
    shift 2 || true
  fi

  local val=""
  if [[ -t 0 ]]; then
    read -r -s -p "Secret value for '$key': " val
    echo >&2
  else
    val="$(cat)"
  fi

  require_clojure
  if [[ -n "$desc" ]]; then
    printf "%s" "$val" | (cd "$ROOT" && clojure -M -m darelwasl.secrets.cli set --key "$key" --description "$desc")
  else
    printf "%s" "$val" | (cd "$ROOT" && clojure -M -m darelwasl.secrets.cli set --key "$key")
  fi
}

cmd_get() {
  local key="${1:-}"
  shift || true
  if [[ -z "$key" ]]; then
    echo "missing key" >&2
    exit 2
  fi
  require_clojure
  (cd "$ROOT" && clojure -M -m darelwasl.secrets.cli get --key "$key" "$@")
}

cmd_materialize() {
  local key="${1:-}"
  local path="${2:-}"
  if [[ -z "$key" || -z "$path" ]]; then
    echo "usage: scripts/secrets.sh materialize <key> <path>" >&2
    exit 2
  fi
  require_clojure
  (cd "$ROOT" && clojure -M -m darelwasl.secrets.cli materialize --key "$key" --path "$path")
}

cmd_list() {
  require_clojure
  (cd "$ROOT" && clojure -M -m darelwasl.secrets.cli list)
}

cmd="${1:-}"
shift || true
case "$cmd" in
  init-master-key) cmd_init_master_key "$@" ;;
  set) cmd_set "$@" ;;
  get) cmd_get "$@" ;;
  materialize) cmd_materialize "$@" ;;
  list) cmd_list "$@" ;;
  ""|-h|--help|help) usage ;;
  *) echo "unknown cmd: $cmd" >&2; usage >&2; exit 2 ;;
esac


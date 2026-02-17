#!/usr/bin/env bash
# Load GitHub token into environment.
#
# Priority:
# 1) Existing env: DARELWASL_GITHUB_TOKEN or GITHUB_TOKEN
# 2) Existing env (legacy/systemd): TERMINAL_GITHUB_TOKEN
# 3) System env file (if readable): /etc/darelwasl/app.env
# 4) Secrets vault (if available): secret key github/token

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
LOG_CFG="${DW_LOGBACK_CLI_CONFIG:-$ROOT/resources/logback-cli.xml}"
SYSTEM_ENV_FILE="${DW_APP_ENV_FILE:-/etc/darelwasl/app.env}"

if [[ -z "${DARELWASL_GITHUB_TOKEN:-}" && -z "${GITHUB_TOKEN:-}" && -z "${TERMINAL_GITHUB_TOKEN:-}" ]]; then
  if [[ -r "$SYSTEM_ENV_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$SYSTEM_ENV_FILE" || true
    set +a
  fi
fi

if [[ -z "${DARELWASL_GITHUB_TOKEN:-}" && -z "${GITHUB_TOKEN:-}" && -z "${TERMINAL_GITHUB_TOKEN:-}" ]]; then
  if command -v clojure >/dev/null 2>&1; then
    token="$(cd "$ROOT" && clojure -J-Dlogback.configurationFile="$LOG_CFG" -M -m darelwasl.secrets.cli get --key github/token --show 2>/dev/null || true)"
    if [[ -n "${token:-}" ]]; then
      export GITHUB_TOKEN="$token"
    fi
  fi
fi

if [[ -z "${DARELWASL_GITHUB_TOKEN:-}" && -z "${GITHUB_TOKEN:-}" && -z "${TERMINAL_GITHUB_TOKEN:-}" ]]; then
  echo "No GitHub token found (env or vault)."
  echo "Set one of:"
  echo "  - export DARELWASL_GITHUB_TOKEN=ghp_xxx"
  echo "  - export GITHUB_TOKEN=ghp_xxx"
  echo "  - export TERMINAL_GITHUB_TOKEN=ghp_xxx"
  echo "Or store it in the vault:"
  echo "  scripts/secrets.sh set github/token"
  return 1 2>/dev/null || exit 1
fi

if [[ -z "${GITHUB_TOKEN:-}" ]]; then
  if [[ -n "${DARELWASL_GITHUB_TOKEN:-}" ]]; then
    export GITHUB_TOKEN="$DARELWASL_GITHUB_TOKEN"
  else
    export GITHUB_TOKEN="$TERMINAL_GITHUB_TOKEN"
  fi
fi

echo "GITHUB_TOKEN loaded for this shell (not persisted)."

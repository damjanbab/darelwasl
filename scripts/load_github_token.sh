#!/usr/bin/env bash
# Load GitHub token into environment.
#
# Priority:
# 1) Existing env: DARELWASL_GITHUB_TOKEN or GITHUB_TOKEN
# 2) Secrets vault (if available): secret key github/token

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"

if [[ -z "${DARELWASL_GITHUB_TOKEN:-}" && -z "${GITHUB_TOKEN:-}" ]]; then
  if command -v clojure >/dev/null 2>&1; then
    token="$(cd "$ROOT" && clojure -M -m darelwasl.secrets.cli get --key github/token --show 2>/dev/null || true)"
    if [[ -n "${token:-}" ]]; then
      export GITHUB_TOKEN="$token"
    fi
  fi
fi

if [[ -z "${DARELWASL_GITHUB_TOKEN:-}" && -z "${GITHUB_TOKEN:-}" ]]; then
  echo "No GitHub token found (env or vault)."
  echo "Set one of:"
  echo "  - export DARELWASL_GITHUB_TOKEN=ghp_xxx"
  echo "  - export GITHUB_TOKEN=ghp_xxx"
  echo "Or store it in the vault:"
  echo "  scripts/secrets.sh set github/token"
  return 1 2>/dev/null || exit 1
fi

if [[ -z "${GITHUB_TOKEN:-}" ]]; then
  export GITHUB_TOKEN="$DARELWASL_GITHUB_TOKEN"
fi

echo "GITHUB_TOKEN loaded for this shell (not persisted)."

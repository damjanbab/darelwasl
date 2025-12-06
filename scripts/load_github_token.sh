#!/usr/bin/env bash
# Load GitHub token into environment. No secrets are stored here; set DARELWASL_GITHUB_TOKEN or GITHUB_TOKEN before sourcing.

if [[ -z "${DARELWASL_GITHUB_TOKEN:-}" && -z "${GITHUB_TOKEN:-}" ]]; then
  echo "DARELWASL_GITHUB_TOKEN or GITHUB_TOKEN not set. Export one of them before running git/gh."
  echo "Example: export DARELWASL_GITHUB_TOKEN=ghp_xxx"
  return 1 2>/dev/null || exit 1
fi

if [[ -z "${GITHUB_TOKEN:-}" ]]; then
  export GITHUB_TOKEN="$DARELWASL_GITHUB_TOKEN"
fi

echo "GITHUB_TOKEN loaded for this shell (not persisted)."

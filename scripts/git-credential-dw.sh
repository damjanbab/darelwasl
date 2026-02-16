#!/usr/bin/env bash
set -euo pipefail

# Git credential helper that serves a GitHub HTTPS token from the Datomic secrets vault.
# Configure:
#   git config credential.helper "!/opt/darelwasl/scripts/git-credential-dw.sh"
#
# Requires:
#   - master key file configured (DW_SECRETS_MASTER_KEY_FILE)
#   - secret key: github/token

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

op="${1:-get}"
if [[ "$op" != "get" ]]; then
  exit 0
fi

proto=""
host=""
while IFS='=' read -r k v; do
  case "$k" in
    protocol) proto="$v" ;;
    host) host="$v" ;;
  esac
done

if [[ "$proto" != "https" || "$host" != "github.com" ]]; then
  exit 0
fi

if ! command -v clojure >/dev/null 2>&1; then
  exit 0
fi

token="$(cd "$ROOT" && clojure -M -m darelwasl.secrets.cli get --key github/token --show 2>/dev/null || true)"
if [[ -z "$token" ]]; then
  exit 0
fi

echo "username=x-access-token"
echo "password=$token"


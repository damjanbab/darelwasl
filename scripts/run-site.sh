#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

: "${SITE_PORT:=3200}"
: "${SITE_HOST:=0.0.0.0}"

echo "Starting public site on ${SITE_HOST}:${SITE_PORT}..."
SITE_PORT="${SITE_PORT}" SITE_HOST="${SITE_HOST}" clojure -M:site "$@"

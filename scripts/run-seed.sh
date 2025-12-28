#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

: "${DATOMIC_ROLE:=seed}"
exec clojure -M:seed "$@"

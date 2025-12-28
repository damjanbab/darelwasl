#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

: "${DATOMIC_ROLE:=import}"
exec clojure -M:import "$@"

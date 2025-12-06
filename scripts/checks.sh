#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  cat <<'EOF'
Usage: scripts/checks.sh [all|registries|schema|actions|views|app-smoke|action-contracts]

This is a starter harness. Extend each stub to run real checks (Datomic temp DB, action contract tests, headless app boot).
EOF
}

check_registries() {
  echo "Checking registry files exist and are non-empty..."
  local missing=0
  for f in schema actions views integrations tooling; do
    local path="$ROOT/registries/$f.edn"
    if [ ! -s "$path" ]; then
      echo "Missing or empty registry: $path"
      missing=1
    fi
  done
  if [ "$missing" -ne 0 ]; then
    echo "Registry check failed."
    exit 1
  fi
  echo "Registry presence check passed."
}

require_keys() {
  local file="$1"; shift
  local missing=0
  for key in "$@"; do
    if ! grep -q "$key" "$file"; then
      echo "Missing key '$key' in $file"
      missing=1
    fi
  done
  if [ "$missing" -ne 0 ]; then
    echo "Field check failed for $file"
    exit 1
  fi
}

check_registry_fields() {
  echo "Checking required fields in registries..."
  require_keys "$ROOT/registries/schema.edn" ":id" ":version" ":attributes" ":invariants" ":history" ":compatibility"
  require_keys "$ROOT/registries/actions.edn" ":id" ":version" ":inputs" ":outputs" ":side-effects" ":adapter" ":audit" ":idempotency" ":contracts" ":compatibility"
  require_keys "$ROOT/registries/views.edn" ":id" ":version" ":data" ":actions" ":ux" ":compatibility"
  require_keys "$ROOT/registries/integrations.edn" ":id" ":version" ":contracts" ":auth" ":failure-modes" ":compatibility" ":adapter"
  require_keys "$ROOT/registries/tooling.edn" ":id" ":version" ":invocation" ":scope" ":determinism" ":enforces"
  echo "Registry field checks passed."
}

stub() {
  echo "TODO: implement $1"
}

target="${1:-all}"
case "$target" in
  registries) check_registries; check_registry_fields ;;
  schema) check_registries; check_registry_fields; stub "schema load into temp Datomic" ;;
  actions|action-contracts) check_registries; check_registry_fields; stub "action contract tests (fixtures, idempotency, audit)" ;;
  views) check_registries; check_registry_fields; stub "view registry integrity checks" ;;
  app-smoke) check_registries; check_registry_fields; stub "headless app boot / browser loader smoke" ;;
  all)
    check_registries
    check_registry_fields
    stub "schema load into temp Datomic"
    stub "action contract tests"
    stub "view registry integrity checks"
    stub "headless app boot / browser loader smoke"
    ;;
  *)
    usage
    exit 1
    ;;
esac

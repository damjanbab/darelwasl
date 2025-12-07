#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  cat <<'EOF'
Deploys the daralwasl app.

Usage: scripts/deploy.sh [--no-build]

Steps:
  - git fetch + checkout main + pull
  - npm install
  - npm run theme:css-vars
  - npm run build (skip with --no-build)
  - clojure -M:seed --temp (to validate fixtures; does not affect prod data)
EOF
}

BUILD=1
if [[ "${1:-}" == "--no-build" ]]; then
  BUILD=0
fi

cd "$ROOT"
git fetch origin main
git checkout main
git pull origin main

echo "Running npm install..."
npm install --no-progress --no-audit

echo "Generating theme CSS vars..."
npm run theme:css-vars

if [[ "$BUILD" -eq 1 ]]; then
  echo "Building frontend..."
  npm run build
else
  echo "Skipping frontend build (--no-build)"
fi

echo "Seeding schema/fixtures into temp DB to validate..."
clojure -M:seed --temp >/tmp/darelwasl-seed.log 2>&1 || { echo \"Seed validation failed; see /tmp/darelwasl-seed.log\"; exit 1; }

echo "Deploy steps complete."

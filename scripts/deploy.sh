#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  cat <<'EOF'
Deploys the daralwasl app (the public site is served by the app when enabled).

Usage: scripts/deploy.sh [--no-build]

Steps:
  - git fetch + checkout main + pull
  - npm install
  - npm run theme:css-vars
  - npm run build (skip with --no-build)
  - clojure -M:seed --temp (to validate fixtures; does not affect prod data)
  - (ops) restart darelwasl.service if present
EOF
}

if [[ "${DEPLOY_APPROVED:-}" != "1" ]]; then
  cat <<'EOF'
Refusing to deploy: DEPLOY_APPROVED is not set.

This repo is configured to avoid accidental redeploys.
To deploy intentionally, run:
  DEPLOY_APPROVED=1 scripts/deploy.sh
EOF
  exit 1
fi

BUILD=1
if [[ "${1:-}" == "--no-build" ]]; then
  BUILD=0
fi

cd "$ROOT"
if [[ ! -d .git || ! -w .git/objects ]]; then
  echo "Deploy user lacks write access to .git/objects; fix ownership before deploy."
  exit 1
fi
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
mkdir -p "$ROOT/.cpcache"
SEED_LOG="$ROOT/.cpcache/darelwasl-seed.log"
clojure -M:seed --temp >"$SEED_LOG" 2>&1 || { echo "Seed validation failed; see $SEED_LOG"; exit 1; }

echo "Deploy steps complete."

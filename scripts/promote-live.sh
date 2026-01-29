#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

cd "$ROOT"

if [[ "${DEPLOY_APPROVED:-}" != "1" ]]; then
  cat <<'EOF'
Refusing to promote live: DEPLOY_APPROVED is not set.

This repo is configured to avoid accidental redeploys.
To promote intentionally, run:
  DEPLOY_APPROVED=1 scripts/promote-live.sh
EOF
  exit 1
fi

echo "[promote] Building current checkout..."

echo "[promote] npm install..."
npm install --no-progress --no-audit

echo "[promote] theme vars..."
npm run theme:css-vars

echo "[promote] build..."
npm run build

echo "[promote] seed validation (temp)..."
mkdir -p "$ROOT/.cpcache"
SEED_LOG="$ROOT/.cpcache/promote-seed.log"
clojure -M:seed --temp >"$SEED_LOG" 2>&1 || { echo "[promote] Seed validation failed; see $SEED_LOG"; exit 1; }

echo "[promote] restarting systemd service..."
sudo systemctl restart darelwasl.service

echo "[promote] waiting for health..."
for i in {1..60}; do
  if curl -sf "http://127.0.0.1:3000/health" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
curl -sf "http://127.0.0.1:3000/health" >/dev/null 2>&1 || { echo "[promote] app health failed"; exit 1; }

# Ensure the promoted app is connected to a persistent Datomic store (not :mem).
HEALTH_JSON="$(curl -sf "http://127.0.0.1:3000/health")" || { echo "[promote] unable to fetch health JSON"; exit 1; }
python3 - <<'PY' "$HEALTH_JSON" || { echo "[promote] datomic persistence check failed"; exit 1; }
import json
import sys

raw = sys.argv[1]
data = json.loads(raw)
ds = data.get("datastore") or {}
status = ds.get("status")
storage_kind = ds.get("storage-kind") or ds.get("storage_kind")

if status != "ok":
    raise SystemExit(f"datastore status not ok: {status!r}")

if storage_kind in (None, "", "unknown"):
    raise SystemExit("datastore storage-kind missing/unknown")

if storage_kind == "mem":
    raise SystemExit("datastore storage-kind is mem (:mem) — refusing to promote")
PY

# Public site health (if enabled in config/env, it will be listening on 3200).
for i in {1..60}; do
  if curl -sf "http://127.0.0.1:3200/health" >/dev/null 2>&1; then
    echo "[promote] site health ok"
    exit 0
  fi
  sleep 1
done
echo "[promote] site health not ready (continuing; site may be disabled)"
exit 0

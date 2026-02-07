#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PORT="${APP_PORT:-3111}"
HOST="${APP_HOST:-127.0.0.1}"
BASE_URL="http://${HOST}:${PORT}"
RUN_LLM="${RUN_LLM:-0}"

TMP_DIR="$(mktemp -d "${ROOT}/.cpcache/system-test-XXXXXX")"
COOKIE_JAR="${TMP_DIR}/cookies.txt"
SERVER_LOG="${TMP_DIR}/server.log"
DATOMIC_TMP="${TMP_DIR}/datomic"
SERVER_PID=""
LLM_WT_DIR=""
LLM_RUN_ID=""

cleanup() {
  if [[ -n "${SERVER_PID:-}" ]] && kill -0 "$SERVER_PID" >/dev/null 2>&1; then
    kill "$SERVER_PID" >/dev/null 2>&1 || true
    wait "$SERVER_PID" >/dev/null 2>&1 || true
  fi
  if [[ -n "${LLM_WT_DIR:-}" ]] && [[ -d "${LLM_WT_DIR:-}" ]]; then
    git worktree remove -f "${LLM_WT_DIR}" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP_DIR" >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_for_health() {
  local url="$1"
  local attempts="${2:-60}"
  for ((i=1; i<=attempts; i++)); do
    if curl -sf "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  echo "Server did not become healthy at ${url}" >&2
  echo "Log: ${SERVER_LOG}" >&2
  return 1
}

assert_catalog_contains_id() {
  local response_json="$1"
  local expected_id="$2"
  python3 - "$expected_id" <<'PY' <<<"$response_json"
import json, sys
expected = sys.argv[1]
data = json.load(sys.stdin)
entries = (data.get("entries") or [])
ids = [e.get("id") for e in entries if isinstance(e, dict)]
if expected not in ids:
    raise SystemExit(f"Missing expected catalog id: {expected}\nGot ids (sample): {ids[:20]}")
PY
}

assert_json_field_equals() {
  local json_path="$1"
  local key="$2"
  local expected="$3"
  python3 - "$json_path" "$key" "$expected" <<'PY'
import json, sys
path, key, expected = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, "r", encoding="utf-8") as f:
    data = json.load(f)
actual = data.get(key)
if actual != expected:
    raise SystemExit(f"Expected {key}={expected!r}, got {actual!r} in {path}")
PY
}

json_get() {
  local json="$1"
  local keypath="$2"
  JSON_PAYLOAD="$json" python3 - "$keypath" <<'PY'
import json, os, sys
kp = sys.argv[1].split(".")
raw = os.environ.get("JSON_PAYLOAD") or ""
data = json.loads(raw)
cur = data
idx = 0
while idx < len(kp):
  k = kp[idx]
  if isinstance(cur, dict) and k in cur:
    cur = cur[k]
    idx += 1
    continue
  if isinstance(cur, dict) and idx + 1 < len(kp):
    k2 = k + "." + kp[idx + 1]
    if k2 in cur:
      cur = cur[k2]
      idx += 2
      continue
  print("")
  raise SystemExit(0)
print(cur if cur is not None else "")
PY
}

theme_ids() {
  # Print one theme id per line, e.g. :theme/default
  grep -oE ":id[[:space:]]+:theme/[a-z0-9-]+" "$ROOT/registries/theme.edn" | awk '{print $2}'
}

echo "[system-test] 1) checks"
scripts/checks.sh governance
scripts/checks.sh registries

echo "[system-test] 2) generate docs/catalog"
scripts/generate-docs.sh >/dev/null

echo "[system-test] 3) seed fixtures + start server"
mkdir -p "$DATOMIC_TMP"
DATOMIC_STORAGE_DIR="$DATOMIC_TMP" APP_HOST="$HOST" APP_PORT="$PORT" SITE_ENABLED=false clojure -M:seed >/dev/null

DATOMIC_STORAGE_DIR="$DATOMIC_TMP" APP_HOST="$HOST" APP_PORT="$PORT" SITE_ENABLED=false clojure -M:dev >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!
wait_for_health "${BASE_URL}/health" 120

echo "[system-test] 4) login + catalog queries"
curl -sS -c "$COOKIE_JAR" -X POST "${BASE_URL}/api/login" \
  -H "content-type: application/json" \
  -d '{"user/username":"damjan","user/password":"Damjan1!"}' >/dev/null

POLICY_JSON="$(curl -sS -b "$COOKIE_JAR" "${BASE_URL}/catalog?kind=policy&q=telegram")"
assert_catalog_contains_id "$POLICY_JSON" "policy/telegram-dev-bot-only"

TOOLING_JSON="$(curl -sS -b "$COOKIE_JAR" "${BASE_URL}/catalog?kind=tooling&q=preview")"
assert_catalog_contains_id "$TOOLING_JSON" "tooling/preview-runner"

INTERNAL_JSON="$(curl -sS -b "$COOKIE_JAR" "${BASE_URL}/catalog?kind=internal&q=catalog")"
assert_catalog_contains_id "$INTERNAL_JSON" "internal/catalog-read"

echo "[system-test] 4b) documents pack actions"
CLIENT_ID="20000000-0000-0000-0000-000000000002"

DOC_PACK_RES="$(curl -sS -b "$COOKIE_JAR" -X POST "${BASE_URL}/api/actions/cap.action.doc-pack-upsert" \
  -H "content-type: application/json" \
  -d "{\"client/id\":\"${CLIENT_ID}\",\"doc.pack/company-name\":\"Darel Wasl\",\"doc.pack/currency\":\"SAR\",\"doc.pack/services-included\":\"Consultation + delivery\",\"doc.pack/payment-plan\":\"50% upfront, 50% on delivery\",\"doc.pack/status-notes\":\"Seeded by system-test\"}")"
if ! grep -Fq "\"doc.pack/id\"" <<<"$DOC_PACK_RES"; then
  echo "Expected doc-pack-upsert to return doc.pack/id" >&2
  echo "$DOC_PACK_RES" >&2
  exit 2
fi

# Upsert again (must not crash on existing doc-pack entity).
DOC_PACK_RES_2="$(curl -sS -b "$COOKIE_JAR" -X POST "${BASE_URL}/api/actions/cap.action.doc-pack-upsert" \
  -H "content-type: application/json" \
  -d "{\"client/id\":\"${CLIENT_ID}\",\"doc.pack/company-name\":\"Darel Wasl\",\"doc.pack/currency\":\"SAR\",\"doc.pack/status-notes\":\"Updated by system-test\"}")"
if ! grep -Fq "\"doc.pack/id\"" <<<"$DOC_PACK_RES_2"; then
  echo "Expected second doc-pack-upsert to return doc.pack/id" >&2
  echo "$DOC_PACK_RES_2" >&2
  exit 2
fi

INV_RES="$(curl -sS -b "$COOKIE_JAR" -X POST "${BASE_URL}/api/actions/cap.action.invoice-create" \
  -H "content-type: application/json" \
  -d "{\"client/id\":\"${CLIENT_ID}\",\"invoice/number\":\"INV-DEV-001\",\"invoice/total-amount\":1500,\"invoice/status\":\"draft\"}")"
INVOICE_ID="$(json_get "$INV_RES" "result.invoice.invoice/id")"
if [[ -z "$INVOICE_ID" ]]; then
  echo "Expected invoice-create to return result.invoice.invoice/id" >&2
  echo "$INV_RES" >&2
  exit 2
fi

PAY_RES="$(curl -sS -b "$COOKIE_JAR" -X POST "${BASE_URL}/api/actions/cap.action.payment-create" \
  -H "content-type: application/json" \
  -d "{\"client/id\":\"${CLIENT_ID}\",\"invoice/id\":\"${INVOICE_ID}\",\"payment/amount\":500,\"payment/method\":\"cash\",\"payment/reference\":\"DEV-CASH\"}")"
PAYMENT_ID="$(json_get "$PAY_RES" "result.payment.payment/id")"
if [[ -z "$PAYMENT_ID" ]]; then
  echo "Expected payment-create to return result.payment.payment/id" >&2
  echo "$PAY_RES" >&2
  exit 2
fi

for action in proposal-generate status-report-generate; do
  RES="$(curl -sS -b "$COOKIE_JAR" -X POST "${BASE_URL}/api/actions/cap.action.${action}" \
    -H "content-type: application/json" \
    -d "{\"client/id\":\"${CLIENT_ID}\"}")"
  FILE_ID="$(json_get "$RES" "result.file.file/id")"
  if [[ -z "$FILE_ID" ]]; then
    echo "Expected cap.action.${action} to return result.file.file/id" >&2
    echo "$RES" >&2
    exit 2
  fi
done

INV_PDF_RES="$(curl -sS -b "$COOKIE_JAR" -X POST "${BASE_URL}/api/actions/cap.action.invoice-pdf-generate" \
  -H "content-type: application/json" \
  -d "{\"client/id\":\"${CLIENT_ID}\",\"invoice/id\":\"${INVOICE_ID}\"}")"
INV_PDF_FILE_ID="$(json_get "$INV_PDF_RES" "result.file.file/id")"
if [[ -z "$INV_PDF_FILE_ID" ]]; then
  echo "Expected invoice-pdf-generate to return result.file.file/id" >&2
  echo "$INV_PDF_RES" >&2
  exit 2
fi

REC_RES="$(curl -sS -b "$COOKIE_JAR" -X POST "${BASE_URL}/api/actions/cap.action.receipt-generate" \
  -H "content-type: application/json" \
  -d "{\"client/id\":\"${CLIENT_ID}\",\"payment/id\":\"${PAYMENT_ID}\"}")"
REC_FILE_ID="$(json_get "$REC_RES" "result.file.file/id")"
if [[ -z "$REC_FILE_ID" ]]; then
  echo "Expected receipt-generate to return result.file.file/id" >&2
  echo "$REC_RES" >&2
  exit 2
fi

if [[ "$RUN_LLM" = "1" ]]; then
  echo "[system-test] 5) llm smoke (website-agent)"
  if ! command -v codex >/dev/null 2>&1; then
    echo "Missing codex CLI on PATH; cannot run RUN_LLM=1 smoke." >&2
    exit 2
  fi
  codex login status >/dev/null

  LLM_RUN_ID="llm-smoke-$(python3 -c 'import uuid; print(uuid.uuid4().hex[:10])')"
  echo "[system-test] LLM_RUN_ID=${LLM_RUN_ID}"
  LLM_WT_DIR="${TMP_DIR}/llm-worktree"
  git worktree add --detach "${LLM_WT_DIR}" HEAD >/dev/null

  LLM_REQUEST="${LLM_REQUEST:-Read registries/theme.edn and update public/robots.txt by replacing the exact line 'Sitemap: https://darelwasl.com/sitemap.xml' with the same line plus a trailing comment listing all theme ids as EDN keywords (e.g. '# themes: :theme/default, :theme/dark'). Do not change anything else.}"
  scripts/website-agent run --run-id "$LLM_RUN_ID" --worktree "$LLM_WT_DIR" --request "$LLM_REQUEST" --agent-json agents/website/AGENT.json >/dev/null

  REPORT_PATH="$ROOT/target/previews/${LLM_RUN_ID}/artifacts/website-agent.report.json"
  if [[ ! -f "$REPORT_PATH" ]]; then
    echo "Missing website-agent report: $REPORT_PATH" >&2
    exit 2
  fi
  assert_json_field_equals "$REPORT_PATH" "status" "ok"
  assert_json_field_equals "$REPORT_PATH" "mode" "llm"
  RECIPE_ID="$(
    python3 - "$REPORT_PATH" <<'PY'
import json, sys
p = sys.argv[1]
with open(p, "r", encoding="utf-8") as f:
    d = json.load(f)
rc = d.get("recipe_created") or {}
print((rc.get("id") or "").strip())
PY
  )"
  if [[ -z "$RECIPE_ID" ]]; then
    echo "Expected LLM run to add a recipe (report.recipe_created.id), but it was empty." >&2
    echo "Report: $REPORT_PATH" >&2
    exit 2
  fi
  echo "[system-test] LLM artifacts: target/previews/${LLM_RUN_ID}/artifacts/"

  ROBOTS_PATH="${LLM_WT_DIR}/public/robots.txt"
  if [[ ! -f "$ROBOTS_PATH" ]]; then
    echo "Missing robots.txt in LLM worktree: $ROBOTS_PATH" >&2
    exit 2
  fi
  while read -r tid; do
    [[ -z "$tid" ]] && continue
    if ! grep -Fq "$tid" "$ROBOTS_PATH"; then
      echo "LLM output missing expected theme id '$tid' in $ROBOTS_PATH" >&2
      exit 2
    fi
  done < <(theme_ids)

  echo "[system-test] 6) recipe reuse smoke (same request, no LLM)"
  LLM_RUN_ID_2="llm-smoke-$(python3 -c 'import uuid; print(uuid.uuid4().hex[:10])')"
  scripts/website-agent run --run-id "$LLM_RUN_ID_2" --worktree "$LLM_WT_DIR" --request "$LLM_REQUEST" --agent-json agents/website/AGENT.json >/dev/null
  REPORT_PATH_2="$ROOT/target/previews/${LLM_RUN_ID_2}/artifacts/website-agent.report.json"
  assert_json_field_equals "$REPORT_PATH_2" "status" "ok"
  assert_json_field_equals "$REPORT_PATH_2" "mode" "recipe"
  python3 - "$REPORT_PATH_2" "$RECIPE_ID" <<'PY'
import json, sys
p = sys.argv[1]
expected = sys.argv[2].lstrip(":")
with open(p, "r", encoding="utf-8") as f:
    d = json.load(f)
actual = (d.get("recipe_id") or "").lstrip(":")
if actual and expected and actual != expected:
    raise SystemExit(f"Expected recipe_id={expected}, got {actual}")
PY
fi

echo "[system-test] ok"

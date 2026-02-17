#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

STATE_DIR="${DW_WORK_PROD_STATE_DIR:-$ROOT/data/work-prod/works}"
ARTIFACT_DIR="${DW_WORK_PROD_ARTIFACT_DIR:-$ROOT/target/work-prod}"
DEFAULT_MODEL="${DARELWASL_WORK_MODEL:-gpt-5.2-high}"

usage() {
  cat <<'EOF'
Usage: scripts/work-prod.sh <cmd> [args...]

State:
  Uses data/work-prod/works/<id>.json to track pipeline state.

Commands:
  new --type <t> --playbook <id> --summary <text> [--id <id>]
  init <id> --agent <agents/.../AGENT.json> --request <text> [--model <m>]
  preflight <id> [--agent <agents/.../AGENT.json>]
  approve-spec <id>
  execute <id>
  preview <id> [--lab stable|canary|session:N] [--public-host <url>]
  approve-proof <id>
  merge-agent [--prefix work/] [--method merge|squash|rebase]

Notes:
  - This script is the "work production pipeline" entrypoint. It is designed
    to be callable by the preview approval link (/_preview/<id>/approve?t=...).
  - It expects the work to be managed via scripts/work.sh (branch/worktree).
EOF
}

die() { echo "error: $*" >&2; exit 2; }

ensure_dirs() {
  mkdir -p "$STATE_DIR" "$ARTIFACT_DIR"
}

state_path() {
  local id="$1"
  echo "$STATE_DIR/$id.json"
}

state_read() {
  local id="$1"
  local p; p="$(state_path "$id")"
  [ -s "$p" ] || die "Missing state: $p (run: scripts/work-prod.sh init $id ...)"
  cat "$p"
}

state_write() {
  local id="$1" tmp
  tmp="$(mktemp)"
  cat >"$tmp"
  mv "$tmp" "$(state_path "$id")"
}

json_get() {
  local key="$1"
  python3 -c 'import json,sys; data=json.loads(sys.stdin.read() or "{}"); print(data.get(sys.argv[1],"") or "")' "$key"
}

json_set() {
  local key="$1" value="$2"
  python3 -c 'import json,sys; key=sys.argv[1]; value=sys.argv[2]; data=json.loads(sys.stdin.read() or "{}"); data[key]=value; print(json.dumps(data, indent=2, sort_keys=True))' "$key" "$value"
}

json_set_obj() {
  local key="$1" payload_json="${2:-{}}"
  python3 -c 'import json,sys; key=sys.argv[1]; payload=json.loads(sys.argv[2] or "{}"); data=json.loads(sys.stdin.read() or "{}"); data[key]=payload; print(json.dumps(data, indent=2, sort_keys=True))' "$key" "$payload_json"
}

worktree_path() {
  local id="$1"
  scripts/work.sh path "$id" 2>/dev/null || true
}

work_branch() {
  local id="$1"
  (scripts/work.sh show "$id" | rg -n "^work/branch:" -o || true) >/dev/null 2>&1
  scripts/work.sh show "$id" | sed -nE 's/^work\/branch:[[:space:]]*//p' | head -n1
}

preview_run_id() {
  # scripts/preview enforces: kebab-case [a-z0-9-], length 3..49.
  # Our work ids can be longer, so derive a stable, short preview id when needed.
  local work_id="$1"
  python3 - "$work_id" <<'PY'
import hashlib, re, sys

work_id = (sys.argv[1] or "").strip()
pat = re.compile(r"[a-z0-9][a-z0-9-]{2,48}$")
if pat.fullmatch(work_id):
    print(work_id)
    raise SystemExit(0)

slug = re.sub(r"[^a-z0-9]+", "-", work_id.lower()).strip("-")
slug = slug or "work"
suffix = hashlib.sha1(work_id.encode("utf-8")).hexdigest()[:8]
prefix = "wp-"
maxlen = 49
avail = maxlen - len(prefix) - 1 - len(suffix)
base = slug[:max(avail, 1)].strip("-") or "work"
out = f"{prefix}{base}-{suffix}"
if not pat.fullmatch(out):
    raise SystemExit(f"derived preview id invalid: {out!r}")
print(out)
PY
}

require_work_item() {
  local id="$1"
  [ -s "$ROOT/docs/work/$id.md" ] || die "Work item missing: docs/work/$id.md (create: scripts/work.sh new ...)"
}

require_cmd() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || die "Missing required command: $cmd"
}

load_github_token_or_die() {
  # Load token into this shell (so later steps can use $GITHUB_TOKEN).
  # shellcheck source=/dev/null
  if ! source "$ROOT/scripts/load_github_token.sh" >/dev/null; then
    die "Missing GitHub token wiring. Fix before proceeding: scripts/secrets.sh set github/token (or export GITHUB_TOKEN)."
  fi
  # Validate token early so work doesn't run without a viable PR/merge path.
  if ! curl -fsS -H "Authorization: Bearer ${GITHUB_TOKEN}" https://api.github.com/user >/dev/null 2>&1; then
    die "GitHub token validation failed. Re-issue token and store it as github/token (or export GITHUB_TOKEN)."
  fi
}

cmd_preflight() {
  local id="${1:-}"; shift || true
  [ -n "$id" ] || die "preflight requires <id>"
  ensure_dirs
  require_work_item "$id"

  local agent=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --agent) agent="${2:-}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) die "preflight: unknown arg: $1" ;;
    esac
  done

  if [ -z "${agent:-}" ]; then
    if [ -s "$(state_path "$id")" ]; then
      agent="$(state_read "$id" | json_get agent_json)"
    fi
  fi
  [ -n "${agent:-}" ] || die "preflight requires --agent (or an existing state with agent_json)"
  [ -s "$ROOT/$agent" ] || die "Agent contract not found: $agent"

  require_cmd git
  require_cmd python3
  require_cmd codex
  require_cmd rg

  [ -x "$ROOT/scripts/agent-runner" ] || die "Missing tooling: scripts/agent-runner"
  [ -x "$ROOT/scripts/preview" ] || die "Missing tooling: scripts/preview"
  [ -x "$ROOT/scripts/lab.sh" ] || die "Missing tooling: scripts/lab.sh"
  [ -x "$ROOT/scripts/pr-merge.sh" ] || die "Missing tooling: scripts/pr-merge.sh"
  [ -x "$ROOT/scripts/git-resolve-conflicts.py" ] || die "Missing tooling: scripts/git-resolve-conflicts.py"

  (cd "$ROOT" && codex login status >/dev/null 2>&1) || die "codex is not logged in (run: codex login)"
  (cd "$ROOT" && scripts/preview --help >/dev/null 2>&1) || die "preview tooling not runnable"

  load_github_token_or_die
  echo "ok"
}

ensure_worktree() {
  local id="$1"
  require_work_item "$id"
  local wt; wt="$(worktree_path "$id")"
  # In git worktrees, `.git` is typically a file (not a directory).
  if [ -z "${wt:-}" ] || [ ! -e "$wt/.git" ]; then
    wt="$(scripts/work.sh start "$id")"
  fi
  echo "$wt"
}

sync_with_origin_main() {
  local wt="$1"
  (cd "$wt" && git fetch origin --prune >/dev/null 2>&1 || true)
  # Best-effort reconsolidation: merge origin/main into current branch.
  # If conflicts occur, leave the repo in a conflicted state for conflict tooling.
  (cd "$wt" && git merge --no-edit origin/main >/dev/null 2>&1) || return 1
  return 0
}

cmd_new() {
  local type="" playbook="" summary="" id=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --type) type="${2:-}"; shift 2 ;;
      --playbook) playbook="${2:-}"; shift 2 ;;
      --summary) summary="${2:-}"; shift 2 ;;
      --id) id="${2:-}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) die "new: unknown arg: $1" ;;
    esac
  done
  [ -n "$type" ] || die "new requires --type"
  [ -n "$playbook" ] || die "new requires --playbook"
  [ -n "$summary" ] || die "new requires --summary"
  scripts/playbook.sh show "$playbook" >/dev/null 2>&1 || die "Unknown playbook: $playbook"

  local dirty
  dirty="$(cd "$ROOT" && git status --porcelain=v1 || true)"
  if [ -n "${dirty:-}" ]; then
    die "Refusing to create a new work item while the base checkout is dirty. Clean it first (or park it), then retry."
  fi

  local new_id
  if [ -n "${id:-}" ]; then
    new_id="$(scripts/work.sh new --type "$type" --playbook "$playbook" --summary "$summary" --id "$id")"
  else
    new_id="$(scripts/work.sh new --type "$type" --playbook "$playbook" --summary "$summary")"
  fi
  [ -n "${new_id:-}" ] || die "work.sh new returned an empty id"

  # Keep the base checkout clean so scripts/work.sh start can run.
  local wf="docs/work/${new_id}.md"
  dirty="$(cd "$ROOT" && git status --porcelain=v1 || true)"
  if [ -n "${dirty:-}" ]; then
    if [ "$dirty" = "?? $wf" ]; then
      (cd "$ROOT" && git add "$wf" && git commit -m "work: ${new_id} (work-prod)" >/dev/null 2>&1) \
        || die "Failed to auto-commit work item file: $wf"
    fi
  fi
  dirty="$(cd "$ROOT" && git status --porcelain=v1 || true)"
  if [ -n "${dirty:-}" ]; then
    die "Base checkout left dirty after work creation. Resolve and retry: git status shows:\n$dirty"
  fi

  echo "$new_id"
}

cmd_init() {
  local id="${1:-}"; shift || true
  [ -n "$id" ] || die "init requires <id>"
  local agent="" request="" model="$DEFAULT_MODEL"
  while [ $# -gt 0 ]; do
    case "$1" in
      --agent) agent="${2:-}"; shift 2 ;;
      --request) request="${2:-}"; shift 2 ;;
      --model) model="${2:-}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) die "init: unknown arg: $1" ;;
    esac
  done
  [ -n "$agent" ] || die "init requires --agent <agents/.../AGENT.json>"
  [ -n "$request" ] || die "init requires --request <text>"

  ensure_dirs
  require_work_item "$id"

  local agent_path="$ROOT/$agent"
  [ -s "$agent_path" ] || die "Agent contract not found: $agent"

  # Block work that cannot complete end-to-end due to missing prerequisites.
  cmd_preflight "$id" --agent "$agent" >/dev/null

  # Store a runtime state record. This is not a git artifact; it's for orchestration.
  local now; now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  local st; st="$(state_path "$id")"
  if [ -e "$st" ]; then
    die "State already exists: $st"
  fi
  local request_json
  request_json="$(DW_WORK_PROD_REQUEST="$request" python3 -c 'import json,os; print(json.dumps(os.environ.get("DW_WORK_PROD_REQUEST","")))' )"
  cat >"$st" <<JSON
{
  "format": "darelwasl/work-prod",
  "version": 1,
  "work_id": "$id",
  "created_at": "$now",
  "updated_at": "$now",
  "status": "draft",
  "agent_json": "$agent",
  "model": "$model",
  "request": $request_json,
  "events": []
}
JSON
  echo "$st"
}

record_approved_spec_if_missing() {
  local wt="$1" id="$2" now="$3" agent_json="$4" model="$5" request="$6"
  local f="$wt/docs/work/$id.md"
  [ -s "$f" ] || die "Work item missing in worktree: $f"
  DW_WORK_FILE="$f" \
  DW_APPROVED_AT="$now" \
  DW_AGENT_JSON="$agent_json" \
  DW_MODEL="$model" \
  DW_REQUEST="$request" \
  python3 - <<'PY'
import os
import sys
from pathlib import Path

path = Path(os.environ["DW_WORK_FILE"])
text = path.read_text(encoding="utf-8")

if "\n## Approved spec\n" in text or text.startswith("## Approved spec\n"):
    sys.exit(3)

approved_at = os.environ.get("DW_APPROVED_AT", "")
agent = os.environ.get("DW_AGENT_JSON", "")
model = os.environ.get("DW_MODEL", "")
request = (os.environ.get("DW_REQUEST", "") or "").rstrip("\n")

block = "\n".join(
    [
        "",
        "## Approved spec",
        "",
        f"- approved_at: `{approved_at}`",
        f"- agent: `{agent}`",
        f"- model: `{model}`",
        "",
        "### Request",
        "",
        "```",
        request,
        "```",
        "",
    ]
)

path.write_text(text.rstrip("\n") + block + "\n", encoding="utf-8")
PY
}

cmd_approve_spec() {
  local id="${1:-}"
  [ -n "$id" ] || die "approve-spec requires <id>"
  ensure_dirs
  local agent_json model request
  agent_json="$(state_read "$id" | json_get agent_json)"
  model="$(state_read "$id" | json_get model)"
  request="$(state_read "$id" | json_get request)"
  [ -n "$agent_json" ] || die "state missing agent_json (run init first)"
  [ -n "$request" ] || die "state missing request (run init first)"

  local now; now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  # Ensure prerequisites are still present at approval time.
  cmd_preflight "$id" --agent "$agent_json" >/dev/null

  # Persist the approved spec into git so it's reviewable in the PR.
  local wt; wt="$(ensure_worktree "$id")"
  if ! sync_with_origin_main "$wt"; then
    die "Failed to reconsolidate with origin/main before spec approval in $wt"
  fi
  if record_approved_spec_if_missing "$wt" "$id" "$now" "$agent_json" "$model" "$request"; then
    (cd "$wt" && git add "docs/work/$id.md" && git commit -m "work-prod($id): approve spec" >/dev/null 2>&1) || true
  fi

  state_read "$id" | DW_NOW="$now" python3 -c 'import json,os,sys; d=json.loads(sys.stdin.read() or "{}"); now=os.environ["DW_NOW"]; d["status"]="spec_approved"; d["updated_at"]=now; d.setdefault("events",[]).append({"at":now,"kind":"spec_approved"}); print(json.dumps(d, indent=2, sort_keys=True))' | state_write "$id"
}

cmd_execute() {
  local id="${1:-}"
  [ -n "$id" ] || die "execute requires <id>"
  ensure_dirs
  local status; status="$(state_read "$id" | json_get status)"
  [ "$status" = "spec_approved" ] || die "work $id not spec_approved (status=$status)"

  local agent_json model request
  agent_json="$(state_read "$id" | json_get agent_json)"
  model="$(state_read "$id" | json_get model)"
  request="$(state_read "$id" | json_get request)"
  [ -n "$agent_json" ] || die "state missing agent_json"
  [ -n "$model" ] || model="$DEFAULT_MODEL"
  [ -n "$request" ] || die "state missing request"

  local wt; wt="$(ensure_worktree "$id")"
  if ! sync_with_origin_main "$wt"; then
    die "Failed to reconsolidate with origin/main in $wt (resolve conflicts, then retry execute)"
  fi

  local now; now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  state_read "$id" | DW_NOW="$now" python3 -c 'import json,os,sys; d=json.loads(sys.stdin.read() or "{}"); now=os.environ["DW_NOW"]; d["status"]="executing"; d["updated_at"]=now; d.setdefault("events",[]).append({"at":now,"kind":"executing"}); print(json.dumps(d, indent=2, sort_keys=True))' | state_write "$id"

  "$ROOT/scripts/agent-runner" run \
    --work-id "$id" \
    --worktree "$wt" \
    --agent-json "$agent_json" \
    --model "$model" \
    --request "$request"

  local sha; sha="$(cd "$wt" && git rev-parse HEAD)"
  local now2; now2="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  state_read "$id" | DW_NOW="$now2" DW_SHA="$sha" python3 -c 'import json,os,sys; d=json.loads(sys.stdin.read() or "{}"); now=os.environ["DW_NOW"]; sha=os.environ["DW_SHA"]; d["status"]="executed"; d["updated_at"]=now; d["executed_sha"]=sha; d.setdefault("events",[]).append({"at":now,"kind":"executed","sha":sha}); print(json.dumps(d, indent=2, sort_keys=True))' | state_write "$id"
}

write_proof_html() {
  local id="$1" preview_id="$2" app_url="$3" site_url="$4" approve_url="$5"
  local out_dir="$ARTIFACT_DIR/$id"
  mkdir -p "$out_dir"
  local out="$out_dir/work-proof-$id.html"
  cat >"$out" <<HTML
<!doctype html>
<meta charset="utf-8">
<title>Work proof: $id</title>
<style>
  body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; margin: 24px; max-width: 980px; }
  .card { border: 1px solid #e5e7eb; border-radius: 12px; padding: 16px; margin: 12px 0; }
  a { word-break: break-all; }
  .cta { display: inline-block; padding: 10px 14px; border-radius: 10px; background: #111827; color: #fff; text-decoration: none; }
  .muted { color: #6b7280; }
  code { background: #f3f4f6; padding: 2px 6px; border-radius: 6px; }
</style>
<h1>Work proof</h1>
<div class="card">
  <div><strong>Work id</strong>: <code>$id</code></div>
</div>
<div class="card">
  <h2>Preview</h2>
  <div><strong>App</strong>: <a href="$app_url" target="_blank" rel="noreferrer">$app_url</a></div>
  <div><strong>Site</strong>: <a href="$site_url" target="_blank" rel="noreferrer">$site_url</a></div>
  <p class="muted">These links are intended to open without login (preview token auto-auth).</p>
</div>
<div class="card">
  <h2>Approve</h2>
  <p><a class="cta" href="$approve_url" target="_blank" rel="noreferrer">Approve proof → create PR → merge</a></p>
  <div class="muted">If your environment lacks GitHub token wiring, approval will fail with an actionable error.</div>
</div>
<div class="card">
  <h2>Service control</h2>
  <div class="muted">Stop the preview when done:</div>
  <pre><code>scripts/preview stop $preview_id</code></pre>
</div>
HTML
  echo "$out"
}

write_links_txt() {
  local id="$1" preview_id="$2" app_url="$3" site_url="$4" approve_url="$5" expires_at="$6"
  local out_dir="$ARTIFACT_DIR/$id"
  mkdir -p "$out_dir"
  local out="$out_dir/work-links-$id.txt"
  cat >"$out" <<TXT
work_id=$id
preview_id=$preview_id
app_url=$app_url
site_url=$site_url
approve_url=$approve_url
expires_at=$expires_at
TXT
  echo "$out"
}

cmd_preview() {
  local id="${1:-}"; shift || true
  [ -n "$id" ] || die "preview requires <id>"
  ensure_dirs

  local lab="stable" public_host="https://haloeddepth.com"
  while [ $# -gt 0 ]; do
    case "$1" in
      --lab) lab="${2:-}"; shift 2 ;;
      --public-host) public_host="${2:-}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) die "preview: unknown arg: $1" ;;
    esac
  done

  local status; status="$(state_read "$id" | json_get status)"
  [ "$status" = "executed" ] || die "work $id not executed (status=$status)"

  local wt; wt="$(ensure_worktree "$id")"
  local branch; branch="$(scripts/work.sh show "$id" | sed -nE 's/^work\/branch:[[:space:]]*//p' | head -n1)"
  [ -n "$branch" ] || branch="work/$id"

  local preview_id
  preview_id="$(preview_run_id "$id")"

  # Start preview from the work branch ref.
  local out_json
  out_json="$(python3 - <<PY
import json,subprocess,shlex,sys
cmd=["bash","-lc", "scripts/preview start " + shlex.quote("$preview_id") + " --ref " + shlex.quote("$branch") + " --mode both --public-host " + shlex.quote("$public_host") + " --verify light"]
p=subprocess.run(cmd, cwd="$ROOT", stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
print(p.stdout)
sys.exit(p.returncode)
PY
  )"
  # scripts/preview prints JSON on success; capture last {...}.
  local preview_json
  preview_json="$(python3 - "$out_json" <<'PY'
import json, sys

_s = sys.argv[1] if len(sys.argv) > 1 else ""
s = _s
try:
    d = json.loads(s)
    print(json.dumps(d))
    raise SystemExit(0)
except Exception:
    pass

# Best-effort: find a JSON object at the end of mixed output.
for i in range(len(s) - 1, -1, -1):
    if s[i] != "{":
        continue
    try:
        d = json.loads(s[i:])
        print(json.dumps(d))
        raise SystemExit(0)
    except Exception:
        continue

raise SystemExit(1)
PY
)" || die "preview start failed:\n$out_json"

  local app_url site_url expires_at
  app_url="$(printf "%s" "$preview_json" | python3 -c 'import json,sys; d=json.load(sys.stdin); print((d.get("urls") or {}).get("app",""))')"
  site_url="$(printf "%s" "$preview_json" | python3 -c 'import json,sys; d=json.load(sys.stdin); print((d.get("urls") or {}).get("site",""))')"
  expires_at="$(printf "%s" "$preview_json" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("expires_at",""))')"
  [ -n "$app_url" ] || die "preview missing app url"
  [ -n "$site_url" ] || die "preview missing site url"

  # Token is in manifest; infer from urls (?t=...).
  local token
  token="$(python3 -c 'import re,sys; s=sys.argv[1]; m=re.search(r"[?&]t=([^&]+)", s); print(m.group(1) if m else "")' "$app_url")"
  [ -n "$token" ] || die "unable to infer token from app url"

  local approve_url="${public_host}/_preview/${id}/approve/?t=${token}"
  local proof_html; proof_html="$(write_proof_html "$id" "$preview_id" "$app_url" "$site_url" "$approve_url")"
  local links_txt; links_txt="$(write_links_txt "$id" "$preview_id" "$app_url" "$site_url" "$approve_url" "$expires_at")"

  case "$lab" in
    stable) "$ROOT/scripts/lab.sh" --stable put-outbox "$proof_html" ;;
    canary) "$ROOT/scripts/lab.sh" --canary put-outbox "$proof_html" ;;
    session:*) n="${lab#session:}"; "$ROOT/scripts/lab.sh" --session "$n" put-outbox "$proof_html" ;;
    *) die "Unknown --lab: $lab (expected stable|canary|session:N)" ;;
  esac
  case "$lab" in
    stable) "$ROOT/scripts/lab.sh" --stable put-outbox "$links_txt" ;;
    canary) "$ROOT/scripts/lab.sh" --canary put-outbox "$links_txt" ;;
    session:*) n="${lab#session:}"; "$ROOT/scripts/lab.sh" --session "$n" put-outbox "$links_txt" ;;
    *) die "Unknown --lab: $lab (expected stable|canary|session:N)" ;;
  esac

  local now; now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  local preview_json_file="$ARTIFACT_DIR/$id/preview.json"
  printf "%s" "$preview_json" >"$preview_json_file"
  state_read "$id" | \
    DW_NOW="$now" \
    DW_PREVIEW_JSON_FILE="$preview_json_file" \
    DW_PREVIEW_ID="$preview_id" \
    DW_TOKEN="$token" \
    DW_EXPIRES_AT="$expires_at" \
    DW_PROOF_HTML="$proof_html" \
    DW_LINKS_TXT="$links_txt" \
    DW_APPROVE_URL="$approve_url" \
    python3 -c 'import json,os,sys; d=json.loads(sys.stdin.read() or "{}"); now=os.environ["DW_NOW"]; preview=json.load(open(os.environ["DW_PREVIEW_JSON_FILE"],"r",encoding="utf-8")); d["status"]="proof_ready"; d["updated_at"]=now; d["preview"]={"id": os.environ.get("DW_PREVIEW_ID",""), "json": preview, "token": os.environ.get("DW_TOKEN",""), "expires_at": os.environ.get("DW_EXPIRES_AT",""), "proof_html": os.environ.get("DW_PROOF_HTML",""), "links_txt": os.environ.get("DW_LINKS_TXT",""), "approve_url": os.environ.get("DW_APPROVE_URL","")}; d.setdefault("events",[]).append({"at":now,"kind":"proof_ready"}); print(json.dumps(d, indent=2, sort_keys=True))' | \
    state_write "$id"
}

cmd_approve_proof() {
  local id="${1:-}"
  [ -n "$id" ] || die "approve-proof requires <id>"
  ensure_dirs
  local status; status="$(state_read "$id" | json_get status)"
  if [ "$status" = "pr_created" ]; then
    state_read "$id" | json_get pr_url
    return 0
  fi
  [ "$status" = "proof_ready" ] || die "work $id not proof_ready (status=$status)"

  local wt; wt="$(ensure_worktree "$id")"
  if ! sync_with_origin_main "$wt"; then
    die "Failed to reconsolidate with origin/main before PR create in $wt"
  fi

  # Push branch + create PR. Requires GitHub token.
  local branch; branch="$(work_branch "$id")"
  [ -n "$branch" ] || branch="work/$id"
  (cd "$wt" && git push -u origin "$branch")
  pr_url="$(scripts/work.sh pr-create "$id")"

  local now; now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  state_read "$id" | DW_NOW="$now" DW_PR_URL="$pr_url" python3 -c 'import json,os,sys; d=json.loads(sys.stdin.read() or "{}"); now=os.environ["DW_NOW"]; url=os.environ.get("DW_PR_URL",""); d["status"]="pr_created"; d["updated_at"]=now; d["pr_url"]=url; d.setdefault("events",[]).append({"at":now,"kind":"pr_created","url":url}); print(json.dumps(d, indent=2, sort_keys=True))' | state_write "$id"

  # Attempt merge (requires DEPLOY_APPROVED=1 + GitHub token).
  if scripts/pr-merge.sh merge-work "$id" --resolve >/dev/null 2>&1; then
    local now3; now3="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    state_read "$id" | DW_NOW="$now3" DW_PR_URL="$pr_url" python3 -c 'import json,os,sys; d=json.loads(sys.stdin.read() or "{}"); now=os.environ["DW_NOW"]; url=os.environ.get("DW_PR_URL",""); d["status"]="merged"; d["updated_at"]=now; d.setdefault("events",[]).append({"at":now,"kind":"merged","url":url}); print(json.dumps(d, indent=2, sort_keys=True))' | state_write "$id"
  fi

  echo "$pr_url"
}

cmd_merge_agent() {
  local prefix="work/" method="merge"
  while [ $# -gt 0 ]; do
    case "$1" in
      --prefix) prefix="${2:-}"; shift 2 ;;
      --method) method="${2:-}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) die "merge-agent: unknown arg: $1" ;;
    esac
  done
  scripts/pr-merge.sh poll --prefix "$prefix" --method "$method" --resolve
}

cmd="${1:-}"
shift || true
case "$cmd" in
  new) cmd_new "$@" ;;
  init) cmd_init "$@" ;;
  preflight) cmd_preflight "$@" ;;
  approve-spec) cmd_approve_spec "$@" ;;
  execute) cmd_execute "$@" ;;
  preview) cmd_preview "$@" ;;
  approve-proof) cmd_approve_proof "$@" ;;
  merge-agent) cmd_merge_agent "$@" ;;
  -h|--help|help|"") usage ;;
  *)
    die "Unknown command: $cmd"
    ;;
esac

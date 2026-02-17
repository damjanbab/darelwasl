#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Current checkout root (works from main checkout or a linked worktree).
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
if git -C "$SCRIPT_DIR" rev-parse --show-toplevel >/dev/null 2>&1; then
  ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
fi

# Shared/common root (where the git common dir lives). Use this for placing worktrees in a stable location.
COMMON_ROOT="$ROOT"
if git -C "$SCRIPT_DIR" rev-parse --git-common-dir >/dev/null 2>&1; then
  common_dir="$(git -C "$SCRIPT_DIR" rev-parse --git-common-dir)"
  if [[ "$common_dir" = /* ]]; then
    common_abs="$common_dir"
  else
    common_abs="$(cd "$SCRIPT_DIR/$common_dir" && pwd)"
  fi
  COMMON_ROOT="$(cd "$common_abs/.." && pwd)"
fi

# Work items are committed, so default to the current checkout.
WORK_DIR="${WORK_DIR:-$ROOT/docs/work}"

# Worktrees should live in one shared place (common root).
WORKTREES_DIR="${WORKTREES_DIR:-$COMMON_ROOT/target/worktrees}"
BASE_BRANCH_DEFAULT="${BASE_BRANCH_DEFAULT:-main}"

usage() {
  cat <<'EOF'
Usage: scripts/work.sh <command> [args]

Commands:
  new --type <t> --summary <s> [--playbook <id>] [--id <id>] [--base <branch>]
  list [--open|--closed] [--type <t>] [--playbook <id>] [--limit <n>]
  show <id>
  search <text>
  path <id>
  start <id> [--base <branch>] [--park]
  verify <id> -- <command...>
  commit <id> -m <message>
  pr <id>
  pr-create <id>
  close <id>

Notes:
  - Work items live in docs/work/<id>.md and are meant to be committed.
  - start creates an isolated branch + git worktree under target/worktrees/<id>/.
  - For parallel work, keep the base checkout clean. By default, start refuses to run when the base checkout is dirty.
  - If you truly need it, --park will snapshot the dirty tree to a local park/<timestamp> branch, then return.
EOF
}

die() { echo "$*" >&2; exit 2; }

utc_iso() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

safe_slug() {
  local s="${1:-}"
  s="$(echo "$s" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-//;s/-$//')"
  echo "${s:0:48}"
}

ensure_dirs() {
  mkdir -p "$WORK_DIR" "$WORKTREES_DIR"
}

require_git_repo() {
  (cd "$ROOT" && git rev-parse --is-inside-work-tree >/dev/null 2>&1) || die "Not a git repo: $ROOT"
}

work_file() {
  local id="${1:-}"
  [ -n "$id" ] || die "Missing work id"
  echo "$WORK_DIR/$id.md"
}

work_get() {
  local id="$1" key="$2"
  local f
  f="$(work_file "$id")"
  [ -s "$f" ] || die "Work item not found: $f"
  grep -E "^work/${key}:" "$f" | head -n1 | sed -E "s/^work\/${key}:[[:space:]]*//"
}

work_set() {
  local id="$1" key="$2" value="$3"
  local f tmp
  f="$(work_file "$id")"
  [ -s "$f" ] || die "Work item not found: $f"
  tmp="$(mktemp)"
  if grep -qE "^work/${key}:" "$f"; then
    sed -E "s|^work/${key}:[[:space:]]*.*$|work/${key}: ${value}|" "$f" >"$tmp"
  else
    { echo "work/${key}: ${value}"; cat "$f"; } >"$tmp"
  fi
  mv "$tmp" "$f"
}

work_touch_updated() {
  local id="$1"
  work_set "$id" "updated_at" "$(utc_iso)"
}

cmd_new() {
  local type="" summary="" playbook="" id="" base="$BASE_BRANCH_DEFAULT"

  while [ $# -gt 0 ]; do
    case "$1" in
      --type) type="${2:-}"; shift 2 ;;
      --summary) summary="${2:-}"; shift 2 ;;
      --playbook) playbook="${2:-}"; shift 2 ;;
      --id) id="${2:-}"; shift 2 ;;
      --base) base="${2:-}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) die "Unknown arg: $1" ;;
    esac
  done

  [ -n "$type" ] || die "new requires --type"
  [ -n "$summary" ] || die "new requires --summary"
  case "$type" in
    change|governance|investigate|question|refactor|delete) ;;
    *) die "Unknown --type: $type (expected: change|governance|investigate|question|refactor|delete)" ;;
  esac

  ensure_dirs
  local now slug
  now="$(date -u +%Y%m%d-%H%M%S)"
  slug="$(safe_slug "$summary")"
  if [ -z "$id" ]; then
    id="${now}-${slug}"
  fi

  local f
  f="$(work_file "$id")"
  if [ -e "$f" ]; then
    die "Work item already exists: $f"
  fi

  cat >"$f" <<EOF
work/id: $id
work/type: $type
work/status: open
work/playbook: ${playbook}
work/summary: ${summary}
work/branch:
work/worktree:
work/base: ${base}
work/created_at: $(utc_iso)
work/updated_at: $(utc_iso)

# Notes

## Proof
- [ ] <fill in exact commands, ideally from the playbook>
EOF

  echo "$id"
}

cmd_list() {
  local want_status="" want_type="" want_playbook="" limit=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --open)
        [ -z "$want_status" ] || die "list: only one of --open/--closed may be set"
        want_status="open"
        shift
        ;;
      --closed)
        [ -z "$want_status" ] || die "list: only one of --open/--closed may be set"
        want_status="closed"
        shift
        ;;
      --type)
        want_type="${2:-}"
        [ -n "$want_type" ] || die "list: --type requires a value"
        shift 2
        ;;
      --playbook)
        want_playbook="${2:-}"
        [ -n "$want_playbook" ] || die "list: --playbook requires a value"
        shift 2
        ;;
      --limit)
        limit="${2:-}"
        [ -n "$limit" ] || die "list: --limit requires a value"
        shift 2
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        die "Unknown arg for list: $1"
        ;;
    esac
  done

  ensure_dirs
  local f
  (
    for f in "$WORK_DIR"/*.md; do
      [ -e "$f" ] || exit 0
      if [ "$(basename "$f")" = "README.md" ]; then
        continue
      fi
      if ! grep -qE "^work/id:" "$f"; then
        continue
      fi
      local id status type playbook summary
      id="$(grep -E "^work/id:" "$f" | head -n1 | sed -E 's/^work\/id:[[:space:]]*//')"
      if [[ "$id" == *"<"* ]]; then
        continue
      fi
      status="$(grep -E "^work/status:" "$f" | head -n1 | sed -E 's/^work\/status:[[:space:]]*//')"
      type="$(grep -E "^work/type:" "$f" | head -n1 | sed -E 's/^work\/type:[[:space:]]*//')"
      playbook="$(grep -E "^work/playbook:" "$f" | head -n1 | sed -E 's/^work\/playbook:[[:space:]]*//')"
      summary="$(grep -E "^work/summary:" "$f" | head -n1 | sed -E 's/^work\/summary:[[:space:]]*//')"

      if [ -n "$want_status" ] && [ "${status:-}" != "$want_status" ]; then
        continue
      fi
      if [ -n "$want_type" ] && [ "${type:-}" != "$want_type" ]; then
        continue
      fi
      if [ -n "$want_playbook" ] && [ "${playbook:-}" != "$want_playbook" ]; then
        continue
      fi

      printf "%s\t%s\t%s\t%s\t%s\n" "$id" "${status:-?}" "${type:-?}" "${playbook:-}" "${summary:-}"
    done
  ) | sort | { if [ -n "$limit" ]; then head -n "$limit"; else cat; fi; }
}

cmd_show() {
  local id="${1:-}"
  [ -n "$id" ] || die "show requires <id>"
  local f
  f="$(work_file "$id")"
  [ -s "$f" ] || die "Work item not found: $f"
  cat "$f"
}

cmd_search() {
  local q="${1:-}"
  [ -n "$q" ] || die "search requires <text>"
  ensure_dirs
  if command -v rg >/dev/null 2>&1; then
    rg -n --hidden --no-heading "$q" "$WORK_DIR" || true
  else
    grep -RIn "$q" "$WORK_DIR" || true
  fi
}

cmd_path() {
  local id="${1:-}"
  [ -n "$id" ] || die "path requires <id>"
  local wt
  wt="$(work_get "$id" "worktree")"
  [ -n "$wt" ] || die "Work item has no worktree yet. Run: scripts/work.sh start $id"
  echo "$wt"
}

park_if_dirty() {
  local reason="${1:-}"
  local orig_branch
  orig_branch="$(cd "$ROOT" && git rev-parse --abbrev-ref HEAD)"
  if [ -n "$(cd "$ROOT" && git status --porcelain)" ]; then
    local ts park_branch
    ts="$(date -u +%Y%m%d-%H%M%S)"
    park_branch="park/${ts}"
    echo "[work] parking dirty tree on ${park_branch} (${reason})" >&2
    (cd "$ROOT" && git switch -c "$park_branch" >/dev/null)
    (cd "$ROOT" && git add -A)
    if (cd "$ROOT" && git diff --cached --quiet); then
      echo "[work] nothing staged after add -A; leaving park branch without commit" >&2
    else
      (cd "$ROOT" && git commit -m "park: snapshot ${ts} ${reason}" >/dev/null)
    fi
    (cd "$ROOT" && git switch "$orig_branch" >/dev/null)
  fi
}

cmd_start() {
  local id="${1:-}"
  shift || true
  [ -n "$id" ] || die "start requires <id>"

  require_git_repo
  ensure_dirs

  local base="$BASE_BRANCH_DEFAULT"
  local do_park=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --base) base="${2:-}"; shift 2 ;;
      --park) do_park=1; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "Unknown arg: $1" ;;
    esac
  done

  local f
  f="$(work_file "$id")"
  [ -s "$f" ] || die "Work item not found: $f"

  if [ -n "$(cd "$ROOT" && git status --porcelain)" ]; then
    if [ "$do_park" -eq 1 ]; then
      park_if_dirty "before start ${id}"
    else
      die "Working tree is dirty; commit/stash/clean it first, or re-run with --park."
    fi
  fi

  local branch wt_path
  branch="work/${id}"
  wt_path="${WORKTREES_DIR}/${id}"

  if (cd "$ROOT" && git show-ref --verify --quiet "refs/heads/${branch}"); then
    :
  else
    (cd "$ROOT" && git branch "$branch" "$base" >/dev/null)
  fi

  if [ -d "$wt_path/.git" ] || (cd "$ROOT" && git worktree list --porcelain | grep -q "worktree ${wt_path}"); then
    echo "[work] worktree already exists: $wt_path" >&2
  else
    (cd "$ROOT" && git worktree add "$wt_path" "$branch" >/dev/null)
  fi

  work_set "$id" "branch" "$branch"
  work_set "$id" "worktree" "$wt_path"
  work_set "$id" "base" "$base"
  work_touch_updated "$id"

  echo "$wt_path"
}

cmd_verify() {
  local id="${1:-}"
  shift || true
  [ -n "$id" ] || die "verify requires <id>"
  [ "${1:-}" = "--" ] || die "verify requires -- <command...>"
  shift
  [ $# -gt 0 ] || die "verify requires a command after --"

  local wt
  wt="$(work_get "$id" "worktree")"
  [ -n "$wt" ] || die "Work item has no worktree yet. Run: scripts/work.sh start $id"
  [ -d "$wt" ] || die "Worktree path missing: $wt"

  (cd "$wt" && "$@")
}

cmd_commit() {
  local id="${1:-}"
  shift || true
  [ -n "$id" ] || die "commit requires <id>"
  local msg=""
  while [ $# -gt 0 ]; do
    case "$1" in
      -m) msg="${2:-}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) die "Unknown arg: $1" ;;
    esac
  done
  [ -n "$msg" ] || die "commit requires -m <message>"

  local wt
  wt="$(work_get "$id" "worktree")"
  [ -n "$wt" ] || die "Work item has no worktree yet. Run: scripts/work.sh start $id"

  (cd "$wt" && git add -A)
  if (cd "$wt" && git diff --cached --quiet); then
    die "No changes staged to commit in worktree: $wt"
  fi
  (cd "$wt" && git commit -m "$msg")
  work_touch_updated "$id"
}

cmd_pr() {
  local id="${1:-}"
  [ -n "$id" ] || die "pr requires <id>"
  local wt branch base summary
  wt="$(work_get "$id" "worktree")"
  branch="$(work_get "$id" "branch")"
  base="$(work_get "$id" "base")"
  summary="$(work_get "$id" "summary")"
  [ -n "$wt" ] || die "Work item has no worktree yet. Run: scripts/work.sh start $id"
  [ -n "$branch" ] || die "Work item has no branch yet. Run: scripts/work.sh start $id"
  [ -n "$base" ] || base="$BASE_BRANCH_DEFAULT"

  local remote repo compare_url
  remote="$(cd "$wt" && git remote get-url origin 2>/dev/null || true)"
  repo=""
  if [[ "$remote" =~ ^git@github\.com:(.+)\.git$ ]]; then
    repo="${BASH_REMATCH[1]}"
  elif [[ "$remote" =~ ^git@github\.com:(.+)$ ]]; then
    repo="${BASH_REMATCH[1]}"
  elif [[ "$remote" =~ ^https://github\.com/(.+)\.git$ ]]; then
    repo="${BASH_REMATCH[1]}"
  elif [[ "$remote" =~ ^https://github\.com/(.+)$ ]]; then
    repo="${BASH_REMATCH[1]}"
  fi
  compare_url=""
  if [ -n "$repo" ]; then
    compare_url="https://github.com/${repo}/compare/${base}...${branch}?expand=1"
  fi

  cat <<EOF
Push:
  (cd $wt && git push -u origin $branch)

PR:
  gh pr create --fill --base $base --head $branch
  ${compare_url:-"(open a PR via the GitHub UI for base=$base head=$branch)"}

Title suggestion:
  $summary
EOF
}

cmd_pr_create() {
  local id="${1:-}"
  [ -n "$id" ] || die "pr-create requires <id>"

  local wt branch base summary
  wt="$(work_get "$id" "worktree")"
  branch="$(work_get "$id" "branch")"
  base="$(work_get "$id" "base")"
  summary="$(work_get "$id" "summary")"
  [ -n "$wt" ] || die "Work item has no worktree yet. Run: scripts/work.sh start $id"
  [ -n "$branch" ] || die "Work item has no branch yet. Run: scripts/work.sh start $id"
  [ -n "$base" ] || base="$BASE_BRANCH_DEFAULT"

  if ! (cd "$wt" && git rev-parse --is-inside-work-tree >/dev/null 2>&1); then
    die "Worktree path missing or not a git checkout: $wt"
  fi

  # Load token into $GITHUB_TOKEN (does not persist).
  # shellcheck source=/dev/null
  source "$ROOT/scripts/load_github_token.sh" >/dev/null || true
  [ -n "${GITHUB_TOKEN:-}" ] || die "GITHUB_TOKEN not loaded"

  if ! curl -fsS -H "Authorization: Bearer ${GITHUB_TOKEN}" https://api.github.com/user >/dev/null; then
    die "GitHub token validation failed (revoke and re-issue; then store it as github/token)"
  fi

  (cd "$wt" && git push -u origin "$branch")

  local remote repo compare_url
  remote="$(cd "$wt" && git remote get-url origin 2>/dev/null || true)"
  repo=""
  if [[ "$remote" =~ ^git@github\.com:(.+)\.git$ ]]; then
    repo="${BASH_REMATCH[1]}"
  elif [[ "$remote" =~ ^git@github\.com:(.+)$ ]]; then
    repo="${BASH_REMATCH[1]}"
  elif [[ "$remote" =~ ^https://github\.com/(.+)\.git$ ]]; then
    repo="${BASH_REMATCH[1]}"
  elif [[ "$remote" =~ ^https://github\.com/(.+)$ ]]; then
    repo="${BASH_REMATCH[1]}"
  fi
  [ -n "$repo" ] || die "Unable to infer GitHub repo from origin remote: ${remote:-<none>}"

  compare_url="https://github.com/${repo}/compare/${base}...${branch}?expand=1"

  local payload resp http_code json pr_url owner
  owner="${repo%%/*}"
  payload="$(DW_PR_TITLE="$summary" DW_PR_HEAD="$branch" DW_PR_BASE="$base" DW_PR_WORK_ID="$id" \
    python3 - <<'PY'
import json, os
title = (os.environ.get("DW_PR_TITLE") or "").strip() or (os.environ.get("DW_PR_WORK_ID") or "").strip() or "Update"
head = (os.environ.get("DW_PR_HEAD") or "").strip()
base = (os.environ.get("DW_PR_BASE") or "").strip()
work_id = (os.environ.get("DW_PR_WORK_ID") or "").strip()
print(json.dumps({
  "title": title,
  "head": head,
  "base": base,
  "body": f"Work item: {work_id}",
}))
PY
  )"

  resp="$(curl -sS -H "Authorization: Bearer ${GITHUB_TOKEN}" \
               -H "Accept: application/vnd.github+json" \
               -d "$payload" \
               -w "\n%{http_code}" \
               "https://api.github.com/repos/${repo}/pulls" || true)"
  http_code="$(printf "%s" "$resp" | tail -n1 | tr -d '\r')"
  json="$(printf "%s" "$resp" | sed '$d')"

  pr_url="$(python3 - <<PY
import json, sys
data = {}
try:
  data = json.loads(sys.stdin.read() or "{}")
except Exception:
  print("")
  raise SystemExit(0)
print(data.get("html_url","") or "")
PY
<<<"$json"
)"
  if [[ -z "$pr_url" ]]; then
    pr_url="$(printf "%s" "$json" | sed -nE 's/.*"html_url"[[:space:]]*:[[:space:]]*"([^"]+)".*/\\1/p' | head -n1)"
  fi

  if [[ "$http_code" == "201" && -n "$pr_url" ]]; then
    work_set "$id" "pr_url" "$pr_url"
    work_touch_updated "$id"
    echo "$pr_url"
    return 0
  fi

  if [[ "$http_code" == "422" ]]; then
    pr_url="$(curl -sS -H "Authorization: Bearer ${GITHUB_TOKEN}" \
                   -H "Accept: application/vnd.github+json" \
                   --get \
                   --data-urlencode "head=${owner}:${branch}" \
                   --data-urlencode "base=${base}" \
                   --data-urlencode "state=open" \
                   "https://api.github.com/repos/${repo}/pulls" \
      | python3 - <<PY
import json, sys
try:
  data = json.loads(sys.stdin.read() or "[]")
except Exception:
  data = []
if isinstance(data, list) and data:
  print(data[0].get("html_url","") or "")
else:
  print("")
PY
)"
    if [[ -n "$pr_url" ]]; then
      work_set "$id" "pr_url" "$pr_url"
      work_touch_updated "$id"
      echo "$pr_url"
      return 0
    fi
  fi

  echo "Failed to create PR (HTTP ${http_code:-?})." >&2
  echo "Compare URL: $compare_url" >&2
  echo "Response: $json" >&2
  return 2
}

cmd_close() {
  local id="${1:-}"
  [ -n "$id" ] || die "close requires <id>"
  local f
  f="$(work_file "$id")"
  [ -s "$f" ] || die "Work item not found: $f"
  work_set "$id" "status" "closed"
  work_touch_updated "$id"
}

cmd="${1:-}"
shift || true
case "$cmd" in
  new) cmd_new "$@" ;;
  list) cmd_list ;;
  show) cmd_show "${1:-}" ;;
  search) cmd_search "${1:-}" ;;
  path) cmd_path "${1:-}" ;;
  start) cmd_start "${1:-}" "${@:2}" ;;
  verify) cmd_verify "${1:-}" "${@:2}" ;;
  commit) cmd_commit "${1:-}" "${@:2}" ;;
  pr) cmd_pr "${1:-}" ;;
  pr-create) cmd_pr_create "${1:-}" ;;
  close) cmd_close "${1:-}" ;;
  -h|--help|help|"") usage ;;
  *) die "Unknown command: $cmd" ;;
esac

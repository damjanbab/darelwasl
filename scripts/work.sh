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
  new --type <t> --summary <s> [--playbook <id>] [--id <id>] [--base <branch>] [--prereq <work-id>]... [--lock <name>]
  list [--open|--closed] [--type <t>] [--playbook <id>] [--limit <n>]
  show <id>
  search <text>
  path <id>
  audit [--into <ref>] [--no-fetch] [--strict]
  start <id> [--base <branch>]
  verify <id> -- <command...>
  commit <id> -m <message>
  pr <id>
  pr-create <id>
  close-merged [--into <ref>] [--no-fetch] [--dry-run]
  close <id>

Notes:
  - Work items live in docs/work/<id>.md and are meant to be committed.
  - start creates an isolated branch + git worktree under target/worktrees/<id>/.
  - Work items may exist only on their work branch until merged; show/list will read from work/<id> or origin/work/<id> when needed.
EOF
}

die() { echo "$*" >&2; exit 2; }

utc_iso() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

pick_base_ref() {
  local want="${1:-}"
  if [ -n "$want" ]; then
    echo "$want"
    return 0
  fi
  if git_ref_exists "refs/remotes/origin/${BASE_BRANCH_DEFAULT}"; then
    echo "origin/${BASE_BRANCH_DEFAULT}"
    return 0
  fi
  echo "${BASE_BRANCH_DEFAULT}"
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

git_ref_exists() {
  local ref="${1:-}"
  [ -n "$ref" ] || return 1
  (cd "$ROOT" && git show-ref --verify --quiet "$ref")
}

resolve_work_branch_ref() {
  local id="${1:-}"
  [ -n "$id" ] || return 1
  local branch remote_ref local_ref
  branch="work/${id}"
  local_ref="refs/heads/${branch}"
  remote_ref="refs/remotes/origin/${branch}"
  if git_ref_exists "$local_ref"; then
    echo "${branch}"
    return 0
  fi
  if git_ref_exists "$remote_ref"; then
    echo "origin/${branch}"
    return 0
  fi
  echo ""
}

work_file() {
  local id="${1:-}"
  [ -n "$id" ] || die "Missing work id"
  echo "$WORK_DIR/$id.md"
}

work_read() {
  local id="$1"
  local ref
  ref="$(resolve_work_branch_ref "$id")"
  if [ -n "${ref:-}" ]; then
    (cd "$ROOT" && git show "${ref}:docs/work/${id}.md" 2>/dev/null) && return 0
  fi
  local f
  f="$(work_file "$id")"
  if [ -s "$f" ]; then
    cat "$f"
    return 0
  fi
  die "Work item not found: docs/work/${id}.md (local or in work/${id})"
}

work_get() {
  local id="$1" key="$2"
  work_read "$id" | grep -E "^work/${key}:" | head -n1 | sed -E "s/^work\/${key}:[[:space:]]*//"
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

work_touch_closed() {
  local id="$1"
  if grep -qE "^work/closed_at:" "$(work_file "$id")"; then
    work_set "$id" "closed_at" "$(utc_iso)"
  else
    work_set "$id" "closed_at" "$(utc_iso)"
  fi
}

cmd_new() {
  local type="" summary="" playbook="" id="" base="$BASE_BRANCH_DEFAULT" lock=""
  local prereqs=()

  while [ $# -gt 0 ]; do
    case "$1" in
      --type) type="${2:-}"; shift 2 ;;
      --summary) summary="${2:-}"; shift 2 ;;
      --playbook) playbook="${2:-}"; shift 2 ;;
      --id) id="${2:-}"; shift 2 ;;
      --base) base="${2:-}"; shift 2 ;;
      --prereq) prereqs+=("${2:-}"); shift 2 ;;
      --lock) lock="${2:-}"; shift 2 ;;
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

  require_git_repo

  local base_ref branch wt_path
  base_ref="$(pick_base_ref "$base")"
  branch="work/${id}"
  wt_path="${WORKTREES_DIR}/${id}"

  if git_ref_exists "refs/heads/${branch}"; then
    die "Work branch already exists: ${branch}"
  fi
  if git_ref_exists "refs/remotes/origin/${branch}"; then
    die "Remote work branch already exists: origin/${branch}"
  fi

  if [ -e "$wt_path/.git" ] || (cd "$ROOT" && git worktree list --porcelain | grep -q "worktree ${wt_path}"); then
    die "Worktree path already exists: $wt_path"
  fi

  (cd "$ROOT" && git worktree add -b "$branch" "$wt_path" "$base_ref" >/dev/null)

  mkdir -p "$wt_path/docs/work"
  local f
  f="$wt_path/docs/work/$id.md"
  if [ -e "$f" ]; then
    die "Work item already exists in worktree: $f"
  fi

  local prereqs_json prereqs_lines
  prereqs_lines="$(printf "%s\n" "${prereqs[@]:-}" | sed '/^$/d' || true)"
  prereqs_json="$(
    DW_PREREQS="$prereqs_lines" python3 - <<'PY'
import json, os
raw = os.environ.get("DW_PREREQS", "")
items = [s.strip() for s in raw.split("\n") if s.strip()]
print(json.dumps(items))
PY
  )"

  cat >"$f" <<EOF
work/id: $id
work/type: $type
work/status: open
work/playbook: ${playbook}
work/summary: ${summary}
work/branch: ${branch}
work/worktree: target/worktrees/${id}
work/base: ${base}
work/prereqs: ${prereqs_json}
work/lock: ${lock}
work/created_at: $(utc_iso)
work/updated_at: $(utc_iso)

# Notes

## Proof
- [ ] <fill in exact commands, ideally from the playbook>
EOF

  (cd "$wt_path" && git add "docs/work/$id.md" && git commit -m "work: ${id}" >/dev/null)

  echo "[work] created ${branch} at ${wt_path}" >&2
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
  (
    declare -A seen

    emit_from_text() {
      local text="$1"
      local id status type playbook summary
      id="$(printf "%s" "$text" | sed -nE 's/^work\/id:[[:space:]]*//p' | head -n1)"
      [ -n "${id:-}" ] || return 0
      if [[ "$id" == *"<"* ]]; then
        return 0
      fi
      if [[ -n "${seen[$id]:-}" ]]; then
        return 0
      fi
      seen["$id"]=1

      status="$(printf "%s" "$text" | sed -nE 's/^work\/status:[[:space:]]*//p' | head -n1)"
      type="$(printf "%s" "$text" | sed -nE 's/^work\/type:[[:space:]]*//p' | head -n1)"
      playbook="$(printf "%s" "$text" | sed -nE 's/^work\/playbook:[[:space:]]*//p' | head -n1)"
      summary="$(printf "%s" "$text" | sed -nE 's/^work\/summary:[[:space:]]*//p' | head -n1)"

      if [ -n "$want_status" ] && [ "${status:-}" != "$want_status" ]; then
        return 0
      fi
      if [ -n "$want_type" ] && [ "${type:-}" != "$want_type" ]; then
        return 0
      fi
      if [ -n "$want_playbook" ] && [ "${playbook:-}" != "$want_playbook" ]; then
        return 0
      fi

      printf "%s\t%s\t%s\t%s\t%s\n" "$id" "${status:-?}" "${type:-?}" "${playbook:-}" "${summary:-}"
    }

    local f
    for f in "$WORK_DIR"/*.md; do
      [ -e "$f" ] || break
      if [ "$(basename "$f")" = "README.md" ]; then
        continue
      fi
      if ! grep -qE "^work/id:" "$f"; then
        continue
      fi
      emit_from_text "$(cat "$f")"
    done

    local b id text
    while IFS= read -r b; do
      [ -n "$b" ] || continue
      id="${b#work/}"
      text="$(cd "$ROOT" && git show "${b}:docs/work/${id}.md" 2>/dev/null || true)"
      [ -n "${text:-}" ] || continue
      emit_from_text "$text"
    done < <((cd "$ROOT" && git for-each-ref 'refs/heads/work' --format='%(refname:short)' | sort) || true)

    while IFS= read -r b; do
      [ -n "$b" ] || continue
      id="${b#origin/work/}"
      text="$(cd "$ROOT" && git show "${b}:docs/work/${id}.md" 2>/dev/null || true)"
      [ -n "${text:-}" ] || continue
      emit_from_text "$text"
    done < <((cd "$ROOT" && git for-each-ref 'refs/remotes/origin/work' --format='%(refname:short)' | sort) || true)
  ) | sort | { if [ -n "$limit" ]; then head -n "$limit"; else cat; fi; }
}

cmd_show() {
  local id="${1:-}"
  [ -n "$id" ] || die "show requires <id>"
  work_read "$id"
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
  wt="$(work_get "$id" "worktree" | tr -d '\r' | xargs || true)"
  if [ -z "${wt:-}" ]; then
    wt="target/worktrees/${id}"
  fi
  if [[ "$wt" != /* ]]; then
    wt="${COMMON_ROOT}/${wt}"
  fi
  [ -d "$wt" ] || die "Worktree path missing: $wt (run: scripts/work.sh start $id)"
  echo "$wt"
}

cmd_start() {
  local id="${1:-}"
  shift || true
  [ -n "$id" ] || die "start requires <id>"

  require_git_repo
  ensure_dirs

  local base="$BASE_BRANCH_DEFAULT"
  while [ $# -gt 0 ]; do
    case "$1" in
      --base) base="${2:-}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) die "Unknown arg: $1" ;;
    esac
  done

  local base_ref branch wt_path
  base_ref="$(pick_base_ref "$base")"
  branch="work/${id}"
  wt_path="${WORKTREES_DIR}/${id}"

  if git_ref_exists "refs/heads/${branch}"; then
    :
  elif git_ref_exists "refs/remotes/origin/${branch}"; then
    (cd "$ROOT" && git branch --track "$branch" "origin/${branch}" >/dev/null)
  else
    (cd "$ROOT" && git branch "$branch" "$base_ref" >/dev/null)
  fi

  if [ -e "$wt_path/.git" ] || (cd "$ROOT" && git worktree list --porcelain | grep -q "worktree ${wt_path}"); then
    :
  else
    (cd "$ROOT" && git worktree add "$wt_path" "$branch" >/dev/null)
  fi

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
  wt="$(cmd_path "$id")"
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
  wt="$(cmd_path "$id")"

  (cd "$wt" && git add -A)
  if (cd "$wt" && git diff --cached --quiet); then
    die "No changes staged to commit in worktree: $wt"
  fi
  (cd "$wt" && git commit -m "$msg")
}

cmd_audit() {
  local into="" do_fetch=1 strict=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --into) into="${2:-}"; shift 2 ;;
      --no-fetch) do_fetch=0; shift ;;
      --strict) strict=1; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "audit: unknown arg: $1" ;;
    esac
  done

  require_git_repo
  ensure_dirs

  if [ "$do_fetch" -eq 1 ]; then
    (cd "$ROOT" && git fetch origin --prune >/dev/null 2>&1 || true)
  fi

  local into_ref
  into_ref="$(pick_base_ref "$into")"

  local issues=0
  echo "[work] audit: into=$into_ref"

  local remote_branch_count=0 remote_open_count=0 remote_merged_count=0 remote_missing_workfile=0
  while IFS= read -r b; do
    [ -n "$b" ] || continue
    remote_branch_count=$((remote_branch_count + 1))
    local id
    id="${b#origin/work/}"
    if !(cd "$ROOT" && git show "${b}:docs/work/${id}.md" >/dev/null 2>&1); then
      echo "[work] warning: remote branch missing workfile: $b (expected docs/work/$id.md in that branch)"
      remote_missing_workfile=$((remote_missing_workfile + 1))
      issues=$((issues + 1))
    fi
    if (cd "$ROOT" && git merge-base --is-ancestor "$b" "$into_ref" >/dev/null 2>&1); then
      remote_merged_count=$((remote_merged_count + 1))
    else
      remote_open_count=$((remote_open_count + 1))
      echo "[work] open: $b"
    fi
  done < <((cd "$ROOT" && git for-each-ref 'refs/remotes/origin/work' --format='%(refname:short)' | sort) || true)

  local open_items=0 stale_open_merged=0 open_missing_branch=0
  local f
  for f in "$WORK_DIR"/*.md; do
    [ -e "$f" ] || break
    if [ "$(basename "$f")" = "README.md" ]; then
      continue
    fi
    if ! grep -qE "^work/id:" "$f"; then
      continue
    fi
    local id status
    id="$(grep -E "^work/id:" "$f" | head -n1 | sed -E 's/^work\/id:[[:space:]]*//')"
    status="$(grep -E "^work/status:" "$f" | head -n1 | sed -E 's/^work\/status:[[:space:]]*//')"
    if [ "$status" != "open" ]; then
      continue
    fi
    open_items=$((open_items + 1))

    local ref
    ref="$(resolve_work_branch_ref "$id")"
    if [ -z "${ref:-}" ]; then
      echo "[work] warning: open work item has no branch ref: $id (set work/branch or create origin/work/$id)"
      open_missing_branch=$((open_missing_branch + 1))
      issues=$((issues + 1))
      continue
    fi

    if (cd "$ROOT" && git merge-base --is-ancestor "$ref" "$into_ref" >/dev/null 2>&1); then
      echo "[work] warning: open work item is already merged: $id (branch=$ref into=$into_ref)"
      stale_open_merged=$((stale_open_merged + 1))
      issues=$((issues + 1))
    fi
  done

  echo "[work] audit summary:"
  echo "  remote branches: $remote_branch_count (merged=$remote_merged_count open=$remote_open_count missing-workfile=$remote_missing_workfile)"
  echo "  open work items: $open_items (open-but-merged=$stale_open_merged open-missing-branch=$open_missing_branch)"

  if [ "$strict" -eq 1 ] && [ "$issues" -ne 0 ]; then
    return 2
  fi
  return 0
}

cmd_close_merged() {
  local into="" do_fetch=1 dry_run=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --into) into="${2:-}"; shift 2 ;;
      --no-fetch) do_fetch=0; shift ;;
      --dry-run) dry_run=1; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "close-merged: unknown arg: $1" ;;
    esac
  done

  require_git_repo
  ensure_dirs

  if [ "$do_fetch" -eq 1 ]; then
    (cd "$ROOT" && git fetch origin --prune >/dev/null 2>&1 || true)
  fi

  local into_ref
  into_ref="$(pick_base_ref "$into")"
  echo "[work] close-merged: into=$into_ref dry-run=$dry_run"

  local closed=0
  local f
  for f in "$WORK_DIR"/*.md; do
    [ -e "$f" ] || break
    if [ "$(basename "$f")" = "README.md" ]; then
      continue
    fi
    if ! grep -qE "^work/id:" "$f"; then
      continue
    fi
    local id status
    id="$(grep -E "^work/id:" "$f" | head -n1 | sed -E 's/^work\/id:[[:space:]]*//')"
    status="$(grep -E "^work/status:" "$f" | head -n1 | sed -E 's/^work\/status:[[:space:]]*//')"
    if [ "$status" != "open" ]; then
      continue
    fi

    local ref
    ref="$(resolve_work_branch_ref "$id")"
    if [ -z "${ref:-}" ]; then
      continue
    fi
    if (cd "$ROOT" && git merge-base --is-ancestor "$ref" "$into_ref" >/dev/null 2>&1); then
      if [ "$dry_run" -eq 1 ]; then
        echo "[work] would close: $id (branch=$ref merged into $into_ref)"
      else
        work_set "$id" "status" "closed"
        work_touch_closed "$id"
        work_touch_updated "$id"
        echo "[work] closed: $id"
      fi
      closed=$((closed + 1))
    fi
  done

  echo "[work] close-merged: affected=$closed"
}

cmd_pr() {
  local id="${1:-}"
  [ -n "$id" ] || die "pr requires <id>"
  local wt branch base summary
  wt="$(cmd_path "$id")"
  branch="$(work_get "$id" "branch" | tr -d '\r' | xargs || true)"
  [ -n "${branch:-}" ] || branch="work/${id}"
  base="$(work_get "$id" "base" | tr -d '\r' | xargs || true)"
  [ -n "${base:-}" ] || base="$BASE_BRANCH_DEFAULT"
  summary="$(work_get "$id" "summary")"

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
  wt="$(cmd_path "$id")"
  branch="$(work_get "$id" "branch" | tr -d '\r' | xargs || true)"
  [ -n "${branch:-}" ] || branch="work/${id}"
  base="$(work_get "$id" "base" | tr -d '\r' | xargs || true)"
  [ -n "${base:-}" ] || base="$BASE_BRANCH_DEFAULT"
  summary="$(work_get "$id" "summary")"

  if ! (cd "$wt" && git rev-parse --is-inside-work-tree >/dev/null 2>&1); then
    die "Worktree path missing or not a git checkout: $wt"
  fi

  # Load token into $GITHUB_TOKEN (does not persist).
  # shellcheck source=/dev/null
  source "$ROOT/scripts/load_github_token.sh" >/dev/null || true
  [ -n "${GITHUB_TOKEN:-}" ] || die "GITHUB_TOKEN not loaded (store it: scripts/secrets.sh set github/token)"

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
    pr_url="$(printf "%s" "$json" | sed -nE 's/.*"html_url"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' | head -n1)"
  fi

  if [[ "$http_code" == "201" && -n "$pr_url" ]]; then
    local wf
    wf="docs/work/${id}.md"
    if [ -s "$wt/$wf" ]; then
      # Record PR metadata in the work branch itself.
      WORK_DIR="$wt/docs/work" work_set "$id" "pr_url" "$pr_url"
      WORK_DIR="$wt/docs/work" work_touch_updated "$id"
      (cd "$wt" && git add "$wf")
      if !(cd "$wt" && git diff --cached --quiet); then
        (cd "$wt" && git commit -m "work: record pr_url" >/dev/null)
        (cd "$wt" && git push >/dev/null)
      fi
    fi
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
      local wf
      wf="docs/work/${id}.md"
      if [ -s "$wt/$wf" ]; then
        WORK_DIR="$wt/docs/work" work_set "$id" "pr_url" "$pr_url"
        WORK_DIR="$wt/docs/work" work_touch_updated "$id"
        (cd "$wt" && git add "$wf")
        if !(cd "$wt" && git diff --cached --quiet); then
          (cd "$wt" && git commit -m "work: record pr_url" >/dev/null)
          (cd "$wt" && git push >/dev/null)
        fi
      fi
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
  audit) cmd_audit "$@" ;;
  start) cmd_start "${1:-}" "${@:2}" ;;
  verify) cmd_verify "${1:-}" "${@:2}" ;;
  commit) cmd_commit "${1:-}" "${@:2}" ;;
  pr) cmd_pr "${1:-}" ;;
  pr-create) cmd_pr_create "${1:-}" ;;
  close-merged) cmd_close_merged "$@" ;;
  close) cmd_close "${1:-}" ;;
  -h|--help|help|"") usage ;;
  *) die "Unknown command: $cmd" ;;
esac

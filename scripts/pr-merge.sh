#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  cat <<'EOF'
Usage: scripts/pr-merge.sh <cmd> [args...]

Commands:
  merge --pr <url|number> [--method merge|squash|rebase] [--resolve]
  merge-work <work-id> [--method ...] [--resolve]
  poll [--prefix work/] [--method ...] [--resolve]

Environment:
  GITHUB_TOKEN                     Required.
  DEPLOY_APPROVED=1                Required to actually merge into main.
  DARELWASL_WORK_MODEL=<model>     Used for conflict resolution (default gpt-5.2).

Notes:
  - Conflict resolution is best-effort: rebase PR branch onto base, resolve conflicts
    via scripts/git-resolve-conflicts.py, force-push with lease, then merge.
EOF
}

die() { echo "error: $*" >&2; exit 2; }

require_token() {
  if [[ -z "${GITHUB_TOKEN:-}" ]]; then
    if [[ -s "$ROOT/scripts/load_github_token.sh" ]]; then
      # shellcheck disable=SC1090
      source "$ROOT/scripts/load_github_token.sh" >/dev/null 2>&1 || true
    fi
  fi
  [[ -n "${GITHUB_TOKEN:-}" ]] || die "Missing GITHUB_TOKEN (set env or store github/token in vault)."
}

require_merge_approved() {
  [[ "${DEPLOY_APPROVED:-}" == "1" ]] || die "Refusing to merge without DEPLOY_APPROVED=1"
}

repo_slug() {
  local remote
  remote="$(git -C "$ROOT" remote get-url origin 2>/dev/null || true)"
  if [[ "$remote" =~ ^git@github\.com:(.+)\.git$ ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  if [[ "$remote" =~ ^git@github\.com:(.+)$ ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  if [[ "$remote" =~ ^https://github\.com/(.+)\.git$ ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  if [[ "$remote" =~ ^https://github\.com/(.+)$ ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  die "Unable to determine GitHub repo from origin remote: $remote"
}

api() {
  local method="$1" url="$2" data="${3:-}"
  if [[ -n "$data" ]]; then
    curl -fsS -X "$method" \
      -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      -H "Content-Type: application/json" \
      -d "$data" \
      "$url"
  else
    curl -fsS -X "$method" \
      -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      "$url"
  fi
}

pr_number_from_arg() {
  local raw="$1"
  if [[ "$raw" =~ ^[0-9]+$ ]]; then
    echo "$raw"
    return 0
  fi
  if [[ "$raw" =~ /pull/([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  die "Invalid PR identifier: $raw"
}

get_pr_json() {
  local n="$1" repo
  repo="$(repo_slug)"
  api GET "https://api.github.com/repos/${repo}/pulls/${n}"
}

wait_mergeable() {
  local n="$1" tries="${2:-12}"
  local i json mergeable
  for i in $(seq 1 "$tries"); do
    json="$(get_pr_json "$n")" || return 1
    mergeable="$(printf "%s" "$json" | python3 -c 'import json,sys; d=json.load(sys.stdin); m=d.get("mergeable", None); print("" if m is None else ("true" if m else "false"))')"
    if [[ -n "$mergeable" ]]; then
      printf "%s" "$json"
      return 0
    fi
    sleep 1
  done
  printf "%s" "$json"
  return 0
}

merge_api() {
  local n="$1" method="$2"
  local repo
  repo="$(repo_slug)"
  require_merge_approved
  api PUT "https://api.github.com/repos/${repo}/pulls/${n}/merge" "$(printf '{"merge_method":"%s"}' "$method")"
}

tmp_merge_worktree() {
  local n="$1"
  echo "$ROOT/target/merge-agent/pr-$n"
}

cleanup_worktree() {
  local wt="$1"
  # In git worktrees, `.git` is typically a file (not a directory). Always try to
  # deregister the worktree path, even if the directory is already gone, so we
  # don't leave stale worktree entries behind.
  git -C "$ROOT" worktree remove --force "$wt" >/dev/null 2>&1 || true
  rm -rf "$wt" >/dev/null 2>&1 || true
}

rebase_with_resolution() {
  local n="$1" head_ref="$2" base_ref="$3"
  local wt tmp_branch
  wt="$(tmp_merge_worktree "$n")"
  tmp_branch="merge-agent/pr-$n"

  mkdir -p "$(dirname "$wt")"
  cleanup_worktree "$wt"

  git -C "$ROOT" fetch origin --prune >/dev/null 2>&1 || true
  git -C "$ROOT" fetch origin "$head_ref":"refs/heads/$tmp_branch" >/dev/null 2>&1
  git -C "$ROOT" worktree add -B "$tmp_branch" "$wt" "$tmp_branch" >/dev/null 2>&1

  # Rebase onto latest base.
  git -C "$wt" fetch origin --prune >/dev/null 2>&1 || true
  if git -C "$wt" rebase "origin/$base_ref" >/dev/null 2>&1; then
    :
  else
    # Best-effort resolve loop.
    local loops=0
    while true; do
      loops=$((loops + 1))
      if [[ "$loops" -gt 25 ]]; then
        echo "merge-agent: too many conflict resolution loops" >&2
        return 1
      fi
      if ! python3 "$ROOT/scripts/git-resolve-conflicts.py" --worktree "$wt"; then
        return 1
      fi
      if git -C "$wt" rebase --continue >/dev/null 2>&1; then
        break
      fi
      # If it still isn't continuing, keep looping (more conflicts).
      if git -C "$wt" status --porcelain | grep -q '^UU '; then
        continue
      fi
      # If rebase ended for some other reason, bail.
      if ! git -C "$wt" rebase --show-current-patch >/dev/null 2>&1; then
        break
      fi
    done
  fi

  # Force push with lease back to the PR branch.
  git -C "$wt" push --force-with-lease origin "HEAD:$head_ref" >/dev/null 2>&1
  return 0
}

cmd_merge() {
  require_token
  local pr="" method="merge" resolve=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --pr) pr="${2:-}"; shift 2 ;;
      --method) method="${2:-}"; shift 2 ;;
      --resolve) resolve=1; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "merge: unknown arg: $1" ;;
    esac
  done
  [[ -n "$pr" ]] || die "merge requires --pr <url|number>"
  local n; n="$(pr_number_from_arg "$pr")"

  local json; json="$(wait_mergeable "$n")"
  local head_ref base_ref
  head_ref="$(printf "%s" "$json" | python3 -c 'import json,sys; d=json.load(sys.stdin); print((d.get("head") or {}).get("ref",""))')"
  base_ref="$(printf "%s" "$json" | python3 -c 'import json,sys; d=json.load(sys.stdin); print((d.get("base") or {}).get("ref","main"))')"
  [[ -n "$head_ref" ]] || die "Unable to read PR head ref"

  if merge_api "$n" "$method" >/dev/null 2>&1; then
    echo "merged: pr#$n"
    return 0
  fi

  if [[ "$resolve" -ne 1 ]]; then
    die "merge failed for pr#$n (try --resolve)"
  fi

  echo "merge-agent: attempting rebase+resolve for pr#$n head=$head_ref base=$base_ref" >&2
  rebase_with_resolution "$n" "$head_ref" "$base_ref" || die "merge-agent: rebase+resolve failed for pr#$n"

  merge_api "$n" "$method" >/dev/null
  echo "merged: pr#$n (after resolve)"
}

cmd_merge_work() {
  require_token
  local id="${1:-}"; shift || true
  [[ -n "$id" ]] || die "merge-work requires <work-id>"
  local method="merge" resolve=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --method) method="${2:-}"; shift 2 ;;
      --resolve) resolve=1; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "merge-work: unknown arg: $1" ;;
    esac
  done

  local pr_url
  pr_url="$(scripts/work.sh show "$id" | sed -nE 's/^work\/pr_url:[[:space:]]*//p' | head -n1)"
  [[ -n "$pr_url" ]] || die "No work/pr_url recorded for $id"
  cmd_merge --pr "$pr_url" --method "$method" $( [[ "$resolve" -eq 1 ]] && printf "%s" "--resolve" )
}

cmd_poll() {
  require_token
  local prefix="work/" method="merge" resolve=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --prefix) prefix="${2:-}"; shift 2 ;;
      --method) method="${2:-}"; shift 2 ;;
      --resolve) resolve=1; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "poll: unknown arg: $1" ;;
    esac
  done

  require_merge_approved
  local repo; repo="$(repo_slug)"
  prs="$(api GET "https://api.github.com/repos/${repo}/pulls?state=open&per_page=100")"
  while IFS= read -r num; do
    [[ -n "$num" ]] || continue
    cmd_merge --pr "$num" --method "$method" $( [[ "$resolve" -eq 1 ]] && printf "%s" "--resolve" ) || true
  done < <(printf "%s" "$prs" | python3 -c 'import json,sys
prefix=sys.argv[1]
data=json.loads(sys.stdin.read() or "[]")
if not isinstance(data, list):
  raise SystemExit(0)
for pr in data:
  head=((pr.get("head") or {}).get("ref") or "")
  if head.startswith(prefix):
    n=pr.get("number")
    if isinstance(n, int):
      print(n)
' "$prefix")
}

cmd="${1:-}"
shift || true
case "$cmd" in
  merge) cmd_merge "$@" ;;
  merge-work) cmd_merge_work "$@" ;;
  poll) cmd_poll "$@" ;;
  -h|--help|help|"") usage ;;
  *) die "Unknown command: $cmd" ;;
esac

#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  cat <<'EOF'
Usage: scripts/github-token.sh <cmd> [args...]

Commands:
  validate [--file PATH] [--token-env VAR]
  install --file PATH [--env-file PATH] [--restart darelwasl.service]
  install-from-lab [--lab stable|canary|session:N] [--name token.txt] [--env-file PATH] [--restart darelwasl.service]

Notes:
  - This script never prints the token value.
  - Validation uses: GET https://api.github.com/user
  - Default env-file: /etc/darelwasl/app.env
EOF
}

die() { echo "error: $*" >&2; exit 2; }

lab_inbox_path() {
  local lab="${1:-stable}"
  case "$lab" in
    stable) "$ROOT/scripts/lab.sh" --stable paths | sed -nE 's/^inbox=//p' | tail -n 1 ;;
    canary) "$ROOT/scripts/lab.sh" --canary paths | sed -nE 's/^inbox=//p' | tail -n 1 ;;
    session:*) n="${lab#session:}"; "$ROOT/scripts/lab.sh" --session "$n" paths | sed -nE 's/^inbox=//p' | tail -n 1 ;;
    *) die "Unknown --lab: $lab (expected stable|canary|session:N)" ;;
  esac
}

read_token_from_file() {
  local path="$1"
  [ -f "$path" ] || die "file not found: $path"
  # Accept a 1-line file; trim whitespace and drop trailing newline.
  python3 - <<PY
from pathlib import Path
import sys
p=Path(${path@Q})
raw=p.read_text(encoding="utf-8",errors="ignore")
tok=raw.strip()
if not tok or len(tok) < 20:
  print("invalid", file=sys.stderr)
  raise SystemExit(2)
print(tok, end="")
PY
}

validate_token() {
  local token="$1"
  python3 - <<PY
import urllib.request, urllib.error, sys
tok=${token@Q}
req=urllib.request.Request("https://api.github.com/user")
req.add_header("Authorization", f"Bearer {tok}")
req.add_header("User-Agent","darelwasl-github-token-validate")
try:
  with urllib.request.urlopen(req, timeout=10) as resp:
    ok = (resp.status == 200)
except urllib.error.HTTPError as e:
  ok = False
except Exception:
  ok = False
sys.exit(0 if ok else 1)
PY
}

upsert_env_var() {
  local env_file="$1" key="$2" value_file="${3:-}"
  python3 - "$env_file" "$key" "$value_file" <<'PY'
from pathlib import Path
import os, re, sys

env_file=sys.argv[1]
key=sys.argv[2]
value_file=(sys.argv[3] or "").strip()
if value_file:
  raw=Path(value_file).read_text(encoding="utf-8",errors="ignore")
  val=raw.strip()
else:
  val=sys.stdin.read().strip("\n")

path=Path(env_file)
text = path.read_text(encoding="utf-8",errors="ignore") if path.exists() else ""
lines = text.splitlines()
out=[]
found=False
pat=re.compile(r"^"+re.escape(key)+r"=.*$")
for line in lines:
  if pat.match(line):
    out.append(f"{key}={val}")
    found=True
  else:
    out.append(line)
if not found:
  if out and out[-1] != "":
    out.append("")
  out.append(f"{key}={val}")

tmp = path.with_suffix(path.suffix + ".tmp")
tmp.write_text("\n".join(out).rstrip("\n") + "\n", encoding="utf-8")
if path.exists():
  st=os.stat(path)
  os.chmod(tmp, st.st_mode)
  try:
    os.chown(tmp, st.st_uid, st.st_gid)
  except PermissionError:
    pass
tmp.replace(path)
PY
}

cmd_validate() {
  local file="" token_env="GITHUB_TOKEN"
  while [ $# -gt 0 ]; do
    case "$1" in
      --file) file="${2:-}"; shift 2 ;;
      --token-env) token_env="${2:-}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) die "validate: unknown arg: $1" ;;
    esac
  done
  local token=""
  if [ -n "${file:-}" ]; then
    token="$(read_token_from_file "$file")"
  else
    token="${!token_env:-}"
  fi
  [ -n "${token:-}" ] || die "No token to validate (use --file or set $token_env)"
  if validate_token "$token"; then
    echo "ok"
  else
    die "token invalid (GitHub /user returned non-200)"
  fi
}

cmd_install() {
  local file="" env_file="/etc/darelwasl/app.env" restart_unit="darelwasl.service"
  while [ $# -gt 0 ]; do
    case "$1" in
      --file) file="${2:-}"; shift 2 ;;
      --env-file) env_file="${2:-}"; shift 2 ;;
      --restart) restart_unit="${2:-}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) die "install: unknown arg: $1" ;;
    esac
  done
  [ -n "${file:-}" ] || die "install requires --file PATH"

  local token; token="$(read_token_from_file "$file")"
  validate_token "$token" || die "Refusing to install invalid token (GitHub /user failed)"

  # Prefer writing TERMINAL_GITHUB_TOKEN for systemd compatibility, and also set GITHUB_TOKEN
  # so scripts that don't know about TERMINAL_GITHUB_TOKEN still work when app.env is sourced.
  sudo -n true >/dev/null 2>&1 || die "sudo required (run as a user with passwordless sudo)"
  # Note: piping via stdin is unreliable when sudo is configured with a PTY; pass a file path instead.
  sudo -n "$ROOT/scripts/github-token.sh" __internal_upsert "$env_file" TERMINAL_GITHUB_TOKEN "$file"
  sudo -n "$ROOT/scripts/github-token.sh" __internal_upsert "$env_file" GITHUB_TOKEN "$file"

  if [ -n "${restart_unit:-}" ]; then
    sudo -n systemctl restart "$restart_unit"
  fi
  echo "installed"
}

cmd_install_from_lab() {
  local lab="stable" name="token.txt" env_file="/etc/darelwasl/app.env" restart_unit="darelwasl.service"
  while [ $# -gt 0 ]; do
    case "$1" in
      --lab) lab="${2:-}"; shift 2 ;;
      --name) name="${2:-}"; shift 2 ;;
      --env-file) env_file="${2:-}"; shift 2 ;;
      --restart) restart_unit="${2:-}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) die "install-from-lab: unknown arg: $1" ;;
    esac
  done
  local inbox; inbox="$(lab_inbox_path "$lab")"
  [ -n "${inbox:-}" ] || die "unable to resolve lab inbox path for $lab"
  local file="$inbox/$name"
  "$ROOT/scripts/github-token.sh" install --file "$file" --env-file "$env_file" --restart "$restart_unit"
}

cmd="${1:-}"
shift || true
case "$cmd" in
  validate) cmd_validate "$@" ;;
  install) cmd_install "$@" ;;
  install-from-lab) cmd_install_from_lab "$@" ;;
  __internal_upsert) upsert_env_var "$@" ;;
  ""|-h|--help|help) usage ;;
  *) die "Unknown cmd: $cmd" ;;
esac

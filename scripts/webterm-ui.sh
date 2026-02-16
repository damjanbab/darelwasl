#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

SRC="$ROOT/ops/webterm-ui/server.py"

MODE_STABLE="stable"
MODE_CANARY="canary"

DST_STABLE="${DW_WEBTERM_UI_DST:-/usr/local/lib/dw-webterm-ui/server.py}"
SERVICE_STABLE="${DW_WEBTERM_UI_SERVICE:-darelwasl-webterm-ui}"
LISTEN_STABLE="${DW_WEBTERM_UI_LISTEN:-http://127.0.0.1:7682}"

DST_CANARY="${DW_WEBTERM_UI_CANARY_DST:-/usr/local/lib/dw-webterm-ui-canary/server.py}"
SERVICE_CANARY="${DW_WEBTERM_UI_CANARY_SERVICE:-darelwasl-webterm-ui-canary}"
LISTEN_CANARY="${DW_WEBTERM_UI_CANARY_LISTEN:-http://127.0.0.1:7684}"

CADDYFILE="${DW_CADDYFILE:-/etc/caddy/Caddyfile}"
ENV_FILE="${DW_WEBTERM_ENV_FILE:-/etc/darelwasl/webterm.env}"

PUBLIC_HOST="${DW_WEBTERM_PUBLIC_HOST:-https://code.haloeddepth.com}"
PUBLIC_CANARY_PREFIX="${DW_WEBTERM_PUBLIC_CANARY_PREFIX:-/canary}"

mode="$MODE_STABLE"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

LAB_STABLE_N="${DW_LAB_SESSION_STABLE:-${DW_LAB_SESSION:-7}}"
LAB_CANARY_N="${DW_LAB_SESSION_CANARY:-$((LAB_STABLE_N + 1))}"

usage() {
  cat <<EOF
Usage: scripts/webterm-ui.sh <cmd> [--canary]

Commands:
  diff              Show diff between repo source and installed server.py
  install           Install repo source (stable by default)
  restart           Restart systemd service (stable by default)
  smoke             Curl a couple endpoints (stable by default)

  ensure-canary      Ensure canary UI service + Caddy route exist (idempotent)
  deploy-canary      ensure-canary + install/restart/smoke canary + print review URL
  deploy-stable      install/restart/smoke stable
  promote-lab        Swap lab stable/canary sessions in ${ENV_FILE} and restart services
  urls              Print public stable/canary URLs

Env overrides:
  DW_WEBTERM_UI_DST=/path/to/server.py
  DW_WEBTERM_UI_SERVICE=service-name
  DW_WEBTERM_UI_LISTEN=http://host:port

  DW_WEBTERM_UI_CANARY_DST=/path/to/server.py
  DW_WEBTERM_UI_CANARY_SERVICE=service-name
  DW_WEBTERM_UI_CANARY_LISTEN=http://host:port

  DW_CADDYFILE=/etc/caddy/Caddyfile
  DW_WEBTERM_ENV_FILE=/etc/darelwasl/webterm.env
  DW_WEBTERM_PUBLIC_HOST=https://code.haloeddepth.com
EOF
}

die() { echo "$*" >&2; exit 2; }

curl_retry() {
  local url="$1"
  local tries="${2:-20}"
  local delay_s="${3:-0.15}"
  local i=1
  while [ "$i" -le "$tries" ]; do
    if curl -fsS "$url" >/dev/null; then
      return 0
    fi
    sleep "$delay_s"
    i=$((i + 1))
  done
  curl -fsS "$url" >/dev/null
}

require_src() {
  if [ ! -s "$SRC" ]; then
    echo "Missing source: $SRC" >&2
    exit 2
  fi
}

pick() {
  local key="$1"
  if [ "$mode" = "$MODE_CANARY" ]; then
    case "$key" in
      dst) echo "$DST_CANARY" ;;
      service) echo "$SERVICE_CANARY" ;;
      listen) echo "$LISTEN_CANARY" ;;
      *) die "unknown pick key: $key" ;;
    esac
  else
    case "$key" in
      dst) echo "$DST_STABLE" ;;
      service) echo "$SERVICE_STABLE" ;;
      listen) echo "$LISTEN_STABLE" ;;
      *) die "unknown pick key: $key" ;;
    esac
  fi
}

timestamp() { date -u +"%Y%m%dT%H%M%SZ"; }

ensure_canary_unit() {
  local unit_path="/etc/systemd/system/${SERVICE_CANARY}.service"
  local tmp
  tmp="$(mktemp)"
  cat >"$tmp" <<EOF
[Unit]
Description=DarelWasl Web Terminal UI (Canary)
After=network.target

[Service]
Type=simple
User=darelwasl
Group=darelwasl
EnvironmentFile=${ENV_FILE}
ExecStart=/usr/bin/env DW_LISTEN_PORT=7684 DW_PUBLIC_BASE_PATH=${PUBLIC_CANARY_PREFIX} /usr/bin/python3 ${DST_CANARY}
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF
  sudo mkdir -p "$(dirname "$unit_path")"
  if sudo test -f "$unit_path"; then
    if sudo diff -u "$unit_path" "$tmp" >/dev/null 2>&1; then
      rm -f "$tmp"
      return 0
    fi
    sudo cp -f "$unit_path" "${unit_path}.bak.$(timestamp)"
  fi
  sudo cp -f "$tmp" "$unit_path"
  rm -f "$tmp"
  sudo systemctl daemon-reload
  sudo systemctl enable --now "$SERVICE_CANARY" >/dev/null 2>&1 || true
}

ensure_caddy_canary_route() {
  if [ ! -f "$CADDYFILE" ]; then
    die "Caddyfile not found: $CADDYFILE"
  fi
  if sudo grep -q "@ui_canary" "$CADDYFILE"; then
    return 0
  fi
  sudo cp -f "$CADDYFILE" "${CADDYFILE}.bak.webterm-ui.$(timestamp)"
  tmp="$(mktemp)"
  python3 - "$CADDYFILE" >"$tmp" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
if "@ui_canary" in text:
    print(text, end="")
    raise SystemExit(0)

needle = "reverse_proxy 127.0.0.1:7682"
idx = text.find(needle)
if idx < 0:
    raise SystemExit("Unable to locate stable reverse_proxy 127.0.0.1:7682 in Caddyfile")

insert = "\t\t@ui_canary path /canary*\n" \
         "\t\thandle @ui_canary {\n" \
         "\t\t\turi strip_prefix /canary\n" \
         "\t\t\treverse_proxy 127.0.0.1:7684\n" \
         "\t\t}\n\n"

out = text[:idx] + insert + text[idx:]
print(out, end="")
PY
  sudo cp -f "$tmp" "$CADDYFILE"
  rm -f "$tmp"
  sudo systemctl reload caddy
}

cmd_urls() {
  echo "stable: ${PUBLIC_HOST}/lab?session=${LAB_STABLE_N}"
  echo "canary: ${PUBLIC_HOST}${PUBLIC_CANARY_PREFIX}/lab?session=${LAB_CANARY_N}"
}

cmd_promote_lab() {
  if [ ! -f "$ENV_FILE" ]; then
    die "env file not found: $ENV_FILE"
  fi

  sudo cp -f "$ENV_FILE" "${ENV_FILE}.bak.promote.$(timestamp)"
  python3 - "$ENV_FILE" <<'PY'
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
lines = path.read_text(encoding="utf-8").splitlines()

kv = {}
order = []
for i, line in enumerate(lines):
    m = re.match(r"^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)\s*$", line)
    if not m:
        continue
    k, v = m.group(1), m.group(2)
    if k not in kv:
        order.append(k)
    kv[k] = v

def geti(key, default):
    raw = kv.get(key, "")
    try:
        return int(raw.strip().strip('"').strip("'"))
    except Exception:
        return default

stable = geti("DW_LAB_SESSION_STABLE", geti("DW_LAB_SESSION", 7))
canary = geti("DW_LAB_SESSION_CANARY", stable + 1)

stable, canary = canary, stable

kv["DW_LAB_SESSION_STABLE"] = str(stable)
kv["DW_LAB_SESSION_CANARY"] = str(canary)
kv["DW_LAB_SESSION"] = str(stable)  # keep legacy in sync

def set_line(k, v):
    pat = re.compile(rf"^\s*{re.escape(k)}=")
    for i, line in enumerate(lines):
        if pat.match(line):
            lines[i] = f"{k}={v}"
            return True
    lines.append(f"{k}={v}")
    return False

set_line("DW_LAB_SESSION_STABLE", kv["DW_LAB_SESSION_STABLE"])
set_line("DW_LAB_SESSION_CANARY", kv["DW_LAB_SESSION_CANARY"])
set_line("DW_LAB_SESSION", kv["DW_LAB_SESSION"])

path.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(f"promoted lab sessions: stable={stable} canary={canary}")
PY

  sudo systemctl restart "$SERVICE_STABLE" || true
  sudo systemctl restart "$SERVICE_CANARY" || true
  "$0" smoke || true
  "$0" smoke --canary || true
  "$0" urls
}

cmd="${1:-}"
parsed=()
for a in "$@"; do
  case "$a" in
    --canary) mode="$MODE_CANARY" ;;
    --stable) mode="$MODE_STABLE" ;;
    *) parsed+=("$a") ;;
  esac
done
cmd="${parsed[0]:-}"

case "$cmd" in
  diff)
    require_src
    dst="$(pick dst)"
    if [ ! -f "$dst" ]; then
      echo "Installed file not found: $dst" >&2
      exit 1
    fi
    diff -u "$dst" "$SRC" || true
    ;;
  install)
    require_src
    dst="$(pick dst)"
    sudo mkdir -p "$(dirname "$dst")"
    sudo install -m 0755 "$SRC" "$dst"
    echo "installed: $dst"
    ;;
  restart)
    service="$(pick service)"
    sudo systemctl restart "$service"
    sudo systemctl status "$service" --no-pager -l || true
    ;;
  smoke)
    listen="$(pick listen)"
    curl_retry "$listen/api/sessions" && echo "sessions ok ($mode)"
    curl_retry "$listen/lab?session=$LAB_STABLE_N" && echo "lab stable ok ($mode)"
    curl_retry "$listen/api/lab/outbox?session=$LAB_STABLE_N" && echo "outbox stable ok ($mode)"
    curl_retry "$listen/api/lab/history?lines=200&session=$LAB_STABLE_N" && echo "history stable ok ($mode)"
    curl_retry "$listen/lab?session=$LAB_CANARY_N" && echo "lab canary ok ($mode)"
    curl_retry "$listen/api/lab/outbox?session=$LAB_CANARY_N" && echo "outbox canary ok ($mode)"
    curl_retry "$listen/api/lab/history?lines=200&session=$LAB_CANARY_N" && echo "history canary ok ($mode)"
    ;;
  ensure-canary)
    require_src
    ensure_canary_unit
    ensure_caddy_canary_route
    ;;
  deploy-canary)
    require_src
    "$0" ensure-canary
    "$0" install --canary
    "$0" restart --canary
    "$0" smoke --canary
    "$0" urls
    ;;
  deploy-stable)
    require_src
    "$0" install
    "$0" restart
    "$0" smoke
    "$0" urls
    ;;
  promote-lab)
    cmd_promote_lab
    ;;
  urls)
    cmd_urls
    ;;
  ""|-h|--help|help)
    usage
    ;;
  *)
    echo "unknown cmd: $cmd" >&2
    usage >&2
    exit 2
    ;;
esac

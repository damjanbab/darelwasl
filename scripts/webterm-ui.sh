#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

SRC_WEBTERM_DIR="$ROOT/src/darelwasl/webterm"
SRC_DEPS="$ROOT/ops/webterm-ui/deps.edn"
SRC_RUN="$ROOT/ops/webterm-ui/run.sh"
SRC_UNIT_STABLE="$ROOT/ops/webterm-ui/systemd/darelwasl-webterm-ui.service"
SRC_UNIT_CANARY="$ROOT/ops/webterm-ui/systemd/darelwasl-webterm-ui-canary.service"
TARGET="${DW_WEBTERM_UI_TARGET:-stable}" # stable|canary

STABLE_DST_DEFAULT="/usr/local/lib/dw-webterm-ui"
CANARY_DST_DEFAULT="/usr/local/lib/dw-webterm-ui-canary"
STABLE_SERVICE_DEFAULT="darelwasl-webterm-ui"
CANARY_SERVICE_DEFAULT="darelwasl-webterm-ui-canary"
STABLE_LISTEN_DEFAULT="http://127.0.0.1:7682"
CANARY_LISTEN_DEFAULT="http://127.0.0.1:7684"

if [[ "${1:-}" == "--target" ]]; then
  TARGET="${2:-}"
  shift 2 || true
fi
cmd="${1:-}"

dst_default="$STABLE_DST_DEFAULT"
service_default="$STABLE_SERVICE_DEFAULT"
listen_default="$STABLE_LISTEN_DEFAULT"
unit_src_default="$SRC_UNIT_STABLE"
if [[ "$TARGET" == "canary" ]]; then
  dst_default="$CANARY_DST_DEFAULT"
  service_default="$CANARY_SERVICE_DEFAULT"
  listen_default="$CANARY_LISTEN_DEFAULT"
  unit_src_default="$SRC_UNIT_CANARY"
fi

DST="${DW_WEBTERM_UI_DST:-$dst_default}"
SERVICE="${DW_WEBTERM_UI_SERVICE:-$service_default}"
LISTEN="${DW_WEBTERM_UI_LISTEN:-$listen_default}"
UNIT_SRC="${DW_WEBTERM_UI_UNIT_SRC:-$unit_src_default}"
UNIT_DST="${DW_WEBTERM_UI_UNIT_DST:-/etc/systemd/system/${SERVICE}.service}"
OWNER="${DW_WEBTERM_UI_OWNER:-darelwasl:darelwasl}"
LAB_STABLE_N="${DW_LAB_SESSION_STABLE:-${DW_LAB_SESSION:-7}}"
LAB_CANARY_N="${DW_LAB_SESSION_CANARY:-$((LAB_STABLE_N + 1))}"
PUBLIC_ORIGIN="${DW_WEBTERM_PUBLIC_ORIGIN:-https://code.haloeddepth.com}"

usage() {
  cat <<EOF
Usage: scripts/webterm-ui.sh [--target stable|canary] <cmd>

Commands:
  diff        Show diff between repo source and installed Clojure service
  install     Install repo source to ${DST} (self-contained Clojure project)
  install-unit  Install systemd unit to ${UNIT_DST}
  check       Run 'clojure --check' in ${DST}
  restart     Restart systemd service (${SERVICE})
  smoke       Curl a couple endpoints (stable + canary)
  deploy-canary  Install+install-unit+restart canary; print proctor link
  deploy-stable  Install+install-unit+restart stable

Env overrides:
  DW_WEBTERM_UI_TARGET=stable|canary
  DW_WEBTERM_UI_DST=/path/to/install/dir
  DW_WEBTERM_UI_SERVICE=service-name
  DW_WEBTERM_UI_LISTEN=http://host:port
  DW_WEBTERM_UI_UNIT_SRC=/path/to/unit.service
  DW_WEBTERM_UI_UNIT_DST=/etc/systemd/system/name.service
  DW_WEBTERM_UI_OWNER=user:group
  DW_WEBTERM_PUBLIC_ORIGIN=https://code.haloeddepth.com
EOF
}

require_sources() {
  local missing=0
  for f in "$SRC_DEPS" "$SRC_RUN" "$UNIT_SRC" ; do
    if [ ! -s "$f" ]; then
      echo "Missing source: $f" >&2
      missing=1
    fi
  done
  if [ ! -d "$SRC_WEBTERM_DIR" ]; then
    echo "Missing source directory: $SRC_WEBTERM_DIR" >&2
    missing=1
  fi
  if [ "$missing" -ne 0 ]; then
    exit 2
  fi
}

diff_tree() {
  local dst="$1"
  if [ ! -d "$dst" ]; then
    echo "Installed dir not found: $dst" >&2
    exit 1
  fi
  diff -u "$dst/deps.edn" "$SRC_DEPS" || true
  diff -u "$dst/run.sh" "$SRC_RUN" || true
  diff -ru "$dst/src/darelwasl/webterm" "$SRC_WEBTERM_DIR" || true
}

case "$cmd" in
  diff)
    require_sources
    diff_tree "$DST"
    ;;
  install)
    require_sources
    install -d "$DST"
    rm -f "$DST/server.py" || true
    rm -rf "$DST/__pycache__" || true
    install -m 0644 "$SRC_DEPS" "$DST/deps.edn"
    install -m 0755 "$SRC_RUN" "$DST/run.sh"
    install -d "$DST/src/darelwasl"
    rm -rf "$DST/src/darelwasl/webterm"
    cp -a "$SRC_WEBTERM_DIR" "$DST/src/darelwasl/webterm"
    mkdir -p "$DST/.cpcache"
    chown -R "$OWNER" "$DST" || true
    echo "installed: $DST (deps.edn, run.sh, src/darelwasl/webterm)"
    ;;
  install-unit)
    require_sources
    install -m 0644 "$UNIT_SRC" "$UNIT_DST"
    systemctl daemon-reload
    echo "installed unit: $UNIT_DST"
    ;;
  check)
    if [ ! -d "$DST" ]; then
      echo "Installed dir not found: $DST" >&2
      exit 1
    fi
    (cd "$DST" && /usr/local/bin/clojure -M -m darelwasl.webterm.server --check)
    ;;
  restart)
    systemctl restart "$SERVICE"
    systemctl status "$SERVICE" --no-pager -l || true
    ;;
  smoke)
    smoke_one() {
      local listen="$1" label="$2"
      local ok=0
      for _ in {1..60}; do
        if curl -fsS "$listen/api/sessions" >/dev/null 2>&1; then
          ok=1
          break
        fi
        sleep 0.25
      done
      if [ "$ok" -ne 1 ]; then
        curl -fsS "$listen/api/sessions" >/dev/null
      fi
      echo "sessions ok (${label})"
      curl -fsS "$listen/lab?session=$LAB_STABLE_N" >/dev/null && echo "lab stable ok (${label})"
      curl -fsS "$listen/api/lab/outbox?session=$LAB_STABLE_N" >/dev/null && echo "outbox stable ok (${label})"
      curl -fsS "$listen/api/lab/history?lines=200&session=$LAB_STABLE_N" >/dev/null && echo "history stable ok (${label})"
      curl -fsS "$listen/lab?session=$LAB_CANARY_N" >/dev/null && echo "lab canary ok (${label})"
      curl -fsS "$listen/api/lab/outbox?session=$LAB_CANARY_N" >/dev/null && echo "outbox canary ok (${label})"
      curl -fsS "$listen/api/lab/history?lines=200&session=$LAB_CANARY_N" >/dev/null && echo "history canary ok (${label})"
    }

    smoke_one "$STABLE_LISTEN_DEFAULT" "stable-ui"
    smoke_one "$CANARY_LISTEN_DEFAULT" "canary-ui"
    ;;
  deploy-canary)
    "$0" --target canary install
    "$0" --target canary install-unit
    "$0" --target canary restart
    "$0" smoke
    echo "Proctor canary:"
    echo "  ${PUBLIC_ORIGIN}/canary/lab?session=${LAB_CANARY_N}"
    echo "Stable:"
    echo "  ${PUBLIC_ORIGIN}/lab?session=${LAB_STABLE_N}"
    ;;
  deploy-stable)
    "$0" --target stable install
    "$0" --target stable install-unit
    "$0" --target stable restart
    "$0" smoke
    echo "Stable:"
    echo "  ${PUBLIC_ORIGIN}/lab?session=${LAB_STABLE_N}"
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

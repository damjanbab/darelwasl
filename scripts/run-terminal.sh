#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

cd "$ROOT"
if [ -n "${TERMINAL_SESSION_ID:-}" ] && [ "${TERMINAL_ALLOW_NESTED:-}" != "1" ]; then
  echo "Refusing to start terminal service inside a session. Set TERMINAL_ALLOW_NESTED=1 to override." >&2
  exit 1
fi
TMUX_LOCAL="$HOME/.local/tmux/usr/bin"
if [ -x "$TMUX_LOCAL/tmux" ]; then
  export PATH="$TMUX_LOCAL:$PATH"
  export TERMINAL_TMUX_BIN="$TMUX_LOCAL/tmux"
fi
TMUX_LIB="$HOME/.local/tmux-lib/usr/lib/x86_64-linux-gnu"
if [ -d "$TMUX_LIB" ]; then
  export LD_LIBRARY_PATH="$TMUX_LIB:${LD_LIBRARY_PATH:-}"
fi
if [ -z "${TERMINAL_CODEX_CMD:-}" ]; then
  export TERMINAL_CODEX_CMD="codex --dangerously-bypass-approvals-and-sandbox"
fi
exec clojure -M:terminal

#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

cd "$ROOT"
TMUX_LOCAL="$HOME/.local/tmux/usr/bin"
if [ -x "$TMUX_LOCAL/tmux" ]; then
  export PATH="$TMUX_LOCAL:$PATH"
  export TERMINAL_TMUX_BIN="$TMUX_LOCAL/tmux"
fi
TMUX_LIB="$HOME/.local/tmux-lib/usr/lib/x86_64-linux-gnu"
if [ -d "$TMUX_LIB" ]; then
  export LD_LIBRARY_PATH="$TMUX_LIB:${LD_LIBRARY_PATH:-}"
fi
exec clojure -M:terminal

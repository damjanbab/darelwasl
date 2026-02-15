# `code.haloeddepth.com` — what it actually serves (web terminals)

`code.haloeddepth.com` is currently a **web terminal gateway** (ttyd/xterm.js + legacy ShellInABox), **not** a code-server instance.

It is fronted by **Caddy** with **Basic Auth** configured in `/etc/caddy/Caddyfile`.

## Routes (public)

- `/` → **terminal session picker UI** (lists tmux sessions like `codex1`, `codex2`, …)
- `/xterm/?arg=codexN` → **ttyd** (xterm.js) attached to tmux session `codexN`
- `/tN` → **legacy** ShellInABox terminal for tmux session `codexN`
- `/lab` → **Lab UI** for the configured lab session (default `codex7`): iframe terminal + upload (inbox) + download (outbox-only)

Note: Caddy persists the chosen xterm session in a cookie (`dw_xterm_session`) so refresh works even if query params are lost.

## Backends (localhost)

These are the local services Caddy proxies to:

- `127.0.0.1:7682` — terminal session picker UI (`/usr/local/lib/dw-webterm-ui/server.py`)
- `127.0.0.1:7683` — `ttyd` (started by `darelwasl-ttyd.service`, base-path `/xterm`)
- `127.0.0.1:7681` — ShellInABox (started by `darelwasl-webterm.service`)

## What runs inside the terminal

Both the xterm (ttyd) and legacy ShellInABox terminals ultimately run:

- `/usr/local/bin/dw-codex-term <session>`

That script attaches to (or creates) a `tmux` session (default prefix `codex`) and sets up PATH so the user-scoped `codex` CLI is available.

## UI endpoints (picker)

The picker UI (`127.0.0.1:7682`) supports:

- `GET /api/sessions` — JSON list of terminal slots + URLs
- `GET /new` — allocate next free tmux slot and redirect to `/xterm/?arg=...`
- `GET /open?n=N` — ensure tmux session exists, then redirect to `/xterm/?arg=...`
- `GET /codex?n=N` — ensure tmux session exists, send `codex` into that tmux session, then redirect
- `GET /kill?n=N` — kill the tmux session
- Lab helpers:
  - `GET /lab` — lab UI page
  - `POST /api/lab/upload` — upload a file to the lab inbox
  - `GET /api/lab/outbox` — list downloadable outbox files
  - `GET /api/lab/outbox/download?name=...` — download a single outbox file (no browsing)

## Configuration knobs

Shared configuration is read from `/etc/darelwasl/webterm.env` (used by the systemd units and picker UI), e.g.:

- `DW_TMUX_PREFIX` (default `codex`)
- `DW_TERMINAL_COUNT` (default `32`)
- `DW_WORKDIR` (default `/opt/darelwasl`)
- `DW_LISTEN_HOST` / `DW_LISTEN_PORT` (picker UI bind)
- `DW_LAB_SESSION` (default `7`)
- `DW_LAB_DIR` (default `/opt/darelwasl/tmp/lab`)
- `DW_LAB_MAX_UPLOAD_BYTES` (default `52428800` / 50MB)

Optional:
- `DW_DEFAULT_TMUX_SESSION` (consumed by `/usr/local/bin/dw-codex-term` when no session arg is provided)

## Quick checks

Local (bypasses Caddy):

```bash
curl -fsS http://127.0.0.1:7682/ >/dev/null && echo "picker ok"
curl -fsS http://127.0.0.1:7682/api/sessions | jq .
```

Service status:

```bash
systemctl status caddy --no-pager -l
systemctl status darelwasl-webterm-ui darelwasl-ttyd darelwasl-webterm --no-pager -l
```

## Related: code-server

There is a `code-server@.service` unit on the machine, but `code.haloeddepth.com` is not currently routed to it via Caddy.

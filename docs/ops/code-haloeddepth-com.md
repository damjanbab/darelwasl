# `code.haloeddepth.com` — what it actually serves (web terminals)

`code.haloeddepth.com` is currently a **web terminal gateway** (ttyd/xterm.js + legacy ShellInABox), **not** a code-server instance.

It is fronted by **Caddy** with **Basic Auth** configured in `/etc/caddy/Caddyfile`.

## Routes (public)

- `/` → **terminal session picker UI** (lists tmux sessions like `codex1`, `codex2`, …)
- `/canary/` → **canary** terminal picker + Lab UI (canary deploy for validating UI/code changes)
- `/xterm/?arg=codexN` → **ttyd** (xterm.js) attached to tmux session `codexN`
- `/tN` → **legacy** ShellInABox terminal for tmux session `codexN`
- `/lab` → **Lab UI** for the selected Lab session (stable/canary): iframe terminal + upload (inbox) + download (outbox-only) + tmux history capture
  - Select with `?session=N` or the UI buttons
  - Persisted in cookie `dw_lab_session`
- In the Lab UI, **outbox** is treated as the shared “library”:
  - Agent outputs should be written there (easy view/download on mobile/desktop).
  - The UI supports in-page viewing for PDFs/images/text via the outbox list.

Note: Caddy persists the chosen xterm session in a cookie (`dw_xterm_session`) so refresh works even if query params are lost.

## Backends (localhost)

These are the local services Caddy proxies to:

- `127.0.0.1:7682` — terminal session picker + Lab UI (**Clojure**, installed at `/usr/local/lib/dw-webterm-ui/`)
- `127.0.0.1:7684` — **canary** terminal session picker + Lab UI (**Clojure**, installed at `/usr/local/lib/dw-webterm-ui-canary/`, served under `/canary/`)
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
  - `POST /api/lab/paste` — save clipboard text as a file (inbox or outbox)
  - `GET /api/lab/inbox` — list inbox files (list only; no download)
  - `GET /api/lab/outbox` — list downloadable outbox files
  - `GET /api/lab/outbox/download?name=...` — download a single outbox file (no browsing)
  - `GET /api/lab/history?lines=N` — capture tmux scrollback for the lab session

## Work sign-off (proctor → PR)

The Lab UI is the canonical “review + sign off” surface for work items.

- Select a work (`Work` → `Select`), then use `Sign off`.
- The UI enforces the correct proctor channel per work:
  - **Lab UI / webterm changes** must be proctored in **canary** (`/canary/lab?...`).
  - Everything else is proctored in **stable** (`/lab?...`).
- On success, `Sign off` creates (or finds) the GitHub PR and returns the PR URL.

Prereq: a GitHub token must be available to the server, via the secrets vault key `github/token`:

```bash
scripts/secrets.sh set github/token
```

## Configuration knobs

Shared configuration is read from `/etc/darelwasl/webterm.env` (used by the systemd units and picker UI), e.g.:

- `DW_TMUX_PREFIX` (default `codex`)
- `DW_TERMINAL_COUNT` (default `32`)
- `DW_WORKDIR` (default `/opt/darelwasl`)
- `DW_LISTEN_HOST` / `DW_LISTEN_PORT` (picker UI bind)
- `DW_LAB_SESSION_STABLE` (default `7`)
- `DW_LAB_SESSION_CANARY` (default: `DW_LAB_SESSION_STABLE + 1`)
- Legacy: `DW_LAB_SESSION` (treated as stable when `DW_LAB_SESSION_STABLE` is not set)
- `DW_LAB_DIR` (default `/opt/darelwasl/tmp/lab`)
- `DW_LAB_LIBRARY_DIR` (default: `<DW_LAB_DIR>/library`)
- `DW_LAB_MAX_UPLOAD_BYTES` (default `52428800` / 50MB)

Optional:
- `DW_DEFAULT_TMUX_SESSION` (consumed by `/usr/local/bin/dw-codex-term` when no session arg is provided)
- `DW_LAB_HISTORY_LINES` (default `20000`; UI default for history capture)
- `DW_TMUX_HISTORY_LIMIT` (default `50000`; tmux history-limit for newly created sessions)

## Auto-publishing artifacts to Lab Library

To make generated PDFs show up in the Lab Library automatically (for one-click review/download), set:

```bash
export DW_LAB_AUTO_OUTBOX=1
```

Supported generators:
- `node scripts/documents-pdf.js ...`
- `node scripts/account-statement-pdf.js ...`

## Deploying UI changes

The webterm UI server is installed outside the repo, so treat the repo as the source of truth and deploy with:

```bash
scripts/webterm-ui.sh diff
scripts/webterm-ui.sh install
scripts/webterm-ui.sh install-unit
scripts/webterm-ui.sh restart
scripts/webterm-ui.sh smoke
sudo scripts/webterm-ui.sh promote --approved
```

Canary-first deploy (recommended for Lab UI/code changes):

```bash
scripts/webterm-ui.sh deploy-canary
```

## Canary upgrades (stable ↔ canary swap)

When making Lab-related changes that could break workflows, validate in **canary** first, then promote via a **blue/green swap** (stable ⇄ canary installed trees). This ensures the *new canary* becomes the *previous stable* automatically, so you don’t “lose” features like the toolbar on the canary after promotion.

Policy: `policies/lab-canary-upgrades.md`

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

# Policy: Lab canary upgrades (stable ↔ canary swap)

## Goal

Make operational changes to the Lab (`code.haloeddepth.com/lab`) safer by always validating in a **canary Lab** first, then promoting by **swapping roles** so the next change has a fresh canary.

## Definitions

- **Stable Lab**: the default Lab session used for day-to-day work.
- **Canary Lab**: the “try changes here first” Lab session.

Both are just separate tmux sessions and separate file roots under `DW_LAB_DIR`.

## Configuration

Preferred (new):
- `DW_LAB_SESSION_STABLE` (default: `7`)
- `DW_LAB_SESSION_CANARY` (default: `DW_LAB_SESSION_STABLE + 1`)

Legacy (still supported):
- `DW_LAB_SESSION` (treated as `DW_LAB_SESSION_STABLE` when set)

The Lab UI also persists the currently selected session in the browser cookie:
- `dw_lab_session`

## Rules

- **Canary-first:** any Lab-related change that could break workflows is validated in **canary** first.
- **No inbox downloads:** inbox remains list-only; outbox remains the only download surface.
- **Promotion = swap:** after canary is proctored, **swap stable/canary session numbers** so the just-proctored canary becomes stable, and the old stable becomes the next canary.
- **PR-first:** Lab UI/code changes land via an isolated worktree + PR (see `AGENTS.md` playbook `work/isolate-pr`).

## Proctoring checklist (minimum)

In `/lab?session=<canary>`:
- Upload a file → appears in inbox list.
- Paste to inbox/outbox → appears in the right list.
- Download an outbox file → download succeeds.
- Capture history and “Save to outbox” → file appears and is downloadable.
- “Start codex” works for the active session.

## Promotion procedure (swap roles)

On the host, update `/etc/darelwasl/webterm.env` (or the equivalent env source) by swapping:

- `DW_LAB_SESSION_STABLE=<old canary>`
- `DW_LAB_SESSION_CANARY=<old stable>`

Then restart the webterm UI service and smoke-test:

- `scripts/webterm-ui.sh restart`
- `scripts/webterm-ui.sh smoke`

## Proof

- `python3 -m py_compile ops/webterm-ui/server.py`
- `scripts/checks.sh governance`

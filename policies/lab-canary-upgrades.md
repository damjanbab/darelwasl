# Policy: Lab canary upgrades (stable ↔ canary)

## Goal

Make operational changes to the Lab (`code.haloeddepth.com/lab`) safer by always validating in a **canary Lab** first, then promoting safely.

## Definitions

- **Stable UI**: `https://code.haloeddepth.com/` (normal picker + Lab UI).
- **Canary UI**: `https://code.haloeddepth.com/canary/` (separate canary deploy for validating UI/code changes).
- **Stable Lab session**: the default tmux session used for day-to-day work.
- **Canary Lab session**: the “try changes here first” tmux session.

Lab sessions are separate tmux sessions and separate file roots under `DW_LAB_DIR`.

## Configuration

Preferred (new):
- `DW_LAB_SESSION_STABLE` (default: `7`)
- `DW_LAB_SESSION_CANARY` (default: `DW_LAB_SESSION_STABLE + 1`)

Legacy (still supported):
- `DW_LAB_SESSION` (treated as `DW_LAB_SESSION_STABLE` when set)

The Lab UI also persists the currently selected session in the browser cookie:
- `dw_lab_session`

## Rules

- **Canary-first:** any Lab UI/code change that could break workflows is validated in the **Canary UI** first.
- **No inbox downloads:** inbox remains list-only; outbox remains the only download surface.
- **PR-first:** Lab UI/code changes land via an isolated worktree + PR (see `AGENTS.md` playbook `work/isolate-pr`).

## Proctoring checklist (minimum)

In the **Canary UI**: `/canary/lab?session=<canary>`:
- Upload a file → appears in inbox list.
- Paste to inbox/outbox → appears in the right list.
- Download an outbox file → download succeeds.
- Capture history and “Save to outbox” → file appears and is downloadable.
- “Start codex” works for the active session.

## Promotion procedure

1) Deploy to canary (operator/agent does this automatically for Lab changes):

```bash
scripts/webterm-ui.sh deploy-canary
```

2) Proctor in the browser:
- `https://code.haloeddepth.com/canary/lab?session=<canary>`

3) After confirmation, open/merge the PR.

4) After merge, deploy to stable:

```bash
scripts/webterm-ui.sh deploy-stable
```

Optional (session rotation): swap stable/canary session numbers in `/etc/darelwasl/webterm.env` and restart the UI.

## Proof

- `clojure -M -m darelwasl.webterm.server --check`
- `scripts/webterm-ui.sh smoke`
- `scripts/checks.sh governance`

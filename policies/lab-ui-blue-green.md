# Policy: Lab UI blue/green (stable + canary UI)

## Goal

Make changes to the Lab UI (`code.haloeddepth.com/lab`) safe and reviewable by always deploying to a **canary UI** first, letting the user inspect it, then landing changes via PR and promoting.

## Definitions

- **Stable UI**: the default picker/Lab UI served at `/` and `/lab` (backed by `127.0.0.1:7682`).
- **Canary UI**: the canary picker/Lab UI served at `/canary/*` (backed by `127.0.0.1:7684`).

Both UIs talk to the same tmux sessions and the same lab directories; the canary is about **UI code deployment**, not data isolation.

## Rules (Lab UI work)

- **Deploy canary first:** before opening a PR, deploy the branch to the canary UI and provide the canary link for review.
- **PR after acceptance:** after the user confirms the canary UI is good, open a PR (no direct pushes to `origin/main`).
- **Promote after merge:** after the PR lands in `main`, deploy to the stable UI. For workflow-risky changes, also perform the Lab session role swap per `policies/lab-canary-upgrades.md`.

## Implementation (this repo)

- Source-of-truth: `ops/webterm-ui/server.py`
- Deployment helpers: `scripts/webterm-ui.sh ensure-canary`, `scripts/webterm-ui.sh deploy-canary`, `scripts/webterm-ui.sh deploy-stable`
- Canary routing: `/canary/*` is proxied by Caddy to the canary UI service.

## Proof

- `python3 -m py_compile ops/webterm-ui/server.py`
- `scripts/webterm-ui.sh smoke`
- `scripts/webterm-ui.sh smoke --canary`
- `scripts/checks.sh governance`


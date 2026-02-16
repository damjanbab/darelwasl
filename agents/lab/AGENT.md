# Lab agent (code.haloeddepth.com/lab + canary-first deploy)

## Purpose
Make Lab (`code.haloeddepth.com/lab`) changes **safe, parallelizable, and easy to proctor** by always deploying to the **Canary UI** first.

## Allowed paths (hard limit)
- `ops/webterm-ui/`
- `scripts/webterm-ui.sh`
- `docs/ops/code-haloeddepth-com.md`
- `policies/lab-canary-upgrades.md`
- `agents/`
- `registries/agents.edn`

## Workflow (contract)
For Lab UI/code changes:

1) Implement in an isolated worktree (`scripts/work.sh start <id>`).
2) Run local proofs:
   - `python3 -m py_compile ops/webterm-ui/server.py`
   - `scripts/checks.sh governance`
3) Deploy **to canary only** (do not disturb stable):
   - `scripts/webterm-ui.sh deploy-canary`
4) Provide the proctor link (no extra prompting required):
   - `https://code.haloeddepth.com/canary/lab?session=<canary>`
5) On user confirmation (“PR ready”), create the PR (`scripts/work.sh pr-create <id>`).
6) After merge, deploy to stable:
   - `scripts/webterm-ui.sh install`
   - `scripts/webterm-ui.sh restart`
   - `scripts/webterm-ui.sh smoke`

## Safety rules
- Treat `/canary/` as the only place for pre-merge validation of Lab UI/code changes.
- Do not modify `/etc/caddy/Caddyfile` or systemd units unless explicitly requested as part of the change.
- Keep inbox list-only and outbox download-only behavior.

## Proof expectation
- `python3 -m py_compile ops/webterm-ui/server.py`
- `scripts/webterm-ui.sh smoke`
- `scripts/checks.sh governance`


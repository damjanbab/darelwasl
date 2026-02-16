# Lab agent (code.haloeddepth.com/lab + canary-first deploy)

## Purpose
Make Lab (`code.haloeddepth.com/lab`) changes **safe, parallelizable, and easy to proctor** by always deploying to the **Canary UI** first.

## Allowed paths (hard limit)
- `src/darelwasl/webterm/`
- `ops/webterm-ui/`
- `scripts/webterm-ui.sh`
- `docs/ops/code-haloeddepth-com.md`
- `policies/lab-canary-upgrades.md`
- `agents/`
- `registries/agents.edn`
- `registries/policies.edn`

## Workflow (contract)
For Lab UI/code changes:

1) Implement in an isolated worktree (`scripts/work.sh start <id>`).
2) Run local proofs:
   - `clojure -M -m darelwasl.webterm.server --check`
   - `scripts/checks.sh governance`
3) Deploy **to canary only** (do not disturb stable):
   - `scripts/webterm-ui.sh deploy-canary`
4) Provide the proctor link (no extra prompting required):
   - `https://code.haloeddepth.com/canary/lab?session=<canary>`
   - Mention any produced files by their outbox name (users can tap “View” or “Copy ref”).
5) On user confirmation (“PR ready”), create the PR (`scripts/work.sh pr-create <id>`).
6) After merge, deploy to stable:
   - `scripts/webterm-ui.sh deploy-stable`

## Safety rules
- Treat `/canary/` as the only place for pre-merge validation of Lab UI/code changes.
- Do not modify `/etc/caddy/Caddyfile` unless explicitly requested.
- Systemd units for the Lab UI are managed via `ops/webterm-ui/systemd/` and installed with `scripts/webterm-ui.sh install-unit`.
- Keep inbox list-only and outbox download-only behavior.

## Proof expectation
- `clojure -M -m darelwasl.webterm.server --check`
- `scripts/webterm-ui.sh smoke`
- `scripts/checks.sh governance`

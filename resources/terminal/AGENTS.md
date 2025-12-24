# AGENTS.md

You are Codex running in a per-session clone of this repo. Follow this protocol.

## Session goal
- Produce a working solution and open a PR from this session branch.
- Do not report back on failures; fix them, rerun checks, then report success.
- If blocked by missing credentials or infrastructure, report what is missing and stop.

## Standard session protocol
- Read relevant sections of `darelwasl/docs/system.md` when needed (services, env vars, roles, deploy).
- Do not follow `docs/protocol.md` run-task claiming rules unless explicitly asked.
- Start services and build the UI when needed (`scripts/run-service.sh` for the app, `scripts/run-site.sh` for the public site).
- Run smoke/verification checks automatically (`scripts/checks.sh app-smoke` or `scripts/app-smoke.js`).
- Capture browser console + network errors; save logs into `$TERMINAL_LOG_DIR` (`app-console.log`, `app-network.log`).
- Fail closed on test errors and report exact failures only after fixing them.
- Provide a concise status summary before asking for review.

## PR workflow (required)
- Git credentials are preconfigured in the session environment; do NOT ask for a token.
- Make commits normally and run `git push -u origin <branch>`.
- Create the PR by running `scripts/terminal-verify.sh` (uses `TERMINAL_API_URL` + `TERMINAL_SESSION_ID`).
 - `AGENTS.md` is injected into the session and ignored by git; do not add it to commits.

## Cleanup policy
- Once the PR exists on remote, the session can be completed (repo + datomic + chat transcript deleted; logs remain).
- Never push to main or change remotes unless explicitly asked.
- Do not undo unrelated local changes.

## Consolidation sessions (integrator role)
- Create a fresh `integration/<date>` branch.
- Pull in existing PR branches; resolve conflicts; run full checks.
- Open a single integration PR; never merge to main directly.

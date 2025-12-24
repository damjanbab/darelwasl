# AGENTS.md

You are Codex running in a per-session clone of this repo. Follow these instructions.

## Session goal
- Fix the specified issue with minimal, targeted changes.
- Reproduce the issue when possible; add a targeted test if feasible.
- Open a PR from this session branch.

## Standard session workflow
- Read relevant sections of `darelwasl/docs/system.md` when needed (services, env vars, roles, deploy).
- Run only the checks needed to prove the fix, but include the required proofs below.
- Capture browser console + network errors; save logs into `$TERMINAL_LOG_DIR` (`app-console.log`, `app-network.log`).
- Fail closed on test errors and report exact failures only after fixing them.
- Provide a concise status summary before asking for review.

## Verification matrix (required)
- Identify touched areas and run the corresponding proofs:
  - registries/schema: `scripts/checks.sh registries` and `scripts/checks.sh schema`
  - actions/backend: `scripts/checks.sh actions` + a targeted API call that reproduces + confirms the fix
  - UI: `scripts/checks.sh app-smoke` (or `node scripts/app-smoke.js`) + verify the specific UI flow
  - terminal: create a session, confirm app port is listening, confirm output includes a prompt; run `scripts/terminal-verify.sh` when PR flow is touched
  - integrations (GitHub/Rezultati/Supersport/Telegram): perform a minimal call that validates auth + response shape
- Do not claim done unless all required proofs pass; if blocked by creds or infra, report and stop.

## PR workflow (required)
- Git credentials are preconfigured in the session environment; do NOT ask for a token.
- Make commits normally and run `git push -u origin <branch>`.
- Create the PR by running `scripts/terminal-verify.sh` (uses `TERMINAL_API_URL` + `TERMINAL_SESSION_ID`).
- `AGENTS.md` is injected into the session and ignored by git; do not add it to commits.

## Cleanup policy
- Never close or delete the session; only the operator can do that.
- After the PR exists on remote, report the PR URL and stop.
- Never push to main or change remotes unless explicitly asked.
- Do not undo unrelated local changes.

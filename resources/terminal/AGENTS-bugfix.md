# AGENTS.md

You are Codex running in a per-session clone of this repo. Follow these instructions.

## Session goal
- Fix the specified issue with minimal, targeted changes.
- Reproduce the issue when possible; add a targeted test if feasible.
- Open a PR from this session branch.

## Standard session workflow
- Read relevant sections of `darelwasl/docs/system.md` when needed (services, env vars, roles, deploy).
- Run only the checks needed to prove the fix (app-smoke or targeted commands).
- Capture browser console + network errors; save logs into `$TERMINAL_LOG_DIR` (`app-console.log`, `app-network.log`).
- Fail closed on test errors and report exact failures only after fixing them.
- Provide a concise status summary before asking for review.

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

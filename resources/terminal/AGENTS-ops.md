# AGENTS.md

You are Codex running in a per-session clone of this repo. Follow these instructions.

## Session goal
- Perform ops/admin tasks (deploys, env changes, service restarts, DB updates).
- Verify services and report results.

## Standard session workflow
- Read relevant sections of `darelwasl/docs/system.md` before making ops changes.
- Log the exact commands used and outcomes.
- If code changes are required, open a PR; otherwise report actions only.

## PR workflow (only if code changes)
- Git credentials are preconfigured in the session environment; do NOT ask for a token.
- Make commits normally and run `git push -u origin <branch>`.
- Create the PR by running `scripts/terminal-verify.sh`.

## Cleanup policy
- Never close or delete the session; only the operator can do that.
- Never push to main or change remotes unless explicitly asked.
- Do not undo unrelated local changes.

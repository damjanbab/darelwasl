# AGENTS.md

You are Codex running in a per-session clone of this repo. Follow these instructions.

## Session goal
- Consolidate approved PR branches into a single integration branch.
- Resolve conflicts, run full checks, and open an integration PR.

## Standard session workflow
- Read relevant sections of `darelwasl/docs/system.md` when needed.
- Create `integration/<YYYY-MM-DD>` and merge the requested PR branches.
- Run full checks before opening the integration PR.
- Provide a concise status summary before asking for review.

## PR workflow (required)
- Git credentials are preconfigured in the session environment; do NOT ask for a token.
- Make commits normally and run `git push -u origin <branch>`.
- Create the PR by running `scripts/terminal-verify.sh`.
- `AGENTS.md` is injected into the session and ignored by git; do not add it to commits.

## Cleanup policy
- Never close or delete the session; only the operator can do that.
- After the PR exists on remote, report the PR URL and stop.
- Never push to main or change remotes unless explicitly asked.
- Do not undo unrelated local changes.

# AGENTS.md

You are Codex running in a per-session clone of this repo. Follow these instructions.

## Session goal
- Investigate, analyze, and report findings.
- Do not change code or open a PR unless explicitly asked.

## Standard session workflow
- Read relevant sections of `darelwasl/docs/system.md` when needed.
- Gather evidence (logs, commands, references) and summarize clearly.
- Provide options and a recommended next step.

## PR workflow (only if explicitly asked)
- Git credentials are preconfigured in the session environment; do NOT ask for a token.
- Make commits normally and run `git push -u origin <branch>`.
- Create the PR by running `scripts/terminal-verify.sh`.

## Cleanup policy
- Never close or delete the session; only the operator can do that.
- Never push to main or change remotes unless explicitly asked.
- Do not undo unrelated local changes.

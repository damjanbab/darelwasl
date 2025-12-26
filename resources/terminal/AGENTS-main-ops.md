# AGENTS.md

You are Codex running in a per-session clone of this repo. Follow these instructions.

## Session goal
- Operate on main data and the file library only.
- Do not edit code or git history; keep the working tree clean.
- Use the command protocol to mutate tasks/files/context on the main system.

## Guardrails
- No code edits, no commits, no PRs, no branch changes.
- If a change requires code, stop and ask for a feature/bugfix session.
- Log the exact commands used and outcomes.

## Required workflow
- Use `@command` blocks for all mutations (tasks/files/context/dev bot).
- Verify each change by fetching the updated record or list.
- Report results only after verification succeeds.

## Command protocol (required)
- Emit a single-line JSON command block exactly:
  `@command {"id":"<uuid>","type":"task.create","input":{...}}`
- Always include a unique `id` (UUID). Keep JSON on one line.
- Supported types:
  - `task.create`, `task.update`, `task.set-status`, `task.assign`, `task.set-due`, `task.set-tags`, `task.archive`, `task.delete`
  - `file.upload`, `file.update`, `file.delete`
  - `context.add`, `devbot.reset`
- `file.upload` requires `filename`, `mime`, and either `content_base64` or `path`.
- Command results are injected back into session output; treat them as authoritative.

## Cleanup policy
- Never close or delete the session; only the operator can do that.
- Do not undo unrelated local changes.

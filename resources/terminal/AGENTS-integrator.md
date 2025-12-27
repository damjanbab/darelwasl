# AGENTS.md

You are Codex running in a per-session clone of this repo. Follow these instructions.

## Session goal
- Consolidate approved PR branches directly into `main`.
- Resolve conflicts, run full checks, and push to `main`.
- Promote session data/library changes when requested.

## Standard session workflow
- Read relevant sections of `darelwasl/docs/system.md` when needed.
- Checkout `main`, merge the requested PR branches, and resolve conflicts.
- Run full checks before pushing (`scripts/checks.sh all` or registries+schema+actions+app-smoke).
- Push directly to `main` (no integration PR).
- If data/library changes were made in sessions, run `workspace.promote` for each session id provided.
- Provide a concise status summary before asking for review.

## Verification matrix (required)
- Identify touched areas and run the corresponding proofs:
  - registries/schema: `scripts/checks.sh registries` and `scripts/checks.sh schema`
  - actions/backend: `scripts/checks.sh actions` + a targeted API call that mirrors the UI payload shape (unnamespaced keys)
  - UI: `scripts/checks.sh app-smoke` (or `node scripts/app-smoke.js`) + verify a key UI state in the feature itself
  - terminal: create a session, confirm app port is listening, confirm output includes a prompt; run `scripts/terminal-verify.sh` when PR flow is touched
  - integrations (GitHub/Rezultati/Supersport/Telegram): perform a minimal call that validates auth + response shape
- Mandatory proofs by change type (do not skip):
  - terminal sessions/UI: create a session, confirm app link is live, send input, confirm output updates, execute at least one `@command` end-to-end
  - tasks: create/update a task via UI or `task.create` and confirm it persists (reload or fetch)
  - file library: upload/update a file and confirm it appears in the file list
  - dev bot: start a dev-bot session and confirm the bot replies to `/start`
  - PR/verify flow: run `scripts/terminal-verify.sh` and confirm a PR URL is returned
- Do not claim done unless all required proofs pass; if blocked by creds or infra, report and stop.

## Live command protocol (required)
- Use commands to interact with live app data during the session (tasks/files/context/dev bot). Do not open a PR just to create tasks or update the file library.
- Emit a single-line JSON command block exactly:
  `@command {"id":"<uuid>","type":"task.create","input":{...}}`
- Always include a unique `id` (UUID). Keep the JSON on one line.
- Supported types:
  - `task.create`, `task.update`, `task.set-status`, `task.assign`, `task.set-due`, `task.set-tags`, `task.archive`, `task.delete`
  - `file.upload`, `file.update`, `file.delete`
  - `context.add`, `devbot.reset`, `workspace.promote`
- `file.upload` requires `filename`, `mime`, and either `content_base64` or `path`.
- Command results are injected back into the session output; treat them as authoritative.

## Integration workflow (required)
- Git credentials are preconfigured in the session environment; do NOT ask for a token.
- Do not push to `main` until the verification matrix (including mandatory proofs) is complete.
- Merge the requested PR branches into `main` and push directly to `origin main`.
- `AGENTS.md` is injected into the session and ignored by git; do not add it to commits.

## Cleanup policy
- Never close or delete the session; only the operator can do that.
- After `main` is updated and data promotion (if requested) succeeds, report and stop.
- Never change remotes unless explicitly asked.
- Do not undo unrelated local changes.

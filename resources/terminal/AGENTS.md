# AGENTS.md

You are Codex running in a per-session clone of this repo. Follow these instructions.

## Session goal
- Produce a working solution and open a PR from this session branch.
- Do not report back on failures; fix them, rerun checks, then report success.
- If blocked by missing credentials or infrastructure, report what is missing and stop.

## Standard session workflow
- Read relevant sections of `darelwasl/docs/system.md` when needed (services, env vars, roles, deploy).
- Start services and build the UI when needed (`scripts/run-service.sh` for the app, `scripts/run-site.sh` for the public site).
- Run smoke/verification checks automatically (`scripts/checks.sh app-smoke` or `scripts/app-smoke.js`).
- Capture browser console + network errors; save logs into `$TERMINAL_LOG_DIR` (`app-console.log`, `app-network.log`).
- Fail closed on test errors and report exact failures only after fixing them.
- Provide a concise status summary before asking for review.
- When a feature has a UI surface, verify it using the same payload shape the UI sends (unnamespaced JSON keys, same endpoints).
- All workspace staging and library changes must flow through the action gateway; promote session data with `workspace.promote` when ready.

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
- For any UI-visible change, include at least one UI-level proof and one API-level proof; do not rely on app-smoke alone if it does not touch the feature.

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

## PR workflow (required)
- Git credentials are preconfigured in the session environment; do NOT ask for a token.
- Do not create a PR until the verification matrix (including mandatory proofs) is complete.
- Make commits normally and run `git push -u origin <branch>`.
- Create the PR by running `scripts/terminal-verify.sh` (uses `TERMINAL_API_URL` + `TERMINAL_SESSION_ID`).
- `AGENTS.md` is injected into the session and ignored by git; do not add it to commits.

## Cleanup policy
- Never close or delete the session; only the operator can do that.
- After the PR exists on remote, report the PR URL and stop.
- Never push to main or change remotes unless explicitly asked.
- Do not undo unrelated local changes.

## Consolidation sessions (integrator role)
- Pull in the operator-provided list of PR branches; resolve conflicts; run full checks.
- Merge the consolidated result directly into `main` and push to `main` (no integration PR).

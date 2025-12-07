# FAQ / Gotchas

Use this document to capture recurring gotchas, clarifications, and instructions that do not fit cleanly in `docs/system.md` or `docs/protocol.md`. Keep entries concise and link to capability IDs, tasks, or files when relevant.

## Entries
- Headless CLJS smoke: use shadow-cljs with Karma/ChromeHeadless or Playwright. Wire the command into `scripts/checks.sh app-smoke` when implemented.
- Auth sessions: session cookie uses ring in-memory store (http-only, SameSite=Lax); server restarts clear sessions, so re-login before calling protected APIs.
- Playwright smoke: first run downloads Chromium/FFmpeg (network required). The harness seeds a temp Datomic under `.cpcache/datomic-smoke-*` and starts the backend on `APP_PORT` (default 3100), killing any process on that port during the check.

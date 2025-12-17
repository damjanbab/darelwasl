# FAQ / Gotchas

Use this document to capture recurring gotchas, clarifications, and instructions that do not fit cleanly in `docs/system.md` or `docs/protocol.md`. Keep entries concise and link to capability IDs, tasks, or files when relevant.

## Entries
- Headless CLJS smoke: use shadow-cljs with Karma/ChromeHeadless or Playwright. Wire the command into `scripts/checks.sh app-smoke` when implemented.
- Proof gating: pick `scripts/checks.sh` targets by capability (registries/schema/actions/import/app-smoke/all) and favor `DATOMIC_STORAGE_DIR=:mem`/`--temp` for importer/seed runs to avoid dirty dev DBs; default to `scripts/checks.sh all` if multiple layers change.
- Auth sessions: session cookie uses ring in-memory store (http-only, SameSite=Lax); server restarts clear sessions, so re-login before calling protected APIs.
- Playwright smoke: first run downloads Chromium/FFmpeg (network required). The harness seeds a temp Datomic under `.cpcache/datomic-smoke-*` and starts the backend on `APP_PORT` (default 3100), killing any process on that port during the check.
- `scripts/checks.sh app-smoke|all` installs npm deps (no-progress), ensures Playwright Chromium, and writes backend logs to `.cpcache/app-smoke.log`; requires both Node/npm and the Clojure CLI.
- Frontend dev vs backend port: shadow-cljs dev server listens on 3000; if the backend also uses 3000, set `APP_PORT` (e.g., 3001) or adjust dev-http to avoid clashes before running `npm run dev`.
- Tags are entities now (not keyword enums). `/api/tags` is the source of truth for names/IDs; tasks store tag refs. Startup runs a migration that upgrades old keyword-based tags and seeds fixtures if tags are missing.
- App switcher: on desktop, push/hover to the top edge or click the Apps trigger to reveal Home/Tasks; keyboard focus works with Esc to close. On mobile, use the Apps button in the top bar. Last selected app is remembered in localStorage.
- Hetzner deploy plan: app runs on Debian host `77.42.30.144` (`haloeddepth.com` points here) as user `darelwasl`, repo at `/opt/darelwasl`, env at `/etc/darelwasl/app.env`, Datomic storage `/var/lib/darelwasl/datomic`, binding `APP_PORT=3000`; add SSH key before prep. Inbound 22/3000 only (80/443 reserved for future proxy); outbound SMTP ports 25/465 are blocked by provider; no TLS/reverse proxy yet.
- CI deploy secrets: GitHub Actions deploy workflow expects `HETZNER_SSH_HOST`, `HETZNER_SSH_USER`, `HETZNER_SSH_KEY` secrets; ensure the SSH key matches authorized_keys on the server.
- Session refresh: the app now restores an existing session on page refresh via `/api/session`; if the server was restarted (in-memory sessions cleared), login is required again.
- Telegram integration: keep `TELEGRAM_BOT_TOKEN`/`TELEGRAM_WEBHOOK_SECRET`/`TELEGRAM_WEBHOOK_BASE_URL` in env; webhook must include `X-Telegram-Bot-Api-Secret-Token` and is disabled by default (enable with `TELEGRAM_WEBHOOK_ENABLED`). No live Telegram calls in CI—stub HTTP and use sample webhook payloads. Chat binding uses single-use link tokens (`POST /api/telegram/link-token`) and stores `:user/telegram-chat-id`; `/stop` clears it.

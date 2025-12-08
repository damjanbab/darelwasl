# System Index (`system.md`)

Single canonical map of the system. Agents must read this before working on any task.

## Invariants
- Correctness > speed; fail-closed.
- No flaky/external-dependent proofs; rely on fixtures and pinned deps.
- Browser loader/app boot must succeed; no regressions allowed.
- Changes align with capability definitions below.

## Capability Index
Maintain stable IDs; reference them in tasks/PRs.

### Boundaries & Contracts
- Every capability must be declared in its registry with required fields (ID, title, version, compatibility/flags, contracts/purpose, adapters where relevant).
- No ad-hoc or internal-only changes outside registries. If a change is not reflected in the registry, it is not allowed.
- Changes to side effects go through adapters; pure cores remain internal and composable.

### Registry Files
- Schema Registry: `registries/schema.edn`
- Action Registry: `registries/actions.edn`
- View/App Registry: `registries/views.edn`
- Integration Registry: `registries/integrations.edn`
- Tooling/Checks Registry: `registries/tooling.edn`
- Theme Registry: `registries/theme.edn`
- Each entry should use a stable `:id` and the fields outlined below; keep registries in sync with the narrative sections.

### Schema (Datomic)
- ID:
- Title:
- Version:
- Attributes (idents, types, cardinality, uniqueness, doc):
- Enums/Value Types:
- Invariants/Constraints:
- History Expectations:
- Compatibility/Flags (backward/forward expectations, gating flags/toggles):
- Related Actions/Views:

### Actions
- ID:
- Title:
- Version:
- Purpose:
- Inputs/Outputs:
- Side Effects:
- Adapter/Integration (if any):
- Audit/Logging:
- Idempotency/Retry Rules:
- Contracts/Fixtures:
- Compatibility/Flags:
- Related Schema/Views:

### Views/Apps
- ID:
- Title:
- Version:
- Purpose:
- Data Displayed (schema refs):
- Actions Invoked:
- UX Contract (states/empty/loading/error):
- Performance/Load Constraints:
- Compatibility/Flags:

### Integrations
- ID:
- Title:
- Version:
- External System:
- Contracts/SLAs:
- Auth/Secrets Handling:
- Failure Modes/Recovery:
- Compatibility/Flags:
- Related Actions/Schema:

### Tooling/Checks
- ID:
- Title:
- Version:
- Purpose:
- Inputs/Outputs or Invocation:
- Determinism/Dependencies:
- Related Capabilities and Proofs:
- Gotchas (link to `docs/faq.md` entries if any):

### Theme
- ID:
- Title:
- Version:
- Purpose:
- Colors (background/surface/text/accent/warn/danger/success/focus):
- Typography (font-family, sizes, line-heights):
- Spacing (base + scale):
- Radius:
- Shadows:
- Motion:
- Compatibility/Flags:
- Related Views/Tools:

## Patterns and Guidelines
- Data modeling: fact-first; prefer attributes over blobs; model history intentionally (use :db.cardinality/one with upserts for identity, or time-indexed facts for history); avoid duplicating derived data unless cached with clear invalidation rules; use enums/idents instead of ad-hoc strings.
- Naming: use stable, descriptive idents; keep capability IDs aligned with registry IDs; avoid abbreviations that hide meaning.
- Actions: define pure command specs (inputs/outputs) and wrap side effects in adapters; enforce idempotency rules; always emit audit records; validate invariants before effects.
- Views/Apps: register views declaratively (data needs, actions invoked, UX state contracts). Handle loading/empty/error states explicitly; no implicit global state. Keep performance constraints noted in registry.
- Integrations: use adapter pattern; isolate external contracts; provide fakes/fixtures; document failure modes and retries.
- Tooling: prefer deterministic, reproducible scripts; pin dependencies; record invocation and scope in the registry.
- Testing: use fixture-driven tests; spin a temp Datomic for schema/action checks; run headless app boot smoke; avoid flaky external calls.

## Technology Baseline
- Backend: Clojure + Datomic Local (dev) with no auth/creds. Use Datomic dev-local style connection (no cloud, no passwords).
- Frontend: CLJS + re-frame via shadow-cljs. Views registered declaratively; state handled through finite-state models.
- Headless checks: Playwright-driven smoke harness (`scripts/checks.sh app-smoke`) that builds the frontend, seeds fixtures, starts the backend, and runs a login + task render flow.

## Runtime & Commands
- Install deps: `npm install` (downloads Playwright Chromium on first run) and `npm run theme:css-vars` to regenerate token CSS; the theme step runs automatically before `npm run dev/build/check`.
- Frontend dev: `npm run dev` (shadow-cljs watch, dev-http on :3000 serving `public/`); adjust `APP_PORT` or dev-http if running the backend on the same port.
- Frontend build/check: `npm run check` for compile-only smoke; `npm run build` for a release bundle to `public/js`.
- Backend start: `clojure -M:dev` from repo root starts Jetty + Datomic dev-local helper (host `0.0.0.0`, port `3000` by default). Override via `APP_HOST`/`APP_PORT`.
- Datomic dev-local config: defaults to absolute `data/datomic` storage under the repo, system `darelwasl`, db `darelwasl`; accepts `DATOMIC_STORAGE_DIR=:mem` for ephemeral storage. Override with `DATOMIC_STORAGE_DIR`/`DATOMIC_SYSTEM`/`DATOMIC_DB_NAME`.
- Temp schema DB: use `darelwasl.schema/temp-db-with-schema!` with `(:datomic (darelwasl.config/load-config))` (override `:storage-dir` to `:mem`) to spin an ephemeral DB preloaded from `registries/schema.edn`; prefer `darelwasl.schema/with-temp-db` to ensure cleanup after checks.
- Fixture seed: `clojure -M:seed` loads schema + fixtures into the configured dev-local DB; `clojure -M:seed --temp` seeds a temp Datomic (:mem by default). Use `darelwasl.fixtures/with-temp-fixtures` for tests/headless checks that need isolated data.
- Checks: `scripts/checks.sh registries|schema|actions|app-smoke|all` (registry presence + EDN parse, schema load, action contracts, headless app smoke). Use `all` before merging.
- App smoke run: `scripts/checks.sh app-smoke` (or `all`) seeds fixtures into a temp Datomic storage under `.cpcache/datomic-smoke-*`, builds the frontend (`npm run check`), starts the backend on `APP_PORT` (default 3100), and runs Playwright headless login + task rendering. Requires Node/npm and initial Playwright browser download.
- Health check: `curl http://localhost:3000/health` returns JSON with service status and Datomic readiness.
- Auth login: `POST http://localhost:3000/api/login` with JSON `{"user/username":"huda","user/password":"Damjan1!"}` (fixtures/users.edn) returns `{ :session/token ..., :user/id ..., :user/username ... }` and sets an http-only SameSite=Lax session cookie backed by an in-memory store; server restarts clear sessions.
- Task API (requires session cookie from `/api/login`):
  - GET `/api/tasks` supports filters `status`, `priority`, `tag`, `assignee`, `archived` (defaults to active tasks; use `archived=all` to include archived) and sort/order (`updated` default desc, `due` default asc, `priority` default desc).
  - POST `/api/tasks` creates a task with title/description/status/assignee/priority plus optional `task/due-date` (ISO-8601), `task/archived?`/`task/extended?` booleans, and `task/tags` as a set of `:tag/id` UUIDs; values are validated against enums/refs and assignees must exist.
  - PUT `/api/tasks/:id` updates title/description/priority/tags/extended?; POST helpers: `/api/tasks/:id/status`, `/assignee`, `/due-date` (null clears), `/tags` (replaces set), `/archive` (toggle archived flag).
  - Tag API (requires session): GET `/api/tags` lists tag entities (id + name sorted by name), POST `/api/tags` creates, PUT `/api/tags/:id` renames, DELETE `/api/tags/:id` removes and detaches from tasks; duplicate names return 409.
  - Example (seed dev DB first):
    ```
    clojure -M:seed
    curl -c /tmp/dw-cookies.txt -H "Content-Type: application/json" -d '{"user/username":"huda","user/password":"Damjan1!"}' http://localhost:3000/api/login
    curl -b /tmp/dw-cookies.txt "http://localhost:3000/api/tasks?status=todo&sort=due&order=asc"
    curl -b /tmp/dw-cookies.txt -H "Content-Type: application/json" -d '{"task/title":"Draft API doc","task/description":"Check filters","task/status":"todo","task/assignee":"00000000-0000-0000-0000-000000000001","task/priority":"high","task/tags":["30000000-0000-0000-0000-000000000001"]}' http://localhost:3000/api/tasks
    ```

## Deployment (Hetzner plan)
- Host: Debian (CPX22) at IPv4 `77.42.30.144` (IPv6 /64 available); `haloeddepth.com` points here; no reverse proxy/SSL yet—serve on the raw IP/domain for now (port 3000).
- Service user and paths: create `darelwasl` user; clone repo to `/opt/darelwasl`; env file at `/etc/darelwasl/app.env` (owned by `darelwasl`, root-readable); Datomic storage under `/var/lib/darelwasl/datomic`.
- Runtime bind: `APP_HOST=0.0.0.0`, `APP_PORT=3000`; reserve 80/443 for a future reverse proxy/SSL front.
- Ports/firewall: allow inbound 22 (SSH) and 3000 (app); add 80/443 only when proxy is configured; outbound SMTP 25/465 blocked by provider (not used).
- Logging: systemd journal for the app service (no separate log files initially); optional logrotate can be added later.
- SSH: add maintainer public key to root and `darelwasl` user `~/.ssh/authorized_keys` before running prep; use repo origin over HTTPS per protocol.
- Env file template (`/etc/darelwasl/app.env`):
  - `APP_HOST=0.0.0.0`
  - `APP_PORT=3000`
  - `DATOMIC_STORAGE_DIR=/var/lib/darelwasl/datomic`
  - `DATOMIC_SYSTEM=darelwasl`
  - `DATOMIC_DB_NAME=darelwasl`
  - Optional: `NODE_ENV=production`, JVM opts via `JAVA_OPTS` if needed.
- Systemd unit (`/etc/systemd/system/darelwasl.service`):
  ```
  [Unit]
  Description=DarelWasl task app
  After=network.target

  [Service]
  Type=simple
  User=darelwasl
  WorkingDirectory=/opt/darelwasl
  EnvironmentFile=/etc/darelwasl/app.env
  ExecStart=/opt/darelwasl/scripts/run-service.sh
  Restart=on-failure
  RestartSec=5
  StandardOutput=journal
  StandardError=journal

  [Install]
  WantedBy=multi-user.target
  ```
  Reload/enable: `sudo systemctl daemon-reload && sudo systemctl enable --now darelwasl`.
- CI deploy: GitHub Actions workflow `.github/workflows/deploy.yml` (triggers on `main` push) SSHes to the host using secrets `HETZNER_SSH_HOST`, `HETZNER_SSH_USER`, `HETZNER_SSH_KEY` and runs `/opt/darelwasl/scripts/deploy.sh` then `systemctl restart darelwasl`.
- Service ops (Hetzner):
  - Deploy manually on server: `cd /opt/darelwasl && sudo -u darelwasl ./scripts/deploy.sh && sudo systemctl restart darelwasl`.
  - Service commands: `systemctl status darelwasl`, `journalctl -u darelwasl -f`, `systemctl restart darelwasl`, `systemctl stop darelwasl`.
  - Health: `curl http://127.0.0.1:3000/health` (or use `http://haloeddepth.com:3000/health` while exposed).
  - Secrets in GitHub: set `HETZNER_SSH_HOST=77.42.30.144`, `HETZNER_SSH_USER=root` (or deploy user), `HETZNER_SSH_KEY` (private key matching server authorized_keys).

## Product Spec: Entity Foundation + App Suite (Home + Tasks)
- Entities and identity:
  - All persisted entities carry `:entity/type` (additive discriminator) alongside their type-specific attrs. Current types: `:entity.type/user`, `:entity.type/task`, `:entity.type/tag`. Future types follow the same pattern (type ident + attrs + refs).
  - Relationships stay Datomic refs (e.g., task → assignee/user, task → tags). History remains via Datomic tx log; no runtime DSL.
  - Backfill/migration: existing user/task/tag rows get `:entity/type` set by migration; fixtures and seeds include it going forward.
- Apps:
  - Home app (default after login): cross-entity summary surface showing a hero + quick actions (e.g., “New task”), recent/updated tasks (subset), task status counts, tag highlights, and room for a small “recent activity” list derived from updated-at timestamps. Uses existing task data/APIs; no new entity types yet.
  - Task app: retains full current behavior (list/detail, filters/sorts, tag management, archive/delete, status/assignee/due/priority/extended flag). Implementation will reuse shared entity primitives but preserve all existing flows.
  - Login remains unchanged (auth required before Home/Tasks).
- Navigation and app switcher:
  - After login, route to Home. A modern app switcher lives at the top edge; pushing/hovering the pointer to the top reveals a drop tab with options (Home, Tasks). Keyboard/focusable with aria labels; mobile-friendly tap target. Remember last visited app if feasible.
  - Switcher does not change URLs beyond view state unless routing is later introduced; respects session guard (unauthenticated users see login only).
- UX/state:
  - Home supports loading/empty/error/ready states. Empty: show helpful copy if no tasks exist. Error: retry affordance. Ready: cards for recents/stats/tags/quick actions.
  - Tasks keep explicit states already defined; entity primitives must not remove loading/empty/error handling.
- Acceptance:
  - Logging in as huda or damjan lands on Home with seeded summary content; app switcher visible/usable (hover/push + click/tap + keyboard). Switching to Tasks shows full task functionality unchanged.
  - `:entity/type` present on user/task/tag entities in seeds and migrated dev DB; no API regressions for tasks or tags.
  - No runtime extensibility required; overrides remain code-based (per-type config/override maps), not registry-executed.

## Product Spec: Task App v1 (two users)
- Users: two seeded users (`huda`, `damjan`) sharing password `Damjan1!`. Login required before accessing tasks.
- Task fields: title (required), description (rich text allowed), status (enum: todo/in-progress/done), assignee (user), due date (optional), priority (enum: low/medium/high), tags (set of tag entities via `:tag/id`), archived flag, feature flag `:task/extended?` (default false) for future fields.
- Actions: create/edit task; change status; assign/reassign; set/clear due date; add/remove/rename/delete tags; archive/unarchive; login (auth action).
- Views: login screen; task list with filters (status, assignee, tag, priority) and sorts (due date, priority, updated); detail side panel for edit/view; inline tag management without leaving the task view; light/dark theme toggle pinned bottom-left. Task app is selectable from the app switcher and may share list/detail primitives with Home.
- UX: explicit loading/empty/error/ready states; inline validation for required fields; keyboard shortcut for new/save; responsive layout (desktop list + side panel; mobile stacked).
- Acceptance: After login as huda or damjan, user can create/edit tasks, change status, assign, set due, manage tags (create/attach/rename/delete), archive, filter/sort; UI shows states correctly; theme (light/dark) applied and switchable; headless smoke passes.

## Design Spec: Home + App Switcher + Entity Primitives
- Home (default post-login):
  - Layout (desktop): hero row with greeting + quick actions (e.g., “New task”), summary cards (status counts), recent tasks list (subset from tasks data), tag highlights (chips/cloud), optional small “recent activity” list using updated-at. Two-column where space allows; cards align to grid; padding consistent with theme tokens.
  - Layout (mobile): stacked sections; summary cards in a horizontal scroll or 2-up grid; recent list collapses to compact cards; quick actions as prominent buttons.
  - States: loading (skeletons for cards/list), empty (friendly copy when no tasks), error (inline message + retry). Ready state shows cards and lists.
  - Interactions: recent tasks and tag chips link into Task app with appropriate filters; quick action opens Task create flow (same route).
  - Theming: reuse existing tokens; no new colors. Motion: subtle fade/slide for cards; keep 150ms ease.
- App switcher:
  - Affordance: pushing/hovering pointer to top edge reveals a drop tab with app options (Home, Tasks); clearly labeled with icons/text. On mobile, a tap target (e.g., top bar button) reveals the same menu.
  - Accessibility: keyboard reachable (focusable trigger, arrow/tab to select, Enter/Space to activate). Aria labels on trigger and menu items. Escape or leaving the area closes it; retains focus appropriately.
  - Behavior: remembers last selected app if feasible; does not expose unauthenticated apps; respects session guard.
  - Motion: smooth slide-down for the tab; no excessive animation.
- Entity primitives (UI):
  - Shared list/detail components can be configured per `:entity/type` in code (field definitions, renderers, actions). Task app uses these; Home can reuse list snippets for recent tasks.
  - Overrides live in code, not registries; config maps keyed by type provide labels/formatters.

## Design Spec & Theming
- Vibe: calm, professional; warm neutrals with teal accent.
- Layout: desktop-first; list on left, detail side panel on right; mobile: stacked with slide-up detail.
- Typography: clean sans (Inter/IBM Plex Sans), hierarchy for title/section/body/meta.
- Components: buttons (primary/secondary/ghost), inputs/textarea/select, tag chips, status/priority badges, cards for list rows, empty/error states.
- Theming: tokens stored in `registries/theme.edn`; generate CSS vars; components must consume tokens/vars only (no hardcoded colors/spacing/fonts). Use radius/shadow/motion tokens for consistency. Light (`:theme/default`) and dark (`:theme/dark`) palettes ship together; UI exposes a sun/moon toggle.
- Login: dedicated screen before tasks; uses same theme/tokens; shows error states clearly; supports shared password flow; minimal hero copy.
- Theme tokens: default `:theme/default` uses background `#F7F4EF`, surface `#FFFFFF`, muted surface `#F0ECE6`, text `#1F2933/#52606D`, accent `#0FA3B1` with strong `#0B7E89`, focus color matches accent, warning/danger/success use `#F59E0B/#D14343/#2D9D78`. Typography uses `Inter, "IBM Plex Sans", system-ui, -apple-system, sans-serif` with sizes 20/16/14/12px and matching line heights 28/22/20/16px. Spacing base 4px with 4–32px scale, radius 4/8/12px, card shadow `0 6px 20px rgba(0,0,0,0.06)`, motion transition `150ms ease`.
- Theme CSS vars: `npm run theme:css-vars` writes `public/css/theme.css` from `registries/theme.edn` (defaults to `:theme/default`) and runs automatically before `npm run dev/build/check`; `public/index.html` loads it ahead of `public/css/main.css`, which uses only generated tokens (no hardcoded fallbacks).

## Composability Rules
- Schema: reuse existing enums/refs when semantics match; avoid duplicating attributes; introduce new attrs only with clear invariants. Shared attributes must not change meaning between entities.
- Actions: separate pure core logic from effect adapters; design inputs/outputs to be reusable; enforce idempotency so actions can be composed safely. Do not couple actions directly to UI concerns.
- Views/Apps: consume data/actions via registries; reuse shared state-handling components; do not embed action logic in views—invoke registered actions. Respect declared loading/empty/error states.
- Tooling/Checks: extend shared entry points (e.g., `scripts/checks.sh`) instead of per-task scripts. New tools belong in `registries/tooling.edn` with stable IDs and scope.
- Integrations: wrap external systems behind adapters; expose contracts via the integration registry; provide fakes for composition in tests.
- Process: when a change affects composability rules, update this section and the relevant registry entries in the same run; tasks must declare their composability impact.
## Fixtures and Test Data
- :fixtures/users (`fixtures/users.edn`): two dev users (`huda` -> `00000000-0000-0000-0000-000000000001`, `damjan` -> `00000000-0000-0000-0000-000000000002`) sharing password `Damjan1!`. Used by auth/login action contracts and any seed tasks; registry checks ensure required keys, unique usernames/IDs, and that tasks reference these users.
- :fixtures/tags (`fixtures/tags.edn`): tag entities with fixed IDs and names (`Ops`, `Home`, `Finance`, `Urgent`) used by tasks and exposed via `/api/tags`.
- :fixtures/tasks (`fixtures/tasks.edn`): four tasks covering all status/priority enums and tag references (lookup refs to `:tag/id`), with due-date variety for sort/filter checks, one archived entry, and one flagged with `:task/extended?` true. Assignees reference the user fixture IDs.
- Loader tooling: `darelwasl.fixtures/seed-dev!` and CLI `clojure -M:seed [--temp]` load schema + fixtures (users first, then tasks with lookup refs). Use `darelwasl.fixtures/temp-db-with-fixtures!` or `with-temp-fixtures` to spin disposable DBs for checks.
- Determinism: fixture UUIDs and timestamps are fixed; loaders insert users before tasks to satisfy refs. Reuse fixtures in schema-load/action-contract/app-smoke checks for predictable state.

## Change Rules
- When adding/updating capabilities, update the relevant section with a stable ID.
- Update the registries in `registries/` to match narrative changes here.
- Record new fixtures and link them to capabilities.
- If a new pattern emerges, document it here and reference it in tasks/PRs.
- Include version and compatibility/flag information in every registry entry; additive changes are preferred.
- Run `scripts/checks.sh registries` to ensure required fields are present before merge.
- Branching/PR flow: main auto-deploys to Hetzner; each run works on a branch `run/<run-id>`, with task branches `run/<run-id>/<task-id>` merging into the run branch via PR. Merge the run branch to `main` via PR only after the run is complete, all proofs are green, and the product owner gives a manual green-signal.

## Change & Compatibility
- Flags/Rollout: introduce new behaviors behind flags or capability toggles; document defaults and rollout plan.
- Compatibility Policy: prefer backward-compatible changes; if breaking, document mitigations and timelines here and in tasks.
- Schema Evolution: favor additive changes; if changing meaning, treat as new attribute/enum; document migration/backfill needs.
- Action/View Contracts: version or gate changes; keep prior contracts working until deprecation window ends.
- Deprecation: declare deprecated items, timeline, and removal steps; ensure tests/fixtures cover both old/new until removal.
- Flags must be represented as explicit attributes/enums with defaults; no implicit string checks.

## Anti-Patterns (reject these)
- Speculative abstractions (“super” modules) that obscure invariants.
- Ad-hoc strings instead of enums/idents; changing meaning of an ident.
- Per-task bespoke scripts/tools that bypass shared tooling and registries.
- Coupling actions directly to UI concerns; skipping adapters for side effects.
- Views that bypass registries or share implicit global state.

## Starter Scaffolding
- Registries: `registries/schema.edn`, `registries/actions.edn`, `registries/views.edn`, `registries/integrations.edn`, `registries/tooling.edn` (placeholder entries to copy/extend).
- Checks harness: `scripts/checks.sh` (registry presence + EDN parse + schema load into a temp Datomic via `schema`; action contracts for auth/tasks via `scripts/checks.sh actions`; views/app-smoke remain stubs until implemented). Extend with real commands as the codebase grows.
- Datomic helpers: `darelwasl.db` (dev-local client/connection helpers) and `darelwasl.schema` (registry reader + schema transact + temp DB helper defaulting to `:mem`) are the entry points for backend schema checks.
- Theme CSS variable generator: `scripts/theme-css-vars.sh` (registry-driven, registered as `:cap/tooling/theme-css-vars`) with npm wrapper `npm run theme:css-vars` that writes `public/css/theme.css` (auto-run before dev/build/check) so the UI shell pulls theme vars.
- Frontend scaffold: `package.json`, `shadow-cljs.edn`, `public/index.html`, `public/css/main.css`, and `src/darelwasl/app.cljs` (re-frame shell). Commands: `npm install`; `npm run dev` (shadow-cljs watch with dev-http on :3000 serving `public/`); `npm run build` (release build to `public/js`); `npm run check` (compile-only smoke).

## Glossary (optional)
- Domain terms and definitions to keep naming consistent.

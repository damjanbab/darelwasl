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
- Pending/new capabilities for site control: `:cap/view/control-panel`, `:cap/schema/content-page`, `:cap/schema/content-block`, `:cap/schema/content-tag`, `:cap/action/content-pages`, `:cap/action/content-blocks`, `:cap/action/content-tags` (stubs added).

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
- Registry dump (admin-only): `GET /api/registries` with optional `?name=` for diagnostics; returns raw registry EDN.

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

## Content Model (Control Panel + Public Site)
- Entities: content tags (`:content.tag/id|name|slug|description`), content pages (`:content.page/id|title|path|summary|navigation-order|visible?` plus tags + blocks), content blocks (`:content.block/id|page|type|title|body|media-ref|slug|order|visible?` plus tags). Block types enum: `:hero`, `:section`, `:rich-text`, `:feature`, `:cta`, `:list`.
- Storage: Datomic schema added (`:cap/schema/content-tag`, `:cap/schema/content-page`, `:cap/schema/content-block`) with :entity/type on all content entities; slugs and IDs are unique; order fields are longs.
- Fixtures: `fixtures/content.edn` seeds Home and About pages with ordered blocks and tags; seeding wires block→page refs and page→block refs after blocks exist.
- Invariants: Paths/slugs non-empty and unique; blocks that reference a page must point at that page; tags referenced by pages/blocks must exist; visibility toggles default to true in fixtures.
- Actions/API: Authenticated CRUD under `/api/content/{tags|pages|blocks}`; create/update validate slugs, paths, block types, refs, and unique constraints; mutations audit log under `darelwasl.content`. Read endpoint `/api/content/v2` returns Saudi license site entities (licenses, comparison rows, journey/activation, personas, support entries, hero stats/flows, FAQs, values, team, business/contact).

## Telegram Integration (tasks app)
- Capabilities: :cap/integration/telegram-bot with actions :cap/action/telegram-send-message, :cap/action/telegram-set-webhook, :cap/action/telegram-handle-update.
- Auth/config: env vars `TELEGRAM_BOT_TOKEN`, `TELEGRAM_WEBHOOK_SECRET`, `TELEGRAM_WEBHOOK_BASE_URL`; flags `TELEGRAM_WEBHOOK_ENABLED` (default false), `TELEGRAM_COMMANDS_ENABLED` (default true when webhook enabled), `TELEGRAM_NOTIFICATIONS_ENABLED` (default false). HTTP calls timeout at 3s; link tokens expire by `TELEGRAM_LINK_TOKEN_TTL_MS` (default 900000). No live Telegram calls in CI (use stubs/fixtures).
- Auto webhook (prod-friendly): when `TELEGRAM_WEBHOOK_ENABLED=true` and `TELEGRAM_WEBHOOK_BASE_URL`/`TELEGRAM_WEBHOOK_SECRET`/`TELEGRAM_BOT_TOKEN` are set, the app calls setWebhook on startup (set `TELEGRAM_AUTO_SET_WEBHOOK=false` to disable).
- Webhook: POST `/api/telegram/webhook` must include `X-Telegram-Bot-Api-Secret-Token`; reject missing/mismatched secret. Allowed updates include `message` and `callback_query`.
- Commands: `/start <link-token>` binds chat to user via one-time token; `/help` lists commands; `/tasks` returns a task list message with filter buttons + per-task “Open” buttons; `/task <uuid>` returns a task card; `/new <title> [| description]` creates a task assigned to the mapped user; `/stop` clears chat mapping. All except `/help` require a chat→user mapping.
- Freeform capture: non-command text shows a capture prompt with inline buttons (“Create task”, “Dismiss”) and only creates on confirmation.
- Inline UX: task cards include inline buttons for status, archive/unarchive, and refresh; list message filters by status/archived and updates in-place.
- Data/mapping: store optional `:user/telegram-chat-id` (unique), `:user/telegram-user-id` (unique for auto-recognition), `:user/telegram-link-token` (single-use, unique), and `:user/telegram-link-token-created-at`. `/start` writes mapping and clears the token fields; `/stop` clears mapping. Link tokens are generated via `POST /api/telegram/link-token` (self-service; admin can generate for others). Recognized users can be registered via `POST /api/telegram/recognize`.
- Auto-recognition: when incoming `from.id` matches `:user/telegram-user-id`, the bot auto-binds chat id to that user (token optional). `scripts/tg-spinup.sh` will register the id from `.secrets/telegram_user_id` if present.
- Notifications: when enabled and a chat is mapped, task events enqueue to the outbox; the worker delivers Telegram messages with retries/backoff. Messages are short and idempotent by `message-key`.
- Ops: use `TELEGRAM_WEBHOOK_BASE_URL` with `telegram-set-webhook`; verify via `getWebhookInfo`. Keep webhook disabled by default; enable flags and secrets explicitly before production use.

## Rezultati Integration (betting CLV)
- Capabilities: :cap/integration/rezultati provides on-demand access to daily match lists and bookmaker odds (1X2) for reference baskets.
- Auth/config: optional `REZULTATI_BASE_URL` (defaults to `https://m.rezultati.com`), `REZULTATI_TIMEOUT_MS` (default 3000), and `REZULTATI_CACHE_TTL_MS` (default 30000).
- Endpoints: `/?d={day}&s=5` (daily soccer odds list), `/utakmica/{id}/?s=5` (match detail odds).
- Guardrails: no high-frequency polling; user-triggered refresh only. A lightweight scheduler may capture close snapshots near kickoff.
- CI: no live Rezultati calls in CI; use stubs/fixtures in tests.

## Betting Config (CLV)
- Reference basket: `BETTING_REFERENCE_BOOKS` (CSV), fallback via `BETTING_FALLBACK_BOOKS` (CSV). Defaults to Pinnacle, Betfair Exchange, SBO/SBOBET, bet365, Unibet (fallback bet365 + Unibet).
- Execution book: `BETTING_EXECUTION_BOOK` (default `supersport.hr`) and Supersport base/timeout via `SUPERSPORT_BASE_URL`, `SUPERSPORT_TIMEOUT_MS`.
- Close scheduler: `BETTING_SCHEDULER_ENABLED` (default true), `BETTING_SCHEDULER_POLL_MS` (default 60000), `BETTING_CLOSE_OFFSET_MINUTES` (default 10), `BETTING_EVENT_HORIZON_HOURS` (default 72).

## Terminal Service (Codex Sessions)
- Separate daemon (not tied to the main app). Sessions survive app restarts and UI closes.
- Access gated by role `:role/codex-terminal` (only Damjan for now).
- Terminal service runs locally (default `TERMINAL_HOST=127.0.0.1`, `TERMINAL_PORT=4010`); main app proxies `/api/terminal/*` to it.
- Sessions persist until an operator explicitly completes them; PR verification does not close sessions.
- Dev bot use is opt-in per session; only one session may run the dev bot at a time.
- On complete/delete, repo + datomic + chat transcript are deleted; logs/worklogs are retained.
- Session storage:
  - Work dirs: `TERMINAL_WORK_DIR` (default `data/terminal/sessions`)
  - Logs/worklogs: `TERMINAL_LOG_DIR` (default `data/terminal/logs`) retained forever
- Terminal config envs:
  - `TERMINAL_API_URL` (optional override of base url)
  - `TERMINAL_ADMIN_TOKEN` (required to complete sessions)
  - `TERMINAL_DATA_DIR`
  - `TERMINAL_WORK_DIR`
  - `TERMINAL_LOG_DIR`
  - `TERMINAL_REPO_URL` (repo clone for sessions)
  - `TERMINAL_CODEX_CMD` (default `codex`)
  - `TERMINAL_TMUX_BIN` (optional full path to tmux binary)
  - `TERMINAL_GIT_NAME`, `TERMINAL_GIT_EMAIL` (git identity for session commits)
  - `TERMINAL_GITHUB_TOKEN` (token used for git push + PR creation inside sessions)
  - `TERMINAL_PORT_RANGE_START`, `TERMINAL_PORT_RANGE_END` (per-session app/site ports)
  - `TERMINAL_POLL_MS` (output polling interval)
  - `TERMINAL_MAX_OUTPUT_BYTES` (per poll)
  - `TELEGRAM_DEV_BOT_TOKEN` (use dev bot for terminal sessions)
  - `TELEGRAM_DEV_WEBHOOK_SECRET`, `TELEGRAM_DEV_WEBHOOK_BASE_URL` (optional dev webhook config)
  - `TELEGRAM_DEV_WEBHOOK_ENABLED`, `TELEGRAM_DEV_COMMANDS_ENABLED`, `TELEGRAM_DEV_NOTIFICATIONS_ENABLED` (optional dev flags)
  - `TELEGRAM_DEV_HTTP_TIMEOUT_MS`, `TELEGRAM_DEV_LINK_TOKEN_TTL_MS` (optional dev overrides)

## Auth Sessions
- Session cookies are backed by an on-disk store so restarts do not log users out.
- Configure `SESSION_STORE_PATH` (default `data/sessions.edn`) to control where session data is persisted.

### Content Model v2 (Saudi license site – implemented schema)
- Goal: structure the intuitionsite content into first-class entities while keeping current content pages/blocks valid. All new fields are additive/optional; existing content renders without v2 data.
- Entities:
  - `:content.page` adds optional `:content.page/section-type` enum (`:section/hero`, `:section/services`, `:section/comparison`, `:section/journey`, `:section/personas`, `:section/support`, `:section/faq`, `:section/contact`, `:section/about`) to hint which data set a section uses.
  - Business (`:entity.type/business`): id, name, tagline, summary, mission, vision, nav label, hero headline/strapline, contact ref, hero stat refs, hero flow refs, visible?.
  - Contact (`:entity.type/contact`): id, email, phone, primary/secondary CTA labels + URLs.
  - License (`:entity.type/license`): id, type enum (`:license.type/general|entrepreneur|gcc`), slug, label, processing-time, ownership, renewal-cost, pricing-lines (string set), activities (string set), who (string set), who-activities (string set), document-checklist (string set), order, visible?.
  - Comparison row (`:entity.type/comparison-row`): id, criterion, order, entrepreneur/general/gcc value strings.
  - Journey phase (`:entity.type/journey-phase`): id, title, kind enum (`:phase/pre-incorporation|incorporation|post-incorporation`), order, bullets (string set).
  - Activation step (`:entity.type/activation-step`): id, title, order, optional phase ref.
  - Persona (`:entity.type/persona`): id, title, detail, optional type keyword, order, visible?.
  - Support entry (`:entity.type/support-entry`): id, role enum (`:support/we`, `:support/you`), text, order.
  - Hero stat/flow (`:entity.type/hero-stat`, `:entity.type/hero-flow`): id, label/value/hint/order for stats; title/detail/order for flows.
  - FAQ (`:entity.type/faq`): id, question, answer, optional scope keyword, order, visible?.
  - Value/team (`:entity.type/value`, `:entity.type/team-member`): id, title/copy/order for values; id, name/title/order/avatar for team. Both can be linked from the business/about section.
- Relationships & invariants:
  - Every entity sets `:entity/type` and uses UUID identity; slugs unique where present.
  - Section-type hint is optional; v1 content pages/blocks remain valid.
  - Lists (pricing-lines, activities, bullets, who, FAQ, etc.) are stored as string sets; ordering is driven by per-entity `:.../order` fields where present, otherwise callers should sort deterministically.
  - License type/phase/support role enums validated via schema; visibility booleans default true in fixtures.
- Compatibility/flags: additive; no existing content removed. Public site renders v2 sections by default (no flag presently).
- Fixtures: `fixtures/content.edn` now seeds the Saudi license site content (business/contact, licenses, comparison rows, journey/activation steps, personas, support roles, hero stats/flows, FAQs, values, team) alongside the presentation/about pages and blocks; IDs are fixed and carry `:entity/type` for backfill.

## Public Site Design Contract (run-site-premium-001)
- Visual tone: “calm authority” with strong hierarchy, generous whitespace, minimal decoration; high-trust cues via early stats/proof and consistent primary CTA.
- Nav & CTA: top-level nav items = Home, Services, Comparison, Process, About, Contact; site-wide primary CTA button = “Schedule a meeting”; max 2 levels (Services may list up to 6–9 leaves; others single level); mobile hamburger must be keyboard navigable with visible focus and Escape to close.
- IA & sections:
  - Home: only page with dark, full-bleed hero; includes trust strip, offer overview (3 cards), “How it works” (3 steps + rail), “Choose a path” teaser (3 cards + rail), proof, short FAQ, global footer CTA band.
  - Services: light hero; license selector tabs (General/Entrepreneur/GCC) with single detail panel; outcomes; FAQ; global footer CTA band.
  - Comparison: light hero with summary + “How to read this”; table with recommended column highlighted; global footer CTA band.
  - Process: light hero with summary; journey/activation timeline; global footer CTA band.
  - About: principles only (no placeholder team); global footer CTA band.
  - Contact: light hero + funnel steps; contact CTAs; global footer CTA band.
- Motifs & rails: keep a single Evidence Pill style for proof/meta labels; Step Rail only in “How it works” and “Choose a path”; functional funnel indicator (Select → Compare → Schedule) on Home/Services/Comparison/Contact with current step highlighted; remove other decorative gates/handles.
- CTA: one global footer CTA band above the footer (static) instead of per-page CTA cards.
- Layout rhythm: dark hero on Home; rest of body is light; compact footer (not a second nav maze).
- Data & ordering: render from existing v2 content entities (licenses, comparison rows, journey/activation, personas/support, FAQs, values/team, business/contact, hero stats/flows); respect visibility flags; deterministic ordering by `.../order` with stable fallback (id/label) when order missing.
- Tokens only: no hardcoded colors/spacing/typography or component sizing—use generated theme CSS variables (colors/typography/spacing/radius/shadows/motion/components). Public site uses `:theme/site-premium` tokens (v2) with `data-theme="site-premium"` and keeps its current visual tone.
- Responsive & a11y: centered max-width, no horizontal scroll; header/nav/CTA remain accessible on desktop/mobile; tap targets ≥44px; keyboard navigable menus; visible focus; no hover-only affordances.

## Patterns and Guidelines
- Data modeling: fact-first; prefer attributes over blobs; model history intentionally (use :db.cardinality/one with upserts for identity, or time-indexed facts for history); avoid duplicating derived data unless cached with clear invalidation rules; use enums/idents instead of ad-hoc strings.
- Naming: use stable, descriptive idents; keep capability IDs aligned with registry IDs; avoid abbreviations that hide meaning.
- Actions: define pure command specs (inputs/outputs) and wrap side effects in adapters; enforce idempotency rules; always emit audit records; validate invariants before effects.
- Views/Apps: register views declaratively (data needs, actions invoked, UX state contracts). Handle loading/empty/error states explicitly; no implicit global state. Keep performance constraints noted in registry.
- Integrations: use adapter pattern; isolate external contracts; provide fakes/fixtures; document failure modes and retries.
- Tooling: prefer deterministic, reproducible scripts; pin dependencies; record invocation and scope in the registry.
- Testing: use fixture-driven tests; spin a temp Datomic for schema/action checks; run headless app boot smoke; avoid flaky external calls.
- Entity view primitives: define list/detail render configs per `:entity/type` in `src/darelwasl/ui/entity.cljs` (title/meta/key + render-row + detail shells). Home/Tasks/Land must consume these instead of ad-hoc list/detail code; add new entity configs rather than hard-coding UI per feature. Stable keys come from the config `:key`; row renderers must be pure and state-free.
- Entity list UI: shared `entity-list` supports optional chip bars for quick filters; chips are passed from the view (label/active/on-click). Keep list/detail loading/empty/error handling explicit.
- Pagination/perf guardrails: list endpoints (tasks; land people/parcels) default to `limit=25`, `offset=0`, with a max limit of 200 and server-side bounding. Clients must send limit/offset (or page/page-size in filters) and render pagination controls (shared `pagination-controls` UI). Responses return `:pagination {:total :limit :offset :page :returned}`; update UI state from these fields.
- Importer idempotency: land importer uses deterministic IDs for person/parcel/ownership rows; re-running the same CSV against the same DB must not change counts or create duplicates. Proof is enforced via `scripts/checks.sh import` (runs importer twice in a temp DB and compares counts).
- Observability: HTTP handlers emit structured logs with method/path/status/user-id/duration; importer logs duration and counts. Keep logs low-noise and deterministic; timing is best-effort for local diagnosis, not SLOs.
- Entity modeling (types/categories):
  - Every entity has exactly one primary `:entity/type`; do not make one entity “be” multiple types.
  - Use a dedicated subtype/kind field when behavior or schema differs; use tags for fuzzy, cross-cutting labels only.
  - Keep status as its own axis (lifecycle), never encoded in type or subtype.
  - Relationships are plain refs unless the link carries its own data (percent, role, dates); if the link has data, model it as its own entity.
  - Roles belong on the relationship (or a relationship entity), not on the entity’s primary type.
  - Add structure because it answers a real query; avoid speculative categories. Start coarse and split later rather than over-fragment early.
  - New types must not break existing queries/views; defaults should be sensible when subtype/kind is absent.
- Design standards (UI):
  - Use shared primitives: `form-input`/`select-field`/`button`, `entity-list` + `list-row`, `task-card`, `stat-group`/`stat-card`, `tag-highlights`, `assignee-pill`, land detail shells (`land-person-detail-view`, `land-parcel-detail-view`), and loading/empty/error states (`home-loading`, `loading-state`, `land-*`). Do not hand-roll new variants without extending the shared component lib.
  - Tokens only: colors, spacing, radius, typography, and component sizing must come from the theme CSS variables; no hardcoded hex/rgb/px outside the spacing/typography scale. Reuse existing classes (`panel`, `card`, `summary-cards`, `chip`, `badge`, `button`, `list-row`) instead of custom styling.
  - Layout defaults: two/three-column shells use the existing CSS grid (`home-layout`, `tasks-layout`, `land-layout`) and stack to single column on narrow screens; avoid horizontal scroll and fixed widths. Controls live in `.section-header` with right-aligned actions; footers via `app-shell`.
  - State handling: every view/list/detail renders explicit loading/empty/error UI (shared components) and must be resilient to empty collections. Keys for list items must be stable (entity IDs) with a deterministic fallback.
  - Accessibility & interaction: keep focusable controls as `<button>/<input>/<select>` with existing classes; ensure app switcher remains keyboard/focus friendly; avoid side effects in render (no async work in component bodies—dispatch effects in events/subs).
  - Responsive layout: ≤768px stacks to single column; >768px can use 2–3 columns. No horizontal scroll; cards wrap. Primary list/detail sit side-by-side on desktop, stack on mobile.
  - Navigation & context: app switcher/top bar always present; detail stays in-place (no route change). Filters persist when switching routes. Mobile detail offers “Back to list.”
  - Scrolling & density: keep filters/controls in headers (no full-page drawers); paginate or chunk long lists; keep rows compact (one title + one meta line; truncate long text).
  - Touch & keyboard: tap targets ≥44px height; 12px spacing between controls. All controls tab-focusable; ESC closes menus; Enter/Space activates. Hover-only affordances must have visible touch equivalents.
  - Forms: inline where possible; labels above inputs; show inline validation; prefill sensible defaults to reduce friction.
  - Lists & detail: `entity-list`/`list-row` for lists; `stat-group` for key metrics; detail actions near the top, with critical actions repeated near the bottom if the panel is long.
  - Performance & feedback: avoid blocking spinners for quick toggles; use optimistic UI when safe; show progress text for long operations.
  - Content hierarchy: section title + meta + actions, then content. Icons optional; never the sole label.

## Proof/Gating Expectations (Always-Correct)
- Always document proofs (command + result) in the task run file/PR. No merge without green proofs for the capabilities you touched. If multiple layers change, default to `scripts/checks.sh all`.
- Capability-specific minimum proofs:
  - Registries/docs-only: `scripts/checks.sh registries`.
  - Schema changes (new attrs/enums/migrations): update `registries/schema.edn` + this doc; run `scripts/checks.sh schema`. If importer/fixtures rely on the change, also run `scripts/checks.sh import` and `clojure -M:seed --temp`.
  - Actions (contracts/side effects): update `registries/actions.edn`; run `scripts/checks.sh actions`. If user-facing flows call the action, also run `scripts/checks.sh app-smoke`.
  - Importer changes: `scripts/checks.sh import` (use `DATOMIC_STORAGE_DIR=:mem` or `--temp`), plus `scripts/checks.sh schema` to confirm attribute coverage.
  - Views/CLJS/shared UI/state: `npm run check` and `scripts/checks.sh app-smoke`. If new API usage was added, include `scripts/checks.sh actions`.
  - Tooling/check harness updates: `scripts/checks.sh all`.
- Fixtures/temp DB discipline: prefer ephemeral DBs for checks (`DATOMIC_STORAGE_DIR=:mem` or `--temp` flags). Do not reuse a dev-local DB between checks. For Clojure tests, use `darelwasl.fixtures/with-temp-fixtures` or `darelwasl.schema/with-temp-db` helpers.
- Port hygiene: smoke checks bind the backend to `APP_PORT` (default 3100) and front-end dev to 3000; free these ports or override before running proofs.

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
- Fixture seeding guard: startup seeds fixtures only when the seed marker is absent and `ALLOW_FIXTURE_SEED` is true (defaults to true for dev). Set `ALLOW_FIXTURE_SEED=false` in prod to prevent reseeding even if app data is empty.
- Temp schema DB: use `darelwasl.schema/temp-db-with-schema!` with `(:datomic (darelwasl.config/load-config))` (override `:storage-dir` to `:mem`) to spin an ephemeral DB preloaded from `registries/schema.edn`; prefer `darelwasl.schema/with-temp-db` to ensure cleanup after checks.
- Land registry importer: `clojure -M:import --file data/land/hrib_parcele_upisane_osobe.csv [--temp] [--dry-run]` loads schema, normalizes/dedupes the CSV (deterministic IDs), and transacts person/parcel/ownership into Datomic. `--temp` uses a :mem DB and cleans up; `--dry-run` parses/validates only.
- Fixture seed: `clojure -M:seed` loads schema + fixtures into the configured dev-local DB; `clojure -M:seed --temp` seeds a temp Datomic (:mem by default). Use `darelwasl.fixtures/with-temp-fixtures` for tests/headless checks that need isolated data.
- Checks: `scripts/checks.sh registries|schema|actions|import|app-smoke|all` (registry presence + EDN parse, schema load, importer, action contracts, headless app smoke). Use `all` before merging.
- Schema/migration check: `scripts/checks.sh schema` also runs a backfill check that strips `:entity/type` in a temp DB seeded with fixtures and ensures the migration repopulates it.
- App smoke run: `scripts/checks.sh app-smoke` (or `all`) seeds fixtures into a temp Datomic storage under `.cpcache/datomic-smoke-*`, builds the frontend (`npm run check`), starts the backend on `APP_PORT` (default 3100), and runs Playwright headless login + task rendering. Requires Node/npm and initial Playwright browser download.
- Health check: `curl http://localhost:3000/health` returns JSON with service status and Datomic readiness.
- Auth login: `POST http://localhost:3000/api/login` with JSON `{"user/username":"huda","user/password":"Damjan1!"}` (fixtures/users.edn) returns `{ :session/token ..., :user/id ..., :user/username ... }` and sets an http-only SameSite=Lax session cookie backed by the on-disk store (`SESSION_STORE_PATH`); server restarts keep sessions.
- Land registry API (auth + flag gating via client): GET `/api/land/people` (query params: `q`, `sort=area|parcels`), GET `/api/land/people/:id`, GET `/api/land/parcels` (filters: `cadastral-id`, `parcel-number`, `min-area`, `max-area`, `completeness=complete|incomplete`, `sort=area|owners`), GET `/api/land/parcels/:id`, GET `/api/land/stats`.
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
- Service user and paths: create `darelwasl` user; clone repo to `/opt/darelwasl`; env files at `/etc/darelwasl/app.env` and `/etc/darelwasl/site.env` (owned by `darelwasl`, root-readable); Datomic storage under `/var/lib/darelwasl/datomic`.
- Runtime bind: `APP_HOST=0.0.0.0`, `APP_PORT=3000`; site on `SITE_HOST=0.0.0.0`, `SITE_PORT=3200` (configurable). Reserve 80/443 for a future reverse proxy/SSL front.
- Ports/firewall: allow inbound 22 (SSH) and 3000 (app) + 3200 (site); add 80/443 only when proxy is configured; outbound SMTP 25/465 blocked by provider (not used).
- Logging: systemd journal for both services (no separate log files initially); optional logrotate can be added later.
- SSH: add maintainer public key to root and `darelwasl` user `~/.ssh/authorized_keys` before running prep; use repo origin over HTTPS per system rules.
- Env file template (`/etc/darelwasl/app.env`):
  - `APP_HOST=0.0.0.0`
  - `APP_PORT=3000`
  - `DATOMIC_STORAGE_DIR=/var/lib/darelwasl/datomic`
  - `DATOMIC_SYSTEM=darelwasl`
  - `DATOMIC_DB_NAME=darelwasl`
  - `ALLOW_FIXTURE_SEED=false` (prod: disable fixture reseed)
  - Optional: `NODE_ENV=production`, JVM opts via `JAVA_OPTS` if needed.
- Site env file template (`/etc/darelwasl/site.env`):
  - `SITE_HOST=0.0.0.0`
  - `SITE_PORT=3200`
  - `DATOMIC_STORAGE_DIR=/var/lib/darelwasl/datomic`
  - `DATOMIC_SYSTEM=darelwasl`
  - `DATOMIC_DB_NAME=darelwasl`
  - `ALLOW_FIXTURE_SEED=false`
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
- Site Systemd unit (`/etc/systemd/system/darelwasl-site.service`):
  ```
  [Unit]
  Description=DarelWasl public site
  After=network.target

  [Service]
  Type=simple
  User=darelwasl
  WorkingDirectory=/opt/darelwasl
  EnvironmentFile=/etc/darelwasl/site.env
  ExecStart=/opt/darelwasl/scripts/run-site.sh
  Restart=on-failure
  RestartSec=5
  StandardOutput=journal
  StandardError=journal

  [Install]
  WantedBy=multi-user.target
  ```
  Reload/enable: `sudo systemctl daemon-reload && sudo systemctl enable --now darelwasl-site`.
- Terminal Systemd unit (`/etc/systemd/system/darelwasl-terminal.service`):
  ```
  [Unit]
  Description=DarelWasl terminal service
  After=network.target

  [Service]
  Type=simple
  User=darelwasl
  WorkingDirectory=/opt/darelwasl
  EnvironmentFile=/etc/darelwasl/app.env
  ExecStart=/opt/darelwasl/scripts/run-terminal.sh
  Restart=on-failure
  RestartSec=5
  StandardOutput=journal
  StandardError=journal

  [Install]
  WantedBy=multi-user.target
  ```
  Reload/enable: `sudo systemctl daemon-reload && sudo systemctl enable --now darelwasl-terminal`.
- CI deploy: GitHub Actions workflow `.github/workflows/deploy.yml` (triggers on `main` push) SSHes to the host using secrets `HETZNER_SSH_HOST`, `HETZNER_SSH_USER`, `HETZNER_SSH_KEY` and runs `/opt/darelwasl/scripts/deploy.sh` then `systemctl restart darelwasl darelwasl-site`.
- Service ops (Hetzner):
  - Deploy manually on server: `cd /opt/darelwasl && sudo -u darelwasl ./scripts/deploy.sh && sudo systemctl restart darelwasl darelwasl-site`.
- Service commands: `systemctl status darelwasl`, `journalctl -u darelwasl -f`, `systemctl restart darelwasl`, `systemctl stop darelwasl`; same for `darelwasl-site`.
  - Terminal: `systemctl status darelwasl-terminal`, `journalctl -u darelwasl-terminal -f`, `systemctl restart darelwasl-terminal`.
  - Health: `curl http://127.0.0.1:3000/health` (or use `http://haloeddepth.com:3000/health` while exposed) and `curl http://127.0.0.1:3200/` (site).
  - Secrets in GitHub: set `HETZNER_SSH_HOST=77.42.30.144`, `HETZNER_SSH_USER=root` (or deploy user), `HETZNER_SSH_KEY` (private key matching server authorized_keys).

## Product Spec: Entity Foundation + App Suite (Home + Tasks)
- Entities and identity:
  - All persisted entities carry `:entity/type` (additive discriminator) alongside their type-specific attrs. Current types: `:entity.type/user`, `:entity.type/task`, `:entity.type/tag`, `:entity.type/note`. Future types follow the same pattern (type ident + attrs + refs).
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
- Task fields: title (required), description (rich text allowed), status (enum: todo/in-progress/pending/done), assignee (user), due date (optional), priority (enum: low/medium/high), tags (set of tag entities via `:tag/id`), archived flag, feature flag `:task/extended?` (default false) for future fields.
- Notes: minimal note entity (`:entity.type/note`) used for status reasons and audit; pending status requires a typed note `:note.type/pending-reason` attached to the task (multiple allowed over time).
- Actions: create/edit task; change status; assign/reassign; set/clear due date; add/remove/rename/delete tags; archive/unarchive; login (auth action).
- Views: login screen; task list with filters (status, assignee, tag, priority) and sorts (due date, priority, updated); detail side panel for edit/view; inline tag management without leaving the task view; light/dark theme toggle pinned bottom-left. Task app is selectable from the app switcher and may share list/detail primitives with Home.
- UX: explicit loading/empty/error/ready states; inline validation for required fields; keyboard shortcut for new/save; responsive layout (desktop list + side panel; mobile stacked).
- Acceptance: After login as huda or damjan, user can create/edit tasks, change status, assign, set due, manage tags (create/attach/rename/delete), archive, filter/sort; UI shows states correctly; theme (light/dark) applied and switchable; headless smoke passes.
- Home view: default after login; shows status counts, recent tasks (updated desc), tag highlights, and quick action to create a task. Uses `/api/tasks/recent` and `/api/tasks/counts`; loading/empty/error/ready states; the app switcher (hover/push desktop, tap mobile) provides navigation to Tasks.

## Product Spec: Entity View Redesign (Task View v2)
- Goal: a minimalist, tap-first task experience on mobile and desktop, while formalizing an entity-view contract reusable for future entities (notes, clients, etc.).
- Entity view contract:
  - List panel: title, meta count, minimal filter chip bar + “More” drawer; list rows are single-tap targets with inline quick actions.
  - Detail panel: consistent header (title/meta/actions), a compact edit form, and quick status/priority/assignee controls.
  - Actions: list/detail must invoke canonical actions; no surface-specific writes.
  - States: loading/empty/error handled in list and detail with shared components.
- Mobile behavior:
  - List is the default surface; detail opens as a bottom sheet.
  - “+” or primary action opens a lightweight create sheet (title first, rest optional).
  - Quick status changes are tap-first (no keyboard required).
- Desktop behavior:
  - Two-pane layout remains, but detail is visually calm and secondary; list stays primary.
  - Filters default to minimal chips; the “More” drawer holds advanced filters/sorts.
- Default task flow:
  - Create with title only; status defaults to todo; assignee defaults to current user; tags/priority/due optional.
  - Pending status requires a reason; reason entry is lightweight and does not require navigation away.
- Acceptance:
  - Task view presents a minimal, uncluttered list; no more than one primary action row per section.
  - Users can create, update, and complete tasks without typing beyond title unless they choose to.
  - Mobile detail flows require at most two taps to reach status/priority/assignee controls.
  - Desktop view remains functional for power users but visually quiet; no layout regressions for Home/Control panel/Land views.

## Product Spec: Betting CLV Trainer (MVP)
- Goal: a CLV-first betting trainer that lets users log bets against reference odds and learn where they consistently beat the close, without implying guaranteed profit or outcome prediction.
- Screens:
  - Match list: upcoming/recent events with sport, start time, and provider status; on-demand refresh only.
  - Match detail: price board with no-vig reference %, best price, execution price (if available), selection picker, and "log bet" action.
  - Bet log + CLV scoreboard: list of logged bets with entry reference %, execution odds (if available), close status, CLV (pp), and result (pending/win/loss/push).
- Core flow:
  - User opens match list, selects an event, and optionally refreshes odds on demand.
  - In match detail, user selects a side; the app logs the bet using current reference median and execution odds if available.
  - Close snapshots are captured automatically by a scheduler at kickoff minus a fixed offset (default 10 min); manual "Capture close" remains as a fallback.
- API surface (betting):
  - `GET /api/betting/events` (Rezultati daily list + event upsert)
  - `GET /api/betting/events/{id}/odds` (reference odds snapshot)
  - `POST /api/betting/events/{id}/close` (close snapshot)
  - `GET /api/betting/bets` + `POST /api/betting/bets` (bet log)
- Data flow and CLV rules:
  - Reference odds come from Rezultati bookmaker lists; cached and fetched on demand.
  - Per-book no-vig probabilities are computed and the reference median is taken across the configured basket.
  - Reference basket = Pinnacle, Betfair Exchange, SBO/SBOBET, bet365, Unibet; fallback to bet365 + Unibet if none available.
  - Bets store the reference median implied probability at entry; execution odds are stored if the execution book is available.
  - Close snapshot is the last capture ≤ kickoff − offset; CLV = close reference prob − entry reference prob (percentage points).
  - If no close snapshot exists, CLV remains "pending" and is not displayed as a number.
- Data retention:
  - Bets are retained indefinitely by default; no automatic deletion in v1.
  - Odds snapshots and event metadata are retained for audit and CLV review; no automated pruning in v1.
  - Manual deletion/cleanup is a future tool (out of scope).
- Constraints:
  - No guaranteed-profit language; CLV is presented as a feedback metric only.
  - No outcome prediction or automated betting.
  - No high-frequency polling; only low-frequency close capture scheduling plus user-triggered refresh.
  - Use existing UI primitives and theme tokens; keep data model explicit and minimal.
- Acceptance criteria:
  - A user can log a bet with a single selection click (no manual odds/stake entry).
  - Reference basket and true % are visible; execution odds appear when the execution book is available.
  - Close snapshots are auto-captured, and CLV is computed for affected bets once close exists.
  - The match list, match detail, and bet log screens show clear loading/empty/error states.
  - CLV is shown only after a closing snapshot exists; otherwise the bet shows "Awaiting close".
  - Odds refresh is user-triggered, with visible last-updated time.
- Non-goals:
  - Any ML/edge prediction, automated betting, bankroll management, or high-frequency odds polling.
  - Multi-market coverage beyond the single selected market per bet in v1.
  - Profit/loss projection beyond the logged bet result.

## Design Spec: Betting CLV Trainer (MVP)
- Visual language: analytical and calm; use existing theme tokens with neutral surfaces and a single accent. No celebratory or "win" styling.
- Layout:
  - Desktop: two-pane shell with match list on the left and match detail on the right; bet log and CLV scoreboard sit inside the detail pane as stacked panels.
  - Mobile: single column; match list first, then detail panel with price board, bet form, bet log, and scoreboard. Include a clear "Back to list" affordance at the top of detail.
- Match list:
  - Use `list-row` with one title line and one meta line (sport + start time + provider status).
  - Status chip shows `Live`, `Upcoming`, or `Final`; no extra badges.
- Match detail + price board:
  - Reference panel shows no-vig % per outcome, best available price, and execution price when available.
  - Selection picker + "Log bet" button; no manual odds/stake inputs.
  - "Refresh odds" and "Capture close" are primary and secondary `button`s; show a small loading state and last update time.
- Bet log + CLV scoreboard:
  - Bet log uses `list-row` with selection, entry reference %, execution odds, and CLV status; include a small meta line for "Logged at" time.
  - Scoreboard uses `stat-group`/`stat-card` for average CLV, % ahead of close, close coverage, and total bets.
- CLV badges and copy:
  - Status chips: `Awaiting close`, `Ahead of close`, `At close`, `Behind close`.
  - Use success/warn/danger token colors with subtle tinted backgrounds; never imply profit or certainty.
  - CLV value shows signed percent points (e.g., +2.4pp); hide numeric CLV until a close snapshot exists.
- Interaction rules:
  - Odds refreshes are user-triggered; close capture may be automated on a schedule.
  - If reference odds are unavailable, show an empty state with a single "Refresh odds" action.
  - Selection changes do not auto-log; user must explicitly log the bet.
- Accessibility and density:
  - Tap targets at least 44px tall; avoid multi-line rows beyond title + meta.
  - Keyboard focus visible for inputs, chips, and actions; no hover-only affordances.

## Design Spec: Entity View Redesign (Task View v2)
- Visual language: calm, low-contrast neutrals with one clear accent; generous whitespace; minimal ornament.
- Typography: expressive but restrained. Use a serif or humanist for headings and a clean sans for body; meta text can use a subtle mono. Avoid default system stacks.
- Layout rhythm:
  - Mobile: list-first, detail as bottom sheet; sticky primary action (floating "+").
  - Desktop: two-pane with the list dominant; detail quieter and narrower.
  - Spacing uses the existing token scale; keep list rows tall enough for 44px targets.
- List rows:
  - One title line + one meta line max; truncate long text.
  - Status chip + quick action cluster on the right; no dense tag pills in the list by default.
- Filters:
  - Minimal chip bar for the 2-3 most common filters; "More" drawer holds advanced filters and sort.
  - Default filter state is calm (no highlighted chips unless active).
- Detail sheet:
  - Header = title, status chip, and two primary actions (Save/Done).
  - Fields use progressive disclosure; advanced fields hidden behind "More".
  - Pending reason is a lightweight inline field (only shown when status is pending).
- Status colors:
  - Todo = blue, In progress = yellow, Pending = red, Done = green.
  - Status chips use subtle tinted backgrounds with colored text.
- Interaction:
  - Tap-first controls; keyboard shortcuts optional for desktop.
  - Inline actions must provide visual feedback (pressed/active state).
- Accessibility:
  - All tap targets >= 44px; focus visible; no hover-only controls.
  - Bottom sheet has a clear close affordance and supports escape.

## Product Spec: Land Registry (People-to-Parcels + Summary Stats)
- Goal: let authenticated users browse people and parcels (land lots) with cross-links and trustworthy summary stats, sourced from `hrib_parcele_upisane_osobe(1).csv`, without regressing existing apps.
- Entities and identity:
  - New entities: person, parcel, ownership share. Each uses `:entity/type` (`:entity.type/person`, `:entity.type/parcel`, `:entity.type/ownership`) plus stable UUIDs generated deterministically from source keys.
  - Person identity: normalize name and address (trim/upper, collapse whitespace, remove commas/punctuation except separators) to form a deterministic key; store raw name/address and list position fields for traceability.
  - Parcel identity: composite of `katastarska_opcina_id` + `k_c_br` (cadastral unit + parcel number). Persist cadastral name, parcel area (m2), parcel address/description, book number `broj_posjedovnog_lista`, and raw strings.
  - Ownership: link person to parcel with share numerator/denominator (also keep raw string), record ordering fields (`upisana_osoba_redni_broj`, `upisana_osoba_pozicija_u_listi`), and effective area share (derived).
- Import/refresh requirements (applies to `:cap/action/parcel-import` and fixtures):
  - Idempotent importer that parses the CSV, validates headers, handles quoted/UTF-8 content, and logs counts. Deterministic IDs are based on normalized keys so re-runs do not duplicate data; a dry-run mode surfaces counts without writes.
  - Dedupe rules: persons deduped by normalized name+address; parcels deduped by cadastral ID + parcel number; ownership deduped by (person, parcel, share fraction, list position).
  - Integrity checks: per-parcel share totals must equal 1.0 +/- tolerance; rows with missing numerators/denominators are rejected with clear logs; invalid rows do not block the whole import if the share set stays consistent.
  - Baseline expectations from the provided CSV: ~582 rows, ~65 parcels, ~7 people, total parcel area ~56,075 m2, 100% share coverage. Import should emit these counts (or differences) in the summary.
  - Traceability: store source filename, load timestamp, and a `:source/ref` per row (e.g., CSV row hash) to allow replays and auditing; keep raw share string and address text.
- Core user flows (land-registry app, gated behind a feature flag/nav entry):
  - People-first: list/search people (name substring, normalized), sortable by parcel count and total owned area. Selecting a person shows their parcels with share %, area contribution, parcel address/cadastral info, and a jump to parcel detail.
  - Parcel-first: list/filter parcels by cadastral number, parcel number, book number, area range, and ownership completeness; sortable by area and owner count. Selecting a parcel shows owners with share %, inferred area share, and a jump to person detail.
  - Summary stats: cards for parcel count, person count, total area, share completeness, and top owners by area share; optional small chart/table for top parcels by area and counts by cadastral unit (single unit now but future-proof).
  - Navigation: exposed via app switcher/nav only when `land-registry` flag is on and data import succeeded; cross-links between people and parcels stay within the land-registry context.
- Performance and UX expectations:
  - Lists are paginated/sliced (default 25–50 items) with server-side filters; target backend responses under 500ms for default queries; frontend renders under 250ms on seeded data.
  - Explicit loading/empty/error states for people, parcels, and stats; errors should hint at data freshness or ingestion problems.
  - Session/auth required; no public access. Feature flag keeps the view hidden until data and importer checks pass.
- Acceptance criteria:
  - After importing the provided CSV into a temp/dev DB, land-registry app shows all people and parcels with accurate share math (parcel share totals within tolerance) and summary stats matching importer counts (about 65 parcels, 7 people, ~56k m2).
  - People detail shows each owned parcel with share % and area contribution; parcel detail shows all owners with share %; cross-links work both ways.
  - Summary cards render (counts, area totals, share completeness, top owners) and remain responsive on desktop/mobile layouts defined in the design spec.
  - Import can be re-run safely (idempotent), reports counts and errors, and stores source refs; failures surface in logs without corrupting existing data.
  - Feature flag `:land-registry/enabled` gates the nav entry; view registered as `:cap/view/land-registry` reuses backend read endpoints.

## Design Spec: Land Registry (People-to-Parcels + Summary Stats)
- Navigation and gating:
  - Land Registry appears in the app switcher/nav only when the `land-registry` flag is on and data import has completed; otherwise it remains hidden. Within the view, cross-links between people and parcels do not leave the land-registry context.
  - Keyboard/focus flow mirrors other apps: switcher trigger is focusable, menu items reachable by arrow/tab, Escape closes; land-registry lists and detail panes support tab order and visible focus.
- Layouts and structure:
  - Desktop: dual-pane shell with a left rail for primary list (toggle between People and Parcels tabs) and a right pane for detail + secondary content. Summary stats live in a top strip of cards; a compact filter bar sits above the list. Detail pane contains ownership tables and quick links.
  - Mobile: stacked sections with a top summary card row (scrollable chips/cards), then list, then a slide-up detail sheet for selections. Filters collapse into a drawer/sheet; tabs remain visible for switching People/Parcels.
- People-first view:
  - List: dense cards with name, normalized address, parcel count, and total owned area; supports search (name substring, normalized) and sort (parcel count, area). Pagination or infinite scroll with clear page size; loading skeletons match card shapes.
  - Detail: shows person header (name + address), summary chips (parcel count, total area share), table/grid of owned parcels with parcel number, cadastral id/name, book number, parcel address, area, share % and area contribution; each parcel row links to parcel detail.
  - States: loading skeletons, empty copy (“No people found”), error with retry. Long lists keep sticky filter/search bar on scroll.
- Parcel-first view:
  - List: cards/rows showing parcel number, cadastral id/name, area, owner count, ownership completeness badge (complete/incomplete), and book number. Filters for cadastral number, parcel number, book number, area range, ownership completeness; sorts for area and owner count.
  - Detail: parcel header with key identifiers and area; map placeholder slot (optional, not implemented now) plus ownership table with person name/address, share %, area share, list position/order fields. Each owner entry links to person detail.
  - States: loading skeletons, empty copy (“No parcels match these filters”), error with retry.
- Summary stats surface:
  - Cards for parcel count, person count, total area, share completeness (percent of parcels summing to 1.0), and top owners by area share (mini list). Optional small chart/table for top parcels by area and counts by cadastral unit (future-proof even though single unit now).
  - Cards show loading shimmer; empty gracefully hides charts if data absent; error state shows inline message and retry.
- Styling and tone:
  - Professional, data-forward styling using existing theme tokens (neutral surfaces, teal accent). Use subtle borders and shadows for cards; avoid playful colors. Typography sticks to current sans stack; emphasize numeric alignment for stats.
  - Density: compact rows with clear spacing; truncate long names/addresses with tooltips where needed. Use badges for completeness and list positions.
- Responsiveness and accessibility:
  - Desktop supports 1200px+ with two columns; medium screens collapse stats into a single row and keep detail pane toggleable; mobile uses stacked cards and a slide-up detail sheet.
  - All controls have aria-labels; tables are keyboard-navigable; focus states visible; contrasts meet WCAG AA using existing tokens.
- Interactions and affordances:
  - Filters and search debounce inputs to avoid excessive queries; clear-all filter control present. Clicking a list row selects and scrolls detail into view; detail has back/close affordance on mobile.
  - Cross-links keep filter state when moving between people and parcels (e.g., from person detail to parcel detail and back).
  - Feature flag respected for routes and app switcher entry; if data import missing, show a friendly “Data not yet loaded” empty state instead of broken lists.

## Schema: Betting CLV (Events/Bookmakers/Quotes/Bets/Facts)
- Entities and identities:
  - `:entity.type/betting-event` with `:betting.event/id` and unique `:betting.event/external-id` (Rezultati match id), plus sport key/title, commence time, and home/away teams.
  - `:entity.type/betting-bookmaker` with `:betting.bookmaker/id` and unique `:betting.bookmaker/key`, plus display title.
  - `:entity.type/betting-quote` snapshot referencing event + bookmaker with market key, selection, odds-decimal, implied probability, capture time, and `:betting.quote/close?`.
  - `:entity.type/betting-bet` referencing event + bookmaker with execution odds (optional), reference implied probability, status, placed-at, and settled-at.
  - `:entity.type/betting-fact` for logged actions (bet log, quote capture, close capture, settle) with type + optional refs.
- Invariants:
  - External event IDs and bookmaker keys are unique; entity type is set on all betting entities.
  - Quotes are immutable snapshots; `:betting.quote/close?` marks the final reference price used for CLV.
  - Odds and implied probability must be positive; CLV is derived from entry vs close reference implied probability (not stored on the bet).
- Compatibility and history:
  - Additive schema; overwrite history strategy; backward/forward compatible; no flags.
- Fixtures and seeding:
  - `fixtures/betting.edn` seeds one event, one bookmaker, one quote, one bet, and a representative fact.

## Schema: Land Registry (Person/Parcel/Ownership)
- Entities and identities:
  - `:entity.type/person` with deterministic `:person/id` derived from normalized name+address; keeps provided `:person/name`/`:person/address` plus normalized forms and `:person/source-ref` for traceability.
  - `:entity.type/parcel` with deterministic `:parcel/id` derived from cadastral id + parcel number; stores cadastral id/name, parcel number, book number, address/description, area in m2, and `:parcel/source-ref`.
  - `:entity.type/ownership` linking person → parcel; carries share numerator/denominator, computed share fraction and area share, list-order fields, raw share string, and `:ownership/source-ref`.
- Invariants:
  - Person identity is normalized name+address; parcel identity is cadastral-id + parcel number. Entity type must be set on all three.
  - Parcel area must be non-negative. Ownership share denominator > 0; per parcel, share totals must sum to 1.0 within tolerance.
  - Ownership requires both person and parcel refs; source refs are required for replay/audit.
- Compatibility and history:
  - Additive schema; overwrite history strategy. Backward/forward compatible; no flags.
  - Importer is responsible for setting deterministic IDs, normalized fields, and derived share/area fields; re-runs must be idempotent.
- Fixtures and seeding:
  - Full dataset comes from `hrib_parcele_upisane_osobe(1).csv` via the importer. For fast checks, use a trimmed fixture subset covering multiple owners per parcel and multiple parcels per person, preserving share math.
  - Temp DB/schema checks should load these attributes and ensure share totals validate during import/backfill.
  - Files: dataset copied to `data/land/hrib_parcele_upisane_osobe.csv`; sample subset for tests at `fixtures/land_registry_sample.csv` (one parcel with nine ownership rows).

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
- Vibe: calm, professional; cool neutrals with cobalt accent for the app; public site keeps its premium palette.
- Layout: desktop mailbox for Tasks (list left, detail center, right spacer); mobile stacks content without slide-in panels for Tasks.
- Typography: clean sans (Manrope/Plus Jakarta for app; site uses its own theme font), hierarchy for title/section/body/meta.
- Components: buttons (primary/secondary/ghost), inputs/textarea/select, tag chips, status/priority badges, list rows, panels, empty/error states. Sizes and spacing come from component tokens.
- Theming: tokens stored in `registries/theme.edn`; generate CSS vars; components must consume tokens/vars only (no hardcoded colors/spacing/fonts). Use radius/shadow/motion tokens for consistency. Light (`:theme/default`) and dark (`:theme/dark`) palettes ship together; UI exposes a sun/moon toggle.
- Login: dedicated screen before tasks; uses same theme/tokens; shows error states clearly; supports shared password flow; minimal hero copy.
- Theme tokens: default `:theme/default` matches `registries/theme.edn` (“Soft linen + cobalt accent”: background `#F6F8FB`, surface `#FFFFFF`, muted surface `#EFF2F7`, text `#0F172A/#5E6B7B`, accent `#2563EB` with strong `#1D4ED8`; warning/danger/success `#D97706/#DC2626/#16A34A`). Typography uses `Manrope`, `Plus Jakarta Sans`, `Avenir Next` with sizes 22/18/15/12px and line heights 30/26/22/18px. Spacing base 4px with 4–32px scale, radius 6/10/14px, card shadow `0 1px 2px rgba(15,23,42,0.06)`, motion transition `140ms ease`.
- Component tokens: theme exports `:components` (controls, compact controls, chips, list rows, panels, toolbars) to standardize sizing across app + site without changing the site’s look.
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
-   Roles: fixtures carry `:user/roles` (huda = `:role/admin` + `:role/content-editor`, damjan = `:role/admin` + `:role/content-editor`) to gate the control panel and content actions.
- :fixtures/tags (`fixtures/tags.edn`): tag entities with fixed IDs and names (`Ops`, `Home`, `Finance`, `Urgent`) used by tasks and exposed via `/api/tags`.
- :fixtures/tasks (`fixtures/tasks.edn`): four tasks covering all status/priority enums and tag references (lookup refs to `:tag/id`), with due-date variety for sort/filter checks, one archived entry, and one flagged with `:task/extended?` true. Assignees reference the user fixture IDs.
- :fixtures/betting (`fixtures/betting.edn`): minimal CLV seed data (one event, one bookmaker, one quote snapshot, one bet, one fact) for schema checks and demo states.
- All fixtures include `:entity/type` (`:entity.type/user`, `:entity.type/task`, `:entity.type/tag`). A backfill helper sets this on existing DBs lacking it (inferred from identity attrs).
- Loader tooling: `darelwasl.fixtures/seed-dev!` and CLI `clojure -M:seed [--temp]` load schema + fixtures (users first, then tags/tasks/content/betting with lookup refs). Use `darelwasl.fixtures/temp-db-with-fixtures!` or `with-temp-fixtures` to spin disposable DBs for checks.
- Determinism: fixture UUIDs and timestamps are fixed; loaders insert users before tasks to satisfy refs. Reuse fixtures in schema-load/action-contract/app-smoke checks for predictable state.
- Entity helper: `darelwasl.entity` provides basic `:entity/type` helpers (list/pull, ensure type); startups backfill types and seeds set them on create flows (tasks/tags).
- Home data (backend): `/api/tasks/recent` returns recent tasks (sorted by updated, default limit 5, archived excluded unless `archived=true`); `/api/tasks/counts` returns counts by status (archived excluded unless `archived=true`). Both require auth and reuse task pulls.
- :fixtures/land-registry-sample (`fixtures/land_registry_sample.csv`): trimmed CSV (one parcel, nine ownership rows) mirroring the HRIB structure for fast importer checks; uses the same header as the full dataset.
- Public site process: `clojure -M:site --dry-run` initializes schema/fixtures for the public site process without starting Jetty; run `scripts/run-site.sh` (env `SITE_HOST`/`SITE_PORT`, defaults `0.0.0.0:3200`) to serve the v2 public site (Home/About/Contact) rendering live v2 entities (hero stats/flows, licenses, comparison rows, journey/activation, personas/support, FAQs, values, team, contact) with visibility filtering.

## Data & Provenance: Entity + Fact Model (v1)
- Identity:
  - Every real-world thing is an entity with `:entity/id` + `:entity/type`. Reuse types; do not fork variants. People are always `:entity.type/person` (staff/user/client/lead are roles/segments, not new types). Channels are `:entity.type/channel` (with `:channel/platform`, `:channel/remote-id`, `:channel/workspace`, `:channel/bot?`).
  - Handles: store external handles on the person (or as a collection) with platform/value/verified flags. Keep a lookup of (platform, remote-id) → `:entity/id` to dedupe.
  - Linking: person ↔ channel bindings are facts (chat-link) scoped by workspace; enforce at most one active link unless explicitly multi-linked.
- Provenance (required on every fact):
  - `:fact/source-id`, `:fact/source-type` (user|integration|rule|import|system), `:fact/adapter` (telegram/web/rule/importer), `:fact/run-id`, `:fact/workspace`.
  - `:fact/created-at`, `:fact/valid-from`, optional `:fact/valid-until`.
  - Evidence/lineage: `:fact/evidence-ref` or hash, `:fact/inputs` (facts), `:fact/rule-id`, `:fact/rule-version`, optional `:fact/confidence`, optional `:fact/signature` for tamper-evidence.
- Fact shapes (immutable events; projections derive “current” state):
  - Tasks: create, status-change, assign, due-set/clear, tags-set, archived-set, pending-reason (as note link), title/description changes.
  - Notes: `:entity.type/note` with `:note/type` (pending-reason, comment, system), `:note/body`, `:note/subject` (entity ref), provenance.
  - Channels: message-received, message-sent, chat-linked-to-person, bot-start token usage.
  - Rules/imports: rule-fired facts (inputs/outputs/lineage); import facts with dataset/version/row hash/source file.
- Invariants:
  - Facts must carry provenance and workspace. Status transitions respect enums; pending requires a pending-reason note. External handles unique per (platform, workspace). One active link per (person, channel, workspace) unless flagged multi-link.
  - Projections read from facts; mutable attrs remain only as compatibility during migration.
- Rollout:
  - Add schema for provenance fields, handles, channels, notes. Backfill existing tasks into fact history with migration provenance, attach workspace. Keep old attrs in sync until projections cut over.
  - Adapt adapters (web UI, Telegram, rules/importers) to emit provenance-aware facts; notifications/logs become message facts. Surface lineage minimally in UI (“from rule X / chat Y”) after projections stabilize.

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

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
- Headless checks: shadow-cljs test with Karma/ChromeHeadless or Playwright; required for “app-smoke” in `scripts/checks.sh` once implemented.

## Runtime & Commands
- Backend start: `clojure -M:dev` from repo root starts Jetty + Datomic dev-local helper (host `0.0.0.0`, port `3000` by default). Override via `APP_HOST`/`APP_PORT`.
- Datomic dev-local config: defaults to absolute `data/datomic` storage under the repo, system `darelwasl`, db `darelwasl`; accepts `DATOMIC_STORAGE_DIR=:mem` for ephemeral storage. Override with `DATOMIC_STORAGE_DIR`/`DATOMIC_SYSTEM`/`DATOMIC_DB_NAME`.
- Health check: `curl http://localhost:3000/health` returns JSON with service status and Datomic readiness.

## Product Spec: Task App v1 (two users)
- Users: two seeded users (`huda`, `damjan`) sharing password `Damjan1!`. Login required before accessing tasks.
- Task fields: title (required), description (rich text allowed), status (enum: todo/in-progress/done), assignee (user), due date (optional), priority (enum: low/medium/high), tags (enum set), archived flag, feature flag `:task/extended?` (default false) for future fields.
- Actions: create/edit task; change status; assign/reassign; set/clear due date; add/remove tags; archive/unarchive; login (auth action).
- Views: login screen; task list with filters (status, assignee, tag, priority) and sorts (due date, priority, updated); detail side panel for edit/view.
- UX: explicit loading/empty/error/ready states; inline validation for required fields; keyboard shortcut for new/save; responsive layout (desktop list + side panel; mobile stacked).
- Acceptance: After login as huda or damjan, user can create/edit tasks, change status, assign, set due, tag, archive, filter/sort; UI shows states correctly; theme applied; headless smoke passes.

## Design Spec & Theming
- Vibe: calm, professional; warm neutrals with teal accent.
- Layout: desktop-first; list on left, detail side panel on right; mobile: stacked with slide-up detail.
- Typography: clean sans (Inter/IBM Plex Sans), hierarchy for title/section/body/meta.
- Components: buttons (primary/secondary/ghost), inputs/textarea/select, tag chips, status/priority badges, cards for list rows, empty/error states.
- Theming: tokens stored in `registries/theme.edn`; generate CSS vars; components must consume tokens/vars only (no hardcoded colors/spacing/fonts). Use radius/shadow/motion tokens for consistency.
- Login: dedicated screen before tasks; uses same theme/tokens; shows error states clearly; supports shared password flow.
- Theme tokens: default `:theme/default` uses background `#F7F4EF`, surface `#FFFFFF`, muted surface `#F0ECE6`, text `#1F2933/#52606D`, accent `#0FA3B1` with strong `#0B7E89`, focus color matches accent, warning/danger/success use `#F59E0B/#D14343/#2D9D78`. Typography uses `Inter, "IBM Plex Sans", system-ui, -apple-system, sans-serif` with sizes 20/16/14/12px and matching line heights 28/22/20/16px. Spacing base 4px with 4–32px scale, radius 4/8/12px, card shadow `0 6px 20px rgba(0,0,0,0.06)`, motion transition `150ms ease`.
- Theme CSS vars: run `scripts/theme-css-vars.sh [theme-id] [output-file]` (defaults to `:theme/default` and stdout) to emit `:root { --color-... }` tokens. Tokens should be stored with CSS-ready units to avoid guessing; generated output can be redirected to a stylesheet when wiring UI.

## Composability Rules
- Schema: reuse existing enums/refs when semantics match; avoid duplicating attributes; introduce new attrs only with clear invariants. Shared attributes must not change meaning between entities.
- Actions: separate pure core logic from effect adapters; design inputs/outputs to be reusable; enforce idempotency so actions can be composed safely. Do not couple actions directly to UI concerns.
- Views/Apps: consume data/actions via registries; reuse shared state-handling components; do not embed action logic in views—invoke registered actions. Respect declared loading/empty/error states.
- Tooling/Checks: extend shared entry points (e.g., `scripts/checks.sh`) instead of per-task scripts. New tools belong in `registries/tooling.edn` with stable IDs and scope.
- Integrations: wrap external systems behind adapters; expose contracts via the integration registry; provide fakes for composition in tests.
- Process: when a change affects composability rules, update this section and the relevant registry entries in the same run; tasks must declare their composability impact.
## Fixtures and Test Data
- Fixture IDs and scopes.
- How to seed/use fixtures in proofs.
- Determinism rules.

## Change Rules
- When adding/updating capabilities, update the relevant section with a stable ID.
- Update the registries in `registries/` to match narrative changes here.
- Record new fixtures and link them to capabilities.
- If a new pattern emerges, document it here and reference it in tasks/PRs.
- Include version and compatibility/flag information in every registry entry; additive changes are preferred.
- Run `scripts/checks.sh registries` to ensure required fields are present before merge.

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
- Checks harness: `scripts/checks.sh` (stub entry point for registry sanity, schema load, action contracts, view integrity, headless app smoke). Extend with real commands as the codebase grows.
- Theme CSS variable generator: `scripts/theme-css-vars.sh` (registry-driven, registered as `:cap/tooling/theme-css-vars`) to translate theme tokens into CSS variables for the UI shell.

## Glossary (optional)
- Domain terms and definitions to keep naming consistent.

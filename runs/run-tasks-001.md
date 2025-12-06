# Run run-tasks-001

Goal: deliver a fully usable local task app (login as huda/damjan, manage tasks end-to-end) with all checks implemented and passing. No stubs or follow-up tasks for core functionality.

## Tasks

- Task ID: product-spec
  - Status: done
  - Objective: Product spec in `docs/system.md` (tasks + auth) with fields, flows, acceptance.
  - Dependencies: requirements from user

- Task ID: design-spec
  - Status: done
  - Objective: Design spec in `docs/system.md` (visual language, theme, layout, components).
  - Dependencies: product-spec

- Task ID: theme-registry
  - Status: done
  - Objective: Theme tokens in `registries/theme.edn`; note CSS-var generation plan.
  - Dependencies: design-spec

- Task ID: registries-complete
  - Status: done
  - Objective: Schema/actions/views/tooling/theme registries reflect tasks/auth; no placeholders.

# Backend

- Task ID: backend-project-setup
  - Status: in-progress (Codex, 2025-12-06 16:37 UTC)
  - Objective: Create Clojure + Datomic Local service scaffold with deps, config, and start scripts.
  - Scope: deps.edn with aliases, env/config handling with defaults, base router + middleware, health endpoint, start command and docs, Datomic dev-local client/connect helper (no auth/creds) wired for future schema/tasks.
  - Out of Scope: auth/session flows, task CRUD endpoints, schema migrations, frontend work, CI wiring beyond start/health proof.
  - Capabilities Touched: [:cap/schema/user :cap/schema/task] (runtime support only)
  - Parallel Safety:
    - Exclusive Capabilities: backend runtime scaffold (server entrypoint, routing, config, Datomic dev-local bootstrap)
    - Shared/Read-only Capabilities: registries (read), fixtures (read)
    - Sequencing Constraints: precedes datomic-setup/auth-implementation/task-api-implementation/fixtures-loader
  - Composability Impact:
    - Layers Affected: tooling/runtime (backend server + Datomic client wiring)
    - Patterns/Registries Reused: config-from-env with defaults; ring/reitit router skeleton
    - New Composability Rules: none
  - Requirement Change & Compatibility: adds runnable backend skeleton; additive/non-breaking; no flags or rollout needed.
  - Breaking/Deprecation: none
  - Dependencies: registries-complete
  - Deliverables: deps.edn + src namespaces for config/db/server/main, health route returning 200 JSON, Datomic dev-local helper, start instructions documented.
  - Proof Plan: start server via `clojure -M:dev`, curl /health returns 200 with service + datastore status.
  - Fixtures/Data Assumptions: none required beyond existing registry fixtures.
  - Protocol/System Updates: document backend start/config defaults in docs/system.md.
  - FAQ Updates: none expected.
  - Tooling/Automation: start alias/command only; no CI wiring yet.
  - Reporting: PR notes server scaffolding, health endpoint, Datomic wiring, proof outputs.

- Task ID: datomic-setup
  - Status: pending
  - Objective: Configure Datomic Local (dev-local, no auth), connection utilities, and schema loader for temp DB.
  - Scope: Datomic config, connection helper, temp DB load function for checks; no cloud/auth.
  - Acceptance: Temp DB load works for schema; config documented; no credentials required.
  - Dependencies: backend-project-setup, schema-tasks, schema-users
  - Proof Plan: scripts/checks.sh schema (once implemented)

- Task ID: auth-implementation
  - Status: pending
  - Objective: Implement login/session for users huda/damjan (password `Damjan1!`), session cookie (in-memory), error handling.
  - Scope: POST /api/login, session middleware, 401 on bad creds, logout optional; plaintext password acceptable for dev.
  - Acceptance: Login with fixtures succeeds; bad creds 401; session persists; error responses defined.
  - Dependencies: backend-project-setup, datomic-setup, registry-actions-auth, fixtures-users
  - Proof Plan: scripts/checks.sh actions (auth) after harness ready
  - Commands: documented curl examples; start command reused from backend

- Task ID: task-api-implementation
  - Status: pending
  - Objective: Implement task endpoints wired to Datomic: list with filters/sorts; create/update; status/assign/due/tags; archive.
  - Scope: REST/JSON handlers, validations for enums/flags, persistence, audit per registry, feature flag respect.
  - Acceptance: Endpoints match action contracts; filters (status/assignee/tag/priority) and sorts (due/priority/updated) work; uses session auth.
  - Dependencies: auth-implementation, datomic-setup, registry-actions-tasks, fixtures-tasks
  - Proof Plan: scripts/checks.sh actions (tasks) after harness ready
  - Commands: documented curl examples; start command reused

- Task ID: fixtures-loader
  - Status: pending
  - Objective: Wire fixtures (users/tasks) into backend/test harness; provide seed/load command.
  - Scope: loader for Datomic temp DB and dev DB; bb/clj task to seed.
  - Acceptance: Seed command loads users/tasks; used by action tests and app smoke.
  - Dependencies: datomic-setup, fixtures-users, fixtures-tasks
  - Proof Plan: seed invoked in checks; scripts/checks.sh uses it
  - Commands: e.g., `bb seed` or `clojure -M:dev -m fixtures.seed` documented

# Frontend

- Task ID: frontend-scaffold
  - Status: done (Codex, 2025-12-06 16:56 UTC)
  - Objective: Scaffold shadow-cljs + re-frame; define build/dev/test commands; include base layout shell.
  - Scope: shadow-cljs.edn, package.json scripts, base app root, state setup, minimal layout shell that consumes theme CSS vars when available.
  - Out of Scope: wiring to backend APIs, login/task feature flows, theme CSS var generation, headless smoke harness.
  - Capabilities Touched: [:cap/view/login :cap/view/tasks :cap/tooling/app-smoke]
  - Parallel Safety:
    - Exclusive Capabilities: frontend build/runtime scaffold (package.json, shadow-cljs config, app root/layout shell)
    - Shared/Read-only Capabilities: theme registry (read), docs (read), registries (read)
    - Sequencing Constraints: precedes theme-css-vars, frontend-login, frontend-task-list, frontend-task-detail, checks-app-smoke
  - Composability Impact:
    - Layers Affected: views/apps, tooling (frontend build/start commands)
    - Patterns/Registries Reused: theme tokens from `registries/theme.edn`, re-frame event/state model
    - New Composability Rules: none
  - Requirement Change & Compatibility:
    - Adds runnable frontend scaffold; additive/backward-compatible; no flags or rollout.
  - Breaking/Deprecation: none.
  - Dependencies: design-spec, theme-registry
  - Deliverables: package.json with scripts (install/dev/build/test placeholder), shadow-cljs.edn app build, src namespaces for app entry/root/layout, public index/asset wiring, docs for commands.
  - Proof Plan: run `npm install` and `npm run build`; document results.
  - Fixtures/Data Assumptions: uses default theme tokens; no data fixtures required.
  - Protocol/System Updates: add frontend commands/start notes to docs/system.md if new.
  - FAQ Updates: none expected.
  - Tooling/Automation: npm scripts for dev/build; hook into scripts/checks.sh views placeholder as needed.
  - Reporting: summarize scaffold, commands, proofs, and any doc/registry updates.

- Task ID: theme-css-vars
  - Status: pending
  - Objective: Generate CSS vars from `registries/theme.edn` and consume in frontend.
  - Scope: script or build step to produce CSS vars; import into app.
  - Acceptance: CSS vars generated; components use vars; no hardcoded colors/spacing/fonts.
  - Dependencies: frontend-scaffold, theme-registry
  - Proof Plan: scripts/checks.sh views

- Task ID: frontend-login
  - Status: pending
  - Objective: Build login view wired to /api/login with error states; uses theme.
  - Scope: form, loading/error states, success stores session (cookie), redirects to tasks.
  - Acceptance: Valid creds (huda/damjan) log in; invalid shows error; responsive.
  - Dependencies: frontend-scaffold, theme-css-vars, auth-implementation, registry-view-login
  - Proof Plan: scripts/checks.sh views; app-smoke includes login

- Task ID: frontend-task-list
  - Status: pending
  - Objective: Build task list UI with filters/sorts, loading/empty/error states.
  - Scope: list pane, filters (status/assignee/tag/priority), sorts (due/priority/updated), uses theme tokens.
  - Acceptance: Filters/sorts work against API; states render correctly; responsive layout.
  - Dependencies: frontend-scaffold, theme-css-vars, task-api-implementation, registry-views-tasks
  - Proof Plan: scripts/checks.sh views; app-smoke covers list

- Task ID: frontend-task-detail
  - Status: pending
  - Objective: Build task detail/edit pane with full interactions.
  - Scope: detail panel, create/edit, status change, assign, due date, tags, archive; feature flag respected.
  - Acceptance: All interactions succeed against API; error handling shown; responsive; uses theme tokens.
  - Dependencies: frontend-task-list, task-api-implementation
  - Proof Plan: scripts/checks.sh views; app-smoke covers detail

# Checks and Tooling

- Task ID: checks-edn-validate
  - Status: done
  - Objective: Real EDN validation in scripts/checks.sh (implemented).

- Task ID: checks-schema-load
  - Status: pending
  - Objective: Implement schema load check using temp Datomic; pin command in scripts/checks.sh schema.
  - Scope: clj/bb script to load registries/schema.edn into temp DB.
  - Acceptance: `scripts/checks.sh schema` loads schema and passes.
  - Dependencies: datomic-setup, fixtures-loader

- Task ID: checks-action-contract
  - Status: pending
  - Objective: Action contract harness for auth/tasks using fixtures; pin command in scripts/checks.sh actions.
  - Scope: tests verifying inputs/outputs, side effects, idempotency, audit expectations per registry.
  - Acceptance: `scripts/checks.sh actions` runs and passes against fixtures.
  - Dependencies: auth-implementation, task-api-implementation, fixtures-loader

- Task ID: checks-app-smoke
  - Status: pending
  - Objective: Headless app smoke via shadow-cljs + chosen runner (Karma/ChromeHeadless or Playwright); pin command in scripts/checks.sh app-smoke.
  - Scope: headless config, minimal smoke test that logs in and renders task list/detail.
  - Acceptance: `scripts/checks.sh app-smoke` runs and passes.
  - Dependencies: frontend-login, frontend-task-detail, fixtures-loader

- Task ID: checks-update-script
  - Status: pending
  - Objective: Update scripts/checks.sh to include real commands for schema, actions, app-smoke; ensure `scripts/checks.sh all` runs everything.
  - Scope: wire commands from the above tasks; ensure exits non-zero on failure.
  - Acceptance: `scripts/checks.sh all` passes locally.
  - Dependencies: checks-schema-load, checks-action-contract, checks-app-smoke

# Fixtures

- Task ID: fixtures-users
  - Status: pending
  - Objective: Ensure users fixtures (huda, damjan, password) are final and referenced.
  - Acceptance: fixtures/users.edn correct; used by seed and tests.
  - Dependencies: schema-users

- Task ID: fixtures-tasks
  - Status: pending
  - Objective: Ensure tasks fixtures are final and referenced.
  - Acceptance: fixtures/tasks.edn correct; used by seed and tests; includes status/priority/tags coverage.
  - Dependencies: schema-tasks

# Docs

- Task ID: protocol-prompt-hardening
  - Status: done (Codex, 2025-12-06 16:24 UTC)
  - Objective: Harden the initial prompt to prevent token/auth hiccups; clarify loader usage and fallback token sourcing.
  - Scope: Update `docs/protocol.md` initial prompt text.
  - Out of Scope: Changing loader scripts or task definitions.
  - Capabilities Touched: docs/protocol.md
  - Dependencies: theme-registry
  - Proof Plan: none (doc change)
  - Reporting: summarize prompt change

- Task ID: docs-commands
  - Status: pending
  - Objective: Update docs/system.md and docs/faq.md with start/run/test commands, acceptance summaries, and any gotchas; ensure registries match code.
  - Scope: backend start, fixture seed, frontend dev/build, checks commands, acceptance per task (brief).
  - Acceptance: Commands verified; docs align with delivered code; registries consistent.
  - Dependencies: all implementation tasks
  - Proof Plan: scripts/checks.sh registries

## Notes
- Follow protocol: claim one task, respect dependencies/parallel safety, run proofs, branch per task, merge only when green.
- Aim for zero stubs: each implementation task must meet its acceptance and command expectations.***

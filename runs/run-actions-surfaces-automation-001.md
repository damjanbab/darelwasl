# Run run-actions-surfaces-automation-001

Goal: standardize “one set of capabilities across many surfaces” (HTTP API, Telegram, future Slack/CLI), and introduce an automation/rules layer that can deterministically change system state (tasks, facts, notifications, assignments, etc.) based on internal and external events, without duplicating business logic.

Non-goals: build a full CRM/client product UI. Keep the model minimal but extensible; prioritize composability, idempotency, and auditability over UI completeness.

## Guiding constraints (decision record)
- Canonical operations live in the backend as action handlers; surfaces are adapters (no duplicate domain logic).
- Rules can produce *any* action invocations (tasks are just one output type).
- All writes must be validated once (domain validation) and be auditable (`actor` + `reason`/`source`).
- Automation must be idempotent (rules can run repeatedly without duplicates or repeated side-effects).
- Delivery to external IO (Telegram/email/Slack) must be decoupled from writes (outbox/worker), so “IO down” never breaks core state changes.
- Surface parity is not required: surfaces may expose a subset, but must reuse the same action layer for anything they do expose.

## Architecture (target)
The system is a pipeline; each layer has one job:

- Facts (Datomic): persisted entities (tasks, subjects, snapshots, etc.)
- Events: uniform envelope that describes “what happened” (internal actions and external inputs)
- Rules: pure decision layer `event + current facts -> intended action calls`
- Actions: canonical write API (validation + transact + audit + emit follow-up events)
- Outbox: persisted delivery queue for external IO (Telegram now, others later)
- Workers: drain outbox with retry/backoff and delivery idempotency

Rules never transact raw Datomic; they only emit action invocations.

## Concrete implementation paths

This run supports two practical paths depending on how soon you need reliability (outbox) vs speed.

### Path A (fast foundation, safe extension)
Use actions + events + rules with synchronous effects for now (Telegram notifications can remain inline), but enforce the invariants that keep later extension clean.

**Use when**: you want to add rules tomorrow (including weather), but can accept that external delivery is “best effort” in the short term.

**Invariants to enforce now**
- Rules output only action invocations (never `d/transact`).
- Every automation-created entity uses a stable idempotency key (start with tasks).
- Every write records an `actor` and `source` (human/telegram/automation/integration).

### Path B (production-grade core)
Same as Path A, plus outbox + worker. All external IO (Telegram/email/Slack) becomes outbox-delivered, which makes rule execution and human actions resilient to integration outages.

**Use when**: you want “it never goes down” semantics and you’re ready to add a worker loop.

## Canonical shapes (make this true everywhere)

### Action invocation (internal API)
All surfaces (HTTP, Telegram, CLI, automation, integrations) call actions in this shape:
- `:action/id` keyword (e.g. `:cap/action/task-create`)
- `:actor` map (who/what initiated)
- `:input` map (validated/normalized)
- optional `:idempotency/key` (required for automation/outbox)

Action execution returns:
- `:result` (success payload) or `:error {:status ... :message ... :details ...}`
- `:events` (follow-up internal events emitted by the action)
- optional `:outbox/enqueue` entries (Path B)

### Event envelope
Everything that triggers rules is an event:
- `:event/id` uuid
- `:event/type` keyword (namespaced, versioned by convention)
- `:event/subject` optional ref (e.g. `{:subject/type :subject.type/client :subject/id ...}` or `{:subject/type :subject.type/system}`)
- `:event/payload` map (source-specific fields)
- `:event/occurred-at` instant
- `:actor` map (human, integration, automation)

## Step-by-step implementation plan (recommended)

### Milestone 1 — Actions as the canonical “capability surface”
**Outcome**: one internal execution path for all operations; HTTP and Telegram become adapters.

1) Add action dispatcher
   - Files:
     - `src/darelwasl/actions.clj` (new): `dispatch!`, handler registry, shared result shaping
   - Handler mapping (initial):
     - `:cap/action/task-create` -> `tasks/create-task!`
     - `:cap/action/task-update` -> `tasks/update-task!`
     - `:cap/action/task-set-status` -> `tasks/set-status!`
     - `:cap/action/task-assign` -> `tasks/assign-task!`
     - `:cap/action/task-set-due` -> `tasks/set-due-date!`
     - `:cap/action/task-set-tags` -> `tasks/set-tags!`
     - `:cap/action/task-archive` -> `tasks/archive-task!`
     - `:cap/action/task-delete` -> `tasks/delete-task!`
     - (later) tag + content actions
   - Acceptance:
     - A direct call to `dispatch!` can create a task and returns the same error shape as existing routes.

2) Add generic HTTP action endpoint
   - Files:
     - `src/darelwasl/http/routes/actions.clj` (new): `POST /api/actions/:id`
     - `src/darelwasl/http.clj`: include routes
   - Notes:
     - Keep existing REST endpoints stable, but refactor them internally to call `dispatch!` so surfaces converge without breaking clients.
   - Acceptance:
     - `POST /api/actions/cap.action.task-create` (or chosen encoding) works end-to-end.

3) Refactor Telegram command handlers to call actions
   - Files:
     - `src/darelwasl/telegram.clj`: `/new`, `/tasks`, `/task` call `dispatch!` (or a read-action equivalent).
   - Acceptance:
     - Telegram and HTTP create the same task shape and enforce the same validations.

### Milestone 2 — Events (standard trigger mechanism)
**Outcome**: rules can respond to internal changes and external inputs uniformly.

1) Add event helpers
   - Files:
     - `src/darelwasl/events.clj` (new): event constructor + minimal validation
   - Acceptance:
     - Every action can emit an event like `:task/created` or `:task/status-changed`.

2) Emit events from actions (initially in action layer)
   - Files:
     - `src/darelwasl/actions.clj`: wrap handler results with emitted events based on action id.
   - Acceptance:
     - Task status change produces a `:task/status-changed` event in the action result.

### Milestone 3 — Rule registry + runner (extendable tomorrow)
**Outcome**: add a new rule by adding one registry entry + one predicate/handler function (if needed).

1) Add automation registry + loader
   - Files:
     - `registries/automations.edn` (new): list of rule definitions (IDs, triggers, enabled flag)
     - `src/darelwasl/automations.clj` (new): load, match, execute
   - Rule execution contract:
     - Input: `state` + `event`
     - Output: list of action invocations
   - Acceptance:
     - A “hello rule” can react to `:telegram/linked` or `:task/created` and create one deterministic follow-up task.

2) Add idempotency keys for automation-created tasks
   - Files:
     - `registries/schema.edn`: add `:task/automation-key` (unique) + `:task/source-*` fields
     - `src/darelwasl/tasks.clj`: support creating tasks with that key (and/or an “ensure task exists” action)
   - Acceptance:
     - Running the same rule twice yields 1 task (not 2).

## Current implementation status (this branch)

This run is now partially implemented (Path A):

- Actions dispatcher + `POST /api/actions/:id` are live:
  - `src/darelwasl/actions.clj`
  - `src/darelwasl/http/routes/actions.clj`
- Task HTTP mutation routes now call `actions/execute!` (single canonical write path):
  - `src/darelwasl/http/routes/tasks.clj`
- Event envelope exists and actions emit task events:
  - `src/darelwasl/events.clj`
  - `src/darelwasl/actions.clj` (`emit-events`, `execute!`, `apply-event!`)
- Automations/rules registry + runner exist (code-first handlers; EDN selects them):
  - `registries/automations.edn`
  - `src/darelwasl/automations.clj`
- Task idempotency key added for automation-created tasks:
  - `registries/schema.edn` adds `:task/automation-key` (unique when present)
  - `src/darelwasl/tasks.clj` supports idempotent create when the key is provided
- Telegram linking emits an external event `:telegram/linked` which triggers automations:
  - `src/darelwasl/telegram.clj` calls `actions/apply-event!` after a successful `/start <token>`
- Telegram auto-recognition (no token required when known):
  - `registries/schema.edn` adds `:user/telegram-user-id`
  - `src/darelwasl/telegram.clj` maps incoming `from.id` to `:user/telegram-user-id`
  - `src/darelwasl/http/routes/telegram.clj` exposes `POST /api/telegram/recognize`
  - `scripts/tg-spinup.sh` reads `.secrets/telegram_user_id` and registers on boot

### How to add a new rule tomorrow

1) Add a registry entry in `registries/automations.edn`:
   - choose `:id`, set `:enabled true`, add `:triggers [{:event/type :some/event}]`
   - point `:handler` to a handler key (keyword)

2) Implement the handler in `src/darelwasl/automations.clj`:
   - signature: `(fn [automation event] => [action-invocation ...])`
   - output action invocations (never raw `d/transact`)
   - for any create-style output, set a stable idempotency key (for tasks use `:task/automation-key`)

3) Emit an event from somewhere:
   - internal: call an action via `actions/execute!` (it emits task events automatically)
   - external: call `actions/apply-event!` with an `events/new-event` envelope

### HTTP event ingestion (external triggers)

There is a generic ingestion endpoint to trigger rules from any system:

   - `POST /api/events` (requires a session)
   - Body shape (examples):
     - `{"event/type":"telegram.linked","event/payload":{"user/id":"<uuid>","chat-id":"123"}}`
     - `{"event/type":"task.created","event/payload":{"task/id":"<uuid>"}}`

The handler parses `event/type` in either `namespace/name` or `namespace.name` form and runs `actions/apply-event!`.

## Path B (outbox + worker) — implemented

- Outbox schema + helpers:
  - `registries/schema.edn` (entity `:cap/schema/outbox`)
  - `src/darelwasl/outbox.clj` (`enqueue!`, `claim-one!`, `mark-success!`, `mark-failure!`)
- Worker:
  - `src/darelwasl/workers/outbox.clj` (polls outbox and delivers)
  - `scripts/run-worker.sh` (runs `clojure -M:dev -m darelwasl.workers.outbox`)
- Telegram notifications now enqueue to outbox (no inline send):
  - `src/darelwasl/telegram.clj` (`notify-task-event!` enqueues `:integration/telegram`)
- Delivery payload shape (stored EDN):
  - `{:chat-id \"...\" :text \"...\" :message-key \"...\" :parse-mode :MarkdownV2?}`
  - Dedupe key = `message-key` (outbox enforces unique when present)
- Worker currently handles `:integration/telegram`; extend `deliver` in `workers/outbox.clj` for more.
- Config: no new env vars needed; defaults run locally.

How to run locally:
1) Start the app as usual (`scripts/run-service.sh`) to expose HTTP + webhook.
2) Start the worker: `scripts/run-worker.sh` (uses same config + DB).
3) Task events enqueue to outbox; worker drains and sends to Telegram with retry/backoff.

## Telegram surface polish (current)

- Freeform capture now prompts confirmation (no auto-create):
  - Bot replies with a “Create task / Dismiss” inline keyboard.
  - Only creates on tap; captures expire after a TTL.
- Inline task cards:
  - `/task <uuid>` or “Open” button shows a task card.
  - Card includes inline buttons for status changes + archive + refresh.
- `/tasks` list message now supports filters + open:
  - Filter buttons (status + archived) update the list in-place.
  - Each task in the list has an “Open” button that shows a full card.
- Callback support:
  - Webhook accepts `callback_query` updates for buttons.
  - `scripts/tg-spinup.sh` registers `allowed_updates=["message","callback_query"]`.

### Milestone 4 — Subject model (optional but recommended)
**Outcome**: rules don’t depend on “client” but can attach consequences to a domain object cleanly.

1) Add a generic subject entity
   - Files:
     - `registries/schema.edn`: `:subject/id` identity, `:subject/type`, `:subject/state` (optional)
     - `registries/schema.edn`: add `:task/subject` ref (optional)
   - Acceptance:
     - Rules can target `{:subject/type :subject.type/system}` today, and `:subject.type/client` later.

### Milestone 5 — Outbox + worker (Path B)
**Outcome**: external IO can fail without breaking writes; retries are durable.

1) Add outbox schema + enqueue helpers
   - Files:
     - `registries/schema.edn`: outbox entity (id, integration, payload, status, retries, idempotency key)
     - `src/darelwasl/outbox.clj` (new): enqueue + claim + mark-success/fail

2) Add worker loop
   - Files:
     - `src/darelwasl/workers/outbox.clj` (new): polling loop
     - `scripts/run-worker.sh` (new): run locally/prod

3) Move Telegram notifications to outbox consumption
   - Files:
     - `src/darelwasl/http/routes/tasks.clj`: replace inline `telegram/notify-task-event!` with outbox enqueue
     - `src/darelwasl/telegram.clj`: keep send-message primitive (used by worker)

## Definition of done (this run)
- There is exactly one canonical write path: action dispatcher.
- Telegram and HTTP reuse it (same validations, same audit actor shape).
- Rules can trigger on any event type (internal or external) and only emit action invocations.
- Automation is idempotent (no duplicate tasks, no duplicate deliveries).
- Optional (Path B): outbox + worker make IO delivery durable.

## Tasks
- Task ID: action-dispatcher
  - Status: planned
  - Objective: Create a canonical action execution layer so every surface calls the same thing.
  - Scope:
    - Implement `darelwasl.actions` (or similar) with `dispatch!` and a handler registry keyed by `:cap/action/*`.
    - Normalize input/output and error shape across handlers (reuse existing `{:error {:status ...}}` pattern).
    - Introduce a consistent `actor` map passed to every action: `{:actor/type ...}` + optional `:user/id`.
  - Out of Scope: Replacing all existing HTTP endpoints in one go.
  - Deliverables:
    - `dispatch!` API + handler map.
    - Documentation on “how to add a new capability”.
  - Proof Plan: small unit smoke (invoke handler), `./scripts/checks.sh actions`.

- Task ID: event-envelope-and-emission
  - Status: planned
  - Objective: Standardize “what happened” so rules can be triggered by anything (client state, weather, Telegram, cron, etc.).
  - Scope:
    - Define an event envelope shape (map keys and invariants).
    - Ensure action handlers can emit events in a consistent way (internal events).
    - Define external ingest points that emit the same envelope (integrations).
  - Deliverables:
    - `darelwasl.events` helpers (`now`, `new-event`, validation).
    - Minimal docs: event types naming, payload conventions, compatibility/versioning.
  - Proof Plan: unit smoke + a single end-to-end rule triggered by an event.

- Task ID: http-surface-standardization
  - Status: planned
  - Objective: Standardize HTTP as “just another surface” over actions.
  - Scope:
    - Add a generic action endpoint (e.g. `POST /api/actions/:id`) for future surfaces.
    - Optionally keep current REST endpoints, but route them through actions internally.
    - Ensure auth/session and `actor` extraction is consistent.
  - Deliverables:
    - Action route(s) and adapter glue.
    - Compatibility notes (which endpoints remain stable).
  - Proof Plan: curl smoke + existing checks.

- Task ID: telegram-surface-standardization
  - Status: planned
  - Objective: Make Telegram command handling call the same action layer used by HTTP.
  - Scope:
    - `/new`, `/tasks`, `/task` call action handlers (not domain fns directly).
    - Keep `/start` chat-linking as a Telegram-specific capability (still audited).
    - Standardize response formatting from action results (success/error).
  - Deliverables:
    - Telegram adapter refactor for action calls.
    - Update run docs and any system docs describing “canonical actions”.
  - Proof Plan: manual Telegram smoke + webhook logs.

- Task ID: automation-rule-registry
  - Status: planned
  - Objective: Define how “rules” are expressed, versioned, executed, and extended.
  - Scope:
    - Add `registries/automations.edn` describing rules (IDs, triggers, guards, actions).
    - Rules are code-first initially (guards/derivers implemented in Clojure), referenced by ID.
    - Define rule outputs as action invocations, not raw Datomic tx.
    - Add a “reconcile mode” that can be run periodically (same rules, idempotent outputs).
  - Deliverables:
    - Registry format + loader + a small rules DSL shape.
    - Documentation of rule lifecycle (enable/disable, rollout).
  - Proof Plan: registry parsing check + a single rule executed in a local run.

- Task ID: idempotent-automation-keys
  - Status: planned
  - Objective: Prevent duplicates for any entities/actions created by automation and prevent repeated side-effects.
  - Scope:
    - Add stable idempotency keys for automation-created entities (starting with tasks).
    - Add consistent `:*/source` metadata (e.g. `:source/type :automation|:telegram|:user|:integration`, `:source/id`).
  - Deliverables:
    - Schema additions + backfill defaults.
    - Action handler support to “ensure exists” / “upsert” by automation key (starting with tasks).
  - Proof Plan: repeated rule execution produces 1 entity / 1 outbox entry, not N.

- Task ID: outbox-and-workers
  - Status: planned
  - Objective: Decouple “fact changes” from “IO delivery” so external outages don’t break writes.
  - Scope:
    - Add an outbox entity/schema (message records: target integration, payload, retries, last-error).
    - Write path enqueues outbox items; a worker drains and performs IO (Telegram now; email/Slack later).
    - Track delivery idempotency keys (avoid duplicate sends on retries).
  - Deliverables:
    - Outbox schema, enqueue helpers, worker loop + ops script.
    - Telegram notifications moved to outbox consumption (optional flag).
  - Proof Plan: simulate Telegram failure; writes still succeed; outbox retries.

- Task ID: “facts” model decision (client vs task-only)
  - Status: planned (decision + implementation)
  - Objective: Establish the “facts” that drive automation without premature product scope.
  - Options:
    - A) Task-only: rules trigger on tasks/tags/status. Fast start, but you’ll later retrofit “client state” and task context.
    - B) Minimal client/case entity: introduce `:client/id` + `:client/state` (+ optional `:client/name`) and attach tasks to it.
    - C) Generic “subject” entity: `:subject/id` + `:subject/type` + `:subject/state`, and tasks reference subject (clients become one subject type).
  - Deliverables:
    - Decision record + minimal schema + one automation rule using the chosen model.
  - Proof Plan: state transition triggers deterministic tasks.

- Task ID: notes-entity-pending-reason
  - Status: planned
  - Objective: Introduce a minimal note entity and require a typed “pending reason” note when a task transitions to `:pending`.
  - Scope:
    - Add `:note/*` schema (id, body, type, subject/task ref, created-at, author/system).
    - Add note type enum `:note.type/pending-reason` and enforce creation on `:task/status` → `:pending`.
    - Add `:pending` to task status enum and update validations/actions to require a pending-reason note.
    - Update UI/Telegram surfaces to expose pending status and prompt for reason.
    - Update status color semantics: todo = blue, in-progress = yellow, done = green, pending = red (Telegram via labels/emoji).
  - Out of Scope: Full note CRUD UI or global note search.
  - Deliverables:
    - Schema + actions to support pending-reason notes.
    - Surface prompts for pending reason (web + Telegram).
    - Docs update in `docs/system.md` (notes + task status semantics).
  - Proof Plan: `scripts/checks.sh schema` + `scripts/checks.sh actions` + `scripts/checks.sh app-smoke`.

## Recommended decision (initial)
- Prefer **C (generic subject)** if you want rules like “weather changed” and “client state changed” to look the same. Clients become one `:subject/type`.
- Avoid coupling the rule engine to “client” as a concept; couple it to events + facts.
- If you want fastest short-term shipping, start with task-only facts, but add idempotency keys + source metadata immediately so you don’t repaint yourself into a corner.

## Extension risks (and mitigations)
- Tunnel/webhook drift (Telegram): mitigate with a webhook watcher (done) + outbox retries (planned).
- Rule explosion / “if soup”: mitigate by keeping rules data-registered, code-first guards, and enforcing idempotency keys.
- Tight coupling to a domain model (e.g. client-only): mitigate by introducing a generic `:subject` and event envelope.
- Repeated side-effects (duplicate sends/creates): mitigate by unique idempotency keys at both “entity creation” and “outbox delivery”.
- Schema evolution: mitigate by additive schema changes, backfills, and action versioning (`:cap/action/* :version`).

## Ops / local workflow
- `./scripts/tg-spinup.sh` spins up backend + tunnel + webhook and prints `/start <token>`.
- `./scripts/tg-watch-webhook.sh` keeps the Telegram webhook in sync if the tunnel reconnects.

# AGENTS.md (repo root) — single entrypoint

This file is the **single start-here entrypoint** for working in this repo (humans + agents).

It is designed for:
1) Fast context retrieval
2) Progressive-disclosure “playbooks” (so you don’t re-explain repeat work)
3) Safe execution via explicit proofs/policies

## Start here (always)
- Pick a request type (below) and then pick the closest playbook in **Playbooks**.
- If no playbook fits, use **Unknown / Triage** and extend `AGENTS.md` (that’s the standardization path).
- Proofs: run the playbook’s `Proof:` commands before calling the work done.

### Quick commands
- Find things (catalog-first): `scripts/query.sh TERM`
- List playbooks: `scripts/playbook.sh list`
- Open a playbook: `scripts/playbook.sh show <id>`
- Create work item: `scripts/work.sh new --type <change|governance|investigate|question|refactor|delete> --playbook <id> --summary "<text>"`
- Start isolated worktree: `scripts/work.sh start <work-id>`
- Verify in isolation: `scripts/work.sh verify <work-id> -- scripts/checks.sh governance`
- Check the query protocol exists: `scripts/checks.sh query`
- Repo baseline sanity: `scripts/checks.sh governance`
- Full verification (slow): `scripts/checks.sh all`

## Sources of truth
- `src/` (implementation)
- `registries/` (capability surface / contracts)
- `scripts/` (automation + checks; CI entrypoint is `scripts/checks.sh`)
- Generated inventory only: `docs/system.generated.md` + `docs/catalog.edn` (via `scripts/generate-docs.sh`)

## Request types (first word)
You (the user) start every initial message with one of:
- `question:` Explain/compare. No repo changes unless explicitly switched.
- `investigate:` Inspect and report. No repo changes unless explicitly switched.
- `change:` Implement a feature/bugfix.
- `refactor:` Restructure while preserving behavior.
- `delete:` Remove code (requires an “alive root set” + proofs).
- `governance:` Policies/tooling/checks/CI wiring and repo contracts.

Default if ambiguous: treat as `question:`.

## Oversight gate (before edits)
If the request type is `change:`, `refactor:`, `delete:`, or `governance:`, do not start editing until you present and get confirmation on:
- Type
- Goal (1 sentence)
- Scope (files/areas)
- Proof (exact command(s))
- Non-goals

Also: create a work item first (so the work is stored + queryable):
- `scripts/work.sh new ...`
- `scripts/work.sh start <id>` (isolates changes into a worktree/branch)

## Query protocol (how to find things fast)
Prefer catalog-backed lookups before `rg` spelunking.
- Inventory snapshot: `docs/system.generated.md`
- Machine-readable catalog: `docs/catalog.edn`
- Query tool: `scripts/query.sh`
- Checks: `scripts/checks.sh query` (also runs under `scripts/checks.sh docs`)

Workflow:
1) `scripts/query.sh TERM` to get candidate IDs + source files
2) Open the cited `:source` file(s) (registry EDN or Clojure namespace)
3) Use `rg` only after you have a narrowed path/namespace

## Ops runbooks
- Telegram dev/prod bots + promotion: `docs/ops/telegram-bots.md`
- `code.haloeddepth.com` endpoint (web terminals): `docs/ops/code-haloeddepth-com.md`
- Secrets vault (Datomic + master key): `docs/ops/secrets.md`
- Telegram commands + documents flows: `docs/telegram.md`

## Lab session (code.haloeddepth.com)
The Lab is split into **stable** and **canary** sessions (defaults: `codex7` and `codex8`).

File handoff convention (browser ↔ codex):
- Stable:
  - Uploads land in `tmp/lab/codex7/inbox/`
  - Downloads come from `tmp/lab/codex7/outbox/` (outbox-only downloads)
- Canary:
  - Uploads land in `tmp/lab/codex8/inbox/`
  - Downloads come from `tmp/lab/codex8/outbox/`

Notes:
- Select stable/canary in the Lab UI (`/lab`) or with `?session=N` (persisted via cookie `dw_lab_session`).
- Roles are configured by `DW_LAB_SESSION_STABLE` / `DW_LAB_SESSION_CANARY` (legacy `DW_LAB_SESSION` still works).

Helper commands:
- Stable: `scripts/lab.sh --stable paths`
- Canary: `scripts/lab.sh --canary paths`
- List inbox: `scripts/lab.sh --stable ls-inbox`
- Put file in outbox: `scripts/lab.sh --stable put-outbox path/to/file.zip`

## Agents (capability contracts)
Agent contracts live under `agents/` (one folder per agent type). Each contract defines:
- Allowed paths (what the agent may change)
- Required proofs (what must pass)
- Intended use (what requests it should handle)

## Playbooks

Use the closest playbook, then follow it top-to-bottom. If none fit, use **Unknown / Triage** and add a new playbook.

### Playbook: query/locate-sources — find code + contracts fast
- When: You need to find “where is X implemented?” or “what contract owns this?”
- Start:
  - `scripts/checks.sh query`
  - `scripts/query.sh TERM`
  - Open the cited `:source` files; only then use `rg` in that narrowed area.
- Policies:
  - `policies/registries-are-contract.md`
- Proof:
  - `scripts/checks.sh query`
- Next:
  - If you touched registries: run `scripts/checks.sh registries` (and `scripts/checks.sh schema` if schema-related).

### Playbook: telegram/dev-proof-and-promote — dev-first Telegram work
- When: Any Telegram change, bot behavior change, or Telegram proof/testing.
- Start:
  - Read: `docs/ops/telegram-bots.md`
  - Use dev preview: `scripts/tgctl.sh dev preview-start <run-id>`
  - Follow the operator flow in: `docs/telegram.md`
- Policies:
  - `policies/telegram-dev-bot-only.md`
  - `policies/telegram-single-flight.md`
  - `policies/preview-before-promote.md`
- Proof:
  - `scripts/checks.sh governance`
  - Manual: verify the flow against the **dev bot** (per `docs/ops/telegram-bots.md`).
- Next:
  - If promoting: follow the “promote” section in `docs/ops/telegram-bots.md`.

### Playbook: documents/pdfs — change PDFs (proposal/invoice/receipt/status)
- When: You change how generated documents look or what data they include.
- Start:
  - Locate the generator entrypoint(s): `scripts/query.sh documents-pdf`
  - Reproduce via script (fast): `node scripts/documents-pdf.js --help`
  - Reproduce via Telegram flow (realistic): `docs/telegram.md` (Documents starter pack)
- Policies:
  - `policies/backend-changes.md`
  - `policies/verification-required.md`
- Proof:
  - `scripts/checks.sh governance`
  - Manual: generate each impacted PDF once on the **dev** path (Telegram or script) and visually verify.
- Next:
  - If you had to invent a new repeatable “how to verify” step, extend this playbook with the exact commands.

### Playbook: backend/api-surface — add/modify a backend route or API contract
- When: You add/modify an HTTP route, request/response shape, or anything that changes API surface.
- Start:
  - Find the owning namespace/files: `scripts/query.sh route` and `scripts/query.sh api`
  - Find the contract entry points: `scripts/query.sh registries/actions.edn` and `scripts/query.sh registries/views.edn`
  - Narrow with `rg` only after you have a likely namespace/file.
- Policies:
  - `policies/backend-changes.md`
  - `policies/registries-are-contract.md`
  - `policies/verification-required.md`
- Proof:
  - `scripts/checks.sh registries`
  - `scripts/checks.sh schema`
  - `scripts/checks.sh actions`
- Next:
  - If this work routinely needs a dedicated “routes policy”, add one via **Unknown / Triage** and then list it here.

### Playbook: registries/change — edit registries safely
- When: You add/modify capabilities in `registries/*.edn`.
- Start:
  - `scripts/checks.sh registries`
  - Edit the smallest registry surface needed (single-form EDN).
  - If schema changes: run the schema check early.
- Policies:
  - `policies/registry-changes.md`
  - `policies/registries-are-contract.md`
- Proof:
  - `scripts/checks.sh registries`
  - `scripts/checks.sh schema`
- Next:
  - If you changed action contracts: run `scripts/checks.sh actions`.

### Playbook: docs/generated — keep generated docs in sync
- When: You change code/registries and the catalog/system docs drift.
- Start:
  - `scripts/generate-docs.sh`
  - Inspect: `git diff -- docs/system.generated.md docs/catalog.edn`
- Policies:
  - `policies/docs-are-output.md`
- Proof:
  - `scripts/checks.sh docs`
- Next:
  - If you created a new repeatable workflow, add a playbook and link it from here.

### Playbook: lab/ui — improve Lab UI (code.haloeddepth.com)
- When: You need better Lab UX (history, exchange, artifacts), or to deploy Lab UI changes.
- Start:
  - Read: `docs/ops/code-haloeddepth-com.md`
  - Edit UI server source: `ops/webterm-ui/server.py`
  - Deploy canary on host (review first): `scripts/webterm-ui.sh deploy-canary`
  - Review in browser: `https://code.haloeddepth.com/canary/lab?session=<N>`
  - After acceptance: `scripts/work.sh pr-create <work-id>`
  - After merge: `scripts/webterm-ui.sh deploy-stable` (and `scripts/webterm-ui.sh promote-lab` if this is workflow-risky)
- Policies:
  - `policies/ops-governance-changes.md`
  - `policies/lab-artifacts-to-outbox.md`
  - `policies/lab-canary-upgrades.md`
  - `policies/lab-ui-blue-green.md`
  - `policies/verification-required.md`
- Proof:
  - `scripts/checks.sh governance`
  - `python3 -m py_compile ops/webterm-ui/server.py`
  - `scripts/webterm-ui.sh smoke`
  - `scripts/webterm-ui.sh smoke --canary`
- Next:
  - If you want auto-published PDFs in the Lab outbox, enable `DW_LAB_AUTO_OUTBOX=1` in the environment running the PDF generators.
  - For risky Lab changes: validate in canary first, then swap stable/canary per `policies/lab-canary-upgrades.md`.

### Playbook: work/isolate-pr — isolated work + PR submission
- When: Any `change:` or `governance:` work that should not touch the base working tree.
- Start:
  - Create a work item: `scripts/work.sh new --type change --playbook work/isolate-pr --summary "<summary>"`
  - Create an isolated worktree: `scripts/work.sh start <work-id>`
  - Open worktree path: `scripts/work.sh path <work-id>`
- Policies:
  - `policies/verification-required.md`
  - `policies/pr-required.md`
- Proof:
  - In worktree: `scripts/work.sh verify <work-id> -- scripts/checks.sh governance`
  - In worktree: run the playbook-specific proofs for the change.
- Next:
  - Install the local guard hook (once): `scripts/hooks.sh install`
  - Commit and PR: `scripts/work.sh commit <work-id> -m "…"`, then `scripts/work.sh pr <work-id>`

## Unknown / Triage (no matching playbook)

Use this when your request doesn’t fit any playbook yet. The goal is to **standardize** by extending `AGENTS.md`.

### Triage steps
1) Restate the work in 1 sentence and pick the closest request type (`question:`/`investigate:`/`change:`/…).
2) Run `scripts/query.sh TERM` for:
   - The domain noun (e.g. “telegram”, “invoice”, “route”, “pdf”)
   - The artifact (e.g. “actions.edn”, “views.edn”, “docs”)
3) If there is an ops runbook under `docs/ops/`, link it from the new playbook.
4) Add a playbook section using the template below.
5) If the playbook needs a new policy (because nothing existing clearly covers the risk), add it:
   - Create `policies/<new-policy>.md`
   - Register it in `registries/policies.edn`
6) Run proofs: `scripts/checks.sh governance` plus the playbook’s `Proof:` commands.

### Playbook template (copy/paste)
### Playbook: <area>/<name> — <short title>
- When: <1 sentence trigger>
- Start:
  - <command or doc to read>
  - <command>
  - <command>
- Policies:
  - `policies/<policy>.md`
- Proof:
  - <command>
- Next:
  - <what to do if still blocked / what follow-up playbook to add>

## Answering contract (for `question:`)
Use this shape:
1) **Answer (now)**: plain language, minimal jargon, grounded in repo context retrieval.
2) **Tooling opportunities (optional)**: only if it removes repetition/breakage (Opportunity / Proposed automation / Proof / Scope).
3) **Stop gate**: ask the user to choose one: stop, draft, or implement.

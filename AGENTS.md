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
- Find work items (catalog-backed): `scripts/query.sh --kind work-item TERM`
- List playbooks: `scripts/playbook.sh list`
- Open a playbook: `scripts/playbook.sh show <id>`
- Create work item: `scripts/work.sh new --type <change|governance|investigate|question|refactor|delete> --playbook <id> --summary "<text>"`
- List open work: `scripts/work.sh list --open`
- Audit work tracking: `scripts/work.sh audit`
- Auto-close merged work items: `scripts/work.sh close-merged --into main`
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

## Language preference
- Prefer **Clojure** for new server-side code + ops tooling when feasible (policy: `policies/clojure-first.md`).

## Request types (first word)
You (the user) start every initial message with one of:
- `question:` Explain/compare. No repo changes unless explicitly switched.
- `investigate:` Inspect and report. No repo changes unless explicitly switched.
- `change:` Implement a feature/bugfix.
- `refactor:` Restructure while preserving behavior.
- `delete:` Remove code (requires an “alive root set” + proofs).
- `governance:` Policies/tooling/checks/CI wiring and repo contracts.

Default if ambiguous: treat as `question:`.

## One-off vs workflow (wiring-first contract)
Default behavior:
- If the request is **one-off**, use existing capabilities (actions/routes/scripts) and avoid adding new automation.
- If capability exists but is **not wired into this repo contract** (no playbook/runbook pointers), wire it:
  - Add/extend a playbook section in `AGENTS.md` and/or link an ops doc under `docs/ops/`.
  - Do this wiring in the same PR as the one-off fix when it is low-risk and clarifies future work.
- Only “productize” into new scripts/policies when:
  - you explicitly ask for a repeatable workflow, or
  - the same gap repeats, or
  - not having tooling is a security/operational risk.

Tooling proposal rule (progressive disclosure):
1) Discover first via the catalog: `scripts/query.sh TERM` (backed by generated `docs/catalog.edn`).
2) If something exists, reuse it; if it’s not referenced in playbooks, wire pointers before inventing new tooling.
3) If nothing exists, propose the smallest helper (script/check/policy) with exact `Proof:` commands.

## Work brief (work-first)
If the request type is `change:`, `refactor:`, `delete:`, or `governance:`, speak in terms of **work**:
- Write a 1-time work brief: Type / Goal / Scope / Proof / Non-goals.
- Create a work item so the work is stored + queryable:
  - `scripts/work.sh new ...`
  - `scripts/work.sh start <id>` (isolates changes into a worktree/branch)
- Proceed with implementation; only pause to ask questions when truly blocked.

## Query protocol (how to find things fast)
Prefer catalog-backed lookups before `rg` spelunking.
- Inventory snapshot: `docs/system.generated.md`
- Machine-readable catalog: `docs/catalog.edn`
- Query tool: `scripts/query.sh`
- Checks: `scripts/checks.sh query` (also runs under `scripts/checks.sh docs`)

Notes:
- `scripts/query.sh` queries the **generated** catalog (`docs/catalog.edn`). If results look stale/missing, run `scripts/checks.sh docs` (or `scripts/generate-docs.sh`) and commit the regenerated docs.

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
  - For Lab review: set `DW_LAB_AUTO_OUTBOX=1` and ensure a work id is set (prefer `DW_LAB_WORK_ID=<work-id>` or worktree branch `work/<id>`).
- Policies:
  - `policies/backend-changes.md`
  - `policies/lab-artifacts-to-outbox.md`
  - `policies/verification-required.md`
- Proof:
  - `scripts/checks.sh governance`
  - Manual: generate each impacted PDF once and visually verify in the Lab UI (Work → Select → Review).
- Next:
  - If you had to invent a new repeatable “how to verify” step, extend this playbook with the exact commands.

### Playbook: secrets/vault — store and retrieve secrets safely
- When: You need to store/rotate operational secrets (GitHub/Telegram/etc.) without putting them in git/logs/chat.
- Start:
  - Read: `docs/ops/secrets.md`
  - One-time master key (per environment): `scripts/secrets.sh init-master-key`
  - Store a secret (stdin or prompt): `scripts/secrets.sh set <key>`
  - Store from a file (preferred for handoffs): `cat path/to/secret.txt | scripts/secrets.sh set <key>`
  - Convention: GitHub PAT lives at key `github/token`
- Policies:
  - `policies/secrets-vault.md`
- Proof:
  - `scripts/checks.sh governance`
  - `scripts/checks.sh schema`
- Next:
  - Prefer Lab inbox (`tmp/lab/.../inbox/`) or File Library for secret handoff; delete plaintext artifacts after import.

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
  - Edit UI server source: `src/darelwasl/webterm/`
  - Canary deploy on host: `scripts/webterm-ui.sh deploy-canary`
  - Stable deploy on host (post-merge): `scripts/webterm-ui.sh deploy-stable`
- Policies:
  - `policies/ops-governance-changes.md`
  - `policies/lab-artifacts-to-outbox.md`
  - `policies/lab-canary-upgrades.md`
  - `policies/verification-required.md`
- Proof:
  - `scripts/checks.sh governance`
  - `clojure -M -m darelwasl.webterm.server --check`
  - `scripts/webterm-ui.sh smoke`
- Next:
  - If you want auto-published PDFs in the Lab outbox, enable `DW_LAB_AUTO_OUTBOX=1` in the environment running the PDF generators.
  - For risky Lab changes: validate in canary first, then swap stable/canary per `policies/lab-canary-upgrades.md`.

### Playbook: work/isolate-pr — isolated work + PR submission
- When: Any `change:` or `governance:` work that should not touch the base working tree.
- Start:
  - Create a work item: `scripts/work.sh new --type change --playbook work/isolate-pr --summary "<summary>"`
  - (Optional) Find existing work: `scripts/work.sh list --open` or `scripts/query.sh --kind work-item TERM`
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

### Playbook: work/production-pipeline — approved work → proof → PR → merge
- When: You want the system to run an approved work end-to-end (agent execution + proof + PR + merge).
- Start:
  - Create work: `scripts/work-prod.sh new --type change --playbook work/production-pipeline --summary "<summary>"`
  - Start worktree: `scripts/work.sh start <work-id>`
  - Initialize work-prod state: `scripts/work-prod.sh init <work-id> --agent agents/<agent>/AGENT.json --request "<request>"`
  - (Optional) Preflight only: `scripts/work-prod.sh preflight <work-id> --agent agents/<agent>/AGENT.json`
  - Approve spec (records runtime approval): `scripts/work-prod.sh approve-spec <work-id>`
  - Execute (auto-syncs origin/main first): `scripts/work-prod.sh execute <work-id>`
  - Publish proof + deliver to Lab outbox: `scripts/work-prod.sh preview <work-id> --lab stable`
  - Approve proof (via approve link in the proof HTML, or CLI): `scripts/work-prod.sh approve-proof <work-id>`
- Policies:
  - `policies/work-production-pipeline.md`
  - `policies/verification-required.md`
  - `policies/pr-required.md`
- Proof:
  - `scripts/checks.sh governance`
  - Manual: open the outbox `work-proof-<id>.html` (or `work-links-<id>.txt`) in the Lab UI, verify preview behavior, then approve (creates PR and attempts merge).
- Next:
  - Run merge agent (for queued work branches): `DEPLOY_APPROVED=1 scripts/work-prod.sh merge-agent`
  - If the work cannot be specified due to missing playbooks/tooling: create a prerequisite governance work and block the work until prerequisites land on `main`.

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
3) **Work brief (if action is requested)**: state Type / Goal / Scope / Proof / Non-goals once, then proceed.

# Run run-data-provenance-001

Goal: make the system fully data-driven with unified entities (people, channels, tasks, notes), strong provenance on every fact, and adapters/views that rely on the same contracts.

## Tasks
- Task ID: data-provenance-spec
  - Status: done (Codex, 2025-12-19 02:26 UTC)
  - Objective: Document a scalable provenance + facts model (entities, roles/segments, channels, handles, lineage, evidence) in `docs/system.md`.
  - Scope: Narrative/schema plan only; no registry or backend mutations yet.
  - Deliverables: New system doc section describing identity strategy, provenance fields, fact shapes, invariants, and rollout notes.
  - Proof Plan: Doc review

- Task ID: data-provenance-schema
  - Status: done (Codex, 2025-12-19 03:21 UTC)
  - Objective: Add schema/registry entries for provenance fields, channel entities, person handles/roles, note entity, and fact projections; keep backward compatibility during migration.
  - Proof Plan: `scripts/checks.sh schema`
  - Proofs: `scripts/checks.sh schema`

- Task ID: data-provenance-adapters
  - Status: done (Codex, 2025-12-19 04:32 UTC)
  - Objective: Begin attaching provenance to write paths; initial coverage for task CRUD/status/assign/due/tag operations via the web adapter, and extend to content CRUD.
  - Scope: Shared provenance helper + enrichment of task and content tx maps; adapters beyond web UI still pending.
  - Out of Scope: Rule/import provenance, channel/message facts, projections, UI surface.
  - Proof Plan: `scripts/checks.sh schema` (structure only; action checks unchanged)
  - Progress:
    - Shared provenance helper (`src/darelwasl/provenance.clj`) and wired into all task actions.
    - All content actions (tags/pages/blocks/licensing/persona/etc.) now enrich tx maps with provenance.
    - Status regression fixed earlier (`set-status!` pull threading). `scripts/checks.sh actions` passes with provenance attached.

- Task ID: data-provenance-adapters
  - Status: pending
  - Objective: Refactor write paths (web UI, Telegram, rules/importers) to emit provenance-aware facts and projections; maintain existing APIs during rollout.
  - Proof Plan: `scripts/checks.sh actions`, targeted integration tests

- Task ID: data-provenance-views
  - Status: done (Codex, 2025-12-19 04:40 UTC)
  - Objective: Swap Task/Channel/Person views to read from the fact projections and surface lineage (e.g., “from rule X / chat Y”), keeping UX minimal.
  - Proof Plan: `scripts/checks.sh app-smoke`
  - Outcome: Task API now pulls provenance fields and exposes a normalized `:task/provenance` map; UI renders provenance badges on task cards and detail sheets using adapter/source/created-at. Content/task adapters already enrich writes with provenance. Remaining adapters (rules/imports/telegram) can plug into the shared helper.

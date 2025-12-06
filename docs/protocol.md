# Protocol

Purpose: guide sandbox agents to deliver changes with total correctness using referenced documents (`docs/system.md`, `docs/faq.md`) as the canonical sources. Agents rely on reasoning, not automation, but must enforce a fail-closed mindset.

## Initial Prompt (copy verbatim to sandbox agents)
You are a sandbox agent with no prior context. Your goal is to implement a run with zero defects. Follow these steps, referencing documents by path:
0) Before any git fetch/push, run `source scripts/load_github_token.sh` to export `DARELWASL_GITHUB_TOKEN`/`GITHUB_TOKEN`. Keep the remote set to HTTPS (`git remote set-url origin https://github.com/damjanbab/darelwasl.git`). If a non-interactive push is needed, create a temporary askpass helper (`cat <<'EOF' >/tmp/git-askpass.sh\n#!/usr/bin/env bash\nif [[ \"$1\" == *Username* ]]; then echo \"x-access-token\"; else echo \"${GITHUB_TOKEN:?}\"; fi\nEOF\nchmod +x /tmp/git-askpass.sh`) and run `GIT_ASKPASS=/tmp/git-askpass.sh GIT_TERMINAL_PROMPT=0 git push origin main`. Do not print or log the token and do not commit the loader script.
1) Read `docs/protocol.md` (operating manual), then `docs/system.md` (System Index) and `docs/faq.md` (gotchas/notes).
2) Read the current run file (`runs/<run-id>.md`). It contains ordered tasks. Validate each task against the Task Schema below. If a task is malformed, fix the brief or split work inside the run file; do not proceed on invalid input.
3) Plan the run; keep scope inside the run file and referenced capabilities. The run should be complete when defined; avoid adding tasks unless a blocker makes it impossible to proceed without an explicit new task entry.
4) Execute tasks in order. Do not hack or introduce untracked changes; every change must map to a task entry. Before starting a task, confirm its parallel-safety fields and current statuses to avoid conflicts.
5) Update `docs/system.md` and relevant registries in `registries/` if capabilities change (schema/actions/views/integrations/tooling/patterns/fixtures); add gotchas to `docs/faq.md`.
6) Run all required proofs (per task brief + defaults). If a proof cannot run, stop and surface why; do not merge.
7) Open a PR with a clear summary and proof results; merge only if everything is green; no manual overrides.
8) Report back with what changed, proofs run, and any protocol/system/faq updates. Archive the run file when complete; the next run gets a new file.

Core invariants: correctness > speed; no flaky or external-dependent proofs; browser loader/app boot must succeed; changes must align with capability definitions in `docs/system.md`.

## How to use `docs/system.md`
- Treat `docs/system.md` as the eternal System Index. It defines capabilities (schema, actions, views/apps, integrations), invariants, patterns, fixtures, and change rules.
- When adding/modifying capabilities, update `docs/system.md` in the appropriate section and reference capability IDs in the task brief and PR.

## Run and Task Structure
- Runs live in `runs/<run-id>.md` and contain an ordered list of tasks that should ideally be complete before work starts.
- Agents should not invent new tasks; if unavoidable (blocker), add a minimal task entry inside the run file and mark it as blocker-driven. Prefer queuing follow-ups for the next run instead of expanding the current one.
- When a run is finished, archive the run file and start a new one for subsequent work.
- Each task must declare parallel-safety information (see schema). Agents decide to start only if their task’s exclusive capability set does not intersect any in-progress tasks.
- Task status is tracked in the run file (`pending`/`in-progress`/`done`/`blocked`). Update status when claiming/finishing a task so other agents can see conflicts.

## Run Definition (before tasks exist)
- Before creating a run, extract requirements from the user until complete. At minimum capture: product goal, success criteria, UX/interaction expectations, design direction, data model seed, constraints/flags, and proof expectations.
- Do not create tasks if requirements are incomplete. Ask clarifying questions until you can articulate a product spec and design spec.
- Create two prerequisite tasks in every run: “product-spec” (flows, acceptance criteria, data/flags) and “design-spec” (visual language, components, layout/responsiveness, accessibility). All implementation tasks depend on these unless already defined in prior runs.

## Task Creation Rules
- Tasks must be small and specific. Avoid large bundles; prefer many granular tasks to preserve parallel safety.
- Each task must map to a clear deliverable and acceptance criteria derived from the product/design specs.
- Do not leave fields vague. If any field is ambiguous, refine with the user before finalizing the task.

## Task Schema (all tasks must conform)
- Task ID
- Objective (succinct goal)
- Scope (what is in)
- Out of Scope (explicitly excluded)
- Capabilities Touched (IDs from `docs/system.md`; include schema/action/view/integration IDs)
- Parallel Safety:
  - Exclusive Capabilities (must not overlap with other in-progress tasks)
  - Shared/Read-only Capabilities (safe to share)
  - Sequencing Constraints (tasks that must precede/follow)
- Composability Impact:
  - What layer(s) are affected (schema/actions/views/tooling/integrations)
  - Which existing patterns/registries are reused or extended
  - Any new composability rule needed in `docs/system.md`
- Requirement Change & Compatibility:
  - What requirement is changing and why
  - Compatibility expectation (backward/forward/none)
  - Flag/Rollout plan (if introducing gated behavior)
- Breaking/Deprecation:
  - Any breaking change or deprecation? Mitigations and timeline
- Dependencies (upstream tasks/capabilities; if missing, spawn/fix before proceeding)
- Deliverables (artifacts/code changes expected)
- Proof Plan (required checks to run; defaults apply)
- Fixtures/Data Assumptions (test data to use or add)
- Protocol/System Updates (any required updates to `docs/protocol.md` or `docs/system.md`)
- FAQ Updates (gotchas/notes to add to `docs/faq.md`)
- Tooling/Automation (whether new tools/scripts/checks are required to reduce friction or enforce correctness)
- Reporting (what to include in the PR/report back)

If any field is missing/ambiguous, pause and correct the brief before coding.

## Default Workflow
1) Bootstrap: read `docs/protocol.md` → read `docs/system.md` → read `docs/faq.md` → read/validate the run file.
2) Plan: outline steps tied to tasks, capabilities, and proofs; respect task order. Check task statuses and parallel-safety; only start tasks with non-conflicting exclusive capabilities.
3) Execute: implement within scope; keep changes consistent with patterns, invariants, and composability rules; no untracked hacks.
4) Update `docs/system.md`, registries in `registries/`, and `docs/faq.md`: record capability changes, fixtures, patterns, tooling, composability notes, and gotchas as needed. If other parallel runs have landed, re-read these docs before final proofs/merge.
5) Proofs: run required checks; if blocked, stop and report. Before merging, ensure proofs run against the latest `docs/system.md`/registries state.
6) PR: include summary, capability references, proof results, and any `docs/system.md`/protocol/faq touches. Note if a rebase was required and how conflicts were resolved.
7) Merge: only with all proofs green; no manual overrides. Avoid force-pushes that could drop others’ changes.
8) Report back per task brief and archive the run file when complete.

## Required Proofs (defaults; task may add more)
- Datomic schema sanity: load schema into a temp DB; ensure invariants hold.
- Action contracts: inputs/outputs, side effects, audit expectations; golden/fixture-based where applicable.
- View/app smoke: browser loader/app boot passes; key view flows for touched capabilities.
- Static checks: type/lint/build as relevant.
- Hermeticity: no external flakiness; use fixtures and pinned deps.
- Registry completeness: required fields present (IDs, versions, compatibility/flags, adapters/contracts where relevant).
- Use `scripts/checks.sh` as the entry point; extend stubs as needed.
- Composability adherence: verify changes follow composability rules in `docs/system.md`; if new rules are needed, add them in the same PR/run.

If a proof cannot run, do not bypass it; surface the blocker and stop.

## Tooling for Friction Reduction
- If a manual step repeats within the run or threatens correctness, create or improve tools/scripts/checks rather than repeating manual work.
- To add tooling within a run, ensure there is a task entry (blocker-driven) or queue it for the next run; document the tool in `docs/system.md`, register it in `registries/tooling.edn`, and note quirks in `docs/faq.md`.
- Tools must be stable and reproducible; prefer deterministic fixtures and pinned dependencies.
- Reference new tooling in task reports and PRs so future agents can reuse it.
- Do not ship per-task bespoke scripts; extend shared entry points (e.g., `scripts/checks.sh`) and registries instead.

## Maintaining `docs/system.md`
- Add/update capability entries (schema/action/view/integration) when they change.
- Record new fixtures/test data references.
- Keep patterns/guidelines current when new ones are introduced by a task.
- Use stable IDs; reference them in tasks/PRs.

## Maintaining `docs/faq.md`
- Capture gotchas, edge cases, and recurring instructions that don’t fit elsewhere.
- Link entries to capability IDs and tasks when relevant.
- Keep concise; update when resolved or superseded.

## Agent Feedback Loop
- If you encounter friction, ambiguity, or new patterns, propose updates: add immediate notes to `docs/faq.md`; if process/patterns change, add a task item (inside the current run or queued for the next run) to update `docs/protocol.md` and/or `docs/system.md`.
- Avoid expanding the current run unless a blocker demands it; otherwise queue follow-ups for the next run file.
- Report feedback and any added notes in the PR and post-merge report.

## Evolving this Protocol
- Default: stable. Only evolve via explicit tasks that include “Protocol/System/FAQ Updates.”
- When protocol changes, record the change and rationale; ensure `docs/system.md` and `docs/faq.md` stay aligned.

## Reporting Expectations
- In PR: summary, capability IDs touched, proofs run/results, `docs/system.md` updates, `docs/faq.md` updates, any protocol notes.
- Post-merge: brief report back per task brief.

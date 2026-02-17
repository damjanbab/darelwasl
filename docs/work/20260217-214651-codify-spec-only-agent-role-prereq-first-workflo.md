work/id: 20260217-214651-codify-spec-only-agent-role-prereq-first-workflo
work/type: governance
work/status: open
work/playbook: work/production-pipeline
work/summary: Codify spec-only agent role + prereq-first workflow
work/branch: work/20260217-214651-codify-spec-only-agent-role-prereq-first-workflo
work/worktree: target/worktrees/20260217-214651-codify-spec-only-agent-role-prereq-first-workflo
work/base: main
work/prereqs: []
work/lock: 
work/created_at: 2026-02-17T21:46:51Z
work/updated_at: 2026-02-17T21:47:56Z

# Notes

## Work brief

- Type: `governance`
- Goal: Make it unmissable that an agent’s default role is **spec-only**: create work items/specs, identify prerequisites/locks, then stop; execution only happens via `work-prod` after approval.
- Scope:
  - Update `AGENTS.md` to state the spec-only role up front (and reference the `work-prod` approval/execution flow).
  - Update `scripts/checks.sh` to assert the spec-only role section exists (prevents drift).
  - Update `policies/work-production-pipeline.md` and/or `docs/work/README.md` to reinforce the workflow from multiple entrypoints.
- Non-goals:
  - Changing the semantics of `scripts/work-prod.sh` beyond documentation/guardrails.
  - Implementing unrelated playbook/query/docs improvements (separate works).

## Prerequisites

- Agent contract: `agents/ops-governance/AGENT.json` (allowed paths cover `AGENTS.md`, `docs/`, `policies/`, `scripts/`).
- No `work/prereqs` needed; no `work/lock` needed.

## Proof
- [ ] `scripts/checks.sh governance`

## Acceptance criteria

- `AGENTS.md` contains a clearly titled “spec-only role” section that says (in plain language) the agent should:
  - create the work item + spec,
  - identify prerequisites/locks,
  - and stop (no implementation) until the work is approved into `work-prod`.
- `scripts/checks.sh governance` fails if the spec-only role section is missing (or the marker string is removed).

# AGENTS.md (repo root)

This file is the entry point and routing contract for working in this repo.
Optimize for:
1) Context retrieval
2) Automation of repetitive work

## Sources of truth
- `src/` (implementation)
- `registries/` (capability surface)
- `scripts/` (automation + checks; CI entrypoint is `scripts/checks.sh`)
- Generated inventory only: `docs/system.generated.md` + `docs/catalog.edn` (via `scripts/generate-docs.sh`)

## Agents (capability contracts)
Agent contracts live under `agents/` (one folder per agent type). Each contract defines:
- Allowed paths (what the agent may change)
- Required proofs (what must pass)
- Intended use (what requests it should handle)

Agent Control uses these contracts to safely run automated changes behind previews.

## Request types (first word)
You (the user) start every initial message with one of:
- `question:` Explain or compare. No repo changes unless you explicitly switch types.
- `investigate:` Inspect repo and report findings. No changes unless explicitly requested.
- `change:` Implement a feature/bugfix. Changes allowed.
- `refactor:` Restructure while preserving behavior. Requires agreed proofs before edits.
- `delete:` Remove code. Requires agreed “alive root set” + proofs before deletion.
- `governance:` Skills/policies/tooling/checks/CI wiring. Changes allowed; keep CI green.

Default if ambiguous: treat as `question:`.

## Question mode (answering contract)
For `question:` responses, always use this shape:
1) **Answer (now)**: plain language, minimal jargon, based on programmatic context retrieval first.
2) **Tooling opportunities (optional)**: only if it would reduce repetition/breakage. Include:
   - Opportunity (what pain it removes)
   - Proposed automation (smallest script/check/registry change)
   - Proof (exact command(s) that should pass)
   - Scope (likely files to touch)
3) **Stop gate**: end by asking you to choose one:
   - A) stop here
   - B) draft a proposal only
   - C) implement (switch to `governance:` or `change:` as appropriate)

## Before any code changes (oversight gate)
If the request type is `change:`, `refactor:`, `delete:`, or `governance:`, do not start editing until you
present and get confirmation on:
- Type, Goal (1 sentence), Scope (files/areas), Proof (exact checks), Non-goals.

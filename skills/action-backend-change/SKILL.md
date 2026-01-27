# Skill: Action + Backend Change

## Intent
Use when you add/change backend behavior exposed as an action (or change action contracts).

## Inputs
- Action id(s) affected (e.g. `:cap/action/...`).
- Expected inputs/outputs and any side effects.

## Procedure (commands)
1) Update or add the action entry in `registries/actions.edn`.
2) Implement the backend change in `src/` (and any adapter/integration code needed).
3) Verify action contract + schema compatibility:
   - `scripts/checks.sh actions`
4) If the change affects UI behavior, also run:
   - `scripts/checks.sh app-smoke`
5) Before merge:
   - `scripts/checks.sh all`

## Proofs
- `scripts/checks.sh actions` exits 0 (contract harness + schema + registries).
- If UI-visible: `scripts/checks.sh app-smoke` exits 0.

## Common failure modes
- Registry action entry missing required fields (see `scripts/checks.sh registries` output).
- Fixture drift: update fixtures under `fixtures/` if the contract expects them.
- UI payload mismatch: ensure server expects the same key shape the UI sends.


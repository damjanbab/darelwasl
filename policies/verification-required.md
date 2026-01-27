# Policy: Verification Required

## Rule
No change is “done” unless the relevant automated checks pass.

## Enforcement
- CI runs `scripts/checks.sh all` on every PR (see `.github/workflows/checks.yml`).
- Local workflow should run targeted checks first, then `scripts/checks.sh all` before merge.

## Notes
If checks are slow, prefer narrowing locally (e.g. `scripts/checks.sh actions`) but keep CI as the gate.


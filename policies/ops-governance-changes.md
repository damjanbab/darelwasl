# Policy: Ops/Governance Changes

## Rule
Changes to automation, checks, agent contracts, and governance wiring must keep CI green and keep the “source of truth” surfaces consistent.

## Change surface
- `scripts/`
- `agents/`
- `policies/`
- `.github/workflows/`
- Generated inventory only: `docs/system.generated.md` + `docs/catalog.edn`

## Required proofs
- `scripts/checks.sh governance`
- If docs generation changes, also run: `scripts/checks.sh docs`


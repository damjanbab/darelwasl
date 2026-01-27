# Skill: Docs Refresh (Generated)

## Intent
Use when generated docs drift or when registry/code changes are expected to update generated docs.

## Inputs
- Which generated files are expected to change (usually `docs/system.generated.md` and/or `docs/catalog.edn`).

## Procedure (commands)
1) Generate:
   - `scripts/generate-docs.sh`
2) Verify no drift remains:
   - `scripts/checks.sh docs`
3) Before merge:
   - `scripts/checks.sh all`

## Proofs
- `scripts/checks.sh docs` exits 0.
- CI “Checks” workflow passes.

## Common failure modes
- Generator relies on stale assumptions: treat generator output as authoritative and fix the generator or source registries.


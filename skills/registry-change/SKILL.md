# Skill: Registry Change

## Intent
Use when you modify any file under `registries/` (schema/actions/views/tooling/integrations/automations).

## Inputs
- Which registry file(s) changed and why.
- Whether the change is backward compatible (and any flags needed).

## Procedure (commands)
- Run targeted validation:
  - `scripts/checks.sh registries`
- If schema-related registries changed (usually `registries/schema.edn`):
  - `scripts/checks.sh schema`
- If action-related registries changed (usually `registries/actions.edn`):
  - `scripts/checks.sh actions`
- If view/UI registries changed (usually `registries/views.edn`):
  - `scripts/checks.sh app-smoke`
- If docs generation is expected to reflect the registry change:
  - `scripts/checks.sh docs`
- Before merge:
  - `scripts/checks.sh all`

## Proofs
- The corresponding `scripts/checks.sh ...` command(s) exit 0.
- Any generated files required by `scripts/checks.sh docs` are committed and CI passes.

## Common failure modes
- EDN parse errors: registries must be single-form EDN and valid syntax.
- Missing required keys: `scripts/checks.sh registries` prints which keys/paths are missing.
- Schema load failures: fix Datomic schema/type/cardinality issues until `scripts/checks.sh schema` passes.


# Policy: Registries Are the Contract

## Rule
When a capability surface changes, update the corresponding registry entry under `registries/`.

Examples:
- New/changed API/action: update `registries/actions.edn`
- New/changed UI view/flow: update `registries/views.edn`
- Schema attribute/entity change: update `registries/schema.edn`
- Tooling/checking behavior change: update `registries/tooling.edn`
- Integration behavior/auth change: update `registries/integrations.edn`
- Automation triggers/handlers: update `registries/automations.edn`

## Enforcement (current + future)
- Current: shape + EDN validity enforced via `scripts/checks.sh registries` (runs in CI).
- Future: add consistency checks between registries and code (e.g., referenced ids must exist).


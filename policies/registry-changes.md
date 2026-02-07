# Policy: Registry Changes

## Rule
Registries define the capability surface. Any change to `registries/` or fixtures must remain parseable and consistent with contracts.

## Change surface
- `registries/`
- `fixtures/`

## Required proofs
- `scripts/checks.sh registries`
- If schema or actions are affected, run: `scripts/checks.sh schema` and/or `scripts/checks.sh actions`
- If generated docs are affected, run: `scripts/checks.sh docs`

## Notes
Registries are intended to be cheap to review: prefer small diffs and stable ids.


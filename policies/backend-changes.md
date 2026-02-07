# Policy: Backend/API Changes

## Rule
Backend and API changes must preserve the capability contract and must pass action/contract verification.

## Typical change surface
- `src/darelwasl/http/` (routes + handlers)
- `src/darelwasl/actions.clj` (cap actions)
- `src/darelwasl/config.clj` (env/config surface)

## Required proofs
- `scripts/checks.sh actions`
- If registries or schema change, also run: `scripts/checks.sh schema`

## Registry requirements
- Follow `policies/registries-are-contract.md` (update registries alongside behavior).

## Promotion gate
- Follow `policies/preview-before-promote.md` and `policies/review-window-required.md`.


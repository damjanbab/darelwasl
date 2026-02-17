# Registry agent

## Purpose
Change `registries/*.edn` safely and keep capability contracts coherent.

## Hard limits (must obey)
- Allowed paths only:
  - `registries/`
  - `fixtures/`

## Safety rules
- Keep EDN valid and minimal.
- If action/view schemas change, run the corresponding checks.

## Proof expectation
- `scripts/checks.sh registries`


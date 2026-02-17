# Ops/governance agent

## Purpose
Improve scripts, policies, CI wiring, and docs while keeping the repo contract consistent.

## Hard limits (must obey)
- Allowed paths only:
  - `scripts/`
  - `agents/`
  - `policies/`
  - `.github/workflows/`
  - `docs/`
  - `AGENTS.md`

## Safety rules
- Prefer wiring-first: reuse existing scripts where possible.
- Add proofs for any new automation.

## Proof expectation
- `scripts/checks.sh governance`

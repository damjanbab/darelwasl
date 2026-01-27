# Skill: UI Change

## Intent
Use when you change anything user-facing (components, pages, styling, view wiring).

## Inputs
- Which view(s) or user flows are affected.
- Whether any backing action/schema changes are required.

## Procedure (commands)
1) If the UI surface changed materially, update `registries/views.edn` (and `registries/theme.edn` if styling tokens changed).
2) Implement the UI change (typically under `src/` and/or `public/`).
3) Run UI/full-stack smoke:
   - `scripts/checks.sh app-smoke`
4) Before merge:
   - `scripts/checks.sh all`

## Proofs
- `scripts/checks.sh app-smoke` exits 0.
- CI “Checks” workflow passes for the PR.

## Common failure modes
- Playwright issues in CI: ensure Playwright chromium deps install; reproduce locally with `scripts/checks.sh app-smoke`.
- Theme drift: prefer changing tokens in `registries/theme.edn` over ad-hoc styling.


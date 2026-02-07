# Policy: App UI Changes

## Rule
Any change that affects the app UI must be reviewable in a preview run and must pass UI/build verification.

## Typical change surface
- `src/darelwasl/ui/`
- `src/darelwasl/features/`
- `public/css/`

## Required proofs
- `npm run check`
- If the change is user-visible or touches routing/auth/session, run: `scripts/checks.sh app-smoke`

## Promotion gate
- Follow `policies/preview-before-promote.md` and `policies/review-window-required.md`.


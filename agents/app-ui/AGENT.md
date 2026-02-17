# App UI agent

## Purpose
Implement UI changes (Re-frame/Reagent/CSS) safely and keep the rest of the system stable.

## Hard limits (must obey)
- Allowed paths only:
  - `src/darelwasl/ui/`
  - `src/darelwasl/features/`
  - `public/css/`

## Safety rules
- Keep UX accessible and avoid regressions.
- Don’t change backend behavior or registries unless re-scoped.

## Proof expectation
- The system will run `npm run check`.


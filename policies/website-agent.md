# Website changes via Agent Control

## Rule
All public-facing website changes must go through **Agent Control** and produce a **sandbox preview link** before promotion.

## Allowed change surface
Website changes are limited to:
- `src/darelwasl/site/`
- `public/`

Any change outside these paths is rejected by automation.

## Proofs
Before a preview is published (and before promotion), the system must:
- Run the website agent contract proofs from `agents/website/AGENT.json`.
- Start a preview and run preview verification (screenshots / smoke where applicable).

## Promotion gate
Promotion to live requires:
- User confirmation via **Accept + go live**.
- The preview worktree is clean and the change is committed (so promotion is a fast-forward merge).


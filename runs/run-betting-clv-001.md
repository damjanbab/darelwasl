# Run run-betting-clv-001

Goal: ship a minimal CLV-first betting trainer that uses no-vig reference probabilities, a configured reference basket, Rezultati bookmaker odds, and scheduled close snapshots (kickoff - 10 min). No manual odds or stake entry.

Constraints: no guaranteed-profit claims; CLV feedback only; low-frequency scheduler only; keep the data model explicit and minimal; avoid high-frequency polling.

## Tasks
- Task ID: product-spec-betting-clv-v2
  - Status: done
  - Objective: Update `docs/system.md` for no-vig true %, reference basket + execution book, scheduled close capture, and no manual odds/stake entry.
  - Scope: Product + design spec bullets, CLV formula, acceptance criteria, config notes.
  - Out of Scope: Implementation.
  - Capabilities Touched: docs/spec.
  - Dependencies: none.
  - Deliverables: Updated betting CLV spec in `docs/system.md`.
  - Proof Plan: Consistency review.

- Task ID: schema-betting-v2
  - Status: done
  - Objective: Remove stake and align implied probability semantics to no-vig reference.
  - Scope: `registries/schema.edn`, fixtures, and related registry docs.
  - Out of Scope: UI.
  - Capabilities Touched: :cap/schema/betting-bet, :cap/schema/betting-quote.
  - Dependencies: product-spec-betting-clv-v2.
  - Deliverables: Updated schema + fixtures.
  - Proof Plan: `scripts/checks.sh registries`.

- Task ID: backend-betting-v2
  - Status: in-progress
  - Objective: Compute no-vig probabilities, reference medians, best/execution odds, and use them for bet logging + CLV.
  - Scope: `src/darelwasl/betting.clj`, actions/routes wiring, Rezultati adapter normalization.
  - Out of Scope: Full Supersport API reverse engineering.
  - Capabilities Touched: :cap/action/betting-odds, :cap/action/betting-bet-log, :cap/action/betting-close.
  - Dependencies: schema-betting-v2.
  - Deliverables: New odds summary payloads + updated CLV computation.
  - Proof Plan: `scripts/checks.sh registries` and app smoke.

- Task ID: scheduler-betting-close
  - Status: done
  - Objective: Add a low-frequency scheduler to capture close snapshots automatically.
  - Scope: new worker + startup wiring; close offset/horizon config.
  - Out of Scope: High-frequency polling.
  - Capabilities Touched: :cap/action/betting-close.
  - Dependencies: backend-betting-v2.
  - Deliverables: Scheduler in `src/darelwasl/workers/betting_scheduler.clj` wired in `main.clj`.
  - Proof Plan: Local smoke run + logs.

- Task ID: ui-betting-v2
  - Status: in-progress
  - Objective: Remove manual odds/stake inputs and surface true % + best/execution odds + CLV in pp.
  - Scope: `src/darelwasl/features/betting.cljs`, state updates, and CSS adjustments.
  - Out of Scope: Design system rework.
  - Capabilities Touched: :cap/view/betting.
  - Dependencies: backend-betting-v2.
  - Deliverables: Updated betting UI + styles.
  - Proof Plan: App smoke.

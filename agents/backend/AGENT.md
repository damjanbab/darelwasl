# Backend/API agent

## Purpose
Implement backend and API changes safely: routes, actions, and supporting backend code.

## Hard limits (must obey)
- Allowed paths only:
  - `src/darelwasl/http/`
  - `src/darelwasl/actions.clj`
  - `src/darelwasl/entity.clj`
  - `src/darelwasl/events.clj`
  - `src/darelwasl/config.clj`
- Do not modify other folders unless explicitly re-scoped by a different agent contract.

## Safety rules
- If you change API surface (routes/request/response), ensure it matches registry contracts if applicable.
- Keep changes minimal and keep existing behavior unless requested.
- Do not introduce new secrets or hard-coded tokens.

## Proof expectation
- The system will refuse disallowed path changes.
- Then it will run the proof commands from `agents/backend/AGENT.json`.


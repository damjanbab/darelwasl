# Telegram ops agent (dev/prod control + promotion wiring)

## Purpose
Make Telegram dev/prod bot operations and promotion workflow **explicit, safe, and discoverable**.

## Allowed paths (hard limit)
- `scripts/`
- `docs/`
- `agents/`
- `policies/`

## Safety rules
- Keep production operations **explicitly gated** (e.g. `DEPLOY_APPROVED=1`).
- Default all Telegram proofs/ops to the **dev bot**.
- If adding scripts that touch Telegram, include a bot identity check (`getMe`) and make bypass opt-in.
- Do not modify application runtime behavior (no changes under `src/`) unless explicitly approved outside this agent.

## Proof expectation
The agent must keep CI green and pass:
- `scripts/checks.sh governance`
- `scripts/checks.sh docs`
- `scripts/checks.sh actions`


# Agents

Agents are **role-specific contracts** that define:
- **Capabilities**: what the agent is supposed to do.
- **Limits**: what files/areas it may touch.
- **Proofs**: what must pass before a preview is published and/or promoted.

The important rule: **the contract is enforced by automation**, not by trust.

Agent contracts live in subfolders (one per agent type), e.g.:
- `agents/website/` – public-facing website changes (templates, CSS, assets).
- `agents/telegram-ops/` – Telegram dev/prod bot ops + promotion wiring (scripts + docs).
- `agents/lab/` – code.haloeddepth.com Lab UI + canary-first upgrades (webterm UI).

# Telegram bots (dev + prod) — ops + promotion

This repo supports **two Telegram bot modes**:

- **Dev bot (proofing)**: safe-by-default, used for verification and manual testing.
- **Prod bot (live)**: user-facing production bot.

The goal is to make it hard to accidentally touch production while still making “promote to prod” explicit and repeatable.

## Source of truth (where configuration comes from)

- App config is loaded in `src/darelwasl/config.clj`.
- Telegram config uses a profile:
  - `TELEGRAM_PROFILE=dev` → uses `TELEGRAM_DEV_*` env vars
  - `TELEGRAM_PROFILE=prod` (or unset) → uses `TELEGRAM_*` env vars

## Recommended flow (dev first, then promote)

1) **Run dev bot proof** (no webhook; polling only)
2) Test the Telegram flow manually against the dev bot
3) Mark preview accepted
4) **Promote** (explicit + gated)

Shortcut wrapper:

```bash
scripts/tgctl.sh dev preview-start <run-id>
```

### 1) Start a Telegram dev preview (polling)

This uses the existing preview runner and enforces single-flight (only one Telegram preview run at a time):

```bash
scripts/preview start <run-id> --mode telegram
```

Notes:
- The preview runner forces:
  - `TELEGRAM_WEBHOOK_ENABLED=false`
  - `TELEGRAM_POLLING_ENABLED=true`
- It pulls dev bot env vars from `/etc/darelwasl/app.env` (`TELEGRAM_DEV_*`) and maps them into the preview runtime.
- Use `scripts/preview stop <run-id>` to stop and release the telegram slot.

### 2) Accept the preview (no auto-deploy)

```bash
scripts/preview respond <run-id> accept
```

Acceptance **never** auto-promotes; promotion is a separate gated step.

### 3) Promote to production (explicit + gated)

```bash
DEPLOY_APPROVED=1 scripts/promote-live.sh
```

This builds, validates seed in a temp DB, restarts the systemd service, then waits for `/health`.

You can additionally require that a preview is accepted:

```bash
DEPLOY_APPROVED=1 scripts/tgctl.sh prod promote --from-preview <run-id>
```

## Optional: run dev bot via webhook (tunnel)

For local/manual webhook testing you can run:

```bash
scripts/tg-spinup.sh
```

Defaults:
- Targets **dev** profile (`TELEGRAM_PROFILE=dev`) and reads `.secrets/telegram_dev_*` files by default.
- If `TELEGRAM_EXPECTED_BOT_USERNAME` is set, it refuses to proceed if the bot username does not match (unless `SKIP_TELEGRAM_BOT_IDENTITY_CHECK=1`).

Files (dev defaults):
- `.secrets/telegram_dev_bot_token`
- `.secrets/telegram_dev_webhook_secret`
- `.secrets/telegram_dev_webhook_base_url` (written by the script)

## Safety controls

### Bot identity check

Scripts that can message Telegram should verify bot identity first:

- Set `TELEGRAM_EXPECTED_BOT_USERNAME` (e.g. `mimi`) to enforce the dev bot.
- If `TELEGRAM_EXPECTED_BOT_USERNAME` is unset, scripts will still call `getMe` and print a warning with the detected bot username.
- Set `SKIP_TELEGRAM_BOT_IDENTITY_CHECK=1` only for exceptional offline/testing cases.

### Single-flight Telegram previews

Only one `scripts/preview start --mode telegram` may run at once.
The lock is stored at:

- `target/previews/_telegram_active.json`

## Troubleshooting

- Preview status: `scripts/preview status <run-id>`
- Stop preview: `scripts/preview stop <run-id>`
- Promote logs: `cat .cpcache/promote-seed.log`
- Telegram webhook tunnel logs (spinup): `.cpcache/tg/tunnel.log`

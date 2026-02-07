# Policy: Telegram Proofs Must Use Dev Bot

## Rule
All Telegram proof work (manual ops scripts and previews) must target the dev bot, not the production bot.

## Why
Telegram actions can be irreversible (messages, webhooks, commands). Proofing against production is high-risk.

## Enforcement
- `scripts/tg-spinup.sh` and `scripts/tg-watch-webhook.sh` default to dev secret files under `.secrets/telegram_dev_*` and refuse to proceed if the bot identity does not match `TELEGRAM_EXPECTED_BOT_USERNAME` (default `mimi`).
- `scripts/preview start <id> --mode telegram` uses `TELEGRAM_DEV_*` env vars (from `/etc/darelwasl/app.env`) and also refuses to start if the bot identity is not `TELEGRAM_EXPECTED_BOT_USERNAME` (default `mimi`).
- To intentionally bypass identity checks (e.g. offline), set `SKIP_TELEGRAM_BOT_IDENTITY_CHECK=1` (not recommended).

This folder is ignored by git (see `.gitignore`).

Create these files for local Telegram testing:

- `telegram_bot_token` (required): paste your bot token as the only contents.
- `telegram_webhook_secret` (optional): leave absent to auto-generate on first run.

Run `scripts/tg-spinup.sh` to start the backend, start a local tunnel, set the webhook, and print a `/start <token>` command for Telegram.

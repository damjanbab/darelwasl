# Policy: Telegram Preview Is Single-Flight

## Rule
Only one Telegram preview run may be active at a time.

## Enforcement
- `scripts/preview start <id> --mode telegram` refuses to start if another telegram preview is running.
- `scripts/preview stop <id>` releases the telegram preview slot.


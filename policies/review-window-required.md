# Policy: Review Window Required

## Rule
A preview run must have a review window (default 6 hours) measured from the last time the preview link was updated. The user must either accept or request changes before the window expires.

## Enforcement
- `scripts/preview start ... --review-window-hours 6` sets `last_preview_updated_at` and `expires_at` in `target/previews/<id>/preview.json`.
- Any subsequent `scripts/preview start <id> ...` refreshes the preview (same id) and resets the window.
- `scripts/preview respond <id> accept` is rejected after expiry.
- The service GC loop (`darelwasl.agent-control.gc`) trashes expired runs and stops previews.

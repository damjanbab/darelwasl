# Skill: Preview + Promote (Public Site / System App / Telegram)

## Intent
Use when you need a sandboxed preview link, automatic verification, and a human approval gate before going live.

## Inputs
- Run id (kebab-case), e.g. `change-2026-01-27-1`
- Mode: `site`, `app`, `both`, or `telegram`

## Procedure (commands)
1) Start preview (includes auto verification by default):
   - `scripts/preview start <id> --mode <site|app|both|telegram>`
2) Review the printed preview URL (and use `scripts/preview status <id>` to see the expiry).
3) If satisfied (promotes to live):
   - `scripts/preview respond <id> accept`
4) If not satisfied (records notes and extends the window):
   - `scripts/preview respond <id> revise --message "<what to change>"`
5) Cleanup expired previews (optional housekeeping):
   - `scripts/preview gc`

## Proofs
- Preview created: `scripts/preview status <id>` prints `status=pending_user_review` and URLs.
- After promote: production service is healthy (`/health`) and the preview run is stopped.

## Common failure modes
- DNS not set up for `*.preview.haloeddepth.com`: preview URLs won’t resolve externally.
- Worktree not committed: promotion is blocked until changes are committed.
- Telegram preview already running: stop the previous telegram preview first.


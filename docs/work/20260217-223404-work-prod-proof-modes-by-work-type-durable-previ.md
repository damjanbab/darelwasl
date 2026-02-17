work/pr_url: https://github.com/damjanbab/darelwasl/pull/77
work/id: 20260217-223404-work-prod-proof-modes-by-work-type-durable-previ
work/type: governance
work/status: open
work/playbook: work/isolate-pr
work/summary: work-prod: proof modes by work type + durable preview processes
work/branch: work/20260217-223404-work-prod-proof-modes-by-work-type-durable-previ
work/worktree: target/worktrees/20260217-223404-work-prod-proof-modes-by-work-type-durable-previ
work/base: main
work/prereqs: []
work/lock: 
work/created_at: 2026-02-17T22:34:04Z
work/updated_at: 2026-02-17T22:39:11Z

# Notes

## Work brief

- Type: `governance`
- Goal:
  - Make proof generation work-type dependent (governance shouldn’t require a haloeddepth preview link).
  - Make `scripts/preview start` start durable preview processes (survive non-interactive callers).
- Scope:
  - `scripts/work-prod.sh`: add a “governance proof” mode that publishes `work-proof-<id>.html` + `work-links-<id>.txt` without starting a preview.
  - `scripts/work-prod.sh`: default proof mode based on `work/type` (overrideable later via an explicit header).
  - `scripts/preview`: replace `nohup` with a `setsid`-based launch so the preview service persists.
- Non-goals:
  - Full per-playbook artifact proofs (e.g. PDFs) beyond adding the minimal mechanism for proof modes.
  - Changing Lab UI behavior; it should continue to find `work-proof-*` and `work-links-*`.

## Proof
- [ ] `scripts/checks.sh governance`
- [ ] Manual: start a preview and confirm it stays up:
  - `scripts/preview start zz-proof-preview --ref HEAD --mode both --public-host https://haloeddepth.com --skip-verify --review-window-hours 1`
  - `curl -fsS \"https://haloeddepth.com/_preview/zz-proof-preview/app/?t=$(jq -r .token target/previews/zz-proof-preview/preview.json)\" >/dev/null`
  - Re-run the `curl` again after 10s; it should still succeed.
  - Cleanup: `scripts/preview stop zz-proof-preview`

## Acceptance criteria

- `scripts/work-prod.sh run <governance-work-id> ...` produces proof artifacts without calling `scripts/preview start`.
- `scripts/preview start <id> ...` leaves a running process that serves both ports until stopped.

# Policy: Preview Before Promote

## Rule
Any change that affects the public website or the system app must be reviewed in an isolated preview run before it is promoted to live.

## Workflow (enforced by tooling)
- Create a preview run: `scripts/preview start <id> --mode site|app|both`
- Wait for automatic verification to finish and use the preview link for manual review.
- Promote only after explicit acceptance (within the review window): `scripts/preview respond <id> accept`

## Notes
- Preview runs are isolated (separate ports, separate Datomic storage dir, separate git worktree).
- Promotion is commit-based: the preview worktree must be clean and committed.


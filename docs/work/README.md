# Work log (repo-local)

This repo stores work items under `docs/work/` as plain Markdown so they are:
- easy to commit and review in PRs
- easy to search (`rg`, GitHub search)
- simple to automate (`scripts/work.sh`)

## Commands

- Create a work item (also creates `work/<id>` + worktree): `scripts/work.sh new --type <change|governance|investigate|question|refactor|delete> --playbook <id> --summary "<text>" [--prereq <work-id>]... [--lock <name>]`
- List work items: `scripts/work.sh list`
  - Only open: `scripts/work.sh list --open`
  - Only closed: `scripts/work.sh list --closed`
  - Filter by type: `scripts/work.sh list --type governance`
  - Filter by playbook: `scripts/work.sh list --playbook work/isolate-pr`
  - Limit output: `scripts/work.sh list --limit 20`
- Show one item: `scripts/work.sh show <id>`
- Search work: `scripts/work.sh search <text>`

## Agent default: spec-only (work/spec authoring)

By default, any agent working in this repo is expected to be **spec-only**:
- Create/update the work item + spec in `docs/work/<id>.md`.
- Declare prerequisites/locks up front (`work/prereqs`, `work/lock`).
- Stop after the spec is produced (no direct implementation, execution, or proofs).

Implementation is gated by the work production pipeline approval steps:
- Approve spec: `scripts/work-prod.sh approve-spec <work-id>`
- Execute + publish proof: `scripts/work-prod.sh run <work-id> --lab stable`
- Approve proof (creates PR / attempts merge): `scripts/work-prod.sh approve-proof <work-id>`

## Catalog-backed queries (fast locate)

Work items are also indexed into `docs/catalog.edn` so you can use the standard query tool:

- Find by summary/id: `scripts/query.sh --kind work-item TERM`
- List open work items (best-effort): `scripts/query.sh --kind work-item open`

## Isolation workflow (recommended)

All changes should be done on an isolated branch + worktree:
- Start worktree (idempotent): `scripts/work.sh start <id>`
- Print worktree path: `scripts/work.sh path <id>`
- Run proofs in worktree: `scripts/work.sh verify <id> -- scripts/checks.sh governance`
- Commit in worktree: `scripts/work.sh commit <id> -m "…" `
- Print PR commands: `scripts/work.sh pr <id>`

`start` does not require a clean base checkout (it does not mutate the base tree).

## File format

Work items are stored as `docs/work/<id>.md` with machine-parseable header lines:

```
work/id: <id>
work/type: <type>
work/status: open|closed
work/playbook: <playbook-id-or-empty>
work/summary: <one line>
work/branch: <git branch>
work/worktree: <path>
work/base: <base branch>
work/prereqs: <json array of prerequisite work ids>
work/lock: <optional lock name>
work/created_at: <utc iso>
work/updated_at: <utc iso>
```

Everything after that is freeform notes.

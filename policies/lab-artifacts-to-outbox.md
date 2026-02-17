# Policy: Lab artifacts to outbox (shared Library)

## Goal

When working in the Lab (`code.haloeddepth.com/lab`), artifacts that are meant to be reviewed/downloaded (especially PDFs) should land in the Lab **Library** automatically, scoped to a **work id**, so you do not have to manually copy files around.

## Rules

- **Library is the only download surface.** Do not add “download from inbox” behavior.
- When generating PDFs in a Lab session (or inside a `work/<id>` git worktree), enable auto-publish:
  - `DW_LAB_AUTO_OUTBOX=1`
- Auto-publish writes into the shared Lab Library:
  - `DW_LAB_LIBRARY_DIR` (default: `<DW_LAB_DIR>/library`)
  - Work-scoped publishing:
    - Prefer: `DW_LAB_WORK_ID=<work-id>` (or `DW_WORK_ID`)
    - Fallback: infer from git branch `work/<work-id>`
  - Library directory becomes:
    - `.../work/<work-id>/` (preferred)
    - or `.../unscoped/` (fallback when no work id)

## Supported generators

- `node scripts/documents-pdf.js ...`
- `node scripts/account-statement-pdf.js ...`

If you add a new PDF generator, it must follow the same convention.

## Proof

- `node --check scripts/documents-pdf.js`
- `node --check scripts/account-statement-pdf.js`

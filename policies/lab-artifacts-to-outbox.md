# Policy: Lab artifacts to outbox

## Goal

When working in the Lab (`code.haloeddepth.com/lab`), artifacts that are meant to be downloaded (especially PDFs) should land in the Lab **outbox** automatically, so you do not have to manually copy files around.

## Rules

- **Outbox is the only download surface.** Do not add “download from inbox” behavior.
- When generating PDFs in a Lab session, enable auto-publish:
  - `DW_LAB_AUTO_OUTBOX=1`
- Auto-publish uses the standard Lab paths:
  - `DW_LAB_DIR` (default: `tmp/lab`)
  - `DW_TMUX_PREFIX` + Lab session number
    - Prefer: `DW_LAB_SESSION` (explicit target for a single run)
    - Else: `DW_LAB_SESSION_STABLE` (default: `7`)
  - Outbox directory becomes: `<DW_LAB_DIR>/<DW_TMUX_PREFIX><N>/outbox/`

## Supported generators

- `node scripts/documents-pdf.js ...`
- `node scripts/account-statement-pdf.js ...`

If you add a new PDF generator, it must follow the same convention.

## Proof

- `node --check scripts/documents-pdf.js`
- `node --check scripts/account-statement-pdf.js`

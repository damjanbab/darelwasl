# Portal work — preview verification (haloeddepth) + service control

Portal (internal app) changes must be verified on **haloeddepth preview links**, delivered via the Lab outbox, before approval/merge.

Do **not** verify portal work against `darelwasl.com` (or any non-preview host). Portal verification is only considered valid on `haloeddepth.com` preview URLs.

This workflow is designed to be run via the work production pipeline (`scripts/work-prod.sh`). Background: `docs/ops/work-production.md`.

## Verification steps (required)

1) Generate a proof + preview links into the Lab outbox:

```bash
scripts/work-prod.sh preview <work-id> --lab stable
```

2) In the Lab UI (stable session), open the outbox artifact:
   - `work-proof-<work-id>.html` (preferred), or
   - `work-links-<work-id>.txt` (machine-readable).

3) Verify the portal behavior using the **App** link shown in the proof.
   - Confirm the host is `https://haloeddepth.com/...` (preview).
   - Perform the minimal set of user flows impacted by the change.

4) Approve proof (creates PR and attempts merge, if gated approvals are present):
   - Use the approve link in `work-proof-<work-id>.html`, or run:

```bash
scripts/work-prod.sh approve-proof <work-id>
```

## Service control (stop previews)

Previews are real services; stop them when review is complete to free ports and reduce background load.

1) Get the preview id from `work-links-<work-id>.txt`:
   - Look for `preview_id=...`

2) Stop the preview:

```bash
scripts/preview stop <preview_id>
```

## Troubleshooting (preview not loading)

- Check preview status: `scripts/preview status <preview_id>`
- Re-run verification for the preview: `scripts/preview verify <preview_id> --mode app --verify full`


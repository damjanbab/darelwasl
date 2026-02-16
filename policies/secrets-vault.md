# Policy: Secrets vault (Datomic ciphertext + external master key)

## Goal

Store operational secrets (GitHub tokens, Telegram tokens, etc.) in a single, queryable system **without** committing secrets to git or repeatedly re-entering them.

## Design (source of truth)

- **Ciphertext in Datomic**, **plaintext never**.
- **Envelope encryption**: AES-256-GCM in-app using a single **master key outside Datomic**.
- If the Datomic database is leaked/backed up, it should contain only ciphertext.

## Rules

- Do not store secrets in:
  - git history
  - generated docs
  - logs
  - chat transcripts
- The master key file must be:
  - readable only by the service user (`0600`)
  - outside the repo (default: `/etc/darelwasl/secrets.key`)
- Rotate secrets by writing a new version; the active version is referenced by `:secret/active-version`.

## Operational interface

- Create master key (once per environment): `scripts/secrets.sh init-master-key`
- Store secret: `scripts/secrets.sh set <key>`
- Materialize to file (0600): `scripts/secrets.sh materialize <key> <path>`
- List keys (metadata only): `scripts/secrets.sh list`

## GitHub convention

- Secret key: `github/token`
- The app uses `:github.token` if set; otherwise it will try to read `github/token` at runtime.

## Proof

- `scripts/checks.sh governance`
- `scripts/checks.sh schema`

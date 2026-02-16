# Secrets vault — Datomic-backed secrets (ciphertext) + master key file

This repo stores secrets in **Datomic as ciphertext**, encrypted with a single **master key file** that lives **outside git**.

Policy: `policies/secrets-vault.md`

## Master key (one-time per environment)

Default location (service-friendly):

- `/etc/darelwasl/secrets.key` (0600, owned by the service user)

Create it:

```bash
scripts/secrets.sh init-master-key
```

Override location (optional):

```bash
DW_SECRETS_MASTER_KEY_FILE=/path/to/secrets.key scripts/secrets.sh init-master-key --path /path/to/secrets.key
```

## Store a secret

```bash
scripts/secrets.sh set github/token
```

Store from a file (recommended for handoffs; avoids pasting secrets into terminals/chat):

```bash
cat path/to/pat.txt | scripts/secrets.sh set github/token
```

Store from the File Library UI (one-off handoff):

- Upload a 1-line `pat.txt` into the File Library.
- Provide the `file/id` (UUID) to the operator/agent.
- Import into the vault and then delete the plaintext file asset.

List stored keys (metadata only):

```bash
scripts/secrets.sh list
```

## Use secrets at runtime (app)

The app reads:

- `DW_SECRETS_MASTER_KEY_FILE` (default `/etc/darelwasl/secrets.key`)
- Datomic secret entities (`:secret/key`, `:secret/active-version`)

GitHub:
- If `GITHUB_TOKEN` env is not set, the app will try secret key `github/token`.

## Git push over HTTPS (no prompts)

If you prefer HTTPS remotes with a PAT stored as `github/token`, configure git to use the credential helper:

```bash
git remote set-url origin https://github.com/damjanbab/darelwasl.git
git config credential.helper "!/opt/darelwasl/scripts/git-credential-dw.sh"
```

Then `git push` should work without interactive prompts (as long as the vault has `github/token` and the master key file is present).

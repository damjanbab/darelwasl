work/id: 20260216-162647-fix-secrets-vault-set-lab-inbox-token-ingest-pr-
work/type: governance
work/status: open
work/playbook: secrets/vault
work/summary: Fix secrets vault set + lab inbox token ingest + PR create automation
work/branch: work/20260216-162647-fix-secrets-vault-set-lab-inbox-token-ingest-pr-
work/worktree: /opt/darelwasl/target/worktrees/20260216-162647-fix-secrets-vault-set-lab-inbox-token-ingest-pr-
work/base: main
work/created_at: 2026-02-16T16:26:47Z
work/updated_at: 2026-02-16T16:26:47Z

# Notes

## Proof
- [x] `env DATOMIC_STORAGE_DIR=/opt/darelwasl/data/datomic bash -lc 'printf "hello2" | scripts/secrets.sh set test/hello2 --description "test"'`
- [x] `env DATOMIC_STORAGE_DIR=/opt/darelwasl/data/datomic bash -lc 'scripts/secrets.sh get test/hello2 --show'`
- [x] `env DATOMIC_STORAGE_DIR=/opt/darelwasl/data/datomic bash -lc 'printf "fromfile" > /tmp/dw-secret && scripts/secrets.sh set test/fromfile --file /tmp/dw-secret && rm -f /tmp/dw-secret'`
- [ ] `scripts/secrets.sh set github/token --file tmp/lab/codex7/inbox/github_pat.txt`
- [ ] `scripts/work.sh pr-create 20260216-162647-fix-secrets-vault-set-lab-inbox-token-ingest-pr-`

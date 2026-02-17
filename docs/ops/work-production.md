# Work production (approved work → proof → PR → merge)

This repo supports a work-centric automation pipeline:

- The unit is a **work id** (see `docs/work/<id>.md`).
- The pipeline orchestrator is `scripts/work-prod.sh`.
- Proof is published via `scripts/preview` and delivered to the Lab outbox (`scripts/lab.sh put-outbox`).

## Prereqs
- GitHub token available as `GITHUB_TOKEN` (or stored in the secrets vault as `github/token`).
- `codex` CLI logged in (the agents run via `codex exec`).

If PR/merge automation is failing due to token wiring, you can install a token
system-wide (for the `darelwasl.service` environment) from the Lab inbox:

- Upload `token.txt` (1 line PAT) into the Lab inbox
- Run: `scripts/github-token.sh install-from-lab --lab stable --name token.txt`

## Model pinning
Default agent model is `gpt-5.2-high`.

Override (only when needed):
- `DARELWASL_WORK_MODEL=<model>`

## Merge gating
Merging into `main` is gated:
- `DEPLOY_APPROVED=1`

## Core commands
- Create + start: `scripts/work-prod.sh new ...` then `scripts/work.sh start <id>`
- Init: `scripts/work-prod.sh init <id> --agent agents/<agent>/AGENT.json --request "<request>"`
- Preflight only: `scripts/work-prod.sh preflight <id> --agent agents/<agent>/AGENT.json`
- Approve spec: `scripts/work-prod.sh approve-spec <id>`
- Execute: `scripts/work-prod.sh execute <id>`
- Preview + outbox proof: `scripts/work-prod.sh preview <id> --lab stable`
- Approve proof: open `work-proof-<id>.html` (or `work-links-<id>.txt`) in Lab outbox and click the approve link (or run `scripts/work-prod.sh approve-proof <id>`).

## Merge agent
To merge queued work PRs (head refs `work/<id>`):

`DEPLOY_APPROVED=1 scripts/work-prod.sh merge-agent`

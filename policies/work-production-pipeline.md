# Policy: Work production pipeline (approved work → proof → PR → merge)

## Rule
- Treat a **work id** as the unit of execution and review.
- Before executing work, the system must verify prerequisites exist on `main`:
  - A playbook exists for the work (`scripts/playbook.sh show <id>` must succeed).
  - A matching agent contract exists (allowed paths + proofs).
  - If the work spec declares `work/prereqs`, every prerequisite work id must exist on `origin/main` before execution starts.
- If the work spec declares `work/lock`, the system must not execute multiple works holding the same lock concurrently.
- The system must **reconsolidate** with `origin/main` before execution and before PR creation.
- The system must produce **user-visible proof** in the Lab (outbox) and require user approval before PR creation/merge.
- Work execution and conflict resolution must use the pinned model `gpt-5.2-high` (configurable only by explicit environment override).

## Enforcement (current)
- Orchestrator: `scripts/work-prod.sh`
- Agent execution contract: `scripts/agent-runner` + `agents/*/AGENT.json`
- Proof delivery: `scripts/work-prod.sh preview` writes `target/work-prod/<id>/proof.html` and copies to Lab outbox via `scripts/lab.sh put-outbox`.
- Approval endpoint: `/_preview/<id>/approve?t=<token>` (runs approve step server-side).
- PR merge automation: `scripts/pr-merge.sh` (requires `DEPLOY_APPROVED=1`).

## Proof
- `scripts/checks.sh governance`

## Notes
- GitHub operations require `GITHUB_TOKEN` (env or secrets vault key `github/token`).

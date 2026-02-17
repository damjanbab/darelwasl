work/pr_url: https://github.com/damjanbab/darelwasl/pull/71
work/id: 20260217-180603-playbook-portal-work-preview-proof
work/type: governance
work/status: open
work/playbook: work/production-pipeline
work/summary: Playbook: portal work (preview + proof)
work/branch: work/20260217-180603-playbook-portal-work-preview-proof
work/worktree: /opt/darelwasl/target/worktrees/20260217-180603-playbook-portal-work-preview-proof
work/base: main
work/created_at: 2026-02-17T18:06:03Z
work/updated_at: 2026-02-17T18:16:03Z

# Notes

## Proof
- [ ] <fill in exact commands, ideally from the playbook>
## Approved spec

- approved_at: `2026-02-17T18:06:15Z`
- agent: `agents/ops-governance/AGENT.json`
- model: `gpt-5.2`

### Request

```
Create a new playbook for portal work (internal app/portal changes). Update AGENTS.md to add: 'Playbook: portal/work — portal (app) work via preview + Lab proof'. It should instruct using the work production pipeline (scripts/work-prod.sh new/init/approve-spec/execute/preview/approve-proof) and require verification via haloeddepth.com preview links delivered in the Lab outbox. Include guidance on choosing the correct agent contract: app-ui for UI-only (proof: npm run check), backend for API-only (proof: scripts/checks.sh actions), and if both are needed, split into two works. Add docs/ops/portal-work.md that explains the verification steps and service control (scripts/preview stop <preview_id>) and states that portal verification must happen on haloeddepth previews, not darelwasl.com.
```


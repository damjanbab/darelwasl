work/id: 20260217-093956-lab-shared-library-across-sessions-business-doc-
work/type: governance
work/status: open
work/playbook: lab/ui
work/summary: Lab shared library across sessions + business-doc review
work/branch: work/20260217-093956-lab-shared-library-across-sessions-business-doc-
work/worktree: /opt/darelwasl/target/worktrees/20260217-093956-lab-shared-library-across-sessions-business-doc-
work/base: main
work/created_at: 2026-02-17T09:39:56Z
work/updated_at: 2026-02-17T09:54:26Z

# Notes

## Proof
- [ ] `scripts/checks.sh governance`
- [ ] `scripts/checks.sh docs`
- [ ] `scripts/checks.sh query`
- [ ] `clojure -M -m darelwasl.webterm.server --check`
- [ ] `sudo scripts/webterm-ui.sh deploy-canary`
- [ ] Manual: Lab UI → Work → Select → Review latest

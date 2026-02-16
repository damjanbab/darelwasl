work/id: 20260216-190824-lab-canary-first-webterm-ui-deploy-proctor-link
work/type: governance
work/status: open
work/playbook: work/isolate-pr
work/summary: Lab: canary-first webterm UI deploy + proctor link
work/branch: work/20260216-190824-lab-canary-first-webterm-ui-deploy-proctor-link
work/worktree: /opt/darelwasl/target/worktrees/20260216-190824-lab-canary-first-webterm-ui-deploy-proctor-link
work/base: bootstrap/20260216-lab-canary-ui
work/created_at: 2026-02-16T19:08:24Z
work/updated_at: 2026-02-16T19:13:40Z

# Notes

Canary proctor URL:
- https://code.haloeddepth.com/canary/lab?session=8

Stable URL (reference):
- https://code.haloeddepth.com/lab?session=7

## Proof
- [x] `python3 -m py_compile ops/webterm-ui/server.py`
- [x] `scripts/checks.sh governance`
- [x] `sudo scripts/webterm-ui.sh deploy-canary`

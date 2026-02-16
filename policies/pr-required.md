# Policy: Changes land via PR (no direct pushes to main)

## Goal

Ensure all changes land in `main` via a Pull Request so work is:

- reviewable
- isolated
- parallelizable (multiple work streams without stepping on each other)

## Rules

- Do not push directly to `origin/main`.
- All work happens on a branch created by `scripts/work.sh start <id>` (or equivalent).
- After verification, publish by pushing the branch and opening a PR.

## Local enforcement (this repo)

This repo provides a git `pre-push` hook that blocks pushes to `main` by default.

Install hooks:

```bash
scripts/hooks.sh install
```

Emergency bypass (rare):

```bash
ALLOW_PUSH_MAIN=1 git push origin main
```

## Proof

- `scripts/checks.sh governance`

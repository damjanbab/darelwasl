# Skills

Skills are reusable workflows for operating this repo safely.

They reference:
- capabilities (usually from `registries/*.edn`), and
- policies (from `policies/`), and
- concrete commands (usually from `scripts/`).

## Layout

Create one folder per skill:
- `skills/<skill-name>/SKILL.md` (required)
- `skills/<skill-name>/references/` (optional)
- `skills/<skill-name>/scripts/` (optional)

## Minimum SKILL.md template

Each skill should define:
- **Intent**: when to use it
- **Inputs**: required info from the user/spec
- **Procedure**: exact commands to run
- **Proofs**: what artifacts or checks demonstrate success
- **Failure modes**: common breakages + recovery steps


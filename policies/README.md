# Policies

Policies are constraints and verification requirements that should be enforceable.

If a policy matters, wire it into `scripts/checks.sh` and/or CI so the repo is controlled by automation,
not by stale documentation.

## Common policy shapes
- **Path-based**: “If `registries/` changes, run schema + registries checks”
- **Change-type-based**: “UI-visible changes require `app-smoke`”
- **Security**: “No secrets committed; require scanning”
- **Process**: “PR required; checks must pass”


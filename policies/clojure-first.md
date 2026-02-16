# Policy: Clojure-first (when possible)

## Goal

Prefer **Clojure** for new server-side code and operational tooling in this repo, to reduce stack sprawl and make agent workflows consistent.

## Rules

- If an implementation can reasonably be done in Clojure (Ring/Jetty, scripts, batch jobs), do it in Clojure.
- Only introduce a new language/runtime when there is a clear, documented reason (vendor SDK only exists there, performance constraints, or an existing subsystem is already committed to that runtime).
- When replacing legacy tooling, keep the public contract stable (URLs, env vars, systemd service names) unless explicitly changing it.

## Proof

- `scripts/checks.sh governance`


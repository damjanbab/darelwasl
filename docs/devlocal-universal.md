# Dev-Local Universal Architecture Spec

## Problem
Dev-local Datomic only allows one process per storage directory. Today, the app,
site, bot, and terminal sessions sometimes start multiple processes against the
same store, which causes lock conflicts and unstable sessions.

## Goals
- One Datomic owner per workspace (single process).
- Main workspace behaves like prod: all traffic flows through one app-owned
  Datomic connection.
- Terminal sessions are isolated but start from the same main state at session
  creation time.
- Session bots are supported without touching main Datomic.
- No manual babysitting; the system should enforce the rules.

## Constraints
- Dev-local is single-process per storage dir.
- Terminal service never connects to main Datomic directly.
- Sessions are per-repo clones and can auto-start their own app.

## Canonical Model
**Rule:** One Datomic process per workspace. Everything else is a client.

Workspace types:
- `main`: the local "prod-like" workspace.
- `session:<id>`: terminal session workspace.

Each workspace has exactly one Datomic owner process. All other services call
the owner over HTTP.

## Main Workspace Topology
The main app is the Datomic owner. Public site and bot must not open Datomic.

### Option A: Co-located (single JVM)
- App, site, and bot handlers live in the main JVM.
- Pros: simplest, no extra services, no Datomic contention.
- Cons: fewer independent deploys; site/bot share app lifecycle.

### Option B: Client-only processes (separate JVMs)
- Site/bot are separate services but **call the app API** only.
- Pros: independent lifecycles, easier to scale separately.
- Cons: needs stable API contracts and auth between services.

**Decision:** Option A is the default for dev-local stability. Option B is only
allowed if the bot/site are API-only clients and never open Datomic.

## Session Workspace Topology
Each terminal session owns its Datomic store and can run its own app + dev bot.

Required invariants:
- `DATOMIC_STORAGE_DIR` must be unique per session.
- Session app is the only process that opens that directory.
- Session bot talks to the session app over HTTP (no Datomic).

## Snapshot Seeding (Main -> Session)
Sessions must start with the same data as main at time of creation.

### Required flow
1. **Snapshot export** (main app, admin-only):
   - Export schema + data for workspace `main` into a snapshot artifact.
2. **Snapshot import** (session app, before start):
   - Create fresh session DB.
   - Transact schema, then snapshot data.

### Implementation contract
- Add an admin action: `workspace.snapshot` (or similar).
- Output format: EDN datoms or a Datomic backup artifact.
- Store snapshots under `data/terminal/snapshots/<ts>/<id>.edn` (or backup dir).
- Terminal service calls snapshot export during session creation and passes the
  snapshot path to the session environment.

## Guardrails (Hard Fails)
These rules must be enforced by scripts and startup:
- **Port guard:** refuse to start the app if `APP_PORT` is already bound.
- **Lock guard:** refuse to start the app if the Datomic `.lock` exists for the
  configured `DATOMIC_STORAGE_DIR`.
- **Session guard:** if `TERMINAL_SESSION_ID` is set, the app must never point at
  main workspace storage.

## Configuration Rules
Main workspace:
- `DATOMIC_STORAGE_DIR=data/datomic`
- `DATOMIC_DB_NAME=darelwasl`
- No role-based suffixing by default.

Session workspace:
- `DATOMIC_STORAGE_DIR=data/terminal/sessions/<id>/datomic`
- `DATOMIC_DB_NAME=darelwasl-<id>`
- Optionally record `SESSION_SNAPSHOT_PATH` for bootstrap.

## Operational Workflow
- Main dev runs one app process (owns Datomic).
- Site/bot call the app API (Option B) or run inside the app JVM (Option A).
- Session creation:
  1) export snapshot from main,
  2) import into session DB,
  3) start session app,
  4) start session bot if requested.
- Promote session data via `workspace.promote` only.

## Migration Plan
1. Remove role-based Datomic suffix defaults from run scripts.
2. Add snapshot export/import support and wire session creation to it.
3. Add start guards (port + lock) to `scripts/run-service.sh`.
4. Ensure bot/site never open Datomic unless co-located.

## Open Questions
- Snapshot format: EDN datoms vs Datomic backup/restore.
- Do we need a snapshot retention policy (e.g., last N per day)?

# skills.md

Single source of truth for development logic. This replaces role-based AGENTS files and the
terminal section of system.md. Specs are inputs; verified proofs are outputs.

## Global Invariants
- Spec-in, proof-out. A task is not done without verified artifacts.
- Commands-only execution. The agent may only act by executing commands.
- Graph-first composition. Skills, steps, constraints, and proofs are nodes with explicit edges.
- Fail-closed. Missing proof keeps a session unverified.
- Auditability by default. All decisions and actions are evented and hashed.

## Defaults (Locked)
- Spec format: EDN only.
- Spec ingestion: terminal input `@spec <single-line-edn>`.
- Identity: Actors are first-class; Codex is a user.
- Plan form: DAG of Steps with explicit dependencies.
- Execution: commands-only; no implicit actions.
- Proofs: required by policy and change type.
- Artifacts: immutable, hashed (sha256), retained by default.
- Closure: session closes only when verified or explicitly blocked.
- Role of AI: elicit and refine specs; expand context and implications to improve correctness.
- Branching: work happens on a dedicated branch per spec.
- Review: PR is the default delivery mechanism for changes.
- Runtime: app restart is required after pushing changes when the app is involved.
- Subagents: use subagents when helpful; every subagent run is explicit and audited.
- Protocolization: all work is spec-driven, including discussion; no manual exceptions.

## Data Model (Graph)
- Spec: user intent, goals, constraints, acceptance criteria.
- Skill: reusable workflow describing how to fulfill a class of specs.
- Step: atomic unit of work (action | verification | decision).
- Command: a concrete execution event (shell/tool invocation).
- ProofArtifact: evidence produced by verification steps.
- Context: environment, repo, policies, constraints.
- Session: execution trace for a spec.
- Decision: captured choice with rationale and tradeoffs.
- Actor: a user identity (human or Codex) that can submit specs and review proofs.
- AgentRun: a delegated subagent run with its own event log and artifacts.

## Actors and Identity
- Codex can be a user. Treat Codex and humans as equivalent Actors with the same spec submission and
  review capabilities.
- Every Spec must include :spec/author that resolves to an Actor.

Actor (defaults):
- :actor/id keyword (example :actor/codex, :actor/damjan)
- :actor/type enum: :actor.type/human | :actor.type/codex
- :actor/roles set of keywords (optional)
- :actor/handle string (optional)

## Execution Rules
- Only Commands mutate state.
- A Step is complete only when its required artifacts exist.
- A Session is verified only when all required verification steps are verified.
- Any claim of correctness must cite a ProofArtifact.
- Subagent work must be declared in the plan and recorded as an AgentRun.
- Subagents follow the same commands-only and proof rules as the primary agent.
- Any workflow not yet formalized must be captured as a Spec before execution.

## Proof Artifacts (Required)
- CLI tests: test-log (stdout+stderr), exit-code, command, cwd, timestamp.
- Manual UI flows: screencast (primary) + start and end screenshots.
- Visual diffs: before image, after image, diff image, diff-summary metadata.

## Decision Capture (Meta Layer)
Every non-trivial choice must be a Decision node with:
- id, title, rationale
- options, tradeoffs, chosen-option
- linked step or spec

## Spec Format
EDN only. Minimal example:
{:spec/id "spec-YYYY-MM-DD-###"
 :spec/title "<short>"
 :spec/author :actor/codex
 :spec/goals ["..."]
 :spec/constraints ["..."]
 :spec/acceptance ["..."]
 :spec/skills [:skill/terminal-session-vnext]
 :spec/policies [:policy/verification-required]}

Required fields:
- :spec/id, :spec/title, :spec/author, :spec/goals, :spec/acceptance, :spec/skills

Optional fields:
- :spec/constraints, :spec/context, :spec/policies, :spec/inputs, :spec/outputs, :spec/deps
- :spec/knowledge (map for domain knowledge ingestion)
- :spec/app (map for app/view requirements)

## AI Role: Spec Elicitation and Expansion
The agent must guide the user toward a complete spec before execution. Being "helpful" means:
- Elicit missing fields by asking targeted questions.
- Expand context and implications (edge cases, constraints, side effects, dependencies).
- Surface assumptions explicitly and ask for confirmation.
- Propose acceptance criteria and verification steps when absent.
- Avoid shortcuts; prefer clarity over speed.

Idea-space default:
- Treat the user as exploring ideas and open to expansion unless they ask to lock decisions.
- Do not force a pragmatic narrowing; ask permission before constraining scope.
- If the user signals uncertainty, expand options and implications first.

Invariant-driven guidance:
- Identify invariants early and use them to evaluate options.
- When a choice is premature, keep alternatives open and record the tradeoffs.
- Only converge on a decision when the invariants are satisfied or the user requests it.

Default prompt sequence (internal):
1) Restate the goal in one line.
2) Ask for missing required fields.
3) Enumerate implications and edge cases.
4) Propose acceptance criteria + proof types.
5) Confirm or amend the spec, then proceed to planning.
6) Use subagents for parallel exploration or specialized depth when useful.

## Terminal Spec Input (No UI Changes)
- Specs are submitted via the terminal input stream.
- Canonical format: a single-line EDN envelope prefixed with `@spec `.
  Example: `@spec {:spec/id \"spec-2025-01-02-001\" ...}`
- Multi-line EDN is not supported; if a spec is large, submit via a file and use a command to load it.

## Spec Schema (EDN Defaults)
Required:
- :spec/id string (default format: spec-YYYY-MM-DD-### if provided by user; otherwise assigned)
- :spec/title string
- :spec/author keyword (must map to Actor)
- :spec/goals vector of strings
- :spec/acceptance vector of strings
- :spec/skills vector of keywords

Optional:
- :spec/constraints vector of strings
- :spec/policies vector of keywords (defaults below)
- :spec/change-types set of keywords (defaults below)
- :spec/delivery keyword (defaults below)
- :spec/context map
- :spec/inputs vector
- :spec/outputs vector
- :spec/deps vector of spec ids
- :spec/knowledge map (defaults below)
- :spec/app map (defaults below)

Optional app fields:
- :app/views (desired views or pages)
- :app/contracts (data needed by views)
- :app/ux (states, empty/error/loading expectations)
- :app/perf (performance constraints)

Optional knowledge fields:
- :knowledge/target (db identifier)
- :knowledge/schema (schema id or namespace)
- :knowledge/mapping (field mapping rules)
- :knowledge/id-strategy (how entities are identified)
- :knowledge/constraints (invariants to enforce)
- :knowledge/tx-format (edn, tx-data, or pull-compatible)

Defaults:
- :spec/policies [:policy/verification-required :policy/codex-is-user :policy/no-pr-without-proofs]
- :spec/change-types #{:change/unknown}
- :spec/delivery (derived; see Delivery Modes)

Change type enum (default set):
- :change/ui
- :change/api
- :change/registry
- :change/terminal
- :change/integration
- :change/data
- :change/scrape
- :change/knowledge
- :change/app
- :change/telegram
- :change/docs
- :change/devops
- :change/unknown

## EDN Validation Rules (Complete)
Validation is strict and deterministic. The validator must return either valid, blocked, or invalid.

Parsing:
- Input must parse as a single EDN map.
- Top-level keys must be :spec/* only.

Required field checks:
- All required fields must exist and be non-empty.
- If :spec/id is missing, assign before validation; otherwise fail.

Type checks:
- :spec/id, :spec/title are non-empty strings.
- :spec/author is a keyword and resolves to an Actor.
- :spec/goals and :spec/acceptance are vectors of non-empty strings.
- :spec/skills, :spec/policies are vectors of keywords.
- :spec/change-types is a set of keywords.
- :spec/context, :spec/knowledge, :spec/app are maps.
- :spec/deps is a vector of strings.

Enum checks:
- :spec/change-types must be a subset of the change type enum.
- :spec/delivery must be in the delivery enum if present.

Cross-field checks:
- If :change/knowledge is present, :spec/knowledge is required.
- If :change/app is present, :spec/app is required.
- If :change/scrape is present, :spec/context must include :scrape/targets and :scrape/fields.

Capability checks (non-fatal, but block execution):
- Unknown skills or policies do not fail validation; they mark the spec as blocked by capability.
- A blocked spec must trigger the capability extension workflow.

## Plan Compiler Defaults
- Input: Spec + Skills + Policies.
- Output: DAG of Steps with explicit dependencies.
- Steps are typed: :step.type/action | :step.type/verification | :step.type/decision.
- Compiler must insert verification steps for every change type and policy requirement.
- Compiler must add a verification gate step that blocks closure until all proofs are verified.

Step (defaults):
- :step/id string
- :step/type keyword
- :step/title string
- :step/depends-on set of step ids
- :step/required? boolean (default true)
- :step/proof-types set of proof types (verification only)
- :step/script optional path for deterministic execution
- :step/agent keyword (default :agent/self)
- :step/subagent-spec optional map (for delegated runs)
- :step/scope keyword (default :scope/read)
- :step/resources set of keywords (default empty)

## Plan Compiler and Parallelism (Complete)
Compiler responsibilities:
- Normalize skills into a single DAG with explicit dependencies.
- Annotate each step with :step/scope and :step/resources.
- Compute parallelizable step groups based on dependency + resource conflicts.
- Emit a deterministic schedule order for tie-breaking.

Scope enum (default set):
- :scope/read (no mutation)
- :scope/write (mutates shared state)
- :scope/verify (produces proofs)
- :scope/decision (merges or selects outcomes)

Parallelism rules:
- Steps with dependencies never run in parallel.
- Steps with overlapping :step/resources never run in parallel if either is :scope/write.
- :scope/read steps may run in parallel with each other and with :scope/verify.
- :scope/verify may run in parallel with :scope/read if they do not share resources.
- :scope/decision is serialized after its inputs are complete.

Subagent assignment:
- Prefer subagents for :scope/read and exploratory tasks.
- Never assign subagents to conflicting :scope/write steps.
- Default subagent concurrency cap: 3.

Conflict resolution:
- If two steps are otherwise parallel but share a resource, serialize by step id order.
- If subagent outputs conflict, insert a :scope/decision merge step with explicit tradeoffs.

Example DAG (parallel groups):
- Step A (read): discover sitemap
- Step B (read): infer data fields from samples
- Step C (decision): merge mapping assumptions (depends on A,B)
- Step D (write): update mapping manifest (depends on C)
- Step E (verify): validate manifest hashes (depends on D)

Parallel groups:
- Group 1: A + B (parallel)
- Group 2: C (serial)
- Group 3: D (serial)
- Group 4: E (parallel with other read/verify if no shared resources)

Status enum:
- :step.status/planned
- :step.status/executing
- :step.status/executed
- :step.status/verified
- :step.status/blocked
- :step.status/failed

## Command Events (Defaults)
Commands are the only executable actions.

CommandEvent (required):
- :command/id
- :command/session-id
- :command/step-id
- :command/cwd
- :command/argv vector of strings
- :command/start-ts
- :command/end-ts
- :command/exit-code
- :command/stdout-hash
- :command/stderr-hash

CommandEvent (optional):
- :command/env-hash
- :command/timeout-ms
- :command/notes

Execution scope defaults:
- :command/cwd must be inside the session workspace.
- No network or system access restrictions are implied here; enforce via policy if needed.
- Subagent commands are logged as CommandEvents and linked to AgentRun ids.

## Proof Artifacts (Defaults)
ProofArtifact (required):
- :artifact/id
- :artifact/type
- :artifact/path or :artifact/uri
- :artifact/sha256
- :artifact/size-bytes
- :artifact/created-at
- :artifact/step-id
- :artifact/session-id

Artifact retention default:
- Retain forever unless explicitly deleted by an operator policy.

## Artifact Store, Retention, Redaction (Complete)
Storage:
- Artifacts must be written to a dedicated artifact store and referenced by path/uri.
- Every artifact is immutable once written; updates create new artifacts.
- Hashes are computed at write time and validated on read.

Retention:
- Default retention is infinite.
- Exceptions are policy-driven and must be recorded as a Decision node.

Redaction:
- Secrets must be redacted at source before writing artifacts.
- If redaction occurs, record :artifact/redacted? true and the redaction method.
- Redaction never deletes the original secret from logs; avoid logging secrets at all.

Integrity:
- If hash verification fails, mark the step blocked and require re-capture.
- Do not trust artifacts without a matching hash record.

Proof type enum (default set):
- :proof/test-log
- :proof/screencast
- :proof/screenshot
- :proof/visual-diff
- :proof/api-response
- :proof/cli-output
- :proof/sitemap-manifest
- :proof/crawl-manifest
- :proof/datomic-tx-log
- :proof/query-result
- :proof/delivery-manifest
- :proof/pr-link
- :proof/telegram-devbot-session
- :proof/telegram-command-response
- :proof/mapping-manifest
- :proof/normalization-report
- :proof/review-task
- :proof/scrape-sample
- :proof/app-smoke

## Policy Matrix (Defaults)
Policies add required proof steps based on change type.

- :change/ui requires :proof/screencast + :proof/screenshot + :proof/api-response
- :change/api requires :proof/test-log + :proof/api-response
- :change/registry requires :proof/test-log
- :change/terminal requires :proof/test-log + at least one end-to-end command proof
- :change/integration requires :proof/test-log + :proof/api-response
- :change/data requires :proof/cli-output or :proof/api-response
- :change/scrape requires :proof/sitemap-manifest + :proof/crawl-manifest + :proof/cli-output
- :change/knowledge requires :proof/datomic-tx-log + :proof/query-result
- :change/app requires :proof/app-smoke + :proof/api-response
- :change/telegram requires :proof/telegram-devbot-session + :proof/telegram-command-response
- :change/docs requires :proof/cli-output (build or lint if present)
- :change/devops requires :proof/cli-output + service status check

## Delivery Modes (Complete)
Delivery is explicit. If :spec/delivery is missing, it is derived during planning and may be
confirmed by a Decision step.

Delivery enum:
- :delivery/pr (code changes delivered via PR)
- :delivery/artifact (deliver files/outputs without PR)
- :delivery/none (no external deliverable; internal analysis only)

Derivation defaults (if :spec/delivery is absent):
1) If the plan includes repo mutations, choose :delivery/pr.
2) Else if the plan produces artifacts, choose :delivery/artifact.
3) Else choose :delivery/none.

Delivery proofs:
- All deliveries require :proof/delivery-manifest.
- :delivery/pr additionally requires :proof/pr-link.

Branch/PR rules (only when :spec/delivery is :delivery/pr):
- Create a dedicated branch per spec (name derived from :spec/id).
- Push only after verification steps are satisfied.
- Deliver via PR; no direct merges.
- If the app is involved, restart it after push and record a proof artifact for the restart.

Artifact rules (only when :spec/delivery is :delivery/artifact):
- Produce a delivery manifest listing paths, hashes, and intended use.
- Artifacts must be stored and hashed before reporting completion.

## Executable Skills (Defaults)
- Skills are declarative playbooks that can include executable scripts.
- A Step may reference a script via :step/script and must execute it as a Command.
- Scripts must be invoked explicitly; no implicit execution is allowed.

## Skill: subagent-orchestration
Description (trigger): Use when a spec benefits from parallel exploration, specialized depth, or
independent verification.

Workflow:
1) Identify sub-tasks that can be delegated without shared mutable state.
2) Spawn subagent runs with explicit scopes and expected outputs.
3) Collect subagent artifacts and summarize decisions.
4) Merge results into the main plan and continue.

Required:
- Record each subagent run as an AgentRun node linked to the parent Session.
- Subagent outputs must be captured as ProofArtifacts or Decision nodes.

## Subagent Runtime Contract (Optional, Required for Real Subagents)
Subagents are executed by the runtime, not the agent. If the runtime is unavailable, treat this as
an impossible spec and trigger the capability extension workflow.

AgentRun (required fields):
- :agentrun/id
- :agentrun/parent-session-id
- :agentrun/spec-id
- :agentrun/status (planned | running | completed | blocked | failed)
- :agentrun/started-at
- :agentrun/ended-at
- :agentrun/artifacts (list of ProofArtifacts or Decision ids)

Command types for subagents:
- agent.run (payload: subagent spec + scope)
- agent.status (payload: agentrun-id)
- agent.collect (payload: agentrun-id)
- agent.cancel (payload: agentrun-id)

Isolation rules:
- Each AgentRun has its own workspace or read-only scope by default.
- Cross-run writes must be serialized and mediated by the parent Session.

## Skill: scrape-discovery
Description (trigger): Use when the user asks to scrape, extract, or discover site content, and
expects the agent to autonomously find sitemaps or navigable entry points.

Workflow:
1) Clarify target domain(s), data fields, and output format if missing.
2) Discover sitemaps without user guidance by exhausting standard methods:
   - robots.txt discovery (Sitemap entries).
   - Common sitemap paths: /sitemap.xml, /sitemap_index.xml, /sitemap-index.xml.
   - Platform defaults: /wp-sitemap.xml, /sitemap.xml.gz, /sitemaps/sitemap.xml.
   - HTML discovery: link rel="sitemap", footer links containing "sitemap".
3) If no sitemap is found, run a crawl:
   - BFS within allowed domain(s).
   - Respect robots unless the Spec explicitly overrides.
   - Cap depth and rate; record limits in the manifest.
4) If content is client-rendered, use a headless browser command to render and extract links.
5) Output:
   - sitemap manifest (found sitemaps and sources),
   - crawl manifest (discovered URLs, depth, limits),
   - data export in requested format.

Required proofs:
- :proof/sitemap-manifest
- :proof/crawl-manifest
- :proof/cli-output

Forbidden:
- Asking the user to provide sitemap locations before attempting discovery.

## Skill: scrape-to-knowledge
Description (trigger): Use when the user requests scraping with the goal of ingesting structured
data into Datomic and updating app/views as needed.

Workflow:
1) Clarify target domain(s), fields, purpose, and :spec/app requirements.
2) Mapping phase (exhaustive discovery):
   - Sitemaps and robots.txt.
   - HTML navigation, pagination, and category indices.
   - Search endpoints or on-site search.
   - Structured data (JSON-LD, RSS/Atom, embedded data).
   - API endpoints if documented or discoverable.
   - Headless browser discovery (Playwright) for client-rendered pages.
3) Produce a mapping manifest that includes:
   - Entry points, URL patterns, pagination rules.
   - Fields, selectors, and extraction rules.
   - Coverage limits (depth/rate) and assumptions.
4) Create a review task for the user with the mapping summary and open questions.
5) If coverage is insufficient after the depth cap, request manual path help with explicit
   instructions on what to find and why.
6) Scrape + normalize:
   - Extract raw data, then normalize (canonical ids, dedupe, types).
   - Produce a normalization report and a sample dataset.
7) Create a second review task summarizing results and asking for direction.
8) After approval, create or update schema and app/views per :spec/app.
9) Ingest into Datomic (use knowledge-ingest-datomic).
10) Verify with required proofs and queries.

Required proofs (minimum):
- :proof/mapping-manifest
- :proof/crawl-manifest
- :proof/scrape-sample
- :proof/normalization-report
- :proof/review-task
- :proof/datomic-tx-log
- :proof/query-result

Forbidden:
- Skipping mapping review.
- Asking for manual paths before exhausting discovery methods.

## Skill: knowledge-ingest-datomic
Description (trigger): Use when the user asks to turn generated output into domain knowledge stored
in Datomic, with a spec describing schema, mapping, and constraints.

Workflow:
1) Confirm :spec/knowledge target, schema, and mapping; ask only if missing.
2) Validate mapping against schema and invariants.
3) Generate deterministic tx-data from the artifacts.
4) Execute the Datomic transaction via a command.
5) Run verification queries that prove the knowledge exists and is correct.
6) Record tx log + query results as proof artifacts.

Required proofs:
- :proof/datomic-tx-log
- :proof/query-result

Forbidden:
- Writing knowledge without a declared schema or id strategy.

## Skill: app-view-update
Description (trigger): Use when app/views must be created or updated based on new data.

Workflow:
1) Read :spec/app requirements and data contracts.
2) Update schema/view models as needed.
3) Implement view changes.
4) Run app smoke + API proof.
5) Capture UI proof if :change/ui is included.

## Scrape-to-Knowledge Spec Template (EDN)
@spec {:spec/id "spec-YYYY-MM-DD-###"
       :spec/title "Scrape <source> into Datomic for <purpose>"
       :spec/author :actor/codex
       :spec/goals ["Map and scrape source data"
                    "Normalize and ingest into Datomic"
                    "Update app/views per requirements"]
       :spec/constraints ["Do not request manual paths until discovery is exhausted"
                          "Provide two review checkpoints (mapping + normalization)"]
       :spec/acceptance ["Mapping manifest + user review task exists"
                         "Normalization report + sample + user review task exists"
                         "Datomic ingest verified by tx log + query results"
                         "App changes verified by app smoke + API proof"]
       :spec/skills [:skill/scrape-to-knowledge
                     :skill/knowledge-ingest-datomic
                     :skill/app-view-update]
       :spec/change-types #{:change/scrape :change/knowledge :change/app}
       :spec/delivery :delivery/pr
       :spec/context {:scrape/targets ["https://example.com"]
                      :scrape/fields ["field-a" "field-b" "field-c"]
                      :scrape/purpose "why this data matters"
                      :scrape/depth-cap 3
                      :scrape/rate-limit "5 rps"}
       :spec/knowledge {:knowledge/target :db/main
                        :knowledge/schema :schema/example
                        :knowledge/id-strategy :id/slug
                        :knowledge/tx-format :tx-data}
       :spec/app {:app/views [:view/example]
                  :app/contracts [:contract/example]
                  :app/ux {:states [:empty :loading :error]}
                  :app/perf {:p95-ms 200}}}

## Skill: telegram-devbot-verify
Description (trigger): Use when Telegram behavior changes or needs verification via the dev bot.

Workflow:
1) Ensure the dev bot is started; do not start a second dev bot if one is already running.
2) Run a minimal command flow that requires user interaction (click + type) when appropriate.
3) Capture proof that the dev bot responded correctly.
4) If secrets are required, resolve them via the secret management rules below.

Required proofs:
- :proof/telegram-devbot-session
- :proof/telegram-command-response

Constraints:
- Only one dev bot may run at a time.
- Prefer click + type interactions over complex command sequences when possible.

Dev-bot lock protocol:
- Acquire a single-writer lock before starting the dev bot.
- If the lock is held, do not start a second bot; wait or ask the user to release.
- Release the lock after verification completes or the session blocks.

Verification checklist:
1) Start dev bot (or confirm already running).
2) Run `/start` and confirm bot response.
3) Execute at least one command that requires click + type.
4) Verify callback handling for a button press.
5) Capture response proof artifacts and logs.

Blocked criteria:
- Missing secrets or bot token references.
- Dev bot not responding to `/start`.
- Callback handling fails or times out.

Artifact naming (defaults):
- telegram-devbot-session.log
- telegram-command-response.log
- telegram-click-trace.json (if available)

## Telegram Spec Template (EDN)
@spec {:spec/id "spec-YYYY-MM-DD-###"
       :spec/title "Telegram dev-bot verification for <feature>"
       :spec/author :actor/codex
       :spec/goals ["Verify Telegram behavior via dev bot"]
       :spec/constraints ["Only one dev bot at a time"
                          "Prefer click + type interactions"]
       :spec/acceptance ["Dev bot started and responsive"
                         "Command response captured"]
       :spec/skills [:skill/telegram-devbot-verify]
       :spec/change-types #{:change/telegram}
       :spec/delivery :delivery/pr}

## Secret Management (Defaults)
- Secrets must never be embedded in specs, logs, or artifacts.
- Refer to secrets by key or path and resolve at execution time.
- Record only the secret reference in CommandEvent metadata (never values).

## Impossible Specs and Capability Extension
Specs may include requirements that are not yet supported. This is allowed and must trigger a
capability extension workflow.

Workflow:
1) Detect unsupported requirement and mark the plan as blocked by capability.
2) Create a sub-spec to extend skills/API to support the capability.
3) Implement the capability extension and produce proofs.
4) Create a review task for user approval of the new capability.
5) After approval, resume the original spec and proceed.

Required proofs:
- :proof/review-task (capability review)

## Capability Extension Spec Template (EDN)
@spec {:spec/id "spec-YYYY-MM-DD-###"
       :spec/title "Extend capability for <requirement>"
       :spec/author :actor/codex
       :spec/goals ["Implement missing capability"
                    "Produce proofs and request review"]
       :spec/constraints ["Do not proceed with original spec until approved"]
       :spec/acceptance ["Capability implemented with proofs"
                         "Review task created and approved"]
       :spec/skills [:skill/terminal-session-vnext]
       :spec/change-types #{:change/terminal}
       :spec/delivery :delivery/pr}

## Review and Task Responses
- Review tasks may be handled either by replying directly to the task or by responding in-session.
- The agent must accept both and map them to the same state transition.
- If a review requires discussion, the task must include explicit questions and options.
- If a review is direct, the task must include a single approve/refine prompt.
- When a user needs time to investigate, the task remains open and the session is paused.

## Review Task Format (Template)
Required fields:
- title
- context (short summary + links/paths)
- status (discussion | direct)
- questions (if discussion)
- options (if discussion)
- prompt (if direct)
- artifacts (paths + hashes)

Example (discussion):
Title: "Review mapping manifest for <source>"
Context: "Mapped 312 pages, 6 templates. See /path/to/mapping-manifest.edn"
Status: discussion
Questions:
- "Is coverage acceptable for the stated purpose?"
- "Should we include the archive section?"
Options:
- "Approve mapping as-is"
- "Refine: add archive section"
- "Refine: change depth cap"
Artifacts:
- /path/to/mapping-manifest.edn (sha256: ...)
- /path/to/crawl-manifest.edn (sha256: ...)

Example (direct):
Title: "Approve capability extension for <requirement>"
Context: "Implemented missing capability; proofs attached."
Status: direct
Prompt: "Approve to resume original spec? (approve/refine)"
Artifacts:
- /path/to/proof.log (sha256: ...)

## Review Task Checklist
- Include a one-line context summary.
- Link all artifacts with hashes.
- State whether the task is discussion or direct.
- For discussion: list questions and explicit options.
- For direct: include a single approve/refine prompt.
- State what happens after approval (resume spec, proceed to next step).

## End-to-End Pipeline
1) Ingest: terminal receives `@spec` EDN and registers a Spec.
2) Validate: schema validation + required fields + policy constraints.
3) Plan: compile Spec -> DAG of Steps (including verification steps).
4) Execute: agent runs Commands to satisfy Steps.
5) Capture: commands and artifacts are hashed and indexed.
6) Verify: artifacts are validated against acceptance criteria.
7) Close: session can only close if verified (or blocked with explicit reason).

## Skill: terminal-session-vnext
Description (trigger): Use for all terminal sessions where a user submits a spec and expects
verified implementation with evidence, no shortcuts.

Workflow:
1) Parse Spec, validate constraints.
2) Compile Spec -> Plan DAG of Steps (including verification steps).
3) Execute Steps via Commands only.
4) Capture ProofArtifacts for verification steps.
5) Validate artifacts against acceptance criteria.
6) Mark session verified or blocked.

Required Proofs:
- All verification steps in the plan must have required artifacts.
- Any UI-visible change requires a UI proof and an API proof.

Forbidden:
- Any action not represented as a Command event.
- Any "done" state without verified proofs.

## Skill: verification-enforcer
Description (trigger): Use when correctness must be proven and evidence must be recorded.

Rules:
- Missing artifact => step blocked.
- Failed criteria => session blocked.
- Verification cannot be skipped by convenience or time.

## Policy Modules (Apply via Spec or Skill)
- policy/verification-required: verification steps are mandatory.
- policy/ui-changes-require-ui-proof: UI proof + API proof required.
- policy/no-pr-without-proofs: completion requires verified status.
- policy/codex-is-user: Codex Actors can submit specs and review proofs.
- policy/branch-per-spec: use a dedicated branch for each spec.
- policy/pr-as-delivery: deliver changes via PR.
- policy/restart-app-on-push: restart app after push and record proof.

## Session Lifecycle
- created -> planned -> running -> verifying -> verified | blocked -> closed

## Minimal CLI Contract
- submit spec -> spec-id
- generate plan -> plan DAG
- run step -> Command event
- attach artifact -> ProofArtifact stored and hashed
- verify step -> status update
- close session -> requires verified status

## Message Envelope Types (Optional)
All structured interactions use typed envelopes so they can be audited and validated.

Envelope (required fields):
- :msg/type keyword
- :msg/id string
- :msg/ts timestamp
- :msg/actor keyword
- :msg/session-id string
- :msg/payload map

Type enum (default set):
- :msg/spec
- :msg/plan
- :msg/command
- :msg/proof
- :msg/decision
- :msg/event
- :msg/task
- :msg/review

Allowed transitions (default):
- :msg/spec -> :msg/plan -> :msg/command -> :msg/proof -> :msg/event
- :msg/review updates the Session state (verified | blocked).

## Minimal API Contract (Optional)
Commands invoke the runtime API behind the scenes. The agent emits commands only.

Command types (default):
- spec.submit (payload: spec EDN)
- plan.generate (payload: spec-id)
- step.run (payload: step-id)
- artifact.attach (payload: artifact metadata)
- step.verify (payload: step-id)
- delivery.finalize (payload: delivery mode + manifest)
- session.close (payload: session-id)

API responses (required):
- status (ok | error | blocked)
- payload (response data or error detail)
- artifacts (if produced)

## App Change Spec Template (EDN) (Optional)
@spec {:spec/id "spec-YYYY-MM-DD-###"
       :spec/title "App/view update for <feature>"
       :spec/author :actor/codex
       :spec/goals ["Update app/view using new data"]
       :spec/acceptance ["App smoke passes"
                         "API proof captured"
                         "UI proof captured if applicable"]
       :spec/skills [:skill/app-view-update]
       :spec/change-types #{:change/app :change/api :change/ui}
       :spec/delivery :delivery/pr
       :spec/app {:app/views [:view/example]
                  :app/contracts [:contract/example]
                  :app/ux {:states [:empty :loading :error]}
                  :app/perf {:p95-ms 200}}}

## Public Site Spec Template (EDN) (Optional)
@spec {:spec/id "spec-YYYY-MM-DD-###"
       :spec/title "Public site update for <section>"
       :spec/author :actor/codex
       :spec/goals ["Update public site content or layout"]
       :spec/acceptance ["Site build or smoke proof captured"
                         "UI proof captured"]
       :spec/skills [:skill/app-view-update]
       :spec/change-types #{:change/app :change/ui}
       :spec/delivery :delivery/pr
       :spec/app {:app/views [:view/public-site]
                  :app/ux {:states [:empty :loading :error]}
                  :app/perf {:p95-ms 200}}}

## Message Envelope Example (Optional)
{:msg/type :msg/spec
 :msg/id "msg-2025-01-02-001"
 :msg/ts "2025-01-02T10:12:00Z"
 :msg/actor :actor/codex
 :msg/session-id "session-123"
 :msg/payload {:spec/id "spec-2025-01-02-001"
               :spec/title "Scrape X into Datomic"
               :spec/author :actor/codex
               :spec/goals ["Map and scrape X"]
               :spec/acceptance ["Proofs exist"]
               :spec/skills [:skill/scrape-to-knowledge]}}

## Migration Guidance
- Treat AGENTS files and system.md terminal section as legacy.
- All new development logic belongs here.
- UI changes are out of scope for this doc; terminal app behavior changes are allowed.

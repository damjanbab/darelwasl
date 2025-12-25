# Pipeline-Lite Spec (Internal App, Telegram-First)

## Goals
- Minimize typing and context switching for primary operator (Telegram-first).
- Capture blockers consistently with free-text waiting reasons.
- Keep internal UI aligned to existing task list/detail patterns.
- Support scale later without adopting full CRM.

## Entities

### Client (dedicated)
- Recommended type: reuse `:entity.type/person` with role/tag `:role/client`.
- Required fields: `client/id`, `client/name`.
- Optional fields: `client/phone`, `client/email`, `client/channel` (WhatsApp/phone/email),
  `client/status` (lead|active|waiting|closed), `client/notes`.

### Task (existing)
- Add link: `task/client` (ref to client).
- Keep existing task fields unchanged.

### Note (existing)
- Used for waiting reasons when a task is pending.
- Free-text `note/body`, optional `note/next-followup`, `note/last-contact`.

## Rules
- Every task must link to a client.
- If task status = `pending`, require a waiting-reason note (free text).
- Each client should have one "Next action" task (workflow rule, not schema-enforced).

## Telegram UX (Extend Existing Bot, Minimal Typing)

### Free-text capture (extended)
- Any free text prompts: "Create from text?"
- Buttons: `Task`, `Client`, `Dismiss`.
- Default highlight: `Task` to preserve current behavior.

### Task creation
- If `Task`: existing `/new` flow.
- After task creation, prompt "Link to client?"
  Buttons: `Pick client`, `Create client`, `Skip`.

### Client creation
- If `Client`: create client using the free-text as name.
- Prompt for next action with quick buttons:
  `Call client`, `Request docs`, `Follow up`, `Schedule meeting`, `Custom...`.
- If `Custom...`, user types a short action.
- Bot creates the next-action task and links it to the client.

### Pending flow (button-first)
- When user taps "Pending" on a task card:
  - Show waiting-reason buttons + `Custom...`.
  - Then show follow-up buttons: `Tomorrow`, `In 3 days`, `Next week`, `Pick date`.
  - Bot creates a pending-reason note.

### Existing commands (unchanged)
- `/start`, `/help`, `/tasks`, `/task <id>`, `/new <title> | <desc>`, `/stop`.
- Inline status/refresh buttons remain.

## Internal UI (Task-Aligned)

### Clients list view
- Looks like task list rows.
- Row shows: client name, status chip, next action title + due date.
- Filters: status (lead|active|waiting|closed).

### Client detail panel
- Mirrors task detail layout.
- Shows: next action task editor, pending reason note, task history.

### Tasks view
- Task list shows client name inline.
- Task detail includes client link + quick change.

## Actions (Minimal)
- Create/update client.
- Create/update task (must include client).
- Set task status (pending requires waiting note).
- Add waiting note.
- Link/unlink task to client.

## Data & Reporting (Optional)
- Active clients count.
- Pending tasks count + average follow-up age.
- Top waiting reasons (free text initially; categorize later).

## Non-Goals
- No full CRM pipeline, no deals, no invoices, no complex multi-stage workflows.
- No heavy typing or multi-step forms in Telegram.

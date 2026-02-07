# Telegram Guide (Darelwasl)

This is the operator guide for using the Telegram bot features (tasks + documents).

## 0) Link your Telegram chat

1. Log into the app (web).
2. Generate a link token (session required):
   - `POST /api/telegram/link-token`
3. In Telegram, send the bot:
   - `/start <token>`

If your chat is not linked, most commands will reply with “Chat not linked”.

## 1) Task commands

### List tasks
- `/tasks`

### Open a task
- `/task <uuid>`

### Create a task
- `/new <title> [| description]`

Examples:
- `/new Call client`
- `/new Prepare proposal | Include payment plan + timeline`

### Edit a task
- `/edit <task-id> <title> [| description]`

### Add a note
- `/note <task-id> <comment>`

### Edit the latest note (for that task)
- `/note-edit <task-id> <comment>`

### Unlink / stop notifications
- `/stop`

## 2) Documents starter pack (proposal / invoice / receipt / status report)

The bot stores “document pack” data per client:
- Company header fields (company name, currency)
- Services included
- Payment plan
- Status notes
- Invoices
- Payments (optionally tied to an invoice)

### Entry point (recommended)
- `/docs`

If you don’t have an active client selected, the bot will ask you to pick one.

### What you can do from the `/docs` menu (buttons)
- Set company name / currency
- Set services included / payment plan / status notes
- Add invoice (wizard)
- Add payment (wizard)
- Generate PDFs:
  - Proposal PDF
  - Status report PDF
  - Invoice → PDF (pick invoice)
  - Receipt → PDF (pick payment)

### Shortcuts
- `/proposal` → opens `/docs` (same menu)
- `/invoice` → opens `/docs` (same menu)
- `/receipt` → starts by asking you to pick a client (then uses the documents flow)
- `/status` → opens `/docs` (same menu)

### Invoice workflow (Telegram)
1. `/docs` → pick client (once)
2. Tap `Add invoice`
3. Follow prompts (number, total amount, status, optional fields)
4. Tap `Invoices → PDF` and select the invoice to generate and receive the PDF

### Receipt workflow (Telegram)
1. `/docs` → pick client
2. Tap `Add payment` and follow prompts
3. Tap `Receipt → PDF` and pick the payment to generate and receive the PDF

## 3) Account statement (legacy flow)

- `/statement`

This is a separate wizard and is not part of the documents pack.

## 4) Dev bot vs prod bot (important)

- Proof/testing must be done on the **dev bot** first.
- Production bot should only be used after you confirm the PDFs look correct in dev.

If messages don’t arrive on dev: send any message once to the dev bot so your chat can be bound (dev may auto-bind by Telegram username depending on env).


#!/usr/bin/env node

/**
 * Render PDFs for:
 * - proposal
 * - invoice
 * - receipt
 * - status-report
 *
 * Usage:
 *   node scripts/documents-pdf.js --type proposal --input <input.json> --out <out.pdf>
 */

const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i];
    if (!key || !key.startsWith("--")) continue;
    const value = argv[i + 1];
    args[key.slice(2)] = value;
    i += 1;
  }
  return args;
}

function present(v) {
  if (v === null || v === undefined) return null;
  const s = String(v).trim();
  return s.length ? s : null;
}

const DEFAULT_TZ = process.env.DARELWASL_TZ || "Asia/Riyadh";

function formatDateOnly(date) {
  return new Intl.DateTimeFormat("en-GB", {
    timeZone: DEFAULT_TZ,
    day: "2-digit",
    month: "short",
    year: "numeric",
  }).format(date);
}

function formatDateTime(date) {
  return new Intl.DateTimeFormat("en-GB", {
    timeZone: DEFAULT_TZ,
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}

function isMidnightInTz(date) {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: DEFAULT_TZ,
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(date);
  const hour = parts.find((p) => p.type === "hour")?.value;
  const minute = parts.find((p) => p.type === "minute")?.value;
  return hour === "00" && minute === "00";
}

function formatDateValue(v) {
  const raw = present(v);
  if (!raw) return "—";
  if (raw === "—") return raw;

  // Accept plain dates like 2026-02-07
  if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) {
    const date = new Date(`${raw}T00:00:00Z`);
    return Number.isNaN(date.getTime()) ? raw : formatDateOnly(date);
  }

  const date = new Date(raw);
  if (Number.isNaN(date.getTime())) return raw;

  const hasTime = /T\d{2}:\d{2}/.test(raw) || /\d{2}:\d{2}/.test(raw);
  if (!hasTime) return formatDateOnly(date);
  if (isMidnightInTz(date)) return formatDateOnly(date);
  return formatDateTime(date);
}

function escapeHtml(s) {
  return String(s || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function safeNumber(v) {
  if (typeof v === "number" && Number.isFinite(v)) return v;
  const s = present(v);
  if (!s) return null;
  const normalized = s.replace(/[^0-9.+-]/g, "");
  const n = Number(normalized);
  if (!Number.isFinite(n)) return null;
  return n;
}

function pick(obj, keys) {
  if (!obj) return undefined;
  for (const k of keys) {
    const v = obj[k];
    if (v !== undefined && v !== null) return v;
  }
  return undefined;
}

function formatMoney(amount, currency) {
  const n = safeNumber(amount);
  if (n === null) return "—";
  const rounded = Math.round(n * 100) / 100;
  const parts = rounded.toFixed(2).split(".");
  parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  const c = present(currency) || "";
  return c ? `${parts[0]}${parts[1] === "00" ? "" : `.${parts[1]}`} ${c}` : `${parts[0]}${parts[1] === "00" ? "" : `.${parts[1]}`}`;
}

function sumAmounts(items, key) {
  if (!Array.isArray(items)) return 0;
  return items.reduce((acc, it) => acc + (safeNumber(it && it[key]) || 0), 0);
}

function renderBlock(label, value) {
  return `
    <div class="kv-row">
      <div class="kv-key">${escapeHtml(label)}</div>
      <div class="kv-val">${escapeHtml(value ?? "—")}</div>
    </div>
  `;
}

function baseStyles() {
  return `
    :root {
      --ink: #0f172a;
      --muted: #475569;
      --border: #e2e8f0;
      --bg: #ffffff;
      --panel: #f8fafc;
      --accent: #1F2147;
      --logo-width: 320px;
    }

    * { box-sizing: border-box; }
    html, body { margin: 0; padding: 0; background: var(--bg); color: var(--ink); font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif; }

    .page { padding: 42px 44px; }

    .header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 16px;
      padding-bottom: 18px;
      border-bottom: 1px solid var(--border);
    }

    .brand { display: flex; align-items: center; }
    .logo svg { width: var(--logo-width); height: auto; display: block; }
    .logo-fallback { width: var(--logo-width); height: 68px; border: 2px solid var(--accent); border-radius: 10px; }

    .doc-title-block { text-align: center; margin-top: 18px; }
    .title { margin: 0; font-size: 22px; letter-spacing: 1.8px; font-weight: 800; color: var(--ink); }
    .meta-line { margin: 6px 0 0; color: var(--muted); font-size: 11px; font-weight: 400; }

    .contact {
      text-align: right;
      color: var(--muted);
      font-size: 11px;
      line-height: 1.35;
      font-weight: 400;
      white-space: pre-line;
    }

    .section { margin-top: 18px; }
    .section-title {
      font-size: 12px;
      text-transform: uppercase;
      letter-spacing: 1.2px;
      color: var(--muted);
      margin: 0 0 10px;
    }

    .panel {
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 14px 16px;
    }

    .kv { display: grid; grid-template-columns: 1fr; gap: 6px; }
    .kv-row { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
    .kv-key { color: var(--muted); font-size: 12px; }
    .kv-val { font-weight: 600; font-size: 13px; color: var(--ink); text-align: right; }

    .summary-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
    .card { background: #fff; border: 1px solid var(--border); border-radius: 12px; padding: 12px 12px; }
    .card .label { color: var(--muted); font-size: 11px; letter-spacing: 0.8px; text-transform: uppercase; margin: 0; }
    .card .value { margin: 8px 0 0; font-size: 16px; font-weight: 800; color: var(--ink); font-variant-numeric: tabular-nums; }

    table {
      width: 100%;
      border-collapse: collapse;
      background: #fff;
      border: 1px solid var(--border);
      border-radius: 12px;
      overflow: hidden;
    }
    thead th {
      text-align: left;
      font-size: 11px;
      letter-spacing: 0.8px;
      text-transform: uppercase;
      color: var(--muted);
      background: #f1f5f9;
      padding: 10px 12px;
      border-bottom: 1px solid var(--border);
    }
    tbody td {
      padding: 10px 12px;
      border-bottom: 1px solid var(--border);
      font-size: 12px;
      vertical-align: top;
    }
    tbody tr:last-child td { border-bottom: none; }
    .cell-amt { text-align: right; font-variant-numeric: tabular-nums; font-weight: 700; }
    .mono { font-variant-numeric: tabular-nums; }

    .prose {
      margin-top: 10px;
      padding: 12px 14px;
      background: #fff;
      border: 1px dashed var(--border);
      border-radius: 12px;
      color: var(--ink);
      font-size: 12px;
      line-height: 1.45;
      white-space: pre-wrap;
    }

    .footer {
      margin-top: 22px;
      padding-top: 12px;
      border-top: 1px solid var(--border);
      color: var(--muted);
      font-size: 10.5px;
      display: flex;
      justify-content: space-between;
      gap: 12px;
    }
    .footer .right { text-align: right; }
  `;
}

function headerHtml({ logoSvg, contactLines }) {
  const logoMarkup = logoSvg
    ? `<div class="logo" aria-hidden="true">${logoSvg}</div>`
    : `<div class="logo-fallback" aria-hidden="true"></div>`;
  const contact = Array.isArray(contactLines) ? contactLines.filter((l) => present(l)) : [];

  return `
    <div class="header">
      <div class="brand">${logoMarkup}</div>
      <div class="contact">${escapeHtml(contact.join("\n"))}</div>
    </div>
  `;
}

function titleBlockHtml({ title, metaLines }) {
  const meta = Array.isArray(metaLines) ? metaLines : [];
  return `
    <div class="doc-title-block">
      <h1 class="title">${escapeHtml(title)}</h1>
      ${meta.map((l) => `<p class="meta-line">${l}</p>`).join("\n")}
    </div>
  `;
}

function proposalBody(input) {
  const company = input.company || {};
  const client = input.client || {};
  const currency = present(company.currency) || "SAR";
  const invoices = Array.isArray(input.invoices) ? input.invoices : [];

  return `
    <div class="section">
      <p class="section-title">Client</p>
      <div class="panel">
        <div class="kv">
          ${renderBlock("Client Name", client.name)}
          ${client.email ? renderBlock("Email", client.email) : ""}
          ${client.phone ? renderBlock("Phone", client.phone) : ""}
        </div>
      </div>
    </div>

    <div class="section">
      <p class="section-title">Services Included</p>
      <div class="prose">${escapeHtml(present(input.servicesIncluded) || "—")}</div>
    </div>

    <div class="section">
      <p class="section-title">Payment Plan</p>
      <div class="prose">${escapeHtml(present(input.paymentPlan) || "—")}</div>
    </div>

    <div class="section">
      <p class="section-title">Invoices (optional)</p>
      <table>
        <thead>
          <tr>
            <th>#</th>
            <th>Issued</th>
            <th>Status</th>
            <th style="text-align:right">Total (${escapeHtml(currency)})</th>
          </tr>
        </thead>
        <tbody>
          ${
            invoices.length
              ? invoices
                  .map((inv) => {
                    const total = formatMoney(pick(inv, ["invoice/total-amount", "total-amount", "totalAmount", "total_amount"]), currency);
                    return `
                      <tr>
                        <td class="mono">${escapeHtml(pick(inv, ["invoice/number", "number", "invoiceNumber", "invoice_number"]) ?? "—")}</td>
                        <td>${escapeHtml(formatDateValue(pick(inv, ["invoice/issued-at", "issued-at", "issuedAt", "issued_at"])))}</td>
                        <td>${escapeHtml(String(pick(inv, ["invoice/status", "status"]) ?? "—"))}</td>
                        <td class="cell-amt">${escapeHtml(total)}</td>
                      </tr>
                    `;
                  })
                  .join("\n")
              : `
                  <tr>
                    <td>—</td><td>—</td><td>—</td><td class="cell-amt">—</td>
                  </tr>
                `
          }
        </tbody>
      </table>
    </div>
  `;
}

function invoiceBody(input) {
  const company = input.company || {};
  const client = input.client || {};
  const currency = present(company.currency) || "SAR";
  const invoice = input.invoice || {};
  const payments = Array.isArray(input.payments) ? input.payments : [];
  const invCurrency = present(pick(invoice, ["invoice/currency", "currency"])) || currency;
  const total = safeNumber(pick(invoice, ["invoice/total-amount", "total-amount", "totalAmount", "total_amount"])) || 0;
  const paid = sumAmounts(payments, "payment/amount") || sumAmounts(payments, "amount");
  const outstanding = Math.max(0, Math.round((total - paid) * 100) / 100);

  return `
    <div class="section">
      <p class="section-title">Invoice</p>
      <div class="panel">
        <div class="kv">
          ${renderBlock("Invoice #", pick(invoice, ["invoice/number", "number"]) ?? "—")}
          ${renderBlock("Issued", formatDateValue(pick(invoice, ["invoice/issued-at", "issued-at", "issuedAt", "issued_at"])))}
          ${pick(invoice, ["invoice/due-at", "due-at", "dueAt", "due_at"]) ? renderBlock("Due", formatDateValue(pick(invoice, ["invoice/due-at", "due-at", "dueAt", "due_at"]))) : ""}
          ${renderBlock("Status", String(pick(invoice, ["invoice/status", "status"]) ?? "—"))}
        </div>
      </div>
    </div>

    <div class="section">
      <p class="section-title">Client</p>
      <div class="panel">
        <div class="kv">
          ${renderBlock("Client Name", client.name)}
          ${client.email ? renderBlock("Email", client.email) : ""}
          ${client.phone ? renderBlock("Phone", client.phone) : ""}
        </div>
      </div>
    </div>

    <div class="section">
      <p class="section-title">Summary</p>
      <div class="summary-grid">
        <div class="card">
          <p class="label">Total</p>
          <p class="value">${escapeHtml(formatMoney(total, invCurrency))}</p>
        </div>
        <div class="card">
          <p class="label">Paid</p>
          <p class="value">${escapeHtml(formatMoney(paid, invCurrency))}</p>
        </div>
        <div class="card">
          <p class="label">Outstanding</p>
          <p class="value">${escapeHtml(formatMoney(outstanding, invCurrency))}</p>
        </div>
      </div>
    </div>

    ${
      present(pick(invoice, ["invoice/description", "description"]))
        ? `<div class="section"><p class="section-title">Description</p><div class="prose">${escapeHtml(pick(invoice, ["invoice/description", "description"]))}</div></div>`
        : ""
    }

    <div class="section">
      <p class="section-title">Payments</p>
      <table>
        <thead>
          <tr>
            <th>Date</th>
            <th>Method</th>
            <th>Reference</th>
            <th style="text-align:right">Amount (${escapeHtml(invCurrency)})</th>
          </tr>
        </thead>
        <tbody>
          ${
            payments.length
              ? payments
                  .map((p) => {
                    const amt = safeNumber(pick(p, ["payment/amount", "amount", "paymentAmount", "payment_amount"]));
                    return `
                      <tr>
                        <td>${escapeHtml(formatDateValue(pick(p, ["payment/paid-at", "paid-at", "paidAt", "paid_at"])))}</td>
                        <td>${escapeHtml(String(pick(p, ["payment/method", "method"]) ?? "—"))}</td>
                        <td class="mono">${escapeHtml(present(pick(p, ["payment/reference", "reference"])) || "—")}</td>
                        <td class="cell-amt">${escapeHtml(formatMoney(amt, invCurrency))}</td>
                      </tr>
                    `;
                  })
                  .join("\n")
              : `<tr><td>—</td><td>—</td><td>—</td><td class="cell-amt">—</td></tr>`
          }
        </tbody>
      </table>
    </div>
  `;
}

function receiptBody(input) {
  const company = input.company || {};
  const client = input.client || {};
  const currency = present(company.currency) || "SAR";
  const payment = input.payment || {};
  const invoice = input.invoice || null;
  const payCurrency = present(pick(payment, ["payment/currency", "currency"])) || currency;
  const amount = safeNumber(pick(payment, ["payment/amount", "amount", "paymentAmount", "payment_amount"]));

  return `
    <div class="section">
      <p class="section-title">Client</p>
      <div class="panel">
        <div class="kv">
          ${renderBlock("Client Name", client.name)}
          ${client.email ? renderBlock("Email", client.email) : ""}
          ${client.phone ? renderBlock("Phone", client.phone) : ""}
        </div>
      </div>
    </div>

    <div class="section">
      <p class="section-title">Payment</p>
      <div class="panel">
        <div class="kv">
          ${renderBlock("Paid At", formatDateValue(pick(payment, ["payment/paid-at", "paid-at", "paidAt", "paid_at"])))}
          ${renderBlock("Method", String(pick(payment, ["payment/method", "method"]) ?? "—"))}
          ${renderBlock("Amount", formatMoney(amount, payCurrency))}
          ${renderBlock("Reference", present(pick(payment, ["payment/reference", "reference"])) || "—")}
        </div>
      </div>
    </div>

    ${
	      invoice
	        ? `
	      <div class="section">
	            <p class="section-title">Related Invoice (optional)</p>
	            <div class="panel">
	              <div class="kv">
	                ${renderBlock("Invoice #", pick(invoice, ["invoice/number", "number"]) ?? "—")}
	                ${renderBlock("Issued", formatDateValue(pick(invoice, ["invoice/issued-at", "issued-at", "issuedAt", "issued_at"])))}
	                ${renderBlock("Total", formatMoney(pick(invoice, ["invoice/total-amount", "total-amount", "totalAmount", "total_amount"]), present(pick(invoice, ["invoice/currency", "currency"])) || payCurrency))}
	              </div>
	            </div>
	          </div>
	        `
	        : ""
	    }

    ${
      present(pick(payment, ["payment/note", "note"]))
        ? `<div class="section"><p class="section-title">Notes</p><div class="prose">${escapeHtml(pick(payment, ["payment/note", "note"]))}</div></div>`
        : ""
    }
  `;
}

function statusReportBody(input) {
  const company = input.company || {};
  const client = input.client || {};
  const currency = present(company.currency) || "SAR";
  const invoices = Array.isArray(input.invoices) ? input.invoices : [];
  const payments = Array.isArray(input.payments) ? input.payments : [];
  const tasks = input.tasks || {};
  const paid = sumAmounts(payments, "payment/amount") || sumAmounts(payments, "amount");
  const invoiced = sumAmounts(invoices, "invoice/total-amount") || sumAmounts(invoices, "total-amount") || sumAmounts(invoices, "totalAmount");
  const outstanding = Math.max(0, Math.round((invoiced - paid) * 100) / 100);

  function taskList(title, arr) {
    const items = Array.isArray(arr) ? arr : [];
    const rows = items.length
      ? items.map((t) => `<li>${escapeHtml(t["task/title"] ?? t.title ?? "—")}</li>`).join("")
      : `<li>—</li>`;
    return `<div class="card"><p class="label">${escapeHtml(title)}</p><div class="prose" style="margin-top:8px">${rows ? `<ul style="margin:0;padding-left:18px">${rows}</ul>` : "—"}</div></div>`;
  }

  return `
    <div class="section">
      <p class="section-title">Client</p>
      <div class="panel">
        <div class="kv">
          ${renderBlock("Client Name", client.name)}
          ${client.email ? renderBlock("Email", client.email) : ""}
          ${client.phone ? renderBlock("Phone", client.phone) : ""}
        </div>
      </div>
    </div>

    <div class="section">
      <p class="section-title">Financial Status</p>
      <div class="summary-grid">
        <div class="card">
          <p class="label">Invoiced</p>
          <p class="value">${escapeHtml(formatMoney(invoiced, currency))}</p>
        </div>
        <div class="card">
          <p class="label">Paid</p>
          <p class="value">${escapeHtml(formatMoney(paid, currency))}</p>
        </div>
        <div class="card">
          <p class="label">Outstanding</p>
          <p class="value">${escapeHtml(formatMoney(outstanding, currency))}</p>
        </div>
      </div>
    </div>

    ${
      present(input.statusNotes)
        ? `<div class="section"><p class="section-title">Status Notes</p><div class="prose">${escapeHtml(input.statusNotes)}</div></div>`
        : ""
    }

    <div class="section">
      <p class="section-title">Work Progress (tasks)</p>
      <div class="summary-grid" style="grid-template-columns: repeat(2, 1fr);">
        ${taskList("Done", tasks.done)}
        ${taskList("In Progress", tasks["in-progress"] || tasks.inProgress)}
        ${taskList("Pending", tasks.pending)}
        ${taskList("To Do", tasks.todo)}
      </div>
    </div>

    <div class="section">
      <p class="section-title">Payments</p>
      <table>
        <thead>
          <tr>
            <th>Date</th>
            <th>Method</th>
            <th>Reference</th>
            <th style="text-align:right">Amount (${escapeHtml(currency)})</th>
          </tr>
        </thead>
        <tbody>
          ${
            payments.length
              ? payments
                  .map((p) => {
                    const amt = safeNumber(pick(p, ["payment/amount", "amount", "paymentAmount", "payment_amount"]));
                    return `
                      <tr>
                        <td>${escapeHtml(formatDateValue(pick(p, ["payment/paid-at", "paid-at", "paidAt", "paid_at"])))}</td>
                        <td>${escapeHtml(String(pick(p, ["payment/method", "method"]) ?? "—"))}</td>
                        <td class="mono">${escapeHtml(present(pick(p, ["payment/reference", "reference"])) || "—")}</td>
                        <td class="cell-amt">${escapeHtml(formatMoney(amt, currency))}</td>
                      </tr>
                    `;
                  })
                  .join("\n")
              : `<tr><td>—</td><td>—</td><td>—</td><td class="cell-amt">—</td></tr>`
          }
        </tbody>
      </table>
    </div>
  `;
}

function buildHtml(type, input, { logoSvg }) {
  const gen = formatDateValue(present(input.generatedAt) || new Date().toISOString());

  let title = "";
  let metaLines = [];
  let body = "";

  if (type === "proposal") {
    title = "PROPOSAL";
    metaLines = [`<strong>Date:</strong> ${escapeHtml(gen)}`];
    body = proposalBody(input);
  } else if (type === "invoice") {
    title = "INVOICE";
    const inv = input.invoice || {};
    metaLines = [
      `<strong>Date:</strong> ${escapeHtml(gen)}`,
      `<strong>Invoice:</strong> ${escapeHtml(present(pick(inv, ["invoice/number", "number"])) || "—")}`,
    ];
    body = invoiceBody(input);
  } else if (type === "receipt") {
    title = "RECEIPT";
    metaLines = [`<strong>Date:</strong> ${escapeHtml(gen)}`];
    body = receiptBody(input);
  } else if (type === "status-report") {
    title = "STATUS REPORT";
    metaLines = [`<strong>Date:</strong> ${escapeHtml(gen)}`];
    body = statusReportBody(input);
  } else {
    throw new Error(`Unknown type: ${type}`);
  }

  const company = input.company || {};
  const phone = present(pick(company, ["phone", "companyPhone", "company_phone"])) || "+966 57 937 3003";
  const address = present(pick(company, ["address", "companyAddress", "company_address"])) || "Sari St, Ar Rawdah, Jeddah 23435";
  const contactLines = [`Phone: ${phone}`, `Address: ${address}`, "www.darelwasl.com"];

  return `<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>${escapeHtml(title)}</title>
    <style>
      ${baseStyles()}
    </style>
  </head>
  <body>
    <div class="page">
      ${headerHtml({ logoSvg, contactLines })}
      ${titleBlockHtml({ title, metaLines })}
      ${body}
      <div class="footer">
        <div>Official Dar El Wasl document.</div>
        <div class="right">Generated ${escapeHtml(gen)}</div>
      </div>
    </div>
  </body>
</html>`;
}

async function run() {
  const args = parseArgs(process.argv.slice(2));
  const type = args.type;
  const inputPath = args.input;
  const outPath = args.out;
  if (!type || !inputPath || !outPath) {
    throw new Error("Usage: documents-pdf.js --type <proposal|invoice|receipt|status-report> --input <input.json> --out <out.pdf>");
  }

  const input = JSON.parse(fs.readFileSync(inputPath, "utf8"));
  const repoRoot = path.resolve(__dirname, "..");
  const logoPath = path.join(repoRoot, "public", "logo.svg");
  const logoSvg = fs.existsSync(logoPath) ? fs.readFileSync(logoPath, "utf8") : null;

  const html = buildHtml(type, input, { logoSvg });
  fs.mkdirSync(path.dirname(outPath), { recursive: true });

  const browser = await chromium.launch({ headless: true });
  try {
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.setContent(html, { waitUntil: "load" });
    if (typeof page.emulateMedia === "function") {
      await page.emulateMedia({ media: "screen" });
    }
    await page.pdf({
      path: outPath,
      format: "A4",
      printBackground: true,
      margin: { top: "18mm", right: "14mm", bottom: "16mm", left: "14mm" },
    });
    await page.close();
    await context.close();
  } finally {
    await browser.close();
  }
}

run().catch((err) => {
  console.error(err && err.stack ? err.stack : err);
  process.exit(1);
});

#!/usr/bin/env node

/**
 * Render a professional Account Statement PDF (A4) from JSON input.
 *
 * Usage:
 *   node scripts/account-statement-pdf.js --input <input.json> --out <out.pdf>
 *
 * Notes:
 * - Uses Playwright Chromium (already used elsewhere in this repo).
 * - Keeps layout deterministic: no remote assets, no external fonts.
 */

const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

const REPO_ROOT = path.resolve(__dirname, "..");

function isTruthyEnv(v) {
  const s = String(v || "").trim().toLowerCase();
  return s === "1" || s === "true" || s === "yes" || s === "on";
}

function labSessionName() {
  const prefix = String(process.env.DW_TMUX_PREFIX || "codex").trim() || "codex";
  const n = String(process.env.DW_LAB_SESSION || "7").trim() || "7";
  return `${prefix}${n}`;
}

function labOutboxDir() {
  const base = String(process.env.DW_LAB_DIR || path.join(REPO_ROOT, "tmp", "lab")).trim();
  return path.join(base, labSessionName(), "outbox");
}

function uniqueCopyDest(dir, filename) {
  const base = path.basename(filename);
  const ext = path.extname(base);
  const stem = ext ? base.slice(0, -ext.length) : base;
  let candidate = base;
  let idx = 1;
  // eslint-disable-next-line no-constant-condition
  while (true) {
    const dest = path.join(dir, candidate);
    if (!fs.existsSync(dest)) return dest;
    candidate = `${stem}-${idx}${ext}`;
    idx += 1;
  }
}

function maybePublishToLabOutbox(outPath) {
  if (!isTruthyEnv(process.env.DW_LAB_AUTO_OUTBOX)) return null;
  try {
    const outbox = labOutboxDir();
    fs.mkdirSync(outbox, { recursive: true });

    const src = path.resolve(outPath);
    const outboxAbs = path.resolve(outbox);
    if (src.startsWith(outboxAbs + path.sep)) return null;

    const dest = uniqueCopyDest(outbox, path.basename(outPath));
    fs.copyFileSync(src, dest);
    return dest;
  } catch (e) {
    console.warn("Lab outbox publish failed:", e && e.message ? e.message : e);
    return null;
  }
}

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

  if (/^\\d{4}-\\d{2}-\\d{2}$/.test(raw)) {
    const date = new Date(`${raw}T00:00:00Z`);
    return Number.isNaN(date.getTime()) ? raw : formatDateOnly(date);
  }

  const date = new Date(raw);
  if (Number.isNaN(date.getTime())) return raw;

  const hasTime = /T\\d{2}:\\d{2}/.test(raw) || /\\d{2}:\\d{2}/.test(raw);
  if (!hasTime) return formatDateOnly(date);
  if (isMidnightInTz(date)) return formatDateOnly(date);
  return formatDateTime(date);
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

function formatMoney(amount, currency) {
  const n = safeNumber(amount);
  if (n === null) return "—";
  const rounded = Math.round(n * 100) / 100;
  const parts = rounded.toFixed(2).split(".");
  parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  const c = present(currency) || "";
  return c ? `${parts[0]}${parts[1] === "00" ? "" : `.${parts[1]}`} ${c}` : `${parts[0]}${parts[1] === "00" ? "" : `.${parts[1]}`}`;
}

function escapeHtml(s) {
  return String(s || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function buildHtml(input, { logoSvg }) {
  const title = present(input.title) || "ACCOUNT STATEMENT";
  const companyName = present(input.companyName) || "—";
  const date = formatDateValue(input.date);
  const clientName = present(input.clientName) || "—";
  const currency = present(input.currency) || "SAR";

  const totalContract = safeNumber(input.totalContractAmount);
  const totalReceived = safeNumber(input.totalAmountReceived);
  const outstanding = safeNumber(input.outstandingBalance);
  const computedOutstanding =
    outstanding !== null ? outstanding : totalContract !== null && totalReceived !== null ? totalContract - totalReceived : null;

  const payments = Array.isArray(input.payments) ? input.payments : [];
  const remarks = present(input.remarks);
  const statementId = present(input.statementId);

  const paymentRows = payments.length
    ? payments
        .map((p) => {
          const rowDate = formatDateValue(p.date);
          const desc = present(p.description) || "—";
          const amount = safeNumber(p.amount);
          const mode = present(p.mode) || "—";
          const status = present(p.status) || "—";
          return `
            <tr>
              <td class="cell-date">${escapeHtml(rowDate)}</td>
              <td class="cell-desc">${escapeHtml(desc)}</td>
              <td class="cell-amt">${escapeHtml(formatMoney(amount, currency))}</td>
              <td class="cell-mode">${escapeHtml(mode)}</td>
              <td class="cell-status">${escapeHtml(status)}</td>
            </tr>
          `;
        })
        .join("\n")
    : `
        <tr>
          <td class="cell-date">—</td>
          <td class="cell-desc">No payments listed.</td>
          <td class="cell-amt">—</td>
          <td class="cell-mode">—</td>
          <td class="cell-status">—</td>
        </tr>
      `;

  const logoMarkup = logoSvg
    ? `<div class="logo" aria-hidden="true">${logoSvg}</div>`
    : `<div class="logo-fallback" aria-hidden="true"></div>`;

  const contactLines = ["Phone: +966 57 937 3003", "Address: Sari St, Ar Rawdah, Jeddah 23435", "www.darelwasl.com"];

  return `<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>${escapeHtml(title)}</title>
    <style>
      :root {
        --ink: #0f172a;
        --muted: #475569;
        --border: #e2e8f0;
        --bg: #ffffff;
        --panel: #f8fafc;
        --accent: #1F2147;
        --success: #15803d;
        --warning: #b45309;
        --danger: #b91c1c;
        --logo-width: 320px;
      }

      * { box-sizing: border-box; }
      html, body { margin: 0; padding: 0; background: var(--bg); color: var(--ink); font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif; }

      .page {
        padding: 42px 44px;
      }

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
      .title { margin: 0; font-size: 20px; letter-spacing: 1px; font-weight: 800; color: var(--ink); }
      .meta-line { margin: 6px 0 0; color: var(--muted); font-size: 12px; }

      .contact {
        text-align: right;
        color: var(--muted);
        font-size: 11px;
        line-height: 1.35;
        font-weight: 400;
        white-space: pre-line;
      }

      .section {
        margin-top: 18px;
      }

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

      .kv {
        display: grid;
        grid-template-columns: 1fr;
        gap: 6px;
      }
      .kv-row {
        display: flex;
        align-items: baseline;
        justify-content: space-between;
        gap: 12px;
      }
      .kv-key { color: var(--muted); font-size: 12px; }
      .kv-val { font-weight: 600; font-size: 13px; color: var(--ink); text-align: right; }

      .summary-grid {
        display: grid;
        grid-template-columns: repeat(3, 1fr);
        gap: 10px;
      }
      .card {
        background: #fff;
        border: 1px solid var(--border);
        border-radius: 12px;
        padding: 12px 12px;
      }
      .card .label { color: var(--muted); font-size: 11px; letter-spacing: 0.8px; text-transform: uppercase; margin: 0; }
      .card .value { margin: 8px 0 0; font-size: 16px; font-weight: 800; color: var(--ink); }

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
      .cell-date { width: 92px; }
      .cell-mode { width: 88px; }
      .cell-status { width: 88px; }

      .remarks {
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

      @media print {
        .page { padding: 0; }
      }
    </style>
  </head>
  <body>
    <div class="page">
      <div class="header">
        <div class="brand">${logoMarkup}</div>
        <div class="contact">${escapeHtml(contactLines.join("\n"))}</div>
      </div>

      <div class="doc-title-block">
        <h1 class="title">${escapeHtml(title)}</h1>
        <p class="meta-line"><strong>Date:</strong> ${escapeHtml(date)}</p>
        ${statementId ? `<p class="meta-line"><strong>Statement ID:</strong> ${escapeHtml(statementId)}</p>` : ``}
      </div>

      <div class="section">
        <p class="section-title">Parties</p>
        <div class="panel">
          <div class="kv">
            <div class="kv-row">
              <div class="kv-key">Company</div>
              <div class="kv-val">${escapeHtml(companyName)}</div>
            </div>
            <div class="kv-row">
              <div class="kv-key">Client Name</div>
              <div class="kv-val">${escapeHtml(clientName)}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="section">
        <p class="section-title">Financial Summary</p>
        <div class="summary-grid">
          <div class="card">
            <p class="label">Total Contract Amount</p>
            <p class="value">${escapeHtml(formatMoney(totalContract, currency))}</p>
          </div>
          <div class="card">
            <p class="label">Total Amount Received</p>
            <p class="value">${escapeHtml(formatMoney(totalReceived, currency))}</p>
          </div>
          <div class="card">
            <p class="label">Outstanding Balance</p>
            <p class="value">${escapeHtml(formatMoney(computedOutstanding, currency))}</p>
          </div>
        </div>
      </div>

      <div class="section">
        <p class="section-title">Payment Details</p>
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Description</th>
              <th style="text-align:right">Amount (${escapeHtml(currency)})</th>
              <th>Mode</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            ${paymentRows}
          </tbody>
        </table>
      </div>

      ${remarks ? `<div class="section"><p class="section-title">Remarks</p><div class="remarks">${escapeHtml(remarks)}</div></div>` : ``}

      <div class="footer">
        <div>Official Dar El Wasl document.</div>
        <div class="right">Generated ${escapeHtml(formatDateValue(new Date().toISOString()))}</div>
      </div>
    </div>
  </body>
</html>`;
}

async function run() {
  const args = parseArgs(process.argv.slice(2));
  const inputPath = args.input;
  const outPath = args.out;
  if (!inputPath || !outPath) {
    throw new Error("Usage: account-statement-pdf.js --input <input.json> --out <out.pdf>");
  }

  const input = JSON.parse(fs.readFileSync(inputPath, "utf8"));
  const logoPath = path.join(REPO_ROOT, "public", "logo.svg");
  const logoSvg = fs.existsSync(logoPath) ? fs.readFileSync(logoPath, "utf8") : null;

  const html = buildHtml(input, { logoSvg });

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
    const published = maybePublishToLabOutbox(outPath);
    if (published) {
      console.log(`[lab] published to outbox: ${published}`);
    }
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

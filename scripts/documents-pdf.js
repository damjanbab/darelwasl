#!/usr/bin/env node

/**
 * Render PDFs for:
 * - company-profile
 * - proposal
 * - consultation
 * - invoice
 * - receipt
 * - status-report
 * - agreement
 *
 * Usage:
 *   node scripts/documents-pdf.js --type company-profile --input <input.json> --out <out.pdf>
 */

const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

const TEMPLATE_VERSION = "pdf-v3-2026-02-07";
const REPO_ROOT = path.resolve(__dirname, "..");

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

function formatMonthValue(v) {
  const raw = present(v);
  if (!raw) return "—";
  if (raw === "—") return raw;
  if (/^\d{4}-\d{2}$/.test(raw)) {
    const date = new Date(`${raw}-01T00:00:00Z`);
    if (Number.isNaN(date.getTime())) return raw;
    return new Intl.DateTimeFormat("en-GB", {
      timeZone: DEFAULT_TZ,
      month: "short",
      year: "numeric",
    }).format(date);
  }
  return raw;
}

function escapeHtml(s) {
  return String(s || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function inlineFormat(text) {
  const escaped = escapeHtml(text || "");
  const bolded = escaped.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
  const coded = bolded.replace(/`(.+?)`/g, '<span class="mono">$1</span>');
  return coded;
}

function termsToHtml(raw) {
  const lines = String(raw || "").split(/\r?\n/);
  const out = [];
  let inList = false;
  let paragraph = [];

  function flushParagraph() {
    if (!paragraph.length) return;
    out.push(`<p>${inlineFormat(paragraph.join(" "))}</p>`);
    paragraph = [];
  }

  function closeList() {
    if (!inList) return;
    out.push("</ul>");
    inList = false;
  }

  for (const line of lines) {
    const trimmed = String(line || "").trimEnd();
    const t = trimmed.trim();
    if (!t) {
      flushParagraph();
      closeList();
      continue;
    }

    if (t.startsWith("# ")) {
      flushParagraph();
      closeList();
      out.push(`<h2>${inlineFormat(t.slice(2).trim())}</h2>`);
      continue;
    }
    if (t.startsWith("## ")) {
      flushParagraph();
      closeList();
      out.push(`<h3>${inlineFormat(t.slice(3).trim())}</h3>`);
      continue;
    }
    if (t.startsWith("### ")) {
      flushParagraph();
      closeList();
      out.push(`<h4>${inlineFormat(t.slice(4).trim())}</h4>`);
      continue;
    }

    if (/^[-•]\s+/.test(t)) {
      flushParagraph();
      if (!inList) {
        out.push("<ul>");
        inList = true;
      }
      out.push(`<li>${inlineFormat(t.replace(/^[-•]\s+/, ""))}</li>`);
      continue;
    }

    // Simple numbered items (render as paragraph, keep the number visible)
    if (/^\d+[\)\.]\s+/.test(t)) {
      flushParagraph();
      closeList();
      out.push(`<p class="clause">${inlineFormat(t)}</p>`);
      continue;
    }

    paragraph.push(t);
  }

  flushParagraph();
  closeList();
  return out.join("\n");
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
      --accent-2: #2563eb;
      --accent-3: #0ea5e9;
      --accent-soft: rgba(31, 33, 71, 0.12);
      --logo-width: 320px;
      --success: #16a34a;
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

    .section { margin-top: 18px; break-inside: avoid; page-break-inside: avoid; }
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
      break-inside: avoid;
      page-break-inside: avoid;
    }

    .two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; break-inside: avoid; page-break-inside: avoid; }
    .callout {
      background: #fff;
      border: 1px solid var(--border);
      border-left: 4px solid var(--accent);
      border-radius: 12px;
      padding: 12px 14px;
      color: var(--ink);
      font-size: 12px;
      line-height: 1.45;
      break-inside: avoid;
      page-break-inside: avoid;
    }
    .callout strong { font-weight: 800; }

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

    .contract-terms {
      margin-top: 10px;
      padding: 14px 16px;
      background: #fff;
      border: 1px solid var(--border);
      border-radius: 12px;
      color: var(--ink);
      font-size: 12px;
      line-height: 1.55;
    }
    .contract-terms h2 {
      margin: 0 0 10px;
      font-size: 16px;
      letter-spacing: 0.6px;
    }
    .contract-terms h3 {
      margin: 14px 0 6px;
      font-size: 13px;
    }
    .contract-terms h4 {
      margin: 12px 0 6px;
      font-size: 12px;
    }
    .contract-terms p { margin: 0 0 8px; }
    .contract-terms p.clause { margin: 0 0 8px; font-weight: 600; }
    .contract-terms ul {
      margin: 0 0 10px;
      padding-left: 18px;
    }
    .contract-terms li { margin: 4px 0; }

    .signature-grid {
      margin-top: 10px;
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 14px;
    }
    .sig {
      background: #fff;
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 12px 14px;
    }
    .sig-title { margin: 0 0 8px; font-weight: 800; letter-spacing: 0.4px; }
    .sig-line { margin: 6px 0; color: var(--muted); }
    .sig-line strong { color: var(--ink); }

    .list {
      margin: 10px 0 0;
      padding: 10px 12px 10px 28px;
      background: #fff;
      border: 1px dashed var(--border);
      border-radius: 12px;
      color: var(--ink);
      font-size: 12px;
      line-height: 1.45;
    }
    .list li { margin: 4px 0; }

    .page-break { break-before: page; page-break-before: always; }

    .dw-stepper {
      margin-top: 14px;
      display: grid;
      grid-template-columns: repeat(5, 1fr);
      gap: 8px;
      align-items: center;
    }
    .dw-step {
      background: #fff;
      border: 1px solid var(--border);
      border-radius: 999px;
      padding: 8px 10px;
      text-align: center;
      color: var(--muted);
      font-size: 10.5px;
      font-weight: 900;
      letter-spacing: 0.7px;
      text-transform: uppercase;
      position: relative;
      overflow: hidden;
    }
    .dw-step .n { font-variant-numeric: tabular-nums; }
    .dw-step.is-on {
      color: var(--accent);
      border-color: rgba(31, 33, 71, 0.18);
      background: linear-gradient(180deg, rgba(31, 33, 71, 0.06), rgba(14, 165, 233, 0.06));
    }

    /* Infographics (proposal sections) */
    .infographic { margin-top: 18px; break-inside: avoid; page-break-inside: avoid; }

    .cover {
      position: relative;
      padding: 22px 22px 18px;
      border-radius: 18px;
      border: 1px solid var(--border);
      background: linear-gradient(135deg, rgba(31, 33, 71, 0.06), rgba(14, 165, 233, 0.06));
      overflow: hidden;
      min-height: 540px;
      break-inside: avoid;
      page-break-inside: avoid;
    }
    .cover.cover--inline {
      min-height: auto;
      padding: 18px 18px 16px;
    }
    .cover::before {
      content: "";
      position: absolute;
      inset: -20%;
      background: radial-gradient(circle at 15% 25%, rgba(31, 33, 71, 0.18), rgba(31, 33, 71, 0) 55%),
                  radial-gradient(circle at 85% 70%, rgba(14, 165, 233, 0.18), rgba(14, 165, 233, 0) 55%);
      filter: blur(0px);
    }
    .cover::after {
      content: "";
      position: absolute;
      right: -16px;
      bottom: -10px;
      width: 68%;
      height: 72%;
      opacity: 0.12;
      background-repeat: no-repeat;
      background-size: contain;
      background-position: right bottom;
    }
    .cover.has-bg::after { background-image: var(--cover-bg); }

    .cover-body { position: relative; z-index: 1; display: grid; grid-template-rows: auto 1fr auto; height: 100%; }
    .cover.cover--inline .cover-body { grid-template-rows: auto auto; height: auto; }

    .cover-title { margin: 6px 0 0; font-size: 40px; letter-spacing: 1.2px; font-weight: 900; color: var(--accent); }
    .cover.cover--inline .cover-title { font-size: 30px; letter-spacing: 1px; }
    .cover-subtitle { margin: 10px 0 0; font-size: 14px; color: var(--muted); line-height: 1.5; max-width: 520px; }
    .cover.cover--inline .cover-subtitle { margin-top: 8px; max-width: 640px; }
    .cover-chip-row { margin-top: 14px; display: flex; flex-wrap: wrap; gap: 8px; }
    .cover.cover--inline .cover-chip-row { margin-top: 10px; }
    .chip {
      background: #fff;
      border: 1px solid var(--border);
      border-left: 4px solid var(--accent-3);
      border-radius: 999px;
      padding: 7px 10px;
      font-size: 11px;
      color: var(--ink);
      font-weight: 700;
      letter-spacing: 0.3px;
    }
    .cover-kv { margin-top: 18px; display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .cover.cover--inline .cover-kv { margin-top: 14px; }
    .kv-card {
      background: rgba(255,255,255,0.9);
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 12px 12px;
    }
    .kv-card .k { color: var(--muted); font-size: 11px; text-transform: uppercase; letter-spacing: 0.9px; margin: 0; }
    .kv-card .v { margin: 8px 0 0; font-size: 14px; font-weight: 800; color: var(--ink); }

    .icon-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; break-inside: avoid; page-break-inside: avoid; }
    .icon-tile {
      background: #fff;
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 12px 12px;
      display: grid;
      grid-template-columns: 34px 1fr;
      gap: 10px;
      align-items: start;
      min-height: 82px;
      break-inside: avoid;
      page-break-inside: avoid;
    }
    .icon {
      width: 34px;
      height: 34px;
      border-radius: 12px;
      background: rgba(31, 33, 71, 0.06);
      border: 1px solid rgba(31, 33, 71, 0.12);
      display: grid;
      place-items: center;
      overflow: hidden;
    }
    .icon svg { width: 22px; height: 22px; }
    .icon-tile .h { margin: 1px 0 0; font-size: 12px; font-weight: 900; color: var(--ink); }
    .icon-tile .d { margin: 6px 0 0; font-size: 11px; color: var(--muted); line-height: 1.35; }

    .flow {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 10px;
      align-items: stretch;
      break-inside: avoid;
      page-break-inside: avoid;
    }
    .flow-step {
      background: #fff;
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 12px 12px;
      position: relative;
      min-height: 92px;
      break-inside: avoid;
      page-break-inside: avoid;
    }
    .flow-step:not(:last-child)::after {
      content: "";
      position: absolute;
      top: 50%;
      right: -12px;
      width: 24px;
      height: 2px;
      background: var(--accent-soft);
    }
    .flow-step .n {
      width: 22px;
      height: 22px;
      border-radius: 999px;
      background: rgba(14, 165, 233, 0.12);
      border: 1px solid rgba(14, 165, 233, 0.24);
      display: grid;
      place-items: center;
      font-size: 11px;
      font-weight: 900;
      color: var(--accent);
    }
    .flow-step .t { margin: 10px 0 0; font-size: 12px; font-weight: 900; color: var(--ink); }
    .flow-step .p { margin: 6px 0 0; font-size: 11px; color: var(--muted); line-height: 1.35; }

    .ms-graphic { margin: 10px 0 12px; }
    .ms-bar {
      height: 12px;
      border-radius: 999px;
      overflow: hidden;
      display: flex;
      border: 1px solid var(--border);
      background: #fff;
    }
    .ms-seg { height: 100%; }
    .ms-legend {
      margin-top: 10px;
      display: grid;
      gap: 8px;
    }
    .ms-legend-row {
      display: grid;
      grid-template-columns: 12px 1fr auto;
      gap: 10px;
      align-items: center;
      font-size: 11px;
      color: var(--muted);
    }
    .ms-legend-row .dot { width: 10px; height: 10px; border-radius: 999px; }
    .ms-legend-row .l { color: var(--ink); font-weight: 700; }
    .ms-legend-row .v { font-variant-numeric: tabular-nums; }

    .meter {
      margin-top: 10px;
      display: flex;
      gap: 6px;
      align-items: center;
    }
    .meter-seg {
      height: 10px;
      flex: 1;
      border-radius: 999px;
      border: 1px solid rgba(226, 232, 240, 0.9);
      background: rgba(71, 85, 105, 0.16);
    }
    .meter-seg.is-on {
      background: linear-gradient(90deg, rgba(14, 165, 233, 0.85), rgba(11, 120, 181, 0.85));
      border-color: rgba(14, 165, 233, 0.35);
    }
    .meter-label {
      margin-top: 8px;
      font-size: 11px;
      color: var(--muted);
    }

    .about {
      display: grid;
      gap: 10px;
      margin-top: 10px;
    }
    .about-line {
      display: grid;
      grid-template-columns: 26px 1fr;
      gap: 10px;
      align-items: start;
      background: #fff;
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 10px 12px;
      color: var(--muted);
      font-size: 11.5px;
      line-height: 1.4;
    }
    .about-line strong { color: var(--ink); }

    .note-box {
      background: #fff;
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 12px 14px;
      min-height: 160px;
      position: relative;
      overflow: hidden;
    }
    .note-box::before {
      content: "";
      position: absolute;
      inset: 0;
      background: repeating-linear-gradient(
        to bottom,
        transparent,
        transparent 20px,
        rgba(148, 163, 184, 0.35) 21px
      );
      pointer-events: none;
    }
    .note-box .hint {
      position: relative;
      color: rgba(71, 85, 105, 0.85);
      font-size: 11px;
      margin: 0 0 10px;
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
      align-items: center;
    }
    .footer .right { text-align: right; }
    .qr {
      width: 88px;
      height: 88px;
      border: 1px solid var(--border);
      border-radius: 10px;
      padding: 6px;
      background: #fff;
      display: inline-block;
    }
    .qr img { width: 100%; height: 100%; display: block; }
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

function splitLines(raw) {
  const s = present(raw);
  if (!s) return [];
  return s
    .replace(/\r\n/g, "\n")
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l.length);
}

function renderAsListOrProse(raw) {
  const lines = splitLines(raw);
  const bullets = lines
    .map((l) => {
      if (l.startsWith("• ")) return l.slice(2).trim();
      if (l.startsWith("- ")) return l.slice(2).trim();
      return null;
    })
    .filter((v) => present(v));

  if (bullets.length >= 2 && bullets.length >= Math.min(6, Math.floor(lines.length * 0.6))) {
    return `<ul class="list">${bullets.map((b) => `<li>${escapeHtml(b)}</li>`).join("\n")}</ul>`;
  }
  return `<div class="prose">${escapeHtml(present(raw) || "—")}</div>`;
}

function tryParseJson(raw) {
  const s = present(raw);
  if (!s) return null;
  const first = s.trim()[0];
  if (first !== "{" && first !== "[") return null;
  try {
    return JSON.parse(s);
  } catch (_e) {
    return null;
  }
}

function loadPublicSvg(name) {
  const safe = present(name);
  if (!safe) return null;
  const p = path.join(REPO_ROOT, "public", "images", `${safe}.svg`);
  try {
    if (!fs.existsSync(p)) return null;
    return fs.readFileSync(p, "utf8");
  } catch (_e) {
    return null;
  }
}

function svgToCssDataUrl(svg) {
  const s = present(svg);
  if (!s) return null;
  const encoded = encodeURIComponent(s)
    .replace(/%0A/g, "")
    .replace(/%0D/g, "")
    .replace(/%09/g, " ")
    .replace(/%20/g, " ");
  return `url("data:image/svg+xml,${encoded}")`;
}

function normalizeSections(raw) {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw;
  const parsed = tryParseJson(raw);
  return Array.isArray(parsed) ? parsed : [];
}

function deriveHighlights(input) {
  const parsed = tryParseJson(input && input.servicesIncluded);
  const deliverables = parsed && Array.isArray(parsed.deliverables) ? parsed.deliverables.map(present).filter(Boolean) : [];
  return deliverables.slice(0, 3);
}

function renderHeroSection(section, input) {
  const layout = present(section.layout) || "cover";
  const inline = layout === "inline" || layout === "compact" || layout === "header";

  const title = present(section.title) || "BUSINESS SETUP PROPOSAL";
  const subtitle = present(section.subtitle) || "Prepared for your business setup and compliance journey in Saudi Arabia.";
  const chips = Array.isArray(section.chips) ? section.chips.map(present).filter(Boolean) : deriveHighlights(input);
  const bgSvgName = present(section.backgroundSvg) || "saudi-hero";
  const bgSvg = loadPublicSvg(bgSvgName);
  const bgUrl = svgToCssDataUrl(bgSvg);

  const clientName = present(input && input.client && input.client.name) || "—";
  const clientEmail = present(input && input.client && input.client.email);
  const clientPhone = present(input && input.client && input.client.phone);

  const companyName = present(input && input.company && input.company.name) || "Dar El Wasl";
  const companyEmail = present(input && input.company && input.company.email);
  const companyPhone = present(input && input.company && input.company.phone);

  const refLine = present(section.refLine) || "Premium proposal · Task-based execution · Clear milestones";
  const issuedAt = formatDateValue(present(input && (input.issuedAt || input.generatedAt)) || new Date().toISOString());

  const kv = Array.isArray(section.kv) ? section.kv : null;
  const hideKv = section && section.hideKv === true;
  const kvHtml =
    hideKv
      ? ""
      : kv && kv.length
      ? `<div class="cover-kv">
          ${kv
            .slice(0, 2)
            .map((card) => {
              const k = present(card && card.k) || "—";
              const v = card && card.v;
              const lines = Array.isArray(v) ? v.map(present).filter(Boolean) : [present(v)].filter(Boolean);
              return `<div class="kv-card"><p class="k">${escapeHtml(k)}</p><p class="v">${lines.length ? lines.map(escapeHtml).join("<br/>") : "—"}</p></div>`;
            })
            .join("")}
        </div>`
      : `<div class="cover-kv">
          <div class="kv-card">
            <p class="k">Prepared For</p>
            <p class="v">${escapeHtml(clientName)}${clientEmail ? `<br/>${escapeHtml(clientEmail)}` : ""}${clientPhone ? `<br/>${escapeHtml(clientPhone)}` : ""}</p>
          </div>
          <div class="kv-card">
            <p class="k">Prepared By</p>
            <p class="v">${escapeHtml(companyName)}${companyEmail ? `<br/>${escapeHtml(companyEmail)}` : ""}${companyPhone ? `<br/>${escapeHtml(companyPhone)}` : ""}<br/>${escapeHtml(issuedAt)}</p>
          </div>
        </div>`;

  return `
    <div class="infographic">
      <div class="cover ${inline ? "cover--inline" : ""} ${bgUrl ? "has-bg" : ""}" style="${bgUrl ? `--cover-bg:${bgUrl};` : ""}">
        <div class="cover-body">
          <div>
            <div class="meta-line"><strong>${escapeHtml(companyName)}</strong> · ${escapeHtml(refLine)}</div>
            <h2 class="cover-title">${escapeHtml(title)}</h2>
            <p class="cover-subtitle">${escapeHtml(subtitle)}</p>
            ${chips && chips.length ? `<div class="cover-chip-row">${chips.map((c) => `<span class="chip">${escapeHtml(c)}</span>`).join("")}</div>` : ""}
          </div>

          ${inline ? "" : `<div></div>`}
          ${kvHtml}
        </div>
      </div>
    </div>
  `;
}

function renderIconGridSection(section) {
  const title = present(section.title) || "What You Receive";
  const items = Array.isArray(section.items) ? section.items : [];
  const tiles = items
    .map((it) => {
      const label = present(it.label) || "—";
      const desc = present(it.desc) || "";
      const iconName = present(it.icon);
      const svg = iconName ? loadPublicSvg(iconName) : null;
      return `
        <div class="icon-tile">
          <div class="icon" aria-hidden="true">${svg ? svg : ""}</div>
          <div>
            <div class="h">${escapeHtml(label)}</div>
            ${desc ? `<div class="d">${escapeHtml(desc)}</div>` : `<div class="d">—</div>`}
          </div>
        </div>
      `;
    })
    .join("\n");

  return `
    <div class="infographic">
      <p class="section-title">${escapeHtml(title)}</p>
      <div class="icon-grid">
        ${tiles || ""}
      </div>
    </div>
  `;
}

function renderProcessFlowSection(section) {
  const title = present(section.title) || "How the Process Works";
  const steps = Array.isArray(section.steps) ? section.steps : [];
  const note = present(section.note);

  const blocks = steps
    .map((s, idx) => {
      const label = present(s.label) || "—";
      const desc = present(s.desc) || "";
      return `
        <div class="flow-step">
          <div class="n">${escapeHtml(String(idx + 1))}</div>
          <div class="t">${escapeHtml(label)}</div>
          ${desc ? `<div class="p">${escapeHtml(desc)}</div>` : `<div class="p">—</div>`}
        </div>
      `;
    })
    .join("\n");

  return `
    <div class="infographic">
      <p class="section-title">${escapeHtml(title)}</p>
      <div class="flow">
        ${blocks || ""}
      </div>
      ${note ? `<p class="meta-line">${escapeHtml(note)}</p>` : ""}
    </div>
  `;
}

function renderSections(sections, input) {
  const items = normalizeSections(sections);
  if (!items.length) return "";
  return items
    .map((s, idx) => {
      const type = present(s.type);
      const html =
        type === "hero"
          ? renderHeroSection(s, input)
          : type === "icon-grid"
          ? renderIconGridSection(s)
          : type === "process-flow"
          ? renderProcessFlowSection(s)
          : "";
      const breakAfter = s && s.pageBreakAfter === true;
      return `${html}${breakAfter && idx !== items.length - 1 ? `<div class="page-break"></div>` : ""}`;
    })
    .join("\n");
}

function renderProgressStepper(activeStep) {
  // Client-facing progress indicator: keeps monotonic "game" feel without exposing internal process terms.
  const steps = ["Consultation", "Proposal", "Agreement", "Execution", "Handover"];
  const n = typeof activeStep === "number" && Number.isFinite(activeStep) ? activeStep : null;
  return `
    <div class="dw-stepper" aria-label="Progress">
      ${steps.map((s, idx) => `<div class="dw-step ${n !== null && idx <= n ? "is-on" : ""}"><span class="n">${escapeHtml(s)}</span></div>`).join("\n")}
    </div>
  `;
}

function docsMeterRank(v) {
  const s = present(v);
  if (!s) return 1;
  if (s === "ready") return 3;
  if (s === "progress") return 2;
  return 1;
}

function renderReadinessMeter(v) {
  const rank = docsMeterRank(v);
  const label = labelDocsStatus(v) || "We’ll guide you";
  return `
    <div>
      <div class="meter" aria-label="Document readiness">
        ${[0, 1, 2].map((i) => `<div class="meter-seg ${i < rank ? "is-on" : ""}"></div>`).join("")}
      </div>
      <div class="meter-label"><strong>Document readiness:</strong> ${escapeHtml(label)}</div>
    </div>
  `;
}

function renderConsultationSnapshot(raw) {
  const parsed = tryParseJson(raw) || {};
  const primaryInterest = labelPrimaryInterest(parsed.primaryInterest) || "To be confirmed today";
  const activities = present(parsed.activities) || "To be confirmed today";
  const ownership = labelOwnership(parsed.ownership) || "To be confirmed today";
  const residency = labelResidency(parsed.residency) || "To be confirmed today";
  const startMonth = formatMonthValue(parsed.startMonth) || "To be confirmed today";
  const preferredLang = labelLang(parsed.preferredLang) || "To be confirmed today";

  const profile = `${ownership} · ${residency}`;
  const timing = `${startMonth} · ${preferredLang}`;

  return `
    <div class="infographic">
      <p class="section-title">Your Setup Snapshot</p>
      <div class="panel">
        <div class="summary-grid" style="grid-template-columns: repeat(2, 1fr);">
          <div class="card"><p class="label">Primary focus</p><p class="value" style="font-size:14px;">${escapeHtml(primaryInterest)}</p></div>
          <div class="card"><p class="label">Timing & language</p><p class="value" style="font-size:14px;">${escapeHtml(timing)}</p></div>
          <div class="card"><p class="label">Activities</p><p class="value" style="font-size:14px;">${escapeHtml(activities)}</p></div>
          <div class="card"><p class="label">Profile</p><p class="value" style="font-size:14px;">${escapeHtml(profile)}</p></div>
        </div>
        <div style="margin-top:10px;">
          ${renderReadinessMeter(parsed.docsStatus)}
        </div>
      </div>
    </div>
  `;
}

function renderConsultationTodayFlow() {
  const section = {
    title: "What happens today",
    steps: [
      { label: "Confirm inputs", desc: "We validate your summary, ownership profile, and timeline." },
      { label: "Agree scope", desc: "We align on deliverables and boundaries." },
      { label: "Set milestones", desc: "We agree fees and the payment schedule." },
      { label: "Issue proposal", desc: "Your official proposal is issued to your portal today." },
    ],
    note: "This pack helps confirm the inputs needed to issue a precise proposal.",
  };
  return renderProcessFlowSection(section);
}

function renderConsultationDeliverables() {
  return renderIconGridSection({
    title: "What you receive today",
    items: [
      { icon: "documents", label: "Official proposal", desc: "Scope and milestones in a clean, shareable PDF." },
      { icon: "process", label: "Clear next steps", desc: "A simple path forward with a monotonic progress feel." },
      { icon: "shield", label: "Secure portal", desc: "Your documents and updates live in a private portal link." },
    ],
  });
}

function renderConsultationBringChecklist() {
  const bullets = [
    "ID/passport copy (if available).",
    "Ownership details (if applicable).",
    "A short description of your activities/services.",
    "Your target timeline (when you want to start).",
    "Hiring plans (if relevant).",
    "Preferred communication channel (email/WhatsApp).",
  ];
  return `
    <div class="infographic">
      <p class="section-title">Prepare (if available)</p>
      <ul class="list">${bullets.map((b) => `<li>${escapeHtml(b)}</li>`).join("\n")}</ul>
      <p class="meta-line">No stress if you don’t have everything today — the goal is to map the path and issue your proposal.</p>
    </div>
  `;
}

function renderConsultationAbout() {
  const lines = [
    { icon: "badge", title: "Premium execution", text: "We run your setup through tasks, documents, and a clear activation plan." },
    { icon: "shield", title: "Transparent delivery", text: "You see progress and official documents as they’re issued — no ambiguity." },
    { icon: "process", title: "Built for speed", text: "Today’s meeting finalizes scope and milestones so the proposal can be issued immediately." },
  ];

  return `
    <div class="infographic">
      <p class="section-title">Why Dar El Wasl</p>
      <div class="about">
        ${lines
          .map((l) => {
            const svg = l.icon ? loadPublicSvg(l.icon) : null;
            return `
              <div class="about-line">
                <div class="icon" aria-hidden="true" style="width:26px;height:26px;border-radius:10px;">${svg ? svg : ""}</div>
                <div><strong>${escapeHtml(l.title)}</strong><br/>${escapeHtml(l.text)}</div>
              </div>
            `;
          })
          .join("\n")}
      </div>
    </div>
  `;
}

function renderWorksheetRow(label, value) {
  const v = present(value);
  const blank = `<span style="display:inline-block;min-width:240px;border-bottom:1px dashed rgba(148,163,184,0.9);height:14px;"></span>`;
  return `
    <div class="kv-row">
      <div class="kv-key">${escapeHtml(label)}</div>
      <div class="kv-val">${v ? escapeHtml(v) : blank}</div>
    </div>
  `;
}

function labelPrimaryInterest(v) {
  const s = present(v);
  if (!s) return null;
  switch (s) {
    case "saudi-setup":
      return "Saudi business setup";
    case "pro-ops":
      return "Operations support";
    case "trademark":
      return "Trademark / IP";
    case "attestation":
      return "Attestation & legalization";
    case "other":
      return "Other / not sure";
    default:
      return s;
  }
}

function labelOwnership(v) {
  const s = present(v);
  if (!s) return null;
  switch (s) {
    case "individual":
      return "Individual";
    case "parent":
      return "Parent company";
    case "gcc":
      return "GCC";
    default:
      return s;
  }
}

function labelResidency(v) {
  const s = present(v);
  if (!s) return null;
  switch (s) {
    case "saudi":
      return "Saudi";
    case "resident":
      return "Resident";
    case "non-resident":
      return "Non-resident";
    default:
      return s;
  }
}

function labelDocsStatus(v) {
  const s = present(v);
  if (!s) return null;
  switch (s) {
    case "ready":
      return "Ready";
    case "progress":
      return "In progress";
    case "unsure":
      return "Not sure";
    default:
      return s;
  }
}

function labelLang(v) {
  const s = present(v);
  if (!s) return null;
  switch (s) {
    case "en":
      return "EN";
    case "ar":
      return "AR";
    case "ur":
      return "UR";
    default:
      return s;
  }
}

function renderConsultationWorksheet(raw) {
  const parsed = tryParseJson(raw);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return "";

  const primaryInterest = labelPrimaryInterest(parsed.primaryInterest);
  const goal = present(parsed.goal);

  return `
    <div class="section">
      <p class="section-title">Proposal Inputs (to confirm)</p>
      <div class="panel">
        <div class="kv">
          ${renderWorksheetRow("Primary service", primaryInterest)}
          ${renderWorksheetRow("Objective", goal)}
          ${renderWorksheetRow("Fee model", null)}
          ${renderWorksheetRow("Payment milestones", null)}
          ${renderWorksheetRow("Target start", formatMonthValue(parsed.startMonth))}
          ${renderWorksheetRow("Preferred language", labelLang(parsed.preferredLang))}
        </div>
      </div>
      <p class="meta-line">Final values will appear in your Proposal document in the portal.</p>
    </div>
  `;
}

function renderConsultationBrief(raw) {
  const parsed = tryParseJson(raw);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    return renderAsListOrProse(raw);
  }

  const primaryInterest = labelPrimaryInterest(parsed.primaryInterest) || "—";
  const activities = present(parsed.activities) || "—";
  const ownership = labelOwnership(parsed.ownership) || "—";
  const residency = labelResidency(parsed.residency) || "—";
  const docsStatus = labelDocsStatus(parsed.docsStatus) || "—";
  const startMonth = formatMonthValue(parsed.startMonth) || "—";
  const preferredLang = labelLang(parsed.preferredLang) || "—";
  const notes = present(parsed.notes);

  return `
    <div class="section">
      <p class="section-title">Consultation Summary</p>
      <div class="panel">
        <div class="kv">
          ${renderBlock("Primary interest", primaryInterest)}
          ${renderBlock("Activities", activities)}
          ${renderBlock("Ownership", ownership)}
          ${renderBlock("Residency", residency)}
          ${renderBlock("Document readiness", docsStatus)}
          ${renderBlock("Target start", startMonth)}
          ${renderBlock("Preferred language", preferredLang)}
        </div>
      </div>
      ${notes ? `<div class="prose">${escapeHtml(notes)}</div>` : ""}
    </div>
  `;
}

function consultationBody(input) {
  const sections = normalizeSections(input && input.sections);
  const hasCover = sections.length && present(sections[0] && sections[0].type) === "hero";

  const client = input.client || {};
  const issuedAt = formatDateValue(present(input && (input.issuedAt || input.generatedAt)) || new Date().toISOString());
  const coverSection = hasCover ? sections[0] : null;

  return `
    ${renderProgressStepper(0)}
    ${
      coverSection
        ? `${renderHeroSection(coverSection, input)}${coverSection.pageBreakAfter ? `<div class="page-break"></div>` : ""}`
        : ""
    }

    ${
      hasCover
        ? ""
        : `<div class="section">
            <div class="two-col">
              <div class="callout">
                Prepared for: ${escapeHtml(client.name || "—")}<br/>
                ${escapeHtml(issuedAt)}
              </div>
              <div class="callout">
                <strong>Today’s consultation</strong><br/>
                We’ll confirm key details and align on scope and milestones. Your proposal is then issued to your portal.
              </div>
            </div>
          </div>`
    }

    ${renderConsultationTodayFlow()}
    ${renderConsultationDeliverables()}
    <div class="page-break"></div>
    ${renderConsultationSnapshot(input.consultationBrief)}
    ${renderConsultationBringChecklist()}
  `;
}

function companyProfileBody(input) {
  return `
    ${renderSections(input && input.sections, input)}
  `;
}

function renderServicesIncluded(raw) {
  const parsed = tryParseJson(raw);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    return renderAsListOrProse(raw);
  }

  const objective = present(parsed.objective);
  const serviceTitle = present(parsed.service && parsed.service.title);
  const deliverables = Array.isArray(parsed.deliverables) ? parsed.deliverables.map(present).filter(Boolean) : [];
  const requirements = Array.isArray(parsed.requirements) ? parsed.requirements.map(present).filter(Boolean) : [];
  const clientNotes = present(parsed.clientNotes);

  return `
    ${objective ? `<div class="callout"><strong>Objective</strong><br/>${escapeHtml(objective)}</div>` : ""}
    ${serviceTitle ? `<p class="meta-line"><strong>Service:</strong> ${escapeHtml(serviceTitle)}</p>` : ""}

    <div class="section">
      <p class="section-title">Scope & Deliverables</p>
      ${
        deliverables.length
          ? `<ul class="list">${deliverables.map((d) => `<li>${escapeHtml(d)}</li>`).join("\n")}</ul>`
          : `<div class="prose">—</div>`
      }
    </div>

    <div class="section">
      <p class="section-title">Client Requirements</p>
      ${
        requirements.length
          ? `<ul class="list">${requirements.map((r) => `<li>${escapeHtml(r)}</li>`).join("\n")}</ul>`
          : `<div class="prose">—</div>`
      }
    </div>

    ${
      clientNotes
        ? `<div class="section"><p class="section-title">Notes</p><div class="prose">${escapeHtml(clientNotes)}</div></div>`
        : ""
    }
  `;
}

function renderPaymentPlan(raw, fallbackCurrency) {
  const parsed = tryParseJson(raw);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    return renderAsListOrProse(raw);
  }

  const currency = present(parsed.currency) || present(fallbackCurrency) || "SAR";
  const validityDays = parsed.validityDays;
  const pricing = parsed.pricing || {};
  const deposit = parsed.deposit || null;
  const milestones = Array.isArray(parsed.milestones) ? parsed.milestones : [];

  const pricingModel = present(pricing.model);
  let pricingLine = "—";
  if (pricingModel === "fixed") {
    pricingLine = formatMoney(pricing.total, currency);
  } else if (pricingModel === "range") {
    pricingLine = `${formatMoney(pricing.min, currency)} – ${formatMoney(pricing.max, currency)}`;
  } else if (pricingModel === "custom") {
    pricingLine = present(pricing["pricing-notes"]) || present(pricing.pricingNotes) || "Custom (see notes)";
  }

  const depositText = (() => {
    if (!deposit) return "—";
    const t = present(deposit.type);
    if (t === "amount") return formatMoney(deposit.value, currency);
    if (t === "percent") return `${safeNumber(deposit.value) ?? deposit.value}%`;
    return "—";
  })();

  const msRows = milestones
    .map((m) => {
      const label = present(m.label) || "—";
      const t = present(m.type);
      const v = safeNumber(m.value);
      const valueText = t === "amount" ? formatMoney(v ?? m.value, currency) : t === "percent" ? `${v ?? m.value}%` : "—";
      return `<tr><td>${escapeHtml(label)}</td><td class="cell-amt">${escapeHtml(valueText)}</td></tr>`;
    })
    .join("\n");

  const graphic = renderPaymentScheduleGraphic({ milestones, pricingModel, pricing, currency });
  const cleanedMilestones = Array.isArray(milestones)
    ? milestones
        .map((m) => ({ type: present(m && m.type), value: safeNumber(m && m.value) }))
        .filter((m) => m.type && (m.value !== null || m.value === 0))
    : [];
  const allPercent = cleanedMilestones.length && cleanedMilestones.every((m) => m.type === "percent");
  const canOmitTable = Boolean(graphic) && allPercent && cleanedMilestones.length <= 6;

  return `
    <div class="section">
      <p class="section-title">Fees</p>
      <div class="panel">
        <div class="kv">
          ${renderBlock("Pricing model", pricingModel || "—")}
          ${renderBlock("Total", pricingLine)}
          ${renderBlock("Deposit", depositText)}
        </div>
      </div>
      ${
        pricingModel === "custom" && (present(pricing["pricing-notes"]) || present(pricing.pricingNotes))
          ? `<div class="prose">${escapeHtml(present(pricing["pricing-notes"]) || present(pricing.pricingNotes))}</div>`
          : ""
      }
    </div>

    <div class="section">
      <p class="section-title">Payment Schedule</p>
      ${graphic}
      ${
        canOmitTable
          ? ""
          : `<table>
              <thead>
                <tr><th>Milestone</th><th style="text-align:right">Amount</th></tr>
              </thead>
              <tbody>
                ${milestones.length ? msRows : `<tr><td>—</td><td class="cell-amt">—</td></tr>`}
              </tbody>
            </table>`
      }
      ${
        Number.isFinite(Number(validityDays)) && Number(validityDays) > 0
          ? `<p class="meta-line"><strong>Validity:</strong> ${escapeHtml(String(validityDays))} days from issue date.</p>`
          : ""
      }
    </div>
  `;
}

function renderPaymentScheduleGraphic({ milestones, pricingModel, pricing, currency }) {
  if (!Array.isArray(milestones) || milestones.length < 2) return "";

  const palette = ["#1F2147", "#2563eb", "#0ea5e9", "#16a34a", "#f59e0b", "#db2777", "#7c3aed"];
  const cleaned = milestones
    .map((m) => ({
      label: present(m && m.label) || "—",
      type: present(m && m.type),
      value: safeNumber(m && m.value),
      raw: m,
    }))
    .filter((m) => m.type && (m.value !== null || present(m.raw && m.raw.value)));

  const hasAny = cleaned.length >= 2;
  if (!hasAny) return "";

  const hasPercent = cleaned.some((m) => m.type === "percent" && m.value !== null);
  const allPercent = cleaned.every((m) => m.type === "percent" && m.value !== null);
  const allAmount = cleaned.every((m) => m.type === "amount" && m.value !== null);

  let segments = [];
  if (allPercent) {
    const total = cleaned.reduce((acc, m) => acc + (m.value || 0), 0);
    if (total <= 0) return "";
    segments = cleaned.map((m) => ({ ...m, pct: Math.max(0, m.value || 0) }));
  } else if (hasPercent && !allAmount) {
    // Mixed percent + amount can't be reliably rendered as a single stacked bar.
    return "";
  } else if (allAmount) {
    const totalFromPricing = pricingModel === "fixed" ? safeNumber(pricing && pricing.total) : null;
    const denom = totalFromPricing && totalFromPricing > 0 ? totalFromPricing : cleaned.reduce((acc, m) => acc + (m.value || 0), 0);
    if (!denom || denom <= 0) return "";
    segments = cleaned.map((m) => ({ ...m, pct: ((m.value || 0) / denom) * 100 }));
  } else {
    return "";
  }

  const normalizedTotal = segments.reduce((acc, s) => acc + (s.pct || 0), 0);
  if (normalizedTotal <= 0) return "";

  const segHtml = segments
    .map((s, idx) => {
      const color = palette[idx % palette.length];
      const width = Math.max(0.5, Math.min(100, (s.pct || 0)));
      return `<div class="ms-seg" style="width:${width}%;background:${color};"></div>`;
    })
    .join("");

  const legendHtml = segments
    .map((s, idx) => {
      const color = palette[idx % palette.length];
      const label = escapeHtml(s.label);
      const valueText =
        s.type === "percent"
          ? `${Math.round((s.value || 0) * 10) / 10}%`
          : s.type === "amount"
          ? formatMoney(s.value, currency)
          : "—";
      return `
        <div class="ms-legend-row">
          <span class="dot" style="background:${color};"></span>
          <span class="l">${label}</span>
          <span class="v">${escapeHtml(valueText)}</span>
        </div>
      `;
    })
    .join("\n");

  return `
    <div class="ms-graphic">
      <div class="ms-bar" aria-hidden="true">${segHtml}</div>
      <div class="ms-legend">${legendHtml}</div>
    </div>
  `;
}

function proposalBody(input) {
  if (input && input.agreement) {
    return agreementBody(input);
  }

  const company = input.company || {};
  const client = input.client || {};
  const currency = present(company.currency) || "SAR";
  const sections = normalizeSections(input && input.sections);

  function parseServicesIncluded(raw) {
    const parsed = tryParseJson(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    return parsed;
  }

  function parseTimeline(tl) {
    if (!tl || typeof tl !== "object" || Array.isArray(tl)) return null;
    const startMonth = present(tl.startMonth || tl.start || tl["start-month"]);
    const endMonth = present(tl.endMonth || tl.end || tl["end-month"]);
    const startLabel = startMonth ? formatMonthValue(startMonth) : null;
    const endLabel = endMonth ? formatMonthValue(endMonth) : null;
    if (startLabel && endLabel && startLabel !== "—" && endLabel !== "—") return `${startLabel} – ${endLabel}`;
    if (startLabel && startLabel !== "—") return startLabel;
    return null;
  }

  function renderProposalDealSnapshot({ serviceTitle, objective, timelineLabel }) {
    const cards = [
      { label: "Primary service", value: serviceTitle || "—" },
      { label: "Timeline", value: timelineLabel || "To be confirmed" },
      { label: "Objective", value: objective || "—" },
      { label: "Delivery model", value: "Tracked execution + portal updates" },
    ];

    return `
      <div class="infographic">
        <p class="section-title">Scope at a glance</p>
        <div class="panel">
          <div class="summary-grid" style="grid-template-columns: repeat(2, 1fr);">
            ${cards
              .map(
                (c) => `
                  <div class="card">
                    <p class="label">${escapeHtml(c.label)}</p>
                    <p class="value" style="font-size:14px;line-height:1.25;">${escapeHtml(c.value)}</p>
                  </div>
                `,
              )
              .join("\n")}
          </div>
        </div>
      </div>
    `;
  }

  function renderBulletSection(title, items) {
    const cleaned = Array.isArray(items) ? items.map(present).filter(Boolean) : [];
    return `
      <div class="section">
        <p class="section-title">${escapeHtml(title)}</p>
        ${
          cleaned.length
            ? `<ul class="list">${cleaned.map((it) => `<li>${escapeHtml(it)}</li>`).join("\n")}</ul>`
            : `<div class="prose">—</div>`
        }
      </div>
    `;
  }

  function renderProposalAssumptions() {
    const bullets = [
      "Third-party / external fees are excluded unless explicitly stated.",
      "Timelines depend on client responsiveness and external review cycles.",
      "Any material scope change will be confirmed before execution.",
    ];
    return `
      <div class="section">
        <p class="section-title">Assumptions</p>
        <ul class="list">${bullets.map((b) => `<li>${escapeHtml(b)}</li>`).join("\n")}</ul>
      </div>
    `;
  }

  function renderProposalNextSteps() {
    const steps = [
      "Confirm proposal scope and milestones.",
      "Sign the agreement to lock delivery terms.",
      "Pay the deposit (if applicable) to start execution.",
    ];
    return `
      <div class="section">
        <p class="section-title">Next steps</p>
        <div class="callout">
          <strong>To start execution</strong><br/>
          <ul style="margin:10px 0 0;padding-left:18px;color:var(--muted);font-size:12px;line-height:1.45;">
            ${steps.map((s) => `<li>${escapeHtml(s)}</li>`).join("\n")}
          </ul>
        </div>
      </div>
    `;
  }

  const services = parseServicesIncluded(input.servicesIncluded);
  const serviceTitle = present(services && services.service && services.service.title) || present(services && services.serviceTitle);
  const objective = present(services && services.objective);
  const deliverables = Array.isArray(services && services.deliverables) ? services.deliverables : [];
  const requirements = Array.isArray(services && services.requirements) ? services.requirements : [];
  const timelineLabel = parseTimeline(input.timeline || services.timeline) || formatMonthValue(services.startMonth) || null;

  return `
    ${renderProgressStepper(1)}
    ${sections.length ? renderSections(sections, input) : ""}

    ${renderProposalDealSnapshot({ serviceTitle, objective, timelineLabel })}

    ${renderBulletSection("Included deliverables", deliverables)}

    ${renderPaymentPlan(input.paymentPlan, currency)}

    ${renderProcessFlowSection({
      title: "Delivery phases",
      steps: [
        { label: "Confirm", desc: "We confirm scope, timeline, and required information." },
        { label: "Prepare", desc: "We prepare drafts and assemble a submission-ready pack." },
        { label: "Review cycles", desc: "We handle questions, corrections, and iterations as needed." },
        { label: "Complete", desc: "We finalize deliverables and hand over next steps." },
      ],
      note: "This is a simplified client view designed to feel consistently progressive.",
    })}

    ${renderBulletSection("Client responsibilities", requirements)}

    ${renderProposalAssumptions()}

    ${renderProposalNextSteps()}
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

function agreementBody(input) {
  const agreement = input.agreement || {};
  const planItems = Array.isArray(input.planItems) ? input.planItems : [];
  const currency = present(pick(agreement, ["agreement/currency", "currency"])) || present(pick(input.company || {}, ["currency"])) || "SAR";

  function deliveryLine() {
    const channels = pick(agreement, ["agreement/delivery-channels", "delivery-channels", "deliveryChannels"]);
    const arr = Array.isArray(channels) ? channels : (typeof channels === "string" ? channels.split(",") : []);
    const cleaned = arr.map((x) => present(x)).filter(Boolean);
    return cleaned.length ? cleaned.join(", ") : null;
  }

  return `
    <div class="section">
      <p class="section-title">Agreement</p>
      <div class="panel">
        <div class="kv">
          ${renderBlock("Agreement #", pick(agreement, ["agreement/number", "number"]) ?? "—")}
          ${renderBlock("Title", pick(agreement, ["agreement/title", "title"]) ?? "—")}
          ${pick(agreement, ["agreement/effective-at", "effective-at", "effectiveAt"]) ? renderBlock("Effective", formatDateValue(pick(agreement, ["agreement/effective-at", "effective-at", "effectiveAt"]))) : ""}
          ${present(pick(agreement, ["agreement/client-company", "client-company", "clientCompany"])) ? renderBlock("Client Company", pick(agreement, ["agreement/client-company", "client-company", "clientCompany"])) : ""}
          ${present(pick(agreement, ["agreement/client-representative", "client-representative", "clientRepresentative"])) ? renderBlock("Client Representative", pick(agreement, ["agreement/client-representative", "client-representative", "clientRepresentative"])) : ""}
          ${present(pick(agreement, ["agreement/our-representative", "our-representative", "ourRepresentative"])) ? renderBlock("Our Representative", pick(agreement, ["agreement/our-representative", "our-representative", "ourRepresentative"])) : ""}
          ${present(pick(agreement, ["agreement/our-recipient", "our-recipient", "ourRecipient"])) ? renderBlock("Recipient", pick(agreement, ["agreement/our-recipient", "our-recipient", "ourRecipient"])) : ""}
          ${pick(agreement, ["agreement/accepted-at", "accepted-at", "acceptedAt"]) ? renderBlock("Accepted", formatDateValue(pick(agreement, ["agreement/accepted-at", "accepted-at", "acceptedAt"]))) : ""}
          ${deliveryLine() ? renderBlock("Delivery", deliveryLine()) : ""}
        </div>
      </div>
    </div>

    <div class="section">
      <p class="section-title">Client</p>
      <div class="panel">
        <div class="kv">
          ${renderBlock("Client Name", (input.client || {}).name)}
          ${(input.client || {}).email ? renderBlock("Email", (input.client || {}).email) : ""}
          ${(input.client || {}).phone ? renderBlock("Phone", (input.client || {}).phone) : ""}
        </div>
      </div>
    </div>

    <div class="section">
      <p class="section-title">Terms</p>
      <div class="contract-terms">${termsToHtml(present(pick(agreement, ["agreement/terms", "terms"])) || "")}</div>
    </div>

    <div class="section">
      <p class="section-title">Signatures</p>
      <div class="signature-grid">
        <div class="sig">
          <p class="sig-title">For Dar El Wasl</p>
          <p class="sig-line"><strong>Name:</strong> ${escapeHtml(present(pick(agreement, ["agreement/our-representative", "our-representative", "ourRepresentative"])) || "—")}</p>
          <p class="sig-line"><strong>Title:</strong> ____________________</p>
          <p class="sig-line"><strong>Signature:</strong> ____________________</p>
          <p class="sig-line"><strong>Date:</strong> ____________________</p>
        </div>
        <div class="sig">
          <p class="sig-title">For the Client</p>
          <p class="sig-line"><strong>Name:</strong> ${escapeHtml(present(pick(agreement, ["agreement/client-representative", "client-representative", "clientRepresentative"])) || (input.client || {}).name || "—")}</p>
          <p class="sig-line"><strong>Title:</strong> ____________________</p>
          <p class="sig-line"><strong>Signature:</strong> ____________________</p>
          <p class="sig-line"><strong>Date:</strong> ____________________</p>
        </div>
      </div>
    </div>

    <div class="section">
      <p class="section-title">Payment Plan</p>
      <table>
        <thead>
          <tr>
            <th>#</th>
            <th>Label</th>
            <th>Due</th>
            <th>Kind</th>
            <th style="text-align:right">Amount (${escapeHtml(currency)})</th>
          </tr>
        </thead>
        <tbody>
          ${
            planItems.length
              ? planItems
                  .map((it, idx) => {
                    const label = pick(it, ["plan.item/label", "label"]) ?? "—";
                    const due = pick(it, ["plan.item/due-at", "due-at", "dueAt"]);
                    const kind = pick(it, ["plan.item/kind", "kind"]);
                    const amt = pick(it, ["plan.item/amount", "amount"]);
                    const cur = present(pick(it, ["plan.item/currency", "currency"])) || currency;
                    return `
                      <tr>
                        <td class="mono">${escapeHtml(String(pick(it, ["plan.item/index", "index"]) ?? idx + 1))}</td>
                        <td>${escapeHtml(label)}</td>
                        <td>${escapeHtml(formatDateValue(due))}</td>
                        <td>${escapeHtml(String(kind ?? "—"))}</td>
                        <td class="cell-amt">${escapeHtml(formatMoney(amt, cur))}</td>
                      </tr>
                    `;
                  })
                  .join("\n")
              : `<tr><td>—</td><td>—</td><td>—</td><td>—</td><td class="cell-amt">—</td></tr>`
          }
        </tbody>
      </table>
    </div>
  `;
}

function buildHtml(type, input, { logoSvg, qrDataUrl }) {
  const templateVersion = present(input.templateVersion) || TEMPLATE_VERSION;
  const issuedAtRaw = present(input.issuedAt) || present(input.generatedAt) || new Date().toISOString();
  const issuedAt = formatDateValue(issuedAtRaw);
  const docRef = present(input.documentRef);
  const verificationCode = present(input.verificationCode);
  const verifyUrl = present(input.verifyUrl);

  let title = "";
  let metaLines = [];
  let body = "";
  let hideTitleBlock = false;

  if (type === "company-profile") {
    title = "COMPANY PROFILE";
    metaLines = [`<strong>Date:</strong> ${escapeHtml(issuedAt)}`];
    const sections = normalizeSections(input && input.sections);
    hideTitleBlock = sections.length && present(sections[0] && sections[0].type) === "hero";
    body = companyProfileBody(input);
  } else if (type === "proposal") {
    title = "PROPOSAL";
    metaLines = [`<strong>Date:</strong> ${escapeHtml(issuedAt)}`];
    const sections = normalizeSections(input && input.sections);
    hideTitleBlock = sections.length && present(sections[0] && sections[0].type) === "hero";
    if (input && input.agreement) {
      const agreementNo = pick(input.agreement, ["agreement/number", "number"]);
      const agreementTitle = pick(input.agreement, ["agreement/title", "title"]);
      if (present(agreementNo)) metaLines.push(`<strong>Agreement #:</strong> ${escapeHtml(agreementNo)}`);
      if (present(agreementTitle)) metaLines.push(`<strong>Title:</strong> ${escapeHtml(agreementTitle)}`);
    }
    body = proposalBody(input);
  } else if (type === "consultation") {
    title = "CONSULTATION PACK";
    metaLines = [`<strong>Date:</strong> ${escapeHtml(issuedAt)}`];
    const sections = normalizeSections(input && input.sections);
    hideTitleBlock = sections.length && present(sections[0] && sections[0].type) === "hero";
    body = consultationBody(input);
  } else if (type === "invoice") {
    title = "INVOICE";
    const inv = input.invoice || {};
    metaLines = [
      `<strong>Date:</strong> ${escapeHtml(issuedAt)}`,
      `<strong>Invoice:</strong> ${escapeHtml(present(pick(inv, ["invoice/number", "number"])) || "—")}`,
    ];
    body = invoiceBody(input);
  } else if (type === "receipt") {
    title = "RECEIPT";
    metaLines = [`<strong>Date:</strong> ${escapeHtml(issuedAt)}`];
    body = receiptBody(input);
  } else if (type === "status-report") {
    title = "STATUS REPORT";
    metaLines = [`<strong>Date:</strong> ${escapeHtml(issuedAt)}`];
    body = statusReportBody(input);
  } else if (type === "agreement") {
    title = "AGREEMENT";
    const agreement = input.agreement || {};
    metaLines = [
      `<strong>Date:</strong> ${escapeHtml(issuedAt)}`,
      `<strong>Agreement:</strong> ${escapeHtml(present(pick(agreement, ["agreement/number", "number"])) || "—")}`,
    ];
    body = agreementBody(input);
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
      ${hideTitleBlock ? "" : titleBlockHtml({ title, metaLines })}
      ${body}
	      <div class="footer">
	        <div>
	          <div>Official Dar El Wasl document.</div>
	          ${qrDataUrl ? `<div class="qr" aria-hidden="true"><img src="${qrDataUrl}" /></div>` : ""}
	        </div>
	        <div class="right">
	          ${docRef ? `Document: <span class="mono">${escapeHtml(docRef)}</span><br/>` : ""}
	          ${verificationCode ? `Verify: <span class="mono">${escapeHtml(verificationCode)}</span><br/>` : ""}
	          ${verifyUrl ? `URL: <span class="mono">${escapeHtml(verifyUrl)}</span><br/>` : ""}
	          Issued ${escapeHtml(issuedAt)} · ${escapeHtml(templateVersion)}
	        </div>
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
    throw new Error("Usage: documents-pdf.js --type <company-profile|consultation|proposal|invoice|receipt|status-report|agreement> --input <input.json> --out <out.pdf>");
  }

  const input = JSON.parse(fs.readFileSync(inputPath, "utf8"));
  let qrDataUrl = null;
  const verifyUrl = present(input.verifyUrl);
  if (verifyUrl) {
    try {
      // Lazy-load so the script still works even if QR is not installed in some environments.
      const QRCode = require("qrcode");
      qrDataUrl = await QRCode.toDataURL(verifyUrl, { width: 220, margin: 0 });
    } catch (e) {
      console.warn("QR generation failed:", e && e.message ? e.message : e);
      qrDataUrl = null;
    }
  }
  const repoRoot = path.resolve(__dirname, "..");
  const logoPath = path.join(repoRoot, "public", "logo.svg");
  const logoSvg = fs.existsSync(logoPath) ? fs.readFileSync(logoPath, "utf8") : null;

  const html = buildHtml(type, input, { logoSvg, qrDataUrl });
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

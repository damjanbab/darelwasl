#!/usr/bin/env node

const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i];
    if (!key.startsWith("--")) continue;
    const value = argv[i + 1];
    args[key.slice(2)] = value;
    i += 1;
  }
  return args;
}

function slugify(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/(^-|-$)/g, "");
}

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

async function isNotFound(page) {
  // Site uses an explicit "Page not found" template.
  const title = await page.title().catch(() => "");
  if (String(title || "").toLowerCase().includes("not found")) return true;
  const h1 = await page.locator("h1").first().innerText().catch(() => "");
  if (String(h1 || "").trim().toLowerCase() === "page not found") return true;
  const bodyText = await page.locator("body").innerText().catch(() => "");
  return String(bodyText || "").includes("No content found at");
}

async function run() {
  const args = parseArgs(process.argv.slice(2));
  const configPath = args.config;
  const outDir = args.out;
  if (!configPath || !outDir) {
    throw new Error("Usage: site-screenshot.js --config <path> --out <dir>");
  }
  const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  const baseUrl = config.baseUrl || "http://127.0.0.1:3200";
  const pages = Array.isArray(config.pages) ? config.pages : [];
  const viewports = Array.isArray(config.viewports) ? config.viewports : [];
  const fullPage = config.fullPage !== false;
  const failOnNotFound = config.failOnNotFound !== false;
  const waitMs = Number.isFinite(config.waitMs) ? config.waitMs : 0;
  ensureDir(outDir);

  const browser = await chromium.launch({ headless: true });
  const items = [];
  try {
    for (const viewport of viewports) {
      const context = await browser.newContext({
        viewport: { width: viewport.width, height: viewport.height },
      });
      const page = await context.newPage();
      for (const entry of pages) {
        const url = new URL(entry.path || "/", baseUrl).toString();
        const response = await page.goto(url, { waitUntil: "networkidle" });
        if (response && typeof response.status === "function") {
          const status = response.status();
          if (status >= 400) {
            throw new Error(`Screenshot failed: HTTP ${status} at ${entry.path}`);
          }
        }
        if (waitMs > 0) {
          await page.waitForTimeout(waitMs);
        }
        if (failOnNotFound && (await isNotFound(page))) {
          throw new Error(`Screenshot failed: page not found at ${entry.path}`);
        }
        const label = slugify(entry.label || entry.path || "page");
        const vp = slugify(viewport.name || `${viewport.width}x${viewport.height}`);
        const filename = `site-${label}-${vp}.png`;
        const filePath = path.join(outDir, filename);
        await page.screenshot({ path: filePath, fullPage });
        items.push({
          path: entry.path,
          label: entry.label,
          viewport: viewport.name || `${viewport.width}x${viewport.height}`,
          file: filename,
          filename,
          slug: `site-${label}-${vp}`,
          mime: "image/png",
        });
      }
      await page.close();
      await context.close();
    }
  } finally {
    await browser.close();
  }

  const manifest = { baseUrl, items };
  fs.writeFileSync(path.join(outDir, config.manifest || "manifest.json"), JSON.stringify(manifest, null, 2));
}

run().catch((err) => {
  console.error(err && err.message ? err.message : err);
  process.exit(1);
});

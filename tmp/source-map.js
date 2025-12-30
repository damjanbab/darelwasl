const { execFileSync } = require("child_process");
const { chromium } = require("playwright");

const BOE_CA = "/home/dami/daral1/tmp/boe-digicert-g2.pem";

const MAX_TIME = "12";

const sources = [
  { id: "uqn.gov.sa", base: "https://uqn.gov.sa/" },
  { id: "boe.gov.sa", base: "https://boe.gov.sa/en/Pages/", cacert: BOE_CA },
  { id: "laws.boe.gov.sa", base: "https://laws.boe.gov.sa/", cacert: BOE_CA },
  { id: "ncar.gov.sa", base: "https://ncar.gov.sa/" },
  { id: "zatca.gov.sa", base: "https://zatca.gov.sa/" },
  { id: "gstc.gov.sa", base: "https://gstc.gov.sa/" },
  { id: "mc.gov.sa", base: "https://mc.gov.sa/" },
  { id: "saudibusiness.gov.sa", base: "https://saudibusiness.gov.sa/" },
  { id: "misa.gov.sa", base: "https://misa.gov.sa/" },
  { id: "investsaudi.sa", base: "https://investsaudi.sa/" },
  { id: "hrsd.gov.sa", base: "https://hrsd.gov.sa/" },
  { id: "qiwa.sa", base: "https://qiwa.sa/ar" },
  { id: "muqeem.sa", base: "https://muqeem.sa/" },
  { id: "absher.sa", base: "https://absher.sa/wps/portal" },
  { id: "www.moi.gov.sa", base: "https://www.moi.gov.sa/wps/portal/Home/" },
  { id: "mudad.com.sa", base: "https://mudad.com.sa/landing-page/home" },
  { id: "rulebook.sama.gov.sa", base: "https://rulebook.sama.gov.sa/" },
  { id: "cma.org.sa", base: "https://cma.org.sa/" },
  { id: "sdaia.gov.sa", base: "https://sdaia.gov.sa/" },
  { id: "nca.gov.sa", base: "https://nca.gov.sa/" },
  { id: "gac.gov.sa", base: "https://gac.gov.sa/" },
  { id: "moj.gov.sa", base: "https://moj.gov.sa/" },
  { id: "najiz.sa", base: "https://najiz.sa/" },
  { id: "safiu.gov.sa", base: "https://safiu.gov.sa/" },
  { id: "momrah.gov.sa", base: "https://momrah.gov.sa/" },
  { id: "rega.gov.sa", base: "https://rega.gov.sa/" },
  { id: "mim.gov.sa", base: "https://mim.gov.sa/" },
  { id: "monshaat.gov.sa", base: "https://monshaat.gov.sa/" },
  { id: "modon.gov.sa", base: "https://modon.gov.sa/" },
  { id: "tga.gov.sa", base: "https://tga.gov.sa/" },
  { id: "gaca.gov.sa", base: "https://gaca.gov.sa/" },
  { id: "mawani.gov.sa", base: "https://mawani.gov.sa/" },
  { id: "mot.gov.sa", base: "https://mot.gov.sa/" },
  { id: "moh.gov.sa", base: "https://moh.gov.sa/" },
  { id: "dga.gov.sa", base: "https://dga.gov.sa/" },
  { id: "ndmo.gov.sa", base: "https://ndmo.gov.sa/" },
  { id: "mof.gov.sa", base: "https://mof.gov.sa/" },
  { id: "etimad.sa", base: "https://etimad.sa/" },
  { id: "cst.gov.sa", base: "https://cst.gov.sa/" },
  { id: "www.sfda.gov.sa", base: "https://www.sfda.gov.sa/" },
  { id: "saso.gov.sa", base: "https://saso.gov.sa/" },
  { id: "www.mewa.gov.sa", base: "https://www.mewa.gov.sa/" },
  { id: "saip.gov.sa", base: "https://saip.gov.sa/" },
  { id: "www.moenergy.gov.sa", base: "https://www.moenergy.gov.sa/" },
  { id: "www.gcc-sg.org", base: "https://www.gcc-sg.org/" },
  { id: "www.oecd.org", base: "https://www.oecd.org/" },
  { id: "data.gov.sa", base: "https://data.gov.sa/" },
  { id: "my.gov.sa", base: "https://my.gov.sa/" },
];

const sourceFilter = process.env.SOURCE_IDS
  ? new Set(process.env.SOURCE_IDS.split(",").map((s) => s.trim()).filter(Boolean))
  : null;

const sitemapCandidates = [
  "/robots.txt",
  "/sitemap.xml",
  "/sitemap_index.xml",
  "/sitemap-index.xml",
  "/sitemap/sitemap.xml",
  "/sitemap.xml.gz",
  "/sitemap_index.xml.gz",
];

function curlFetch(url, opts = {}) {
  const args = [
    "-L",
    "--max-time",
    MAX_TIME,
    "-s",
    "-A",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
    "-H",
    "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "-H",
    "Accept-Language: en-US,en;q=0.9",
    "-H",
    "Cache-Control: no-cache",
    "-H",
    "Pragma: no-cache",
    "--compressed",
  ];
  if (opts.cacert) {
    args.push("--cacert", opts.cacert);
  }
  args.push("-w", "\n__HTTP_STATUS__:%{http_code}\n", url);
  try {
    const out = execFileSync("curl", args, { encoding: "utf8" });
    const idx = out.lastIndexOf("__HTTP_STATUS__:");
    const body = idx >= 0 ? out.slice(0, idx).trimEnd() : "";
    const code = idx >= 0 ? out.slice(idx + "__HTTP_STATUS__:".length).trim() : "000";
    return { ok: true, code, body, method: "curl" };
  } catch (err) {
    return { ok: false, code: "000", body: "", error: err.message, method: "curl" };
  }
}

function curlHead(url, opts = {}) {
  const args = [
    "-I",
    "-L",
    "--max-time",
    MAX_TIME,
    "-s",
    "-A",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
  ];
  if (opts.cacert) {
    args.push("--cacert", opts.cacert);
  }
  args.push("-w", "\n__HTTP_STATUS__:%{http_code}\n", url);
  try {
    const out = execFileSync("curl", args, { encoding: "utf8" });
    const idx = out.lastIndexOf("__HTTP_STATUS__:");
    const body = idx >= 0 ? out.slice(0, idx).trimEnd() : "";
    const code = idx >= 0 ? out.slice(idx + "__HTTP_STATUS__:".length).trim() : "000";
    return { ok: true, code, body, method: "curl-head" };
  } catch (err) {
    return { ok: false, code: "000", body: "", error: err.message, method: "curl-head" };
  }
}

async function playwrightFetch(url, browser) {
  const page = await browser.newPage({
    userAgent:
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
  });
  try {
    const resp = await page.goto(url, { waitUntil: "domcontentloaded", timeout: 30000 });
    const code = resp ? String(resp.status()) : "000";
    const body = await page.content();
    const finalUrl = page.url();
    await page.close();
    return { ok: true, code, body, finalUrl, method: "playwright" };
  } catch (err) {
    await page.close();
    return { ok: false, code: "000", body: "", error: err.message, method: "playwright" };
  }
}

function extractSitemapsFromRobots(robotsText) {
  const lines = robotsText.split(/\r?\n/);
  const sitemaps = [];
  for (const line of lines) {
    const m = line.match(/^\s*Sitemap:\s*(\S+)\s*$/i);
    if (m) sitemaps.push(m[1]);
  }
  return sitemaps;
}

(async () => {
  const results = [];
  const browser = await chromium.launch();
  for (const source of sources) {
    if (sourceFilter && !sourceFilter.has(source.id)) {
      continue;
    }
    const item = {
      id: source.id,
      base: source.base,
      baseCheck: null,
      robots: null,
      sitemaps: [],
      notes: [],
    };

    // Base check
    let baseResp = curlFetch(source.base, { cacert: source.cacert });
    if (["403", "000"].includes(baseResp.code)) {
      const pw = await playwrightFetch(source.base, browser);
      item.baseCheck = { code: pw.code, method: pw.method, error: pw.error, finalUrl: pw.finalUrl };
      if (pw.code === "403") item.notes.push("Base blocked via Playwright");
      if (pw.code === "000") item.notes.push("Base unreachable via Playwright");
    } else {
      item.baseCheck = { code: baseResp.code, method: baseResp.method };
    }

    // Robots
    const robotsUrl = `https://${source.id}/robots.txt`;
    let robotsResp = curlFetch(robotsUrl, { cacert: source.cacert });
    if (["403", "000"].includes(robotsResp.code)) {
      const pw = await playwrightFetch(robotsUrl, browser);
      item.robots = { url: robotsUrl, code: pw.code, method: pw.method, error: pw.error };
      if (pw.code === "403") item.notes.push("robots.txt blocked via Playwright");
      if (pw.code === "000") item.notes.push("robots.txt unreachable via Playwright");
      if (pw.code === "200") {
        const sitemaps = extractSitemapsFromRobots(pw.body || "");
        item.sitemaps.push(...sitemaps.map((url) => ({ url, from: "robots" })));
      }
    } else {
      item.robots = { url: robotsUrl, code: robotsResp.code, method: robotsResp.method };
      if (robotsResp.code === "200") {
        const sitemaps = extractSitemapsFromRobots(robotsResp.body || "");
        item.sitemaps.push(...sitemaps.map((url) => ({ url, from: "robots" })));
      }
    }

    // Sitemap candidates if none found
    if (item.sitemaps.length === 0) {
      for (const path of sitemapCandidates.filter((p) => p !== "/robots.txt")) {
        const candidate = `https://${source.id}${path}`;
        const resp = curlHead(candidate, { cacert: source.cacert });
        if (resp.code === "200") {
          item.sitemaps.push({ url: candidate, from: "common" });
        }
      }
      if (item.sitemaps.length === 0) item.notes.push("No sitemap discovered via robots/common paths");
    }

    results.push(item);
  }
  await browser.close();

  const payload = {
    generatedAt: new Date().toISOString(),
    sources: results,
  };

  console.log(JSON.stringify(payload, null, 2));
})();

#!/usr/bin/env node

const { chromium } = require("playwright");
const fs = require("fs");
const path = require("path");

const BASE_URL = process.env.APP_URL || "http://localhost:3000";
const ARTIFACT_DIR = path.join(__dirname, "..", ".cpcache");
const SCREENSHOT_PATH = path.join(ARTIFACT_DIR, "app-smoke.png");

async function run() {
  fs.mkdirSync(ARTIFACT_DIR, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  try {
    await page.goto(BASE_URL, { waitUntil: "networkidle" });
    await page.waitForSelector("form.login-form", { timeout: 10000 });

    await page.fill("#username", "huda");
    await page.fill("#password", "Damjan1!");
    await page.click('button[type="submit"]');

    await page.waitForSelector(".tasks-layout", { timeout: 15000 });
    await page.waitForSelector(".task-card", { timeout: 15000 });

    const cards = await page.$$(".task-card");
    if (!cards.length) {
      throw new Error("No task cards rendered after login");
    }

    await cards[0].click();
    await page.waitForSelector("#task-title", { timeout: 10000 });
    const title = await page.inputValue("#task-title");

    if (!title || !title.trim()) {
      throw new Error("Task title input is empty after selecting a task");
    }

    await page.waitForSelector(".detail-actions", { timeout: 5000 });

    console.log(`App smoke passed against ${BASE_URL}`);
    await browser.close();
    process.exit(0);
  } catch (err) {
    console.error("App smoke failed:", err.message || err);
    try {
      await page.screenshot({ path: SCREENSHOT_PATH, fullPage: true });
      console.error("Saved failure screenshot to", SCREENSHOT_PATH);
    } catch (_) {
      console.error("Unable to capture screenshot");
    }
    await browser.close();
    process.exit(1);
  }
}

run();

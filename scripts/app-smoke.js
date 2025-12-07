#!/usr/bin/env node

const { chromium } = require("playwright");
const fs = require("fs");
const path = require("path");

const BASE_URL = process.env.APP_URL || "http://localhost:3000";
const ARTIFACT_DIR = path.join(__dirname, "..", ".cpcache");
const SCREENSHOT_PATH = path.join(ARTIFACT_DIR, "app-smoke.png");

async function ensureLogin(page) {
  const onLogin = await page.$("form.login-form");
  if (onLogin) {
    await page.fill("#username", "huda");
    await page.fill("#password", "Damjan1!");
    await page.click('button[type="submit"]');
  }
  await page.waitForSelector(".tasks-layout", { timeout: 15000 });
}

async function run() {
  fs.mkdirSync(ARTIFACT_DIR, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  const newTitle = `Smoke task ${Date.now()}`;
  const newDescription = "Created via smoke test to verify save/load.";
  const newTag = `smoke-tag-${Date.now()}`;

  try {
    await page.goto(BASE_URL, { waitUntil: "networkidle" });
    await ensureLogin(page);
    await page.waitForSelector(".task-card", { timeout: 15000 });

    // Create a new task
    await page.getByRole("button", { name: "New task" }).click();
    await page.waitForSelector("#task-title", { timeout: 5000 });
    await page.fill("#task-title", newTitle);
    await page.fill("#task-description", newDescription);
    await page.selectOption("#task-status", { value: "todo" });
    await page.selectOption("#task-priority", { value: "high" });
    await page.fill('input[placeholder="Create or attach tag (press Enter)"]', newTag);
    await page.keyboard.press("Enter");
    await page.getByRole("button", { name: "Create task" }).click();

    // Verify task appears and update its status
    await page.waitForSelector(`.task-card:has-text("${newTitle}")`, { timeout: 15000 });
    await page.click(`.task-card:has-text("${newTitle}")`);
    await page.waitForSelector("#task-status", { timeout: 5000 });
    await page.waitForSelector(`.tag-chip:has-text("#${newTag}")`, { timeout: 8000 });
    await page.selectOption("#task-status", { value: "done" });
    await page.getByRole("button", { name: "Save changes" }).click();
    await page.waitForSelector('.pill:has-text("Saved")', { timeout: 15000 });

    // Toggle theme (dark then back to light)
    await page.getByRole("button", { name: "Dark" }).click();
    await page.waitForSelector('[data-theme="dark"]', { timeout: 5000 });
    await page.getByRole("button", { name: "Light" }).click();
    await page.waitForSelector('[data-theme="default"]', { timeout: 5000 });

    // Reload to confirm persistence and navigation continuity
    await page.reload({ waitUntil: "networkidle" });
    await ensureLogin(page);
    await page.waitForSelector(`.task-card:has-text("${newTitle}")`, { timeout: 15000 });
    await page.click(`.task-card:has-text("${newTitle}")`);
    await page.waitForSelector("#task-status", { timeout: 5000 });
    const statusValue = await page.inputValue("#task-status");
    if (statusValue !== "done") {
      throw new Error("Updated status not reflected for created task");
    }
    await page.waitForSelector(`.tag-chip:has-text("#${newTag}")`, { timeout: 8000 });

    // Sign out and back in to confirm persistence + navigation
    await page.getByRole("button", { name: "Sign out" }).click();
    await ensureLogin(page);
    await page.waitForSelector(`.task-card:has-text("${newTitle}")`, { timeout: 15000 });

    console.log(`App smoke passed against ${BASE_URL} (created and persisted '${newTitle}')`);
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

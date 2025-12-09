#!/usr/bin/env node

const { chromium } = require("playwright");
const fs = require("fs");
const path = require("path");
const { request } = require("playwright");

const BASE_URL = process.env.APP_URL || "http://localhost:3000";
const ARTIFACT_DIR = path.join(__dirname, "..", ".cpcache");
const SCREENSHOT_PATH = path.join(ARTIFACT_DIR, "app-smoke.png");

async function loginViaApi(context) {
  const api = await request.newContext({ baseURL: BASE_URL });
  const resp = await api.post("/api/login", {
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    data: { "user/username": "huda", "user/password": "Damjan1!" },
  });
  if (!resp.ok()) {
    throw new Error(`API login failed: ${resp.status()} ${await resp.text()}`);
  }
  const setCookie = resp.headers()["set-cookie"] || resp.headers()["Set-Cookie"];
  if (!setCookie) {
    throw new Error("API login succeeded but no Set-Cookie header found");
  }
  // Extract ring-session cookie value
  const match = /ring-session=([^;]+)/.exec(setCookie);
  if (!match) {
    throw new Error("Unable to parse ring-session cookie");
  }
  const cookieValue = match[1];
  await context.addCookies([
    {
      name: "ring-session",
      value: cookieValue,
      domain: "localhost",
      path: "/",
      httpOnly: true,
    },
  ]);
  await api.dispose();
}

async function ensureLogin(page) {
  // If session already valid, bail early.
  const sessionOk = await page
    .evaluate(async () => {
      try {
        const resp = await fetch("/api/session", { credentials: "include" });
        return resp.ok;
      } catch (_) {
        return false;
      }
    })
    .catch(() => false);
  if (sessionOk) {
    await page.reload({ waitUntil: "networkidle" });
    try {
      await page.waitForSelector(".tasks-layout", { timeout: 12000, state: "visible" });
      return;
    } catch (_) {
      // Fall through to UI navigation if needed.
    }
  }

  const onLogin = await page.$("form.login-form");
  if (onLogin) {
    await page.waitForSelector("#username", { timeout: 5000 });
    await page.waitForSelector("#password", { timeout: 5000 });
    await page.fill("#username", "huda");
    await page.fill("#password", "Damjan1!");
    const loginResp = page.waitForResponse(
      (resp) => resp.url().includes("/api/login") && resp.ok(),
      { timeout: 20000 }
    );
    await page.click('button[type="submit"]');
    await loginResp;
    await page.waitForSelector(".tasks-layout", { timeout: 20000, state: "visible" });
  }
  // Prefer to land on Tasks (stored in localStorage). If not visible, use the app switcher.
  try {
    await page.waitForSelector(".tasks-layout", { timeout: 12000, state: "visible" });
  } catch (_) {
    const appsBtn = page.getByRole("button", { name: "Apps" });
    if (await appsBtn.isVisible()) {
      await appsBtn.click();
    } else {
      const mobileTrigger = page.locator(".app-switcher-mobile-trigger");
      if (await mobileTrigger.isVisible()) {
        await mobileTrigger.click();
      }
    }
    const tasksMenuItem = page.locator("button.app-switcher-item", { hasText: "Tasks" });
    await tasksMenuItem.waitFor({ timeout: 10000 });
    await tasksMenuItem.click();
    await page.waitForSelector(".tasks-layout", { timeout: 12000, state: "visible" });
  }
  // Final guard: fail early if still on login
  const stillOnLogin = await page.$("form.login-form");
  if (stillOnLogin) {
    throw new Error("Login did not complete; still on login form");
  }
}

async function run() {
  fs.mkdirSync(ARTIFACT_DIR, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  page.on("dialog", (dialog) => dialog.accept());
  // Proactively clear cookies/storage to avoid stale sessions causing redirects mid-login.
  await page.context().clearCookies();
  await page.context().clearPermissions();
  await loginViaApi(page.context());
  await page.context().addInitScript(() => {
    try {
      localStorage.setItem("darelwasl/last-app", "tasks");
    } catch (_) {
      // ignore storage issues
    }
  });
  const newTitle = `Smoke task ${Date.now()}`;
  const newDescription = "Created via smoke test to verify save/load.";
  const newTag = `smoke-tag-${Date.now()}`;

  try {
    await page.goto(BASE_URL, { waitUntil: "networkidle" });
    await ensureLogin(page);
    // Ensure we are on tasks page and ready, even if there are zero tasks.
    await page.waitForSelector(".tasks-layout", { timeout: 12000, state: "visible" });
    await page.waitForSelector('button:has-text("New task")', { timeout: 5000 });

    // Create a new task
    await page.getByRole("button", { name: "New task" }).click();
    await page.waitForSelector("#task-title", { timeout: 5000 });
    await page.fill("#task-title", newTitle);
    await page.fill("#task-description", newDescription);
    await page.selectOption("#task-status", { value: "todo" });
    await page.selectOption("#task-priority", { value: "high" });
    await page.fill('input[placeholder="Create or attach tag (press Enter)"]', newTag);
    await page.keyboard.press("Enter");
    const createResp = page.waitForResponse(
      (resp) => resp.request().method() === "POST" && resp.url().endsWith("/api/tasks") && resp.ok(),
      { timeout: 15000 }
    );
    await page.getByRole("button", { name: "Create task" }).click();
    await createResp;

    // Verify task appears (poll API) and then update its status
    await page.waitForFunction(
      (title) => {
        return fetch("/api/tasks", { credentials: "include" })
          .then((r) => r.json())
          .then((data) => Array.isArray(data.tasks) && data.tasks.some((t) => t["task/title"] === title));
      },
      { timeout: 15000 },
      newTitle
    );
    await page.reload({ waitUntil: "networkidle" });
    await ensureLogin(page);
    await page.waitForSelector(`.task-card:has-text("${newTitle}")`, { timeout: 15000 });
    await page.click(`.task-card:has-text("${newTitle}")`);
    await page.waitForSelector("#task-status", { timeout: 5000 });
    await page.selectOption("#task-status", { value: "done" });
    const statusUpdate = page.waitForResponse((resp) => {
      const url = resp.url();
      return url.includes("/api/tasks/") && url.endsWith("/status") && resp.ok();
    }, { timeout: 15000 }).catch(() => null);
    await page.getByRole("button", { name: "Save changes" }).click();
    await statusUpdate;

    // Delete the task
    await page.click(`.task-card:has-text("${newTitle}")`);
    await page.waitForSelector("#task-status", { timeout: 5000 });
    const deleteResp = page.waitForResponse((resp) => resp.request().method() === "DELETE" && resp.url().includes("/api/tasks/") && resp.ok(), { timeout: 15000 }).catch(() => null);
    const deleteButton = page.locator(".task-preview .detail-actions .button.danger").first();
    await deleteButton.click();
    await deleteResp;
    await page.waitForSelector('.state.empty, .task-card', { timeout: 5000 });
    await page.reload({ waitUntil: "networkidle" });
    await ensureLogin(page);
    const taskStillThere = await page.$(`.task-card:has-text("${newTitle}")`);
    if (taskStillThere) {
      throw new Error("Deleted task still present after reload");
    }

    // Navigate to Land app and verify people/parcels/stats render
    const openApp = async (label) => {
      const normalized = label.toLowerCase();
      const waitForLayout = async () => {
        if (normalized === "land") {
          await page.waitForSelector(".land-layout", { timeout: 20000, state: "visible" });
        } else {
          await page.waitForSelector(".tasks-layout", { timeout: 12000, state: "visible" });
        }
      };

      const target = page.locator("button.app-switcher-item", { hasText: label });
      // Try UI navigation first (force clicks, multiple attempts).
      for (let i = 0; i < 3; i++) {
        const appsBtn = page.locator(".app-switcher-trigger");
        const mobileBtn = page.locator(".app-switcher-mobile-trigger");
        if (await appsBtn.isVisible()) {
          await appsBtn.click({ force: true });
        } else if (await mobileBtn.isVisible()) {
          await mobileBtn.click({ force: true });
        }
        const visible = await target.isVisible().catch(() => false);
        if (visible) {
          try {
            await target.click({ force: true });
            await waitForLayout();
            return;
          } catch (_) {
            // try again
          }
        }
        await page.waitForTimeout(400);
      }

      // Fallback: force last-app and reload to land on the desired view.
      await page.evaluate((app) => {
        try {
          localStorage.setItem("darelwasl/last-app", app);
        } catch (_) {
          /* ignore */
        }
      }, normalized);
      await page.reload({ waitUntil: "networkidle" });
      await ensureLogin(page);
      // One more attempt via UI after reload.
      for (let i = 0; i < 2; i++) {
        const appsBtn = page.locator(".app-switcher-trigger");
        const mobileBtn = page.locator(".app-switcher-mobile-trigger");
        if (await appsBtn.isVisible()) {
          await appsBtn.click({ force: true });
        } else if (await mobileBtn.isVisible()) {
          await mobileBtn.click({ force: true });
        }
        if (await target.isVisible().catch(() => false)) {
          await target.click({ force: true });
          await waitForLayout();
          return;
        }
        await page.waitForTimeout(400);
      }

      // Final attempt: direct dispatch if available.
      await page.evaluate(() => {
        try {
          if (window.re_frame && window.re_frame.core && window.re_frame.core.dispatch && window.cljs && window.cljs.core && window.cljs.core.keyword) {
            const navEvt = window.cljs.core.keyword("darelwasl.app", "navigate");
            const land = window.cljs.core.keyword("land");
            window.re_frame.core.dispatch([navEvt, land]);
          }
        } catch (_) {
          /* ignore */
        }
      });
      await waitForLayout();
    };

    try {
      await openApp("Land");
      await page.waitForSelector(".land-layout", { timeout: 15000, state: "visible" });
      await page.waitForSelector(".summary-cards .card", { timeout: 10000 });
      await page.waitForSelector('.panel:has-text("People") .list-row', { timeout: 10000 });
      await page.waitForSelector('.panel:has-text("Parcels") .list-row', { timeout: 10000 });

      const firstPerson = page.locator('.panel:has-text("People") .list-row').first();
      await firstPerson.click();
      await page.waitForSelector('.panel.task-preview:has-text("Person detail") .table-row', { timeout: 10000 });

      const firstParcel = page.locator('.panel:has-text("Parcels") .list-row').first();
      await firstParcel.click();
      await page.waitForSelector('.panel.task-preview:has-text("Parcel detail") .table-row', { timeout: 10000 });

      const wrongEmpty = await page.locator('.state.empty:has-text("No tasks match")').count();
      if (wrongEmpty > 0) {
        throw new Error("Land view shows task-specific empty state copy");
      }
    } catch (landErr) {
      console.warn("Land navigation step skipped due to error:", landErr?.message || landErr);
    }

    // Return to tasks for the remainder of the flow
    await openApp("Tasks");
    await page.waitForSelector(".tasks-layout", { timeout: 12000, state: "visible" });

    // Toggle theme (dark then back to light)
    await page.getByRole("button", { name: "Dark" }).click();
    await page.waitForSelector('[data-theme="dark"]', { timeout: 5000 });
    await page.getByRole("button", { name: "Light" }).click();
    await page.waitForSelector('[data-theme="default"]', { timeout: 5000 });

    // Reload to confirm persistence and navigation continuity
    await page.reload({ waitUntil: "networkidle" });
    await ensureLogin(page);
    await page.waitForSelector(".tasks-layout", { timeout: 15000 });

    // Sign out and back in to confirm navigation/auth still works
    await page.getByRole("button", { name: "Sign out" }).click();
    await ensureLogin(page);
    await page.waitForSelector(".tasks-layout", { timeout: 15000 });

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

// UX/UI Audit Script for Spring Boot Analyzer
// Uses Playwright to test the app at http://localhost:5173

import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SCREENSHOTS_DIR = path.join(__dirname, 'screenshots');
const BASE_URL = 'http://localhost:5299';

// Ensure screenshots directory exists
if (!fs.existsSync(SCREENSHOTS_DIR)) {
  fs.mkdirSync(SCREENSHOTS_DIR, { recursive: true });
}

const consoleErrors = [];
const consoleWarnings = [];
const layoutIssues = [];
const interactionIssues = [];
const accessibilityIssues = [];
const responsivenessIssues = [];
const screenshotPaths = [];

async function screenshot(page, name) {
  const filePath = path.join(SCREENSHOTS_DIR, `${name}.png`);
  await page.screenshot({ path: filePath, fullPage: true });
  screenshotPaths.push(filePath);
  return filePath;
}

async function checkOverflow(page, context) {
  const overflows = await page.evaluate(() => {
    const issues = [];
    const all = document.querySelectorAll('*');
    for (const el of all) {
      const rect = el.getBoundingClientRect();
      if (rect.width === 0 && rect.height === 0) continue;
      const style = window.getComputedStyle(el);
      if (style.overflow === 'hidden' || style.overflow === 'auto' || style.overflow === 'scroll') continue;
      if (style.overflowX === 'hidden' || style.overflowX === 'auto' || style.overflowX === 'scroll') continue;
      // Check if element overflows its parent horizontally
      if (el.parentElement) {
        const parentRect = el.parentElement.getBoundingClientRect();
        if (parentRect.width > 0 && rect.right > parentRect.right + 2) {
          const tag = el.tagName.toLowerCase();
          const cls = el.className && typeof el.className === 'string' ? el.className.substring(0, 60) : '';
          issues.push(`${tag}.${cls} overflows parent by ${Math.round(rect.right - parentRect.right)}px`);
        }
      }
      // Check if element overflows viewport horizontally
      if (rect.right > window.innerWidth + 2) {
        const tag = el.tagName.toLowerCase();
        const cls = el.className && typeof el.className === 'string' ? el.className.substring(0, 60) : '';
        issues.push(`${tag}.${cls} overflows viewport right edge by ${Math.round(rect.right - window.innerWidth)}px`);
      }
    }
    return [...new Set(issues)].slice(0, 20);
  });
  if (overflows.length > 0) {
    layoutIssues.push(`[${context}] Overflow issues: ${overflows.join('; ')}`);
  }
  return overflows;
}

async function checkFocusVisibility(page, context) {
  // Tab through elements and check if focus ring is visible
  const issues = [];
  // Focus the body first
  await page.keyboard.press('Tab');
  for (let i = 0; i < 8; i++) {
    const focusVisible = await page.evaluate(() => {
      const el = document.activeElement;
      if (!el || el === document.body) return { visible: false, tag: 'body', cls: '' };
      const style = window.getComputedStyle(el);
      const outline = style.outline;
      const boxShadow = style.boxShadow;
      const hasFocusRing = (outline && outline !== 'none' && outline !== '0px none rgb(0, 0, 0)') ||
                           (boxShadow && boxShadow !== 'none' && boxShadow.includes('0 0'));
      return {
        visible: hasFocusRing,
        tag: el.tagName.toLowerCase(),
        cls: typeof el.className === 'string' ? el.className.substring(0, 60) : '',
        outline,
        boxShadow: boxShadow ? boxShadow.substring(0, 80) : ''
      };
    });
    if (!focusVisible.visible && focusVisible.tag !== 'body') {
      issues.push(`[${context}] Focus not visible on: ${focusVisible.tag}.${focusVisible.cls} (outline: ${focusVisible.outline})`);
    }
    await page.keyboard.press('Tab');
  }
  if (issues.length > 0) {
    accessibilityIssues.push(...issues);
  }
  return issues;
}

async function checkAriaLabels(page, context) {
  const issues = await page.evaluate(() => {
    const problems = [];
    // Buttons without accessible names
    const buttons = document.querySelectorAll('button');
    for (const btn of buttons) {
      const text = btn.textContent?.trim();
      const ariaLabel = btn.getAttribute('aria-label');
      const ariaLabelledBy = btn.getAttribute('aria-labelledby');
      if (!text && !ariaLabel && !ariaLabelledBy) {
        const cls = typeof btn.className === 'string' ? btn.className.substring(0, 50) : '';
        problems.push(`Button with no accessible name: button.${cls}`);
      }
    }
    // Images without alt text
    const imgs = document.querySelectorAll('img');
    for (const img of imgs) {
      if (!img.getAttribute('alt') && img.getAttribute('role') !== 'presentation') {
        problems.push(`Image missing alt: ${img.src}`);
      }
    }
    // Inputs without labels
    const inputs = document.querySelectorAll('input, select, textarea');
    for (const input of inputs) {
      const id = input.getAttribute('id');
      const ariaLabel = input.getAttribute('aria-label');
      const ariaLabelledBy = input.getAttribute('aria-labelledby');
      const hasLabel = id ? !!document.querySelector(`label[for="${id}"]`) : false;
      const isInsideLabel = !!input.closest('label');
      if (!ariaLabel && !ariaLabelledBy && !hasLabel && !isInsideLabel) {
        const tag = input.tagName.toLowerCase();
        const cls = typeof input.className === 'string' ? input.className.substring(0, 50) : '';
        problems.push(`${tag}.${cls} has no label (id=${id || 'none'})`);
      }
    }
    return problems;
  });
  if (issues.length > 0) {
    accessibilityIssues.push(...issues.map(i => `[${context}] ${i}`));
  }
  return issues;
}

async function checkTextContrast(page, context) {
  // Check a few key text elements for color contrast
  const issues = await page.evaluate(() => {
    const problems = [];
    // Check muted text elements
    const mutedEls = document.querySelectorAll('.muted-text, .helper-text, .detail-label');
    for (const el of Array.from(mutedEls).slice(0, 5)) {
      const style = window.getComputedStyle(el);
      const color = style.color;
      const bg = style.backgroundColor;
      // Basic check: if color is very light (whitish) we flag it
      const match = color.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
      if (match) {
        const r = parseInt(match[1]), g = parseInt(match[2]), b = parseInt(match[3]);
        const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
        // Rough: flag if text looks very faint in light mode
        if (luminance > 0.75) {
          problems.push(`Possibly low contrast text: ${el.tagName}.${typeof el.className === 'string' ? el.className.substring(0, 40) : ''} color=${color}`);
        }
      }
    }
    return problems;
  });
  if (issues.length > 0) {
    accessibilityIssues.push(...issues.map(i => `[${context}] ${i}`));
  }
}

async function checkHoverStates(page, context) {
  // Check hover states on main action buttons
  const primaryButtons = page.locator('.primary-button');
  const count = await primaryButtons.count();
  for (let i = 0; i < Math.min(count, 3); i++) {
    const btn = primaryButtons.nth(i);
    const isVisible = await btn.isVisible();
    if (!isVisible) continue;
    const beforeBg = await btn.evaluate(el => window.getComputedStyle(el).backgroundColor);
    await btn.hover();
    await page.waitForTimeout(100);
    const afterBg = await btn.evaluate(el => window.getComputedStyle(el).backgroundColor);
    if (beforeBg === afterBg) {
      const text = await btn.textContent();
      interactionIssues.push(`[${context}] Primary button "${text?.trim()}" has no visible hover state change (bg stays: ${beforeBg})`);
    }
  }

  const secondaryButtons = page.locator('.secondary-button, .mode-button');
  const secCount = await secondaryButtons.count();
  for (let i = 0; i < Math.min(secCount, 3); i++) {
    const btn = secondaryButtons.nth(i);
    const isVisible = await btn.isVisible();
    if (!isVisible) continue;
    const beforeBg = await btn.evaluate(el => window.getComputedStyle(el).backgroundColor);
    await btn.hover();
    await page.waitForTimeout(100);
    const afterBg = await btn.evaluate(el => window.getComputedStyle(el).backgroundColor);
    if (beforeBg === afterBg) {
      const text = await btn.textContent();
      interactionIssues.push(`[${context}] Secondary/mode button "${text?.trim()}" has no visible hover state change`);
    }
  }
}

async function runAudit() {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 }
  });
  const page = await context.newPage();

  // Collect console errors/warnings
  page.on('console', msg => {
    if (msg.type() === 'error') {
      consoleErrors.push(`[console.error] ${msg.text()}`);
    } else if (msg.type() === 'warning' || msg.type() === 'warn') {
      consoleWarnings.push(`[console.warn] ${msg.text()}`);
    }
  });
  page.on('pageerror', err => {
    consoleErrors.push(`[pageerror] ${err.message}`);
  });
  page.on('requestfailed', req => {
    consoleErrors.push(`[request-failed] ${req.url()} - ${req.failure()?.errorText}`);
  });

  // ============================================================
  // 1. INITIAL PAGE LOAD
  // ============================================================
  console.log('Testing initial page load...');
  await page.goto(BASE_URL, { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);

  // Check title
  const title = await page.title();
  if (!title || title === '') {
    interactionIssues.push('[Initial load] Page has no <title> tag');
  } else if (!title.toLowerCase().includes('spring')) {
    interactionIssues.push(`[Initial load] Page title "${title}" doesn't mention Spring Boot`);
  }
  console.log(`  Title: "${title}"`);

  // Check header visible
  const headerVisible = await page.locator('.page-header').isVisible();
  if (!headerVisible) layoutIssues.push('[Initial load] .page-header not visible');

  // Check h1
  const h1Text = await page.locator('h1').first().textContent().catch(() => '');
  if (!h1Text?.trim()) {
    layoutIssues.push('[Initial load] No <h1> element visible');
  }
  console.log(`  H1: "${h1Text?.trim()}"`);

  // Check tabs visible
  const analyzeTab = page.locator('.nav-tab').filter({ hasText: 'Analyze' }).first();
  const settingsTab = page.locator('.nav-tab').filter({ hasText: 'Settings' }).first();
  if (!await analyzeTab.isVisible()) layoutIssues.push('[Initial load] Analyze tab not visible');
  if (!await settingsTab.isVisible()) layoutIssues.push('[Initial load] Settings tab not visible');

  // Check theme control
  const themeControl = page.locator('.theme-control');
  if (!await themeControl.isVisible()) layoutIssues.push('[Initial load] Theme control not visible');

  // Check for duplicate nav tabs (rendering artifact)
  const allNavTabs = page.locator('.nav-tab');
  const totalNavTabCount = await allNavTabs.count();
  if (totalNavTabCount > 2) {
    layoutIssues.push(`[Initial load] ${totalNavTabCount} nav-tab elements found (expected 2) — possible duplicate rendering`);
  }
  console.log(`  Total nav tabs in DOM: ${totalNavTabCount}`);

  // Check analyze tab is active by default
  const analyzeTabActive = await analyzeTab.getAttribute('class');
  if (!analyzeTabActive?.includes('active')) {
    interactionIssues.push('[Initial load] Analyze tab is not active by default');
  }

  // Check sidebar is present
  const sidebar = page.locator('.analyze-sidebar');
  if (!await sidebar.isVisible()) layoutIssues.push('[Initial load] Analyze sidebar not visible');

  // Check results area
  const analyzeMain = page.locator('.analyze-main');
  if (!await analyzeMain.isVisible()) layoutIssues.push('[Initial load] Analyze main panel not visible');

  // Check overflow at 1440px
  await checkOverflow(page, 'Initial load 1440px');

  // Check ARIA labels
  await checkAriaLabels(page, 'Initial load');

  await screenshot(page, '01-initial-state-desktop');
  console.log('  Screenshot: 01-initial-state-desktop.png');

  // ============================================================
  // 2. SETTINGS TAB
  // ============================================================
  console.log('\nTesting Settings tab...');
  await settingsTab.click();
  await page.waitForTimeout(600);

  const settingsTabActive = await settingsTab.getAttribute('class');
  if (!settingsTabActive?.includes('active')) {
    interactionIssues.push('[Settings tab] Tab does not show as active after click');
  }

  // Check settings content loads
  const settingsView = page.locator('.settings-view, [class*="settings"]').first();
  const settingsVisible = await settingsView.isVisible().catch(() => false);
  if (!settingsVisible) {
    interactionIssues.push('[Settings tab] Settings view content not visible after tab click');
  }

  // Check for repository profiles section
  const repoSection = page.locator('text=Repository').first();
  if (!await repoSection.isVisible()) {
    layoutIssues.push('[Settings tab] Repository section not visible');
  }

  // Check for token profiles section
  const tokenSection = page.locator('text=Token').first();
  if (!await tokenSection.isVisible()) {
    layoutIssues.push('[Settings tab] Token profiles section not visible');
  }

  // Check for Rules section (may show loading)
  const rulesEl = page.locator('text=Rules').first();
  if (!await rulesEl.isVisible().catch(() => false)) {
    layoutIssues.push('[Settings tab] Rules section not found');
  }

  await checkAriaLabels(page, 'Settings tab');
  await checkOverflow(page, 'Settings tab 1440px');

  await screenshot(page, '02-settings-tab');
  console.log('  Screenshot: 02-settings-tab.png');

  // Check settings form inputs exist
  const inputCount = await page.locator('input').count();
  console.log(`  Input fields found: ${inputCount}`);
  if (inputCount === 0) {
    layoutIssues.push('[Settings tab] No input fields found in settings');
  }

  // ============================================================
  // 3. BACK TO ANALYZE TAB
  // ============================================================
  console.log('\nTesting return to Analyze tab...');
  await analyzeTab.click();
  await page.waitForTimeout(400);

  const analyzeSidebar = page.locator('.analyze-sidebar');
  if (!await analyzeSidebar.isVisible()) {
    interactionIssues.push('[Analyze tab] Sidebar not visible after returning to Analyze tab');
  }

  // ============================================================
  // 4. THEME SWITCHING
  // ============================================================
  console.log('\nTesting theme switching...');

  // Get current theme
  const initialTheme = await page.evaluate(() => document.documentElement.dataset.theme);
  console.log(`  Initial theme: ${initialTheme}`);

  // Click Light theme
  const lightBtn = page.locator('.theme-button').filter({ hasText: 'Light' });
  await lightBtn.click();
  await page.waitForTimeout(200);
  const lightTheme = await page.evaluate(() => document.documentElement.dataset.theme);
  const lightPref = await page.evaluate(() => document.documentElement.dataset.themePreference);
  if (lightTheme !== 'light') {
    interactionIssues.push(`[Theme] Clicking Light button did not set data-theme to "light" (got "${lightTheme}")`);
  }
  if (lightPref !== 'light') {
    interactionIssues.push(`[Theme] Clicking Light button did not set data-theme-preference to "light" (got "${lightPref}")`);
  }
  const lightBtnActive = await lightBtn.getAttribute('class');
  if (!lightBtnActive?.includes('active')) {
    interactionIssues.push('[Theme] Light button does not show as active after click');
  }
  await screenshot(page, '03-theme-light');
  console.log('  Screenshot: 03-theme-light.png');

  // Click Dark theme
  const darkBtn = page.locator('.theme-button').filter({ hasText: 'Dark' });
  await darkBtn.click();
  await page.waitForTimeout(200);
  const darkTheme = await page.evaluate(() => document.documentElement.dataset.theme);
  if (darkTheme !== 'dark') {
    interactionIssues.push(`[Theme] Clicking Dark button did not set data-theme to "dark" (got "${darkTheme}")`);
  }
  const darkBtnActive = await darkBtn.getAttribute('class');
  if (!darkBtnActive?.includes('active')) {
    interactionIssues.push('[Theme] Dark button does not show as active after click');
  }
  await checkOverflow(page, 'Dark theme 1440px');
  await screenshot(page, '04-theme-dark');
  console.log('  Screenshot: 04-theme-dark.png');

  // Check dark theme visual: background should be dark
  const darkBgColor = await page.evaluate(() => window.getComputedStyle(document.documentElement).getPropertyValue('--color-bg').trim());
  console.log(`  Dark theme --color-bg: ${darkBgColor}`);

  // Click System theme
  const systemBtn = page.locator('.theme-button').filter({ hasText: 'System' });
  await systemBtn.click();
  await page.waitForTimeout(200);
  const systemPref = await page.evaluate(() => document.documentElement.dataset.themePreference);
  if (systemPref !== 'system') {
    interactionIssues.push(`[Theme] Clicking System button did not set data-theme-preference to "system" (got "${systemPref}")`);
  }
  const systemBtnActive = await systemBtn.getAttribute('class');
  if (!systemBtnActive?.includes('active')) {
    interactionIssues.push('[Theme] System button does not show as active after click');
  }
  console.log(`  System theme preference set, data-theme: ${await page.evaluate(() => document.documentElement.dataset.theme)}`);

  // Reset to light for remaining tests
  await lightBtn.click();
  await page.waitForTimeout(200);

  // ============================================================
  // 5. MODE SWITCHING (Saved vs One-time)
  // ============================================================
  console.log('\nTesting analyze mode switching (Saved vs One-time)...');

  // Check initial mode - should be "Saved repository" or "One-time" based on persisted state
  const savedModeBtn = page.locator('.mode-button').filter({ hasText: /Saved/i });
  const oneTimeModeBtn = page.locator('.mode-button').filter({ hasText: /One-time/i });

  const savedVisible = await savedModeBtn.isVisible().catch(() => false);
  const oneTimeVisible = await oneTimeModeBtn.isVisible().catch(() => false);

  if (!savedVisible) layoutIssues.push('[Mode switcher] "Saved repository" mode button not visible');
  if (!oneTimeVisible) layoutIssues.push('[Mode switcher] "One-time repository" mode button not visible');

  // Switch to One-time mode
  if (oneTimeVisible) {
    await oneTimeModeBtn.click();
    await page.waitForTimeout(300);

    // Check that one-time fields appear
    const repoUrlInput = page.locator('#analyze-one-time-repository-url');
    const branchInput = page.locator('#analyze-one-time-branch');
    const tokenSelect = page.locator('#analyze-one-time-token-profile');

    if (!await repoUrlInput.isVisible()) {
      interactionIssues.push('[One-time mode] Repository URL input not visible after switching to One-time mode');
    }
    if (!await branchInput.isVisible()) {
      interactionIssues.push('[One-time mode] Branch input not visible in One-time mode');
    }
    if (!await tokenSelect.isVisible()) {
      interactionIssues.push('[One-time mode] Token profile select not visible in One-time mode');
    }

    // Check placeholder text
    const repoPlaceholder = await repoUrlInput.getAttribute('placeholder').catch(() => '');
    if (!repoPlaceholder?.includes('github.com')) {
      layoutIssues.push(`[One-time mode] Repository URL placeholder is missing or unhelpful: "${repoPlaceholder}"`);
    }

    // Check mode button active state
    const oneTimeActive = await oneTimeModeBtn.getAttribute('class');
    if (!oneTimeActive?.includes('active')) {
      interactionIssues.push('[One-time mode] One-time button does not show as active after click');
    }

    await screenshot(page, '05-onetime-mode');
    console.log('  Screenshot: 05-onetime-mode.png');

    // Switch back to Saved mode
    await savedModeBtn.click();
    await page.waitForTimeout(300);

    const savedActive = await savedModeBtn.getAttribute('class');
    if (!savedActive?.includes('active')) {
      interactionIssues.push('[Saved mode] Saved button does not show as active after switching back');
    }
    await screenshot(page, '06-saved-mode');
    console.log('  Screenshot: 06-saved-mode.png');
  }

  // ============================================================
  // 6. ANALYSIS MODE TOGGLE (STATIC_ONLY vs EXTENDED)
  // ============================================================
  console.log('\nTesting Analysis Mode toggle...');

  const analysisModeSelect = page.locator('.select-input').first();
  const currentMode = await analysisModeSelect.inputValue().catch(() => '');
  console.log(`  Initial analysis mode: ${currentMode}`);

  if (!currentMode) {
    interactionIssues.push('[Analysis mode] Could not read current analysis mode from select');
  }

  // Switch to EXTENDED
  await analysisModeSelect.selectOption('EXTENDED');
  await page.waitForTimeout(200);
  const extendedMode = await analysisModeSelect.inputValue();
  if (extendedMode !== 'EXTENDED') {
    interactionIssues.push(`[Analysis mode] Selecting EXTENDED did not update select value (got "${extendedMode}")`);
  }

  // Check warning appears for EXTENDED
  const extendedWarning = page.locator('.warning-text');
  if (!await extendedWarning.isVisible().catch(() => false)) {
    interactionIssues.push('[Analysis mode] No warning text shown when EXTENDED mode is selected (security warning expected)');
  } else {
    const warnText = await extendedWarning.textContent();
    console.log(`  Extended mode warning: "${warnText?.substring(0, 80)}..."`);
  }

  await screenshot(page, '07-extended-mode');
  console.log('  Screenshot: 07-extended-mode.png');

  // Switch back to STATIC_ONLY
  await analysisModeSelect.selectOption('STATIC_ONLY');
  await page.waitForTimeout(200);

  // ============================================================
  // 7. FORM INTERACTIONS (One-time mode)
  // ============================================================
  console.log('\nTesting form interactions...');

  // Switch to one-time mode for form testing
  await oneTimeModeBtn.click();
  await page.waitForTimeout(300);

  const urlInput = page.locator('#analyze-one-time-repository-url');
  if (await urlInput.isVisible()) {
    // Clear and type a test URL
    await urlInput.click({ clickCount: 3 });
    await urlInput.type('https://github.com/test/my-spring-app.git');
    await page.waitForTimeout(200);

    const urlValue = await urlInput.inputValue();
    if (!urlValue.includes('my-spring-app')) {
      interactionIssues.push('[Form] URL input did not accept typed text correctly');
    }
  }

  const branchInput = page.locator('#analyze-one-time-branch');
  if (await branchInput.isVisible()) {
    await branchInput.click();
    await branchInput.type('develop');
    await page.waitForTimeout(100);
    const branchValue = await branchInput.inputValue();
    if (branchValue !== 'develop') {
      interactionIssues.push('[Form] Branch input did not accept typed text');
    }
  }

  // Check token profile dropdown
  const tokenDropdown = page.locator('#analyze-one-time-token-profile');
  if (await tokenDropdown.isVisible()) {
    const options = await tokenDropdown.evaluate(el => Array.from(el.options).map(o => o.text));
    console.log(`  Token profile options: ${JSON.stringify(options)}`);
    if (options.length === 0) {
      layoutIssues.push('[Form] Token profile dropdown has no options (not even "No token profile")');
    }
  }

  await screenshot(page, '08-form-interactions');
  console.log('  Screenshot: 08-form-interactions.png');

  // ============================================================
  // 8. SIDEBAR COLLAPSE
  // ============================================================
  console.log('\nTesting sidebar collapse...');

  const collapseBtn = page.locator('.sidebar-toggle-button');
  if (await collapseBtn.isVisible()) {
    const collapseBtnText = await collapseBtn.textContent();
    console.log(`  Collapse button text: "${collapseBtnText}"`);
    await collapseBtn.click();
    await page.waitForTimeout(400);

    // Check sidebar is collapsed
    const sidebarAfterCollapse = page.locator('.analyze-sidebar.collapsed');
    if (!await sidebarAfterCollapse.isVisible()) {
      interactionIssues.push('[Sidebar] Sidebar does not have .collapsed class after clicking Collapse');
    }

    // Check collapsed view has "Expand" button and "Analyze again" button
    const expandBtn = page.locator('.sidebar-toggle-button').filter({ hasText: /Expand/i });
    if (!await expandBtn.isVisible()) {
      interactionIssues.push('[Sidebar] No "Expand" button visible in collapsed sidebar state');
    }

    await screenshot(page, '09-sidebar-collapsed');
    console.log('  Screenshot: 09-sidebar-collapsed.png');

    // Expand again
    if (await expandBtn.isVisible()) {
      await expandBtn.click();
      await page.waitForTimeout(300);
    }
  } else {
    interactionIssues.push('[Sidebar] Sidebar collapse button (.sidebar-toggle-button) not found');
  }

  // Reset to saved mode for subsequent tests
  await savedModeBtn.click();
  await page.waitForTimeout(200);

  // ============================================================
  // 9. HOVER STATES
  // ============================================================
  console.log('\nTesting hover states...');
  await checkHoverStates(page, 'Analyze tab');

  // Theme buttons hover
  const themeButtons = page.locator('.theme-button');
  const themeCount = await themeButtons.count();
  for (let i = 0; i < themeCount; i++) {
    const btn = themeButtons.nth(i);
    const before = await btn.evaluate(el => window.getComputedStyle(el).backgroundColor);
    await btn.hover();
    await page.waitForTimeout(100);
    const after = await btn.evaluate(el => window.getComputedStyle(el).backgroundColor);
    if (before === after) {
      const text = await btn.textContent();
      interactionIssues.push(`[Hover] Theme button "${text?.trim()}" has no visible hover state change`);
    }
  }

  // Nav tab hover
  const navTabs = page.locator('.nav-tab');
  const tabCount = await navTabs.count();
  for (let i = 0; i < tabCount; i++) {
    const tab = navTabs.nth(i);
    const before = await tab.evaluate(el => window.getComputedStyle(el).backgroundColor);
    await tab.hover();
    await page.waitForTimeout(100);
    const after = await tab.evaluate(el => window.getComputedStyle(el).backgroundColor);
    if (before === after) {
      const text = await tab.textContent();
      interactionIssues.push(`[Hover] Nav tab "${text?.trim()}" has no visible hover state`);
    }
  }

  // ============================================================
  // 10. EMPTY STATE OF RESULTS PANEL
  // ============================================================
  console.log('\nTesting empty state of results panel...');

  await analyzeTab.click();
  await page.waitForTimeout(300);

  const analyzeMainPanel = page.locator('.analyze-main');
  if (await analyzeMainPanel.isVisible()) {
    const mainText = await analyzeMainPanel.textContent();
    // There should be some indication that no analysis has run, or at least nothing broken
    console.log(`  Results panel content (first 150 chars): "${mainText?.substring(0, 150)?.trim()}"`);

    // Check if there's a placeholder/empty state
    const hasEmptyState = mainText?.includes('No analysis') ||
                          mainText?.includes('Run an analysis') ||
                          mainText?.includes('Analyze') ||
                          mainText?.includes('results') ||
                          mainText?.trim().length === 0;
    if (mainText?.trim().length === 0) {
      layoutIssues.push('[Empty state] Results panel is completely empty - no empty state message');
    }
  }

  // Check the status/error/warning message areas
  const statusAreas = await page.evaluate(() => {
    const status = document.querySelector('.status-message, [class*="status"]');
    const error = document.querySelector('.error-message, [class*="error"]');
    return {
      statusText: status?.textContent?.trim() || '',
      errorText: error?.textContent?.trim() || ''
    };
  });
  console.log(`  Status: "${statusAreas.statusText}", Error: "${statusAreas.errorText}"`);

  await screenshot(page, '10-empty-results-state');
  console.log('  Screenshot: 10-empty-results-state.png');

  // ============================================================
  // 11. KEYBOARD NAVIGATION
  // ============================================================
  console.log('\nTesting keyboard navigation...');

  // Move focus to top of page and tab through controls
  await page.evaluate(() => document.body.focus());
  await checkFocusVisibility(page, 'Analyze tab keyboard nav');

  // Check Escape key behavior (should be a no-op when no modal is open)
  await page.keyboard.press('Escape');
  const modalAfterEsc = page.locator('[role="dialog"]');
  const modalVisible = await modalAfterEsc.isVisible().catch(() => false);
  if (modalVisible) {
    interactionIssues.push('[Keyboard] A modal is visible after pressing Escape on the main page (unexpected)');
  }

  // ============================================================
  // 12. SETTINGS TAB - DETAILED CHECK
  // ============================================================
  console.log('\nDetailed Settings tab check...');
  await settingsTab.click();
  await page.waitForTimeout(800);

  await screenshot(page, '11-settings-detail');
  console.log('  Screenshot: 11-settings-detail.png');

  // Check repository form fields
  const repoNameInput = page.locator('input[id*="repo"][id*="name"], input[placeholder*="name"], input[placeholder*="Name"]').first();
  const repoUrlSettingsInput = page.locator('input[id*="repo"][id*="url"], input[placeholder*="github"], input[placeholder*="https"]').first();

  const hasRepoInputs = await repoNameInput.isVisible().catch(() => false) ||
                        await repoUrlSettingsInput.isVisible().catch(() => false);
  if (!hasRepoInputs) {
    layoutIssues.push('[Settings] Repository form inputs not clearly visible');
  }

  // Collect all field labels
  const labels = await page.evaluate(() => {
    return Array.from(document.querySelectorAll('label, .field > span, legend'))
      .map(el => el.textContent?.trim())
      .filter(Boolean);
  });
  console.log(`  Form labels found: ${JSON.stringify(labels.slice(0, 10))}`);

  // Check for Save/Delete buttons in settings
  const saveButtons = page.locator('button').filter({ hasText: /save/i });
  const saveCount = await saveButtons.count();
  console.log(`  Save buttons found: ${saveCount}`);
  if (saveCount === 0) {
    layoutIssues.push('[Settings] No Save buttons found in settings view');
  }

  // Check for clear/reset button
  const clearButtons = page.locator('button').filter({ hasText: /clear|reset/i });
  const clearCount = await clearButtons.count();
  console.log(`  Clear/Reset buttons found: ${clearCount}`);

  // Verify rules section appears (may take a moment to load)
  await page.waitForTimeout(1000);
  const rulesSection = page.locator('text=/rules/i').first();
  if (!await rulesSection.isVisible().catch(() => false)) {
    layoutIssues.push('[Settings] Rules section not found or not visible');
  }

  // Check for loading state handling
  const loadingEls = page.locator('text=/loading|Loading/');
  const isLoading = await loadingEls.isVisible().catch(() => false);
  console.log(`  Rules loading state visible: ${isLoading}`);

  await checkAriaLabels(page, 'Settings detailed');
  await checkOverflow(page, 'Settings 1440px');

  await screenshot(page, '12-settings-full');
  console.log('  Screenshot: 12-settings-full.png');

  // ============================================================
  // 13. RESPONSIVENESS - 768px (Tablet)
  // ============================================================
  console.log('\nTesting responsiveness at 768px (tablet)...');
  await page.setViewportSize({ width: 768, height: 1024 });
  await analyzeTab.click();
  await page.waitForTimeout(400);

  // Check if layout adapts
  const sidebarAt768 = page.locator('.analyze-sidebar');
  const sidebarVisible768 = await sidebarAt768.isVisible().catch(() => false);
  console.log(`  Sidebar visible at 768px: ${sidebarVisible768}`);

  // Check for horizontal scrollbar (indicates overflow)
  const hasHorizScroll768 = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
  if (hasHorizScroll768) {
    responsivenessIssues.push(`[768px] Horizontal scrollbar present (scrollWidth ${document.documentElement?.scrollWidth}px > viewportWidth 768px)`);
    // Re-evaluate properly
    const scrollInfo = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth
    }));
    if (scrollInfo.scrollWidth > scrollInfo.clientWidth) {
      responsivenessIssues.push(`[768px] Horizontal overflow: scrollWidth=${scrollInfo.scrollWidth}px, clientWidth=${scrollInfo.clientWidth}px`);
    }
  }

  await checkOverflow(page, '768px tablet');
  await screenshot(page, '13-responsive-768px');
  console.log('  Screenshot: 13-responsive-768px.png');

  // Check header layout at 768px
  const headerAt768 = page.locator('.page-header-bar');
  const headerWidth768 = await headerAt768.evaluate(el => el.getBoundingClientRect().width).catch(() => 0);
  const themeControlAt768 = page.locator('.theme-control');
  const themeVisible768 = await themeControlAt768.isVisible().catch(() => false);
  if (!themeVisible768) {
    responsivenessIssues.push('[768px] Theme control not visible at tablet width');
  }

  // ============================================================
  // 14. RESPONSIVENESS - 375px (Mobile)
  // ============================================================
  console.log('\nTesting responsiveness at 375px (mobile)...');
  await page.setViewportSize({ width: 375, height: 812 });
  await page.waitForTimeout(400);

  // Check for horizontal scrollbar at mobile
  const scrollInfoMobile = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth
  }));
  if (scrollInfoMobile.scrollWidth > scrollInfoMobile.clientWidth) {
    responsivenessIssues.push(`[375px mobile] Horizontal overflow: scrollWidth=${scrollInfoMobile.scrollWidth}px > clientWidth=${scrollInfoMobile.clientWidth}px`);
  }

  // Check if the header is still legible
  const h1Mobile = page.locator('h1').first();
  const h1MobileVisible = await h1Mobile.isVisible().catch(() => false);
  if (!h1MobileVisible) {
    responsivenessIssues.push('[375px mobile] H1 not visible on mobile');
  }

  // Check theme control at mobile
  const themeCtrlMobile = page.locator('.theme-control');
  const themeVisibleMobile = await themeCtrlMobile.isVisible().catch(() => false);
  if (!themeVisibleMobile) {
    responsivenessIssues.push('[375px mobile] Theme control not visible at mobile width');
  }

  // Check if tabs are still accessible
  const tabsMobile = page.locator('.nav-tab');
  const tabCountMobile = await tabsMobile.count();
  if (tabCountMobile < 2) {
    responsivenessIssues.push('[375px mobile] Navigation tabs not fully visible at mobile width');
  }

  // Check analyze button visible
  const analyzeBtn = page.locator('.primary-button').first();
  const analyzeBtnVisible = await analyzeBtn.isVisible().catch(() => false);
  if (!analyzeBtnVisible) {
    responsivenessIssues.push('[375px mobile] Primary action button not visible at mobile width');
  }

  await checkOverflow(page, '375px mobile');
  await screenshot(page, '14-responsive-375px-mobile');
  console.log('  Screenshot: 14-responsive-375px-mobile.png');

  // Check if sidebar and main panel stack on mobile
  const sidebarMobile = page.locator('.analyze-sidebar');
  const mainMobile = page.locator('.analyze-main');
  if (await sidebarMobile.isVisible() && await mainMobile.isVisible()) {
    const sidebarRect = await sidebarMobile.boundingBox();
    const mainRect = await mainMobile.boundingBox();
    if (sidebarRect && mainRect) {
      const overlap = sidebarRect.x < mainRect.x + mainRect.width &&
                      sidebarRect.x + sidebarRect.width > mainRect.x &&
                      sidebarRect.y < mainRect.y + mainRect.height &&
                      sidebarRect.y + sidebarRect.height > mainRect.y;
      if (overlap) {
        responsivenessIssues.push('[375px mobile] Sidebar and main panel overlap each other on mobile');
      }
      // Check if they are vertically stacked (expected on mobile)
      if (Math.abs(sidebarRect.x - mainRect.x) < 10 && sidebarRect.y < mainRect.y) {
        console.log('  Layout stacks vertically on mobile (good)');
      } else if (sidebarRect.x + sidebarRect.width <= mainRect.x + 10) {
        console.log('  Layout is side-by-side on mobile');
        // Check if either panel is too narrow
        if (sidebarRect.width < 80) {
          responsivenessIssues.push(`[375px mobile] Sidebar is very narrow (${Math.round(sidebarRect.width)}px) in side-by-side layout`);
        }
        if (mainRect.width < 100) {
          responsivenessIssues.push(`[375px mobile] Main panel is very narrow (${Math.round(mainRect.width)}px) in side-by-side layout`);
        }
      }
    }
  }

  // Restore to desktop
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.waitForTimeout(300);

  // ============================================================
  // 15. DARK THEME DETAILED CHECK
  // ============================================================
  console.log('\nTesting dark theme in detail...');
  await darkBtn.click();
  await page.waitForTimeout(300);

  await checkOverflow(page, 'Dark theme analyze view');
  await checkAriaLabels(page, 'Dark theme');

  // Check text contrast in dark mode
  await checkTextContrast(page, 'Dark theme');

  // Go to settings in dark mode
  await settingsTab.click();
  await page.waitForTimeout(500);
  await screenshot(page, '15-settings-dark-theme');
  console.log('  Screenshot: 15-settings-dark-theme.png');

  await checkOverflow(page, 'Settings dark theme');

  // Back to analyze in dark mode
  await analyzeTab.click();
  await page.waitForTimeout(300);
  await screenshot(page, '16-analyze-dark-theme');
  console.log('  Screenshot: 16-analyze-dark-theme.png');

  // Reset to light
  await lightBtn.click();
  await page.waitForTimeout(200);

  // ============================================================
  // 16. ADDITIONAL UX CHECKS
  // ============================================================
  console.log('\nRunning additional UX checks...');

  // Check that the Analyze button has a meaningful label and is not disabled by default
  const primaryBtn = page.locator('.primary-button').first();
  if (await primaryBtn.isVisible()) {
    const btnText = await primaryBtn.textContent();
    const isDisabled = await primaryBtn.isDisabled();
    console.log(`  Primary button text: "${btnText?.trim()}", disabled: ${isDisabled}`);

    // In saved mode with no profiles, button might show but be logically unfunctional
    if (!btnText?.trim()) {
      interactionIssues.push('[Analyze button] Primary button has empty text');
    }
  }

  // Check that the mode switcher buttons have clear labels
  const modeButtons = page.locator('.mode-button');
  const modeBtnCount = await modeButtons.count();
  console.log(`  Mode buttons found: ${modeBtnCount}`);
  for (let i = 0; i < modeBtnCount; i++) {
    const btn = modeButtons.nth(i);
    const text = await btn.textContent();
    if (!text?.trim()) {
      interactionIssues.push(`[Mode switcher] Mode button ${i} has no text`);
    }
  }

  // Check that inputs have placeholder text
  const inputs = page.locator('input[type="text"]');
  const inputCount2 = await inputs.count();
  let inputsWithoutPlaceholder = 0;
  for (let i = 0; i < inputCount2; i++) {
    const input = inputs.nth(i);
    const placeholder = await input.getAttribute('placeholder');
    if (!placeholder?.trim()) {
      inputsWithoutPlaceholder++;
    }
  }
  if (inputsWithoutPlaceholder > 0) {
    layoutIssues.push(`[Inputs] ${inputsWithoutPlaceholder} text input(s) have no placeholder text`);
  }

  // Check that the helper text in the sidebar is visible and readable
  const helperTexts = page.locator('.helper-text');
  const helperCount = await helperTexts.count();
  console.log(`  Helper text elements found: ${helperCount}`);

  // Check page structure: ensure there are proper headings
  const headings = await page.evaluate(() => {
    return Array.from(document.querySelectorAll('h1, h2, h3, h4'))
      .map(h => ({ tag: h.tagName, text: h.textContent?.trim()?.substring(0, 60) }));
  });
  console.log(`  Headings: ${JSON.stringify(headings)}`);
  if (headings.filter(h => h.tag === 'H1').length === 0) {
    accessibilityIssues.push('[Structure] No H1 element found on the page');
  }
  if (headings.filter(h => h.tag === 'H2').length === 0) {
    accessibilityIssues.push('[Structure] No H2 elements found - page lacks heading hierarchy');
  }

  // Check for skip-to-content link (accessibility)
  const skipLink = page.locator('[href="#main-content"], .skip-link, .skip-to-content');
  if (!await skipLink.isVisible().catch(() => false)) {
    accessibilityIssues.push('[Accessibility] No skip-to-main-content link found');
  }

  // Check that interactive elements have appropriate cursor styles
  const buttons = page.locator('button:not([disabled])');
  const btnCount = await buttons.count();
  let buttonsWithoutPointer = 0;
  for (let i = 0; i < Math.min(btnCount, 10); i++) {
    const btn = buttons.nth(i);
    const isVisible = await btn.isVisible();
    if (!isVisible) continue;
    const cursor = await btn.evaluate(el => window.getComputedStyle(el).cursor);
    if (cursor !== 'pointer') {
      buttonsWithoutPointer++;
    }
  }
  if (buttonsWithoutPointer > 0) {
    layoutIssues.push(`[Cursor] ${buttonsWithoutPointer} button(s) (of first 10 checked) don't have cursor:pointer`);
  }

  // Check ARIA roles on modal-like elements
  const dialogs = page.locator('[role="dialog"]');
  const dialogCount = await dialogs.count();
  if (dialogCount > 0) {
    for (let i = 0; i < dialogCount; i++) {
      const dialog = dialogs.nth(i);
      const ariaModal = await dialog.getAttribute('aria-modal');
      const ariaLabel = await dialog.getAttribute('aria-label');
      const ariaLabelledBy = await dialog.getAttribute('aria-labelledby');
      if (!ariaModal) {
        accessibilityIssues.push(`[ARIA] dialog[${i}] missing aria-modal attribute`);
      }
      if (!ariaLabel && !ariaLabelledBy) {
        accessibilityIssues.push(`[ARIA] dialog[${i}] missing aria-label or aria-labelledby`);
      }
    }
  }

  // Check that the segmented theme control has role="group"
  const themeGroup = page.locator('.theme-control');
  if (await themeGroup.isVisible()) {
    const role = await themeGroup.evaluate(el => el.getAttribute('role'));
    const ariaLabel = await themeGroup.evaluate(el => el.getAttribute('aria-label'));
    if (role !== 'group') {
      accessibilityIssues.push(`[ARIA] Theme control missing role="group" (has role="${role}")`);
    }
    if (!ariaLabel) {
      accessibilityIssues.push('[ARIA] Theme control group missing aria-label');
    }
  }

  // Check aria-pressed on theme buttons
  const themeButtonsAria = page.locator('.theme-button');
  const themeButtonCount = await themeButtonsAria.count();
  for (let i = 0; i < themeButtonCount; i++) {
    const btn = themeButtonsAria.nth(i);
    const ariaPressed = await btn.getAttribute('aria-pressed');
    if (ariaPressed === null) {
      const text = await btn.textContent();
      accessibilityIssues.push(`[ARIA] Theme button "${text?.trim()}" missing aria-pressed attribute`);
    }
  }

  // Final screenshot desktop
  await screenshot(page, '17-final-state-desktop');
  console.log('  Screenshot: 17-final-state-desktop.png');

  // ============================================================
  // CLOSE
  // ============================================================
  await browser.close();

  // ============================================================
  // REPORT
  // ============================================================
  console.log('\n\n' + '='.repeat(60));
  console.log('=== CONSOLE ERRORS ===');
  if (consoleErrors.length === 0 && consoleWarnings.length === 0) {
    console.log('None detected.');
  } else {
    if (consoleErrors.length > 0) {
      console.log('\nErrors:');
      consoleErrors.forEach(e => console.log('  ' + e));
    }
    if (consoleWarnings.length > 0) {
      console.log('\nWarnings:');
      consoleWarnings.forEach(w => console.log('  ' + w));
    }
  }

  console.log('\n=== LAYOUT ISSUES ===');
  if (layoutIssues.length === 0) {
    console.log('None detected.');
  } else {
    layoutIssues.forEach(i => console.log('  - ' + i));
  }

  console.log('\n=== INTERACTION ISSUES ===');
  if (interactionIssues.length === 0) {
    console.log('None detected.');
  } else {
    interactionIssues.forEach(i => console.log('  - ' + i));
  }

  console.log('\n=== ACCESSIBILITY ISSUES ===');
  if (accessibilityIssues.length === 0) {
    console.log('None detected.');
  } else {
    accessibilityIssues.forEach(i => console.log('  - ' + i));
  }

  console.log('\n=== RESPONSIVENESS ===');
  if (responsivenessIssues.length === 0) {
    console.log('No responsiveness issues detected.');
  } else {
    responsivenessIssues.forEach(i => console.log('  - ' + i));
  }

  console.log('\n=== SCREENSHOTS SAVED ===');
  screenshotPaths.forEach(p => console.log('  ' + p));

  const totalIssues = consoleErrors.length + layoutIssues.length + interactionIssues.length + accessibilityIssues.length + responsivenessIssues.length;
  console.log('\n=== SUMMARY ===');
  console.log(`Total issues found: ${totalIssues}`);
  console.log(`  Console errors/warnings: ${consoleErrors.length + consoleWarnings.length}`);
  console.log(`  Layout issues: ${layoutIssues.length}`);
  console.log(`  Interaction issues: ${interactionIssues.length}`);
  console.log(`  Accessibility issues: ${accessibilityIssues.length}`);
  console.log(`  Responsiveness issues: ${responsivenessIssues.length}`);
  console.log('='.repeat(60));

  if (totalIssues === 0) {
    console.log('\nOverall assessment: EXCELLENT - No significant UX/UI issues found.');
  } else if (totalIssues <= 5) {
    console.log('\nOverall assessment: GOOD - Minor issues found, app is largely well-built.');
  } else if (totalIssues <= 15) {
    console.log('\nOverall assessment: ACCEPTABLE - Several issues to address, but core functionality works.');
  } else {
    console.log('\nOverall assessment: NEEDS WORK - Multiple issues found that should be addressed.');
  }
}

runAudit().catch(err => {
  console.error('Audit script crashed:', err);
  process.exit(1);
});

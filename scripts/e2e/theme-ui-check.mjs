/**
 * A2 · Web 端暗夜模式实测（2026-10-20）。
 *
 * 为什么不能只做静态检查：CSS 变量写了不等于生效 ——
 * 变量被后面的规则覆盖、Provider 没挂上、按钮点了没反应，
 * 页面看起来都"还能用"。只有真的打开页面、读**计算样式**才算数。
 *
 * 用法（基址用 8088 的静态服务，dev server 5173 未起）：
 *   $env:UI_BASE = "http://localhost:8088"; node scripts/e2e/theme-ui-check.mjs
 */
import { chromium } from 'playwright';

const UI = process.env.UI_BASE ?? 'http://localhost:8088';

let pass = 0;
const fails = [];
function check(name, ok, got) {
  if (ok) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    fails.push(name);
    console.log(`  [FAIL] ${name}${got === undefined ? '' : `  实际=${JSON.stringify(got)}`}`);
  }
}

const browser = await chromium.launch();
const ctx = await browser.newContext();
const page = await ctx.newPage();
await page.goto(UI, { waitUntil: 'networkidle' });

// ---------- 1) 默认态：浅色 ----------
const baseTheme = await page.evaluate(() => document.documentElement.dataset.theme ?? 'light');
check('默认主题为浅色（未强制深色）', baseTheme === 'light', baseTheme);

const lightBg = await page.evaluate(() =>
  getComputedStyle(document.body).backgroundColor);
check('浅色：页面底色是浅的', isLight(lightBg), lightBg);

// ---------- 2) 切换按钮存在且可点 ----------
const toggle = page.locator('.theme-toggle').first();
check('顶栏存在主题切换按钮', (await toggle.count()) > 0, await toggle.count());

// 三态循环：日间 → 暗夜 → 跟随系统 → 日间
await toggle.click();
await page.waitForTimeout(120);
let theme = await page.evaluate(() => document.documentElement.dataset.theme ?? 'light');
check('第一次点击 → 暗夜', theme === 'dark', theme);

const darkBg = await page.evaluate(() =>
  getComputedStyle(document.body).backgroundColor);
check('暗夜：页面底色真的是深色', !isLight(darkBg), darkBg);

const darkText = await page.evaluate(() => getComputedStyle(document.body).color);
check('暗夜：正文文字色是浅的（对比度可用）', isLight(darkText), darkText);

// 深色底上的卡片要与底色区分得开，否则整页像一块黑
const cardBg = await page.evaluate(() => {
  const card = document.querySelector('.card');
  return card ? getComputedStyle(card).backgroundColor : null;
});
if (cardBg) {
  check('暗夜：卡片与页面底色不同（有层次）', cardBg !== darkBg, `card=${cardBg} bg=${darkBg}`);
}

// 浏览器原生控件也跟上了（滚动条/表单），否则会露出一抹刺眼的白
const colorScheme = await page.evaluate(() => document.documentElement.style.colorScheme);
check('暗夜：color-scheme 已同步（原生控件不露白）', colorScheme === 'dark', colorScheme);

// 核心语义变量确实被换掉了（不只是 data-theme 属性在）
const brandVar = await page.evaluate(() =>
  getComputedStyle(document.documentElement).getPropertyValue('--brand').trim());
check('暗夜：--brand 已切到深色档取值', brandVar.toLowerCase() === '#ff8a5b', brandVar);

// ---------- 3) 三态到位 ----------
await toggle.click();
await page.waitForTimeout(120);
const label3 = await page.locator('.theme-toggle').first().textContent();
check('第二次点击 → 跟随系统', /跟随系统/.test(label3 ?? ''), label3);

await toggle.click();
await page.waitForTimeout(120);
theme = await page.evaluate(() => document.documentElement.dataset.theme ?? 'light');
check('第三次点击 → 回到日间', theme === 'light', theme);

// ---------- 4) 刷新后保持（localStorage 生效） ----------
await toggle.click(); // → dark
await page.waitForTimeout(100);
await page.reload({ waitUntil: 'networkidle' });
theme = await page.evaluate(() => document.documentElement.dataset.theme ?? 'light');
check('刷新后仍为暗夜（选择被记住）', theme === 'dark', theme);

// ---------- 5) 跟随系统：系统偏好为暗时自动变暗 ----------
const sysCtx = await browser.newContext({ colorScheme: 'dark' });
const sysPage = await sysCtx.newPage();
await sysPage.goto(UI, { waitUntil: 'networkidle' });
await sysPage.evaluate(() => localStorage.setItem('dd-theme', 'system'));
await sysPage.reload({ waitUntil: 'networkidle' });
const sysTheme = await sysPage.evaluate(() => document.documentElement.dataset.theme ?? 'light');
check('跟随系统 + 系统为暗 → 自动深色', sysTheme === 'dark', sysTheme);
await sysCtx.close();

await browser.close();

/** 亮度判定：用感知加权，避免只看某个通道导致深浅误判 */
function isLight(rgb) {
  const m = String(rgb).match(/(\d+(?:\.\d+)?)/g);
  if (!m || m.length < 3) return false;
  const [r, g, b] = m.slice(0, 3).map(Number);
  return (0.299 * r + 0.587 * g + 0.114 * b) > 128;
}

console.log(`\n=== Web 端暗夜模式实测：通过 ${pass} 项，失败 ${fails.length} 项 ===`);
if (fails.length) {
  for (const f of fails) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);

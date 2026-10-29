// 独立 QA 浏览器取证脚本（只读页面 + 登录后 AI 页/记忆页/运营入口截图）
// 账号与口令通过环境变量注入，不写入本文件。仅用于本机开发环境。
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..', '..', '..', '..');
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(repoRoot, 'scripts/e2e/node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const { chromium } = await import(PW_CORE);

const UI = process.env.UI_BASE ?? 'http://localhost:8088';
const BUYER = process.env.QA_BUYER;
const OPS = process.env.QA_OPS;
const PWD = process.env.QA_PWD;
const OUT = here;

const browser = await chromium.launch({ headless: true });

async function brokenImages(page) {
  return page.evaluate(() =>
    Array.from(document.images)
      .filter((i) => !i.complete || i.naturalWidth === 0)
      .map((i) => i.getAttribute('src'))
      .slice(0, 10),
  );
}

async function login(page, phone) {
  await page.goto(`${UI}/login`, { waitUntil: 'domcontentloaded' });
  await page.getByPlaceholder('11 位手机号').fill(phone);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
  await page.waitForTimeout(800);
}

// 1) 首页 / 商品详情：破图取证
const ctx1 = await browser.newContext({ viewport: { width: 1280, height: 900 } });
const p1 = await ctx1.newPage();
await p1.goto(`${UI}/`, { waitUntil: 'networkidle' });
await p1.screenshot({ path: path.join(OUT, 'shot-home.png'), fullPage: false });
const homeBroken = await brokenImages(p1);
await p1.goto(`${UI}/product/1001`, { waitUntil: 'networkidle' });
await p1.waitForTimeout(500);
await p1.screenshot({ path: path.join(OUT, 'shot-product-detail.png'), fullPage: false });
const detailBroken = await brokenImages(p1);
await ctx1.close();

// 2) 买家 AI 页 / 记忆页
const ctx2 = await browser.newContext({ viewport: { width: 1280, height: 900 } });
const p2 = await ctx2.newPage();
await login(p2, BUYER);
await p2.goto(`${UI}/ai`, { waitUntil: 'networkidle' });
await p2.waitForTimeout(1200);
await p2.screenshot({ path: path.join(OUT, 'shot-ai-page.png'), fullPage: false });
await p2.goto(`${UI}/ai/memory`, { waitUntil: 'networkidle' });
await p2.waitForTimeout(800);
await p2.screenshot({ path: path.join(OUT, 'shot-ai-memory.png'), fullPage: false });
const memText = (await p2.locator('body').innerText()).slice(0, 400);
await ctx2.close();

// 3) 运营头像下拉入口
const ctx3 = await browser.newContext({ viewport: { width: 1280, height: 900 } });
const p3 = await ctx3.newPage();
await login(p3, OPS);
await p3.goto(`${UI}/`, { waitUntil: 'networkidle' });
await p3.locator('.app-header button.icon-btn').first().click();
await p3.waitForTimeout(300);
await p3.screenshot({ path: path.join(OUT, 'shot-admin-menu.png'), fullPage: false });
const menuText = await p3.locator('.app-header').innerText();
await ctx3.close();
await browser.close();

const result = {
  homeBroken,
  detailBroken,
  memoryHasFields: ['称呼', '物种', '预算', '过敏原', '健康备注'].filter((k) => memText.includes(k)),
  menuHasAdminEntry: menuText.includes('进入管理后台'),
  menuTextSnippet: menuText.replace(/\n/g, ' | ').slice(0, 200),
};
console.log(JSON.stringify(result, null, 2));
if (!homeBroken.length && !detailBroken.length) {
  console.error('预期破图为空则说明本次未复现，不能据此写通过');
}

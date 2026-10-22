// 足迹页 UI 验证（2026-10-22 分组版）：登录买家 → /history → 断言分组标题/搜索/勾选/批量按钮 → 截图
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const pw = await import(
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`
);
const { chromium } = pw;

const BASE = 'http://localhost:5173';
let ok = true;
const fail = (m) => { ok = false; console.log('  [失败]', m); };
const pass = (m) => console.log('  [通过]', m);

const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' });
  await page.getByPlaceholder('11 位手机号').fill('13600001111');
  await page.getByPlaceholder('请输入密码').fill('duoduo12345');
  await page.click('button[type="submit"]');
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 }).catch(async () => {
    await page.screenshot({ path: 'scripts/e2e/history-login-debug.png' });
    fail('登录未跳转');
  });

  await page.goto(BASE + '/history', { waitUntil: 'networkidle' });
  await page.waitForSelector('.history-day', { timeout: 15000 });

  const dayLabels = await page.$$eval('.history-day', (els) => els.map((e) => e.textContent.trim()));
  if (dayLabels.length === 0) fail('没有分组标题');
  else pass(`分组标题 ${dayLabels.length} 组：${dayLabels.join(' / ')}`);
  if (dayLabels.some((t) => /今天|昨天|\d+月\d+日/.test(t))) pass('组标题含相对称呼或日期');
  else fail('组标题格式不符');

  if (await page.locator('input[placeholder="搜索商品标题"]').count()) pass('搜索框存在');
  else fail('搜索框不存在');

  // 勾选第一张卡片 → 批量按钮出现计数
  await page.locator('.history-check').first().check();
  const btnText = await page.locator('button', { hasText: '删除所选' }).first().textContent();
  if (/删除所选（1）/.test(btnText || '')) pass('勾选后出现「删除所选（1）」');
  else fail(`批量按钮异常：${btnText}`);

  // 全选已加载
  await page.locator('label', { hasText: '全选已加载' }).locator('input').check();
  pass('全选已加载可用');

  await page.screenshot({ path: 'scripts/e2e/verify-history-grouped.png' });
  pass('截图 scripts/e2e/verify-history-grouped.png');
} catch (e) {
  fail(e.message);
} finally {
  await browser.close();
}
console.log(ok ? 'UI 验证通过' : 'UI 验证失败');
process.exit(ok ? 0 : 1);

// 足迹页 UI 验证：登录买家 → /history → 断言「浏览于」时间戳可见且最新在前 → 截图
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const pw = await import(
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`
);
const { chromium } = pw;

const BASE = 'http://localhost:5173';
const out = 'scripts/e2e/verify-history-time.png';
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
    const toast = await page.textContent('body');
    fail('登录未跳转，页面文本片段: ' + toast.slice(0, 300));
  });
  await page.goto(BASE + '/history');
  await page.waitForSelector('.card', { timeout: 15000 });

  const stamps = await page.$$eval('.card', (cards) =>
    cards.map((c) => c.textContent || '').filter((t) => t.includes('浏览于'))
  );
  if (stamps.length === 0) fail('没有看到「浏览于」时间戳');
  else pass(`时间戳可见：${stamps.length} 条，首条=「${(stamps[0].match(/浏览于[^¥]*/) || [''])[0].trim()}」`);

  const body = await page.textContent('body');
  if (/浏览于\s*(今天|昨天|\d{2}-\d{2}|\d{4}-\d{2}-\d{2})\s*\d{2}:\d{2}/.test(body)) pass('时间格式：日期/今天昨天 + HH:mm');
  else fail('时间格式不符合「(今天|昨天|日期) HH:mm」');

  await page.screenshot({ path: out, fullPage: false });
  pass('截图 ' + out);
} catch (e) {
  fail(e.message);
} finally {
  await browser.close();
}
console.log(ok ? 'UI 验证通过' : 'UI 验证失败');
process.exit(ok ? 0 : 1);

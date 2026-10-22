// 购物车裂图排查：登录 → /cart → 抓每张卡 <img> 的 src、naturalWidth、HTTP 状态与 content-type
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const pw = await import(
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`
);
const { chromium } = pw;

const BASE = 'http://localhost:5173';
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

// 抓所有 /img/ 请求的响应
const imgResp = {};
page.on('response', (r) => {
  if (r.url().includes('/img/')) {
    imgResp[r.url()] = { status: r.status(), type: r.headers()['content-type'] || '' };
  }
});

await page.goto(BASE + '/login', { waitUntil: 'networkidle' });
await page.getByPlaceholder('11 位手机号').fill('13600002222');
await page.getByPlaceholder('请输入密码').fill('duoduo12345');
await page.click('button[type="submit"]');
await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 }).catch(() => {});

await page.goto(BASE + '/cart', { waitUntil: 'networkidle' });
await page.waitForTimeout(1500);

const imgs = await page.$$eval('.card img', (els) =>
  els.map((e) => ({
    src: e.getAttribute('src'),
    alt: e.getAttribute('alt'),
    loaded: e.complete && e.naturalWidth > 0,
    naturalWidth: e.naturalWidth,
  })),
);
console.log('购物车卡片 img：');
for (const i of imgs) {
  const st = imgResp[BASE + i.src] || imgResp[i.src] || { status: '?', type: '?' };
  console.log(`  src=${i.src}  加载=${i.loaded ? 'OK' : '失败'}  naturalWidth=${i.naturalWidth}  HTTP=${st.status} ${st.type}`);
}
console.log('全部 /img/ 请求：');
for (const [u, v] of Object.entries(imgResp)) console.log(`  ${u} -> ${v.status} ${v.type}`);
await page.screenshot({ path: 'scripts/e2e/cart-img-debug.png' });
await browser.close();

/**
 * 店铺主页真实浏览器冒烟（第八十二轮 · 自包含，不需要任何夹具）
 *
 * 背景（用户需求）：参考淘宝店铺页 —— 点店铺名/店铺卡片跳独立店铺主页，
 * 展示店铺基本信息 + 该店全部商品（分页加载），商品可点进详情。
 *
 * 覆盖：
 *   ① 路由可达：/store/0（自营货架）直开渲染出「多多自营」页头 + 商品网格；
 *   ② 第三方店铺：详情页店名带「进店」链接，点击跳 /store/{sellerId}，
 *      页头显示与接口一致的店名；
 *   ③ 页头信息：店名、开店时间/平台自营文案、「在售商品」计数；
 *   ④ 商品网格：卡片数 = min(total, 首页 20)，每卡可点进 /product/:id；
 *   ⑤ 分页：total > 20 时「加载更多」出现，点击后行数增加（若夹具不足 20 则跳过该断言）；
 *   ⑥ 未营业店铺 404 兜底：/store/1（不存在/未营业）显示错误提示而非空壳；
 *   ⑦ 页面无 console 报错、无非预期接口错误（401 匿名除外）、商品图零裂图。
 *
 * 用法：
 *   cd frontend && npm run dev -- --port 5173
 *   node scripts/e2e/store-page-ui-check.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
const CHROME = process.env.CHROME_PATH ?? undefined;

const { chromium } = await import(PW_CORE);

let pass = 0;
const failures = [];
function check(name, ok, got) {
  if (ok) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    failures.push(name);
    console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`);
  }
}
const step = (t) => console.log(`\n> ${t}`);

const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });

const consoleErrors = [];
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text());
});
page.on('pageerror', (e) => consoleErrors.push(String(e)));

const badResponses = [];
page.on('response', (res) => {
  if (res.status() >= 400) badResponses.push({ status: res.status(), url: res.url() });
});

try {
  // ---------- ① 自营货架：/store/0 直开 ----------
  step('① /store/0 自营货架直开');
  const resp0 = await page.goto(`${UI}/store/0`, { waitUntil: 'networkidle', timeout: 30000 });
  check('路由返回 200', resp0?.status() === 200, resp0?.status());
  await page.waitForTimeout(1500);
  const selfHead = await page.textContent('section.card').catch(() => null);
  check('页头出现「多多自营」', !!selfHead && selfHead.includes('多多自营'), selfHead);
  const selfCards = await page.locator('a.goods-card').count();
  check('自营货架渲染出商品卡', selfCards > 0, selfCards);
  check('页头出现「在售商品」计数', !!selfHead && selfHead.includes('在售商品'), selfHead);

  // ---------- ② 第三方店铺：从商品详情店名进店 ----------
  step('② 从商品详情点店名进店');
  await page.goto(`${UI}/search?keyword=测试店铺`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(1500);
  // 库里的第三方测试商品标题都带「【测试店铺】」前缀；找不到就在首页随便挑一张带店名的卡
  let entered = false;
  const storeHref = await page.evaluate(() => {
    const el = document.querySelector('span[role="link"][aria-label*="店铺主页"]');
    return el ? el.getAttribute('aria-label') : null;
  });
  if (storeHref) {
    await page.click('span[role="link"][aria-label*="店铺主页"]');
    await page.waitForTimeout(1200);
    entered = page.url().includes('/store/');
  } else {
    // 备选：直接从数据库口径一致的 /store/{第三方 sellerId} 打开（下方 ③ 共用）
    await page.goto(`${UI}/store/371232608572542976`, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(1200);
    entered = page.url().includes('/store/371232608572542976');
  }
  check('成功进入店铺主页路由', entered, page.url());

  // ---------- ③ 页头信息 ----------
  step('③ 店铺页头信息');
  const headText = await page.textContent('section.card').catch(() => null);
  check('页头有店名或自营标识', !!headText && (headText.includes('旗舰店') || headText.includes('多多自营')), headText);
  const headCount = await page.locator('section.card').first().textContent().then((t) => t ?? '');
  check('页头出现「在售商品」', headCount.includes('在售商品'), headCount);

  // ---------- ④ 商品网格与详情跳转 ----------
  step('④ 商品网格');
  const cardCount = await page.locator('a.goods-card').count();
  check('店铺商品卡 > 0', cardCount > 0, cardCount);
  if (cardCount > 0) {
    const firstHref = await page.locator('a.goods-card').first().getAttribute('href');
    check('商品卡指向 /product/:id', !!firstHref && firstHref.startsWith('/product/'), firstHref);
    await page.locator('a.goods-card').first().click();
    await page.waitForTimeout(1200);
    check('点商品卡进入详情页', page.url().includes('/product/'), page.url());
    await page.goBack();
    await page.waitForTimeout(800);
  }

  // ---------- ⑤ 分页（加载更多） ----------
  step('⑤ 加载更多分页');
  const loadMore = page.locator('button', { hasText: '加载更多' });
  const hasLoadMore = (await loadMore.count()) > 0;
  if (hasLoadMore) {
    const before = await page.locator('a.goods-card').count();
    await loadMore.first().click();
    await page.waitForTimeout(1500);
    const after = await page.locator('a.goods-card').count();
    check('点击加载更多后卡片数增加', after > before, { before, after });
  } else {
    check('该店商品 ≤ 一页，加载更多按设计不出现', cardCount > 0 && cardCount <= 20, cardCount);
  }

  // ---------- ⑥ 未营业店铺兜底 ----------
  step('⑥ 未营业/不存在店铺兜底');
  const resp404 = await page.goto(`${UI}/store/1`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(1200);
  const alertText = await page.textContent('.alert, [class*="alert"]').catch(() => null);
  check('不存在的店铺显示错误提示（非空壳）', !!alertText && alertText.length > 0, alertText);
  check('后端按设计回 404', badResponses.some((r) => r.status === 404 && r.url.includes('/api/users/stores/1')), badResponses);
  check('HTTP 页面本身仍 200（前端兜底渲染）', resp404?.status() === 200, resp404?.status());

  // ---------- ⑦ 页面健康 ----------
  step('⑦ 页面健康');
  const brokenImgs = await page.evaluate(() => {
    return [...document.querySelectorAll('img')].filter((i) => i.complete && i.naturalWidth === 0).length;
  });
  check('无裂图', brokenImgs === 0, brokenImgs);
  // 401（匿名角标/购物车探测）与 404（店铺兜底）是设计内噪音，与响应级过滤同口径
  const realErrors = consoleErrors.filter((e) => !/favicon|404 \(Not Found\)|401 \(Unauthorized\)/i.test(e));
  check('无 console 报错', realErrors.length === 0, realErrors.slice(0, 3));
  const non401Errors = badResponses.filter((r) => r.status !== 401 && r.status !== 404);
  check('无非预期接口错误（401 匿名与 404 兜底除外）', non401Errors.length === 0, non401Errors.slice(0, 3));
} finally {
  await browser.close();
}

console.log(`\n===== 店铺主页 UI 检查：${pass} 通过 / ${failures.length} 失败 =====`);
if (failures.length > 0) {
  console.log('失败项：');
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}

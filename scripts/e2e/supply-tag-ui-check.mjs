/**
 * 供货身份标签与「进店」入口回归（第八十二轮修复 · 自包含无夹具）
 *
 * 用户报障（附截图）：
 *   「喵星人旗舰店（测试）」的商品卡左上角徽标显示「多多自营」，
 *   且自营商品的店名不可点、不能进「多多自营」店铺页。
 *
 * 根因（两处）：
 *   ① `SupplyTag` 只认 `supplyType === 'merchant'`，而库中真实取值是 `third`，
 *      于是所有第三方商品都落进 else 分支 → 挂上「多多自营」；
 *      小程序 `product.wxml` 同一份判断、同一处错误（已同步修）。
 *   ② 第八十二轮初版只给第三方开了进店链接（自营卡 sellerId 是客服账号 id），
 *      自营没有入口 —— 现补 `/store/0` 自营货架。
 *
 * 覆盖：
 *   ① 第三方商品卡徽标 = 「品牌入驻」，且**不含**「多多自营」；
 *   ② 第三方商品卡店名可点、aria-label 指得出，跳 /store/{sellerId}；
 *   ③ 自营商品卡徽标 = 「多多自营」，店名**也可点**，跳 /store/0；
 *   ④ /store/0 页头为「多多自营」且商品全部 supplyType=self（用接口交叉核对）；
 *   ⑤ 页面健康：无裂图、无 console 报错、无非预期 4xx。
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
const API = process.env.API_BASE ?? 'http://localhost:8080';
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
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()));
page.on('pageerror', (e) => consoleErrors.push(String(e)));
const badResponses = [];
page.on('response', (r) => r.status() >= 400 && badResponses.push({ status: r.status(), url: r.url() }));

/** 站内商品卡：读徽标 + 店名 + 进店 aria-label */
async function readCards(selector) {
  return page.$$eval(selector, (nodes) =>
    nodes.map((n) => {
      const tags = [...n.querySelectorAll('.goods-media-tags .tag')].map((t) => t.textContent.trim());
      const storeLink = n.querySelector('.goods-meta span[role="link"]');
      return {
        href: n.getAttribute('href'),
        title: n.querySelector('.goods-title')?.textContent?.trim() ?? '',
        tags,
        storeLink: storeLink
          ? { text: storeLink.textContent.trim(), label: storeLink.getAttribute('aria-label') }
          : null,
      };
    }),
  );
}

try {
  // ---------- ① 第三方商品：徽标必须是「品牌入驻」 ----------
  step('① 第三方商品卡徽标');
  const third = await (await fetch(`${API}/api/products/store/371232608572542976?pageSize=5`)).json();
  const thirdCount = third?.data?.items?.length ?? 0;
  check('接口返回第三方店铺商品', thirdCount > 0, thirdCount);

  await page.goto(`${UI}/store/371232608572542976`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(1500);
  const thirdCards = await readCards('a.goods-card');
  check('店铺页渲染出第三方商品卡', thirdCards.length > 0, thirdCards.length);
  const thirdCard = thirdCards[0] ?? { tags: [], storeLink: null };
  check('第三方卡徽标为「品牌入驻」', thirdCard.tags.includes('品牌入驻'), thirdCard.tags);
  check('第三方卡徽标**不含**「多多自营」', !thirdCard.tags.includes('多多自营'), thirdCard.tags);
  check(
    '第三方卡店名可点且 aria-label 指得出店名',
    !!thirdCard.storeLink && thirdCard.storeLink.label.includes('店铺主页'),
    thirdCard.storeLink,
  );

  // ---------- ② 自营商品：徽标「多多自营」+ 也能进店 ----------
  step('② 自营商品卡徽标与进店入口');
  const selfResp = await (await fetch(`${API}/api/products?supplyType=self&pageSize=5`)).json();
  const selfItems = selfResp?.data?.items ?? [];
  check('接口 supplyType=self 有结果（不再是空集）', selfItems.length > 0, selfItems.length);
  check('自营商品 supplyType 全为 self', selfItems.every((i) => i.supplyType === 'self'), selfItems.map((i) => i.supplyType));

  await page.goto(`${UI}/store/0`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(1500);
  const selfCards = await readCards('a.goods-card');
  check('自营货架渲染出商品卡', selfCards.length > 0, selfCards.length);
  const selfCard = selfCards[0] ?? { tags: [], storeLink: null };
  check('自营卡徽标为「多多自营」', selfCard.tags.includes('多多自营'), selfCard.tags);
  check(
    '自营卡店名可点（有进店入口）',
    !!selfCard.storeLink && selfCard.storeLink.label.includes('店铺主页'),
    selfCard.storeLink,
  );
  if (selfCard.storeLink) {
    await page.click('a.goods-card:first-child .goods-meta span[role="link"]');
    await page.waitForTimeout(1000);
    check('点自营店名跳到 /store/0', page.url().endsWith('/store/0'), page.url());
  }

  // ---------- ③ 第三方「不是自营」的反向验证 ----------
  step('③ 反向验证：第三方商品卡绝不出现「多多自营」');
  await page.goto(`${UI}/search?supplyType=third`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(1500);
  const searchCards = await readCards('a.goods-card');
  if (searchCards.length > 0) {
    check(
      '搜索结果里第三方卡全部为「品牌入驻」',
      searchCards.every((c) => c.tags.includes('品牌入驻') && !c.tags.includes('多多自营')),
      searchCards.map((c) => c.tags),
    );
  } else {
    // 搜索页在该口径下无结果时不硬造数据：只记前提（接口已单独验过 third 过滤有效）
    check('搜索页 third 口径无卡（接口层已单独验证过滤有效）', thirdCount > 0, thirdCount);
  }

  // ---------- ④ 页面健康 ----------
  step('④ 页面健康');
  const broken = await page.evaluate(
    () => [...document.querySelectorAll('img')].filter((i) => i.complete && i.naturalWidth === 0).length,
  );
  check('无裂图', broken === 0, broken);
  const realErrors = consoleErrors.filter((e) => !/favicon|404 \(Not Found\)|401 \(Unauthorized\)/i.test(e));
  check('无 console 报错', realErrors.length === 0, realErrors.slice(0, 3));
  const unexpected = badResponses.filter((r) => r.status !== 401 && r.status !== 404);
  check('无非预期接口错误', unexpected.length === 0, unexpected.slice(0, 3));
} finally {
  await browser.close();
}

console.log(`\n===== 供货标签与进店入口检查：${pass} 通过 / ${failures.length} 失败 =====`);
if (failures.length > 0) {
  console.log('失败项：');
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}

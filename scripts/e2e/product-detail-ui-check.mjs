/**
 * 商品详情页真实浏览器冒烟（第七十三轮 · 自包含，不需要任何夹具）
 *
 * 背景（R73 用户报障）：
 *   1. 小程序端有自营/入驻身份标签，Web 端曾没有 —— 两端判定规则须一致（supplyType）；
 *   2. 小程序有「商品介绍」图片，Web 缺失；主图不可点击放大；两端规格参数不一致；
 *   3. 评分与评价必须是**真实数据驱动**：库内没有评价就如实显示「暂无评分」，
 *      不许再出现种子期残留的「4.7 / 3240 条评价」假数据。
 *
 * 覆盖：
 *   ① 身份标签：详情页出现「多多自营」或「品牌入驻」（与小程序同规则：supplyType）；
 *   ② 商品介绍：区块存在且渲染出图片；
 *   ③ 主图交互：点击放大出弹层（modal-mask），Esc 与关闭按钮都能收起；
 *   ④ 缩略图切换：点击缩略图后主图 src 变化；
 *   ⑤ 包装规格：区块出现（spec 行 / SKU 编码 / 库存）；
 *   ⑥ 评分真实数据驱动：页面评分区与评价域聚合接口（/api/reviews/product/:id/summary）
 *      逐字一致 —— total=0 → 「暂无评分」；total>0 → 「N 条评价」；
 *   ⑦ 页面无 console 报错、无非预期接口错误（401 匿名除外）、商品图零裂图。
 *
 * 用法：
 *   cd frontend && npm run dev -- --port 5173        （或交给 scripts/e2e/run-ui-checks.ps1 托管）
 *   node scripts/e2e/product-detail-ui-check.mjs
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
  step('从首页进入商品详情');
  const resp = await page.goto(UI + '/', { waitUntil: 'networkidle', timeout: 30000 });
  check('首页返回 200', resp?.status() === 200, resp?.status());
  await page.waitForTimeout(2500);

  // 拿第一个商品链接：只认「href 指向 /product/:id」这一不变事实（ home 套件同款选择器策略）
  const detailHref = await page.evaluate(() => {
    const a = document.querySelector('a[href*="product"]');
    return a ? a.getAttribute('href') : null;
  });
  check('首页存在商品链接', !!detailHref, detailHref);
  if (!detailHref) throw new Error('首页没有商品链接，环境异常，后续断言全部无法执行');

  await page.goto(new URL(detailHref, UI).href, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(2000);

  const spuId = new URL(page.url()).pathname.split('/').pop();
  check('详情 URL 形如 /product/:id', !!spuId && /^\d+$/.test(spuId), page.url());

  step('① 身份标签（R73-1：与小程序同规则 supplyType）');
  const badge = await page.evaluate(() => {
    const tags = [...document.querySelectorAll('.pd-layout .tag, .app-main .tag')];
    return tags.map((t) => t.textContent?.trim());
  });
  check(
    '详情页出现「多多自营」或「品牌入驻」身份标签',
    badge.includes('多多自营') || badge.includes('品牌入驻'),
    badge,
  );

  step('② 商品介绍（R73-2①：小程序有图，Web 也必须有）');
  const intro = await page.evaluate(() => {
    const h3s = [...document.querySelectorAll('h3')];
    const node = h3s.find((h) => h.textContent?.includes('商品介绍'));
    if (!node) return null;
    // 介绍图片在该卡片（h3 的父级容器）内
    const card = node.closest('section, div');
    return card ? card.querySelectorAll('img').length : 0;
  });
  check('「商品介绍」区块存在', intro !== null, intro);
  check('「商品介绍」渲染出图片（与小程序同池）', (intro ?? 0) > 0, intro);

  step('③ 主图点击放大（R73-2②）');
  const hasMainImg = await page.locator('.pd-gallery-main img').count();
  check('主图存在', hasMainImg > 0, hasMainImg);
  if (hasMainImg > 0) {
    await page.locator('.pd-gallery-main img').first().click();
    await page.waitForTimeout(400);
    const maskOpen = await page.locator('.modal-mask').count();
    check('点击主图弹出放大预览（.modal-mask）', maskOpen === 1, maskOpen);
    const closeBtn = await page.locator('.modal-mask button[aria-label="关闭"]').count();
    check('弹层有关闭按钮', closeBtn === 1, closeBtn);
    // Esc 关闭（Modal 组件能力，R72 已实现）
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
    check('Esc 能收起弹层', (await page.locator('.modal-mask').count()) === 0, 'Esc 后 modal-mask 应为 0');
    // 关闭按钮路径：再开一次，用按钮关
    await page.locator('.pd-gallery-main img').first().click();
    await page.waitForTimeout(300);
    await page.locator('.modal-mask button[aria-label="关闭"]').click();
    await page.waitForTimeout(300);
    check('关闭按钮能收起弹层', (await page.locator('.modal-mask').count()) === 0, '按钮后 modal-mask 应为 0');
  }

  step('④ 缩略图切换主图');
  const thumbs = await page.locator('.pd-gallery-main ~ div img').count();
  if (thumbs > 1) {
    const srcBefore = await page.locator('.pd-gallery-main img').first().getAttribute('src');
    await page.locator('.pd-gallery-main ~ div img').nth(1).click();
    await page.waitForTimeout(400);
    const srcAfter = await page.locator('.pd-gallery-main img').first().getAttribute('src');
    check('点击缩略图后主图切换', srcBefore !== srcAfter, { srcBefore, srcAfter });
  } else {
    check('缩略图多于 1 张（可切换）', false, thumbs);
  }

  step('⑤ 包装规格（R73-2③：与小程序逐行同构）');
  const specText = await page.evaluate(() => document.body.innerText || '');
  check('「包装规格」区块出现', specText.includes('包装规格'), 'body 不含「包装规格」');
  check(
    '规格行出现（SKU 编码 / 规格 / 当前库存 至少其一）',
    specText.includes('SKU 编码') || specText.includes('当前库存') || specText.includes('规格'),
    '未命中任何规格行标志',
  );

  step('⑥ 评分真实数据驱动（R73-3①：页面 = 评价域聚合）');
  // 页面上评分区只有两种合法形态，取决于评价域实时聚合：
  //   total=0 → 「暂无评分」；total>0 → 「N 条评价」。种子假数据已清除，这里两端都钉。
  const summary = await page.evaluate(async (id) => {
    const r = await fetch(`/api/reviews/product/${id}/summary`);
    return r.json();
  }, spuId);
  check('评价聚合接口可达', summary?.code === 'SUCCESS', summary);
  const total = Number(summary?.data?.total ?? -1);
  const body = await page.evaluate(() => document.body.innerText || '');
  if (total === 0) {
    check('库内 0 评价 → 页面如实显示「暂无评分」', body.includes('暂无评分'), 'body 不含「暂无评分」');
    check(
      '不得残留种子假数据（无「条评价」文案）',
      !body.includes('条评价'),
      'body 出现了「条评价」但库内 total=0',
    );
  } else if (total > 0) {
    check(
      `库内 ${total} 条评价 → 页面显示「${total} 条评价」`,
      body.includes(`${total} 条评价`),
      'body 未出现与聚合一致的条数文案',
    );
  } else {
    check('聚合接口返回合法 total', false, summary?.data);
  }

  step('⑦ 页面健康');
  const broken = await page.evaluate(() =>
    [...document.querySelectorAll('img')].filter((im) => im.complete && im.naturalWidth === 0).map((im) => im.getAttribute('src')));
  check('详情页商品图零裂图', broken.length === 0, broken.slice(0, 4));
  // 放行规则沿用各套件惯例：401 匿名例外按 URL 判定，其余 4xx/5xx 一律上报
  const unexpectedHttp = badResponses.filter((r) => r.status !== 401);
  const realErrors = consoleErrors.filter(
    (t) => !/favicon|404 \(Not Found\)/i.test(t) && !(/\(Unauthorized\)/.test(t) && unexpectedHttp.length === 0),
  );
  check('详情页无 console 报错', realErrors.length === 0, realErrors.slice(0, 3));
  check('详情页无非预期的接口错误（401 匿名除外）', unexpectedHttp.length === 0, unexpectedHttp.slice(0, 3));
} finally {
  await browser.close();
}

console.log(`\n=== 商品详情页冒烟：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  failures.forEach((f) => console.log('  - ' + f));
  process.exit(1);
}

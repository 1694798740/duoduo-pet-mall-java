/**
 * 商品管理改造 · UI 验收（2026-10-24，第九十三轮）。
 *
 * 覆盖（全部打容器产物，不是 dev server）：
 *  ① 商家端编辑弹窗出现「商品介绍 / 富文本 / 规格参数 / 包装规格」四块，且可保存；
 *  ② 商家端「商品分析」页：KPI 卡 + 逐商品表格（销量/成交额/退款/评分/好评率）+ 评价弹窗；
 *  ③ 商城详情页：第一张图 = 主图；富文本渲染为 .rich-view（而非纯文本）。
 *
 * 前置：容器在跑（8088 / 8091 / 8080）；卖家 13600003333 已有 R93 冒烟商品。
 * 用法：NO_PROXY="*" node scripts/e2e/product-manage-r93-ui-check.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const MALL = process.env.UI_BASE ?? 'http://localhost:8088';
const MERCHANT = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';

let pass = 0;
const failures = [];
function check(name, ok, got) {
  if (ok) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    failures.push(name);
    console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`.slice(0, 300));
  }
}
const step = (t) => console.log(`\n> ${t}`);

async function call(method, p, body, token, entry) {
  const res = await fetch(API + p, {
    method,
    headers: {
      ...(body ? { 'content-type': 'application/json' } : {}),
      ...(token ? { authorization: 'Bearer ' + token } : {}),
      ...(entry ? { 'x-duoduo-entry': entry } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}

const token = (await call('POST', '/api/auth/login/token',
  { phone: SELLER, password: PWD }, null, 'merchant')).body?.data?.accessToken;
const products = (await call('GET', '/api/seller/products?page=1&pageSize=100', null, token)).body?.data;
const target = (products?.items ?? []).find((p) => String(p.title).startsWith('R93 冒烟商品'));
if (!target) {
  console.log('找不到 R93 冒烟商品，请先跑 scripts/product-manage-r93-smoke.mjs');
  process.exit(1);
}
// 保证它在售（冒烟最后一步会把它留在下架态）
if (target.status === 'OFF_SHELF') {
  await call('POST', `/api/seller/products/${target.id}/status`, { action: 'ON_SALE' }, token);
}
const before = (await call('GET', `/api/seller/products/${target.id}`, null, token)).body?.data;

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH ?? undefined, args: ['--no-sandbox'] });
let savedSpecCount = null;

try {
  const page = await browser.newPage({ viewport: { width: 1500, height: 940 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));

  step('商家端登录');
  await page.goto(`${MERCHANT}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[placeholder*="手机号"]').fill(SELLER);
  await page.locator('input[type="password"]').fill(PWD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
  check('商家端登录成功', !page.url().includes('/login'), page.url());

  step('商品编辑弹窗：四类内容字段');
  await page.goto(`${MERCHANT}/seller/products`, { waitUntil: 'networkidle' });
  await page.getByText('R93 冒烟商品', { exact: false }).first().waitFor({ timeout: 20000 });
  const row = page.locator('tr', { hasText: 'R93 冒烟商品' }).first();
  await row.getByRole('button', { name: '编辑' }).click();
  await page.locator('textarea[placeholder*="特点"]').waitFor({ timeout: 15000 });
  check('存在「商品介绍」文本域', (await page.locator('textarea[placeholder*="特点"]').count()) >= 1);
  check('存在富文本编辑器 .rich-editor', (await page.locator('.rich-editor').count()) === 1);
  check('存在两个键值编辑器（规格参数/包装规格）', (await page.locator('.kv-editor').count()) === 2);
  check('富文本编辑器内有工具栏（加粗/标题/插图）',
    (await page.locator('.rich-toolbar button').count()) >= 8);
  const richHtml = await page.locator('.rich-body').first().innerHTML();
  check('富文本回填了已保存的标题与加粗', /h2|strong/i.test(richHtml), richHtml.slice(0, 100));

  step('保存规格参数改动');
  const kv = page.locator('.kv-editor').first();
  await kv.getByRole('button', { name: /添加一行/ }).click();
  const nameInputs = kv.locator('input.input');
  const last = (await nameInputs.count()) - 2;
  await nameInputs.nth(last).fill('保质期');
  await nameInputs.nth(last + 1).fill('18 个月');
  await page.getByRole('button', { name: '保存内容' }).click();
  await page.waitForTimeout(2500);
  const after = (await call('GET', `/api/seller/products/${target.id}`, null, token)).body?.data;
  savedSpecCount = (after?.specParams ?? []).length;
  // 幂等：每次运行前条数可能已包含上次新增的「保质期」，断言用 +1 而不是写死 3
  check('规格参数已保存到服务端（条数 +1）', savedSpecCount === (before?.specParams ?? []).length + 1, {
    before: (before?.specParams ?? []).length, after: savedSpecCount,
  });
  check('新增参数名与值正确', (after?.specParams ?? []).some((x) => x.name === '保质期' && x.value === '18 个月'),
    after?.specParams);

  step('改价 / 改库存（不触发重审）');
  // 上一步「保存内容」会关闭弹窗，重新打开编辑（价格与库存是独立保存入口）
  await page.locator('tr', { hasText: 'R93 冒烟商品' }).first().getByRole('button', { name: '编辑' }).click();
  await page.getByRole('button', { name: '保存价格/库存' }).waitFor({ timeout: 15000 });
  const priceTable = page.locator('table', { hasText: '价格（元）' }).last();
  const priceInput = priceTable.locator('input').first();
  const stockInput = priceTable.locator('input').nth(1);
  const oldPrice = Number(await priceInput.inputValue());
  const oldStock = Number(await stockInput.inputValue());
  await priceInput.fill((oldPrice + 1).toFixed(2));
  await stockInput.fill(String(oldStock + 1));
  await page.getByRole('button', { name: '保存价格/库存' }).click();
  await page.waitForTimeout(2500);
  const afterPriceSave = (await call('GET', `/api/seller/products/${target.id}`, null, token)).body?.data;
  const firstSku = (afterPriceSave?.skus ?? [])[0];
  check('界面改价已落库（+1 元）', firstSku?.price === Math.round((oldPrice + 1) * 100),
    { before: oldPrice, after: firstSku?.price });
  check('界面改库存已落库（+1）', firstSku?.stock === oldStock + 1, { before: oldStock, after: firstSku?.stock });
  check('改价未触发重新审核', afterPriceSave?.auditStatus === before?.auditStatus,
    { before: before?.auditStatus, after: afterPriceSave?.auditStatus });

  step('商品分析页');
  await page.goto(`${MERCHANT}/seller/product-insights`, { waitUntil: 'networkidle' });
  await page.getByRole('heading', { name: '商品分析' }).waitFor({ timeout: 20000 });
  check('页面标题为「商品分析」', true);
  check('KPI 卡包含好评率', (await page.getByText('好评率', { exact: true }).count()) >= 1);
  check('存在商品统计表格', (await page.locator('table tbody tr').count()) >= 1);
  const headText = await page.locator('table thead').innerText();
  check('表头含 销量/成交额/退款/评分/好评率',
    ['销量', '成交额', '退款', '评分', '好评率'].every((k) => headText.includes(k)), headText.replace(/\s+/g, ' '));

  step('评价弹窗');
  await page.locator('tr', { hasText: 'R93 冒烟商品' }).first().getByRole('button', { name: '查看评价' }).click();
  await page.waitForTimeout(1200);
  // Modal 的实现细节（类名/portal 层级）不该成为断言对象：只看"页面上是否出现了评价面板"
  const bodyText = await page.locator('body').innerText();
  check('弹出商品评价面板（含「商品评价」标题）', /商品评价/.test(bodyText), bodyText.slice(0, 80));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);

  step('商城详情页：主图第一张 + 富文本渲染');
  await page.goto(`${MALL}/product/${target.id}`, { waitUntil: 'networkidle' });
  const mainSrc = await page.locator('.pd-gallery-main img').first().getAttribute('src');
  const detail = (await call('GET', `/api/products/${target.id}`)).body?.data;
  check('详情页首图 = 商品主图', mainSrc === detail?.images?.[0], { mainSrc, first: detail?.images?.[0] });
  check('富文本详情以 .rich-view 渲染', (await page.locator('.rich-view').count()) >= 1);
  const richViewText = await page.locator('.rich-view').first().innerText().catch(() => '');
  check('富文本内容可见（详情标题/正文）', /详情标题|正文/.test(richViewText), richViewText.slice(0, 60));
  check('规格参数显示商家填写的「净含量」', (await page.getByText('净含量', { exact: true }).count()) >= 1);
  check('包装规格显示「包装形式」', (await page.getByText('包装形式', { exact: true }).count()) >= 1);
  check('无页面级 JS 异常', errors.length === 0, errors.slice(0, 2));

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) console.log('失败项：\n - ' + failures.join('\n - '));
} finally {
  await browser.close();
}
process.exitCode = failures.length ? 1 : 0;

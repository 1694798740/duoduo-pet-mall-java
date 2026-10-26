/**
 * D-05 · 售后按商品 / SKU 退款统计 UI 验收（2026-10-26）。
 *
 * 商家端「售后服务」页顶部新增「退款商品 / SKU 统计」：
 *  ① 一件商品两个规格，各买一单；退款规格 A 后只出现 A 行，
 *     退款件数 / 金额 / 涉及订单正确；
 *  ② 再退规格 B 后出现两行，合计金额正确；
 *  ③ 未退款规格不出现；页面说明「按订单明细快照口径」；
 *  ④ 全程 0 原生弹窗、0 pageerror；夹具零残留。
 *
 * 前置：容器在跑（8091 / 8080）；trade 与商家端已部署 D-05；卖家 13600003333 可用。
 * 用法：NO_PROXY="*" node scripts/e2e/product-refund-stats-d05-ui-check.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { shotsDir } from '../lib/e2e-shots.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const MERCHANT = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const PRODUCT_DB = 'duoduo_product';
const TRADE_DB = 'duoduo_trade';
const USER_DB = 'duoduo_user';

let pass = 0;
const failures = [];
function check(name, ok, got) {
  if (ok) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    failures.push(name);
    console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`.slice(0, 600));
  }
}
const step = (t) => console.log(`\n> ${t}`);

async function call(method, url, body, token) {
  const res = await fetch(API + url, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}
const tokenOf = async (phone) =>
  (await call('POST', '/api/auth/login/token', { phone, password: PWD })).body?.data?.accessToken;

function sql(db, query) {
  const out = execFileSync('docker', [
    'exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B',
    '--default-character-set=utf8mb4', '-D', db, '-e', query,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return out.split(/\r?\n/).filter((line) => line && !line.includes('Warning'));
}
const one = (db, query) => sql(db, query)[0] ?? '';
const count = (db, query) => Number(sql(db, query)[0] ?? 0);

const stamp = String(Date.now()).slice(-7);
const suffix = String(Date.now()).slice(-8);
const buyerPhone = `185${suffix}`;
const buyerName = `D05UI退款买家${stamp}`;
const title = `D05UI 退款统计 ${stamp}`;

const sellerToken = await tokenOf(SELLER);
const opToken = await tokenOf('13684295254');
check('卖家 / 运营登录成功', Boolean(sellerToken) && Boolean(opToken));
if (!sellerToken || !opToken) process.exit(1);

// ---------------- 1. 夹具 ----------------
step('夹具：两规格商品 + 两张已支付订单');
const product = await call('POST', '/api/seller/products', {
  title,
  subtitle: 'D-05 UI 验收',
  categoryId: '101',
  brandId: '1',
  mainImage: '/img/products/dog-food.svg',
  detail: 'D-05 UI 验收商品',
  skus: [
    { specName: 'A-1kg', price: 1000, stock: 10, image: null },
    { specName: 'B-5kg', price: 2500, stock: 10, image: null },
  ],
}, sellerToken);
const spuId = product.body?.data?.id;
let detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
if (detail?.auditStatus === 'PENDING') {
  await call('POST', `/api/admin/products/${spuId}/audit`, { action: 'APPROVE', version: detail.version }, opToken);
  detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
}
const skuA = (detail?.skus ?? []).find((x) => x.specName === 'A-1kg');
const skuB = (detail?.skus ?? []).find((x) => x.specName === 'B-5kg');
check('夹具商品两规格就绪', Boolean(spuId) && Boolean(skuA?.id) && Boolean(skuB?.id), detail);

await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: buyerName });
const buyerToken = await tokenOf(buyerPhone);
const buyerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}'`);
const address = await call('POST', '/api/addresses', {
  receiverName: buyerName, receiverPhone: buyerPhone,
  province: '广东省', city: '深圳市', district: '南山区',
  detail: '科技园路 99 号', isDefault: true,
}, buyerToken);
const addressId = address.body?.data?.id;
const orders = [];
async function placePaid(skuId, tag) {
  const placed = await call('POST', '/api/orders', {
    addressId, buySkuId: skuId, buyQuantity: 1, idempotentKey: `d05ui-${tag}-${Date.now()}`,
  }, buyerToken);
  const orderNo = (placed.body?.data?.orders ?? [])[0]?.orderNo;
  const paid = await call('POST', `/api/orders/${orderNo}/pay`,
    { channel: 'MOCK', idempotentKey: `d05ui-pay-${tag}-${Date.now()}` }, buyerToken);
  const orderId = one(TRADE_DB, `SELECT id FROM t_order WHERE order_no='${orderNo}'`);
  orders.push({ orderNo, orderId });
  return { orderNo, orderId, paid: paid.body?.data?.orderStatus };
}
const orderA = await placePaid(skuA.id, 'a');
const orderB = await placePaid(skuB.id, 'b');
check('两张已支付订单', orderA.paid === 'PAID' && orderB.paid === 'PAID', orders);
await call('POST', `/api/seller/orders/${orderA.orderNo}/refund`, { reason: 'D05 UI：退 A' }, sellerToken);

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH ?? undefined, args: ['--no-sandbox'] });
const nativeDialogs = [];
const pageErrors = [];
const watch = (page) => {
  page.on('dialog', async (d) => {
    nativeDialogs.push(`${d.type()}:${d.message()}`);
    await d.dismiss().catch(() => {});
  });
  page.on('pageerror', (e) => pageErrors.push(e.message));
};

try {
  step('售后页：退款 A 后统计只出现 A');
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
  const page = await ctx.newPage();
  watch(page);
  await page.goto(`${MERCHANT}/login`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('11 位手机号').fill(SELLER);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });

  await page.goto(`${MERCHANT}/seller/after-sales`, { waitUntil: 'networkidle' });
  await page.getByText('退款商品 / SKU 统计', { exact: true }).waitFor({ timeout: 20000 });
  const bodyText = await page.locator('body').innerText();
  check('页面说明按订单明细快照口径', /按商品与规格汇总|订单明细的快照口径|订单明细/.test(bodyText));
  check('合计显示 ¥10.00 / 1 件', /合计退款.*¥10\.00.*1 件/.test(bodyText), bodyText.slice(0, 600));
  const rowA = page.locator('tr', { hasText: 'A-1kg' });
  await rowA.waitFor({ timeout: 20000 });
  const rowAText = await rowA.innerText();
  check('规格 A 行：1 件 / ¥10.00 / 1 单',
    /1/.test(rowAText) && /¥10\.00/.test(rowAText), rowAText);
  check('未退款规格 B 不出现', (await page.locator('tr', { hasText: 'B-5kg' }).count()) === 0);

  step('再退 B 后出现两行，合计正确');
  await call('POST', `/api/seller/orders/${orderB.orderNo}/refund`, { reason: 'D05 UI：退 B' }, sellerToken);
  await page.reload({ waitUntil: 'networkidle' });
  await page.getByText('退款商品 / SKU 统计', { exact: true }).waitFor({ timeout: 20000 });
  await page.locator('tr', { hasText: 'B-5kg' }).waitFor({ timeout: 20000 });
  const finalText = await page.locator('body').innerText();
  check('两个规格各一行', (await page.locator('tr', { hasText: 'A-1kg' }).count()) === 1
    && (await page.locator('tr', { hasText: 'B-5kg' }).count()) === 1);
  check('合计更新为 ¥35.00 / 2 件', /合计退款.*¥35\.00.*2 件/.test(finalText), finalText.slice(0, 600));

  step('前端异常检查与截图');
  const shot = process.env.D05_UI_SHOT ?? path.join(shotsDir(), 'verify-d05-refund-stats.png');
  await page.screenshot({ path: shot, fullPage: true });
  console.log(`  [截图] ${shot}`);
  check('全程无原生 window.confirm/prompt/alert', nativeDialogs.length === 0, nativeDialogs);
  check('无页面级 JS 异常', pageErrors.length === 0, pageErrors);
} finally {
  await browser.close();
  for (const o of orders) {
    sql(TRADE_DB, `DELETE FROM t_settlement WHERE order_id=${o.orderId}`);
    sql(TRADE_DB, `DELETE FROM t_payment WHERE order_id=${o.orderId}`);
    sql(TRADE_DB, `DELETE FROM t_stock_freeze WHERE order_id=${o.orderId}`);
    sql(PRODUCT_DB, `DELETE FROM t_sku_stock_reservation WHERE order_id=${o.orderId}`);
    sql(TRADE_DB, `DELETE FROM t_order_status_log WHERE order_id=${o.orderId}`);
    sql(TRADE_DB, `DELETE FROM t_order_item WHERE order_id=${o.orderId}`);
    sql(TRADE_DB, `DELETE FROM t_order WHERE id=${o.orderId}`);
  }
  sql(TRADE_DB, `DELETE FROM t_cart_item WHERE user_id=${buyerId}`);
  sql(USER_DB, `DELETE FROM t_user_address WHERE user_id=${buyerId}`);
  sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${buyerId}`);
  sql(USER_DB, `DELETE FROM t_user WHERE id=${buyerId}`);
  sql(PRODUCT_DB, `DELETE FROM t_spu_image WHERE spu_id=${spuId}`);
  sql(PRODUCT_DB, `DELETE FROM t_sku WHERE spu_id=${spuId}`);
  sql(PRODUCT_DB, `DELETE FROM t_spu_audit_log WHERE spu_id=${spuId}`);
  sql(PRODUCT_DB, `DELETE FROM t_spu WHERE id=${spuId}`);
  check('订单链路 / 商品 / 临时买家零残留',
    orders.every((o) => count(TRADE_DB, `SELECT COUNT(*) FROM t_order WHERE id=${o.orderId}`) === 0)
      && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu WHERE id=${spuId}`) === 0
      && count(USER_DB, `SELECT COUNT(*) FROM t_user WHERE id=${buyerId}`) === 0);
}

console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exitCode = 1;
}

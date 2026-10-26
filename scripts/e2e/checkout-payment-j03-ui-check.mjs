/**
 * J-03 · 下单 → 支付主链路 · UI 自测（**自包含**）
 *
 * 覆盖：
 *   S1 夹具：临时卖家店铺 + 已上架商品；临时买家 + 默认地址；
 *   S2 买家从商品详情「立即购买」→ 购物车 → 去结算 → 提交订单；
 *   S3 下单成功页 → 去支付 / 查看详情 → 沙箱渠道「本机模拟扫码」→ 支付成功；
 *   S4 回库：订单 PAID、支付单 SUCCESS、库存冻结转扣减；页面显示已付款；
 *   S5 0 pageerror / 0 5xx；订单链路 / 商品 / 店铺 / 临时账号零残留。
 *
 * 前置：容器在跑；商城 8088、网关 8080；卖家 / 运营账号可用。
 */

import path from 'node:path';
import { shotsDir } from '../lib/e2e-shots.mjs';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const MALL_UI = process.env.MALL_UI_BASE ?? 'http://localhost:8088';
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? undefined;
const PWD = 'duoduo12345';
const OPERATOR = '13684295254';
const USER_DB = 'duoduo_user';
const PRODUCT_DB = 'duoduo_product';
const TRADE_DB = 'duoduo_trade';
const SHOTS = shotsDir();
fs.mkdirSync(SHOTS, { recursive: true });

let pass = 0;
const failures = [];
function check(name, ok, got) {
  if (ok) { pass++; console.log(`  [PASS] ${name}`); }
  else { failures.push(name); console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`); }
}
const step = (t) => console.log(`\n> ${t}`);

async function call(method, p, body, token, jar, entry) {
  const hasBody = body !== undefined && body !== null;
  const headers = {};
  if (hasBody) headers['content-type'] = 'application/json';
  if (token) headers.authorization = 'Bearer ' + token;
  if (jar && jar.size) headers.cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
  if (entry) headers['X-Duoduo-Entry'] = entry;
  const res = await fetch(GW + p, { method, headers, body: hasBody ? JSON.stringify(body) : undefined });
  if (jar) for (const c of res.headers.getSetCookie?.() ?? []) {
    const pair = c.split(';')[0]; const i = pair.indexOf('=');
    if (i > 0) jar.set(pair.slice(0, i), pair.slice(i + 1));
  }
  const raw = await res.text();
  let json = null; try { json = JSON.parse(raw); } catch { /* raw */ }
  return { status: res.status, data: json?.data, json };
}
const tokenOf = async (phone, entry) =>
  (await call('POST', '/api/auth/login/token', { phone, password: PWD }, null, null, entry)).data?.accessToken;
const randPhone = (p) => p + String(Math.floor(1e7 + Math.random() * 8e7));

const stamp = String(Date.now()).slice(-7);
const buyerPhone = randPhone('135');
const buyerName = `J03下单买家${stamp}`;
const title = `J03 下单商品 ${stamp}`;

const opToken = await tokenOf(OPERATOR, 'admin');
check('运营登录成功', Boolean(opToken));
if (!opToken) process.exit(1);

step('S1 夹具：卖家 + 商品 + 买家 + 地址');
const seller = { phone: randPhone('134'), jar: new Map() };
await call('POST', '/api/auth/register', { phone: seller.phone, password: PWD, nickname: 'J03卖家' }, null, seller.jar);
const { createSellerFixture } = await import('../lib/e2e-seller-fixture.mjs');
const fixtureCall = (method, p, body, jar) => call(method, p, body, null, jar);
const fixture = await createSellerFixture({
  phone: seller.phone, storeName: 'J03下单店', doRealname: true, callFn: fixtureCall, jar: seller.jar,
});
const sellerToken = await tokenOf(seller.phone, 'merchant');
const product = await call('POST', '/api/seller/products', {
  title,
  subtitle: 'J-03 下单链路',
  categoryId: '101',
  brandId: '1',
  mainImage: '/img/products/dog-food.svg',
  detail: 'J-03 下单链路商品',
  skus: [{ specName: '默认规格', price: 2590, stock: 10, image: null }],
}, sellerToken);
const spuId = product.data?.id;
let detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).data;
if (detail?.auditStatus === 'PENDING') {
  await call('POST', `/api/admin/products/${spuId}/audit`, { action: 'APPROVE', version: detail.version }, opToken);
  detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).data;
}
if (detail?.status !== 'ON_SALE') {
  await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, sellerToken);
  detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).data;
}
check('商品已上架', Boolean(spuId) && detail?.status === 'ON_SALE', detail);

await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: buyerName });
const buyerToken = await tokenOf(buyerPhone, 'mall');
const buyerId = (await mysql(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}';`)).trim();
const address = await call('POST', '/api/addresses', {
  receiverName: buyerName, receiverPhone: buyerPhone,
  province: '广东省', city: '深圳市', district: '南山区',
  detail: '科技园路 99 号', isDefault: true,
}, buyerToken);
check('买家与默认地址就绪', Boolean(buyerToken) && Boolean(address.data?.id), address.json);

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
const ctx = await browser.newContext({ viewport: { width: 1366, height: 1000 }, locale: 'zh-CN' });
const page = await ctx.newPage();
const consoleErrors = [];
const httpErrors = [];
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
page.on('response', (r) => { if (r.status() >= 500) httpErrors.push(`${r.status()} ${r.url()}`); });

let orderNo = null;
try {
  step('S2 登录 → 商品详情 → 立即购买 → 购物车 → 结算');
  await page.goto(`${MALL_UI}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[placeholder="11 位手机号"]').fill(buyerPhone);
  await page.locator('input[type="password"]').fill(PWD);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.waitForURL((u) => !String(u).includes('/login'), { timeout: 15000 });

  await page.goto(`${MALL_UI}/product/${spuId}`, { waitUntil: 'networkidle' });
  await page.getByRole('button', { name: '立即购买' }).click();
  await page.waitForURL((u) => String(u).includes('/cart'), { timeout: 15000 });
  await page.getByRole('button', { name: '去结算' }).click();
  await page.waitForURL((u) => String(u).includes('/checkout'), { timeout: 15000 });
  await page.getByText('收货信息').waitFor({ timeout: 10000 });
  check('结算页出现默认地址', (await page.getByText(buyerName).count()) >= 1);
  await page.getByRole('button', { name: '提交订单' }).click();
  await page.getByText('下单成功').waitFor({ timeout: 15000 });
  await page.getByRole('button', { name: '去支付 / 查看详情' }).first().click();
  await page.waitForURL((u) => String(u).includes('/orders/'), { timeout: 15000 });
  orderNo = decodeURIComponent(String(page.url()).split('/orders/')[1] ?? '');
  check('下单成功并进入订单详情', Boolean(orderNo), page.url());
  await page.screenshot({ path: `${SHOTS}/j03_order_created.png` });

  step('S3 沙箱支付：本机模拟扫码');
  await page.getByRole('button', { name: '去支付' }).click();
  await page.getByText('扫码支付').waitFor({ timeout: 10000 });
  await page.getByRole('button', { name: '本机模拟扫码' }).click();
  await page.getByText('支付成功').first().waitFor({ timeout: 15000 });
  check('页面提示支付成功', true);
  await page.screenshot({ path: `${SHOTS}/j03_order_paid.png` });
  await page.getByRole('button', { name: '关闭' }).click().catch(() => {});

  step('S4 回库：订单 / 支付 / 库存');
  const orderRow = (await mysql(TRADE_DB,
    `SELECT id,status FROM t_order WHERE order_no='${orderNo}';`)).trim();
  const [orderId, orderStatus] = orderRow.split('\t');
  const payStatus = (await mysql(TRADE_DB,
    `SELECT status FROM t_payment WHERE order_id=${orderId} ORDER BY id DESC LIMIT 1;`)).trim();
  check('★ 订单状态 = PAID', orderStatus === 'PAID', orderRow);
  check('★ 支付单 = SUCCESS', payStatus === 'SUCCESS', payStatus);
  const reservation = (await mysql(PRODUCT_DB,
    `SELECT status FROM t_sku_stock_reservation WHERE order_id=${orderId} LIMIT 1;`)).trim();
  check('库存预占已转为 DEDUCTED', reservation === 'DEDUCTED', reservation);
  await page.getByText('已付款').first().waitFor({ timeout: 10000 });
  check('订单详情显示已付款', true);

  step('S5 无 pageerror / 5xx');
  check('无 pageerror / 5xx',
    httpErrors.length === 0 && !consoleErrors.some((e) => e.startsWith('pageerror:')),
    { http: httpErrors, console: consoleErrors });
} finally {
  await browser.close();
  step('收尾：订单链路 / 商品 / 店铺 / 临时账号');
  if (orderNo) {
    const orderId = (await mysql(TRADE_DB, `SELECT id FROM t_order WHERE order_no='${orderNo}';`)).trim();
    if (orderId) {
      await mysql(TRADE_DB, `DELETE FROM t_settlement WHERE order_id=${orderId};`);
      await mysql(TRADE_DB, `DELETE FROM t_payment WHERE order_id=${orderId};`);
      await mysql(TRADE_DB, `DELETE FROM t_stock_freeze WHERE order_id=${orderId};`);
      await mysql(PRODUCT_DB, `DELETE FROM t_sku_stock_reservation WHERE order_id=${orderId};`);
      await mysql(TRADE_DB, `DELETE FROM t_order_status_log WHERE order_id=${orderId};`);
      await mysql(TRADE_DB, `DELETE FROM t_order_item WHERE order_id=${orderId};`);
      await mysql(TRADE_DB, `DELETE FROM t_order WHERE id=${orderId};`);
    }
  }
  if (buyerId) {
    await mysql(TRADE_DB, `DELETE FROM t_cart_item WHERE user_id=${buyerId};`);
    await mysql(USER_DB, `DELETE FROM t_user_address WHERE user_id=${buyerId};`);
  }
  if (spuId) {
    await mysql(PRODUCT_DB, `DELETE FROM t_spu_audit_log WHERE spu_id=${spuId};`);
    await mysql(PRODUCT_DB, `DELETE FROM t_spu_image WHERE spu_id=${spuId};`);
    await mysql(PRODUCT_DB, `DELETE FROM t_sku WHERE spu_id=${spuId};`);
    await mysql(PRODUCT_DB, `DELETE FROM t_spu WHERE id=${spuId};`);
  }
  if (fixture?.merchantId) {
    await mysql(USER_DB, `DELETE FROM t_merchant_member WHERE merchant_id=${fixture.merchantId};`);
    await mysql(USER_DB, `DELETE FROM t_merchant WHERE id=${fixture.merchantId};`);
  }
  for (const phone of [buyerPhone, seller.phone]) {
    await mysql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=(SELECT id FROM t_user WHERE phone='${phone}');`);
    await mysql(USER_DB, `DELETE FROM t_user WHERE phone='${phone}';`);
  }
  const remain = (await mysql(USER_DB,
    `SELECT COUNT(*) FROM t_user WHERE phone IN ('${buyerPhone}','${seller.phone}');`)).trim();
  check('临时账号已清理', remain === '0', remain);
}

console.log(`\n=== J-03 下单支付 UI 自测：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log('  - ' + f);
}
process.exit(failures.length ? 1 : 0);

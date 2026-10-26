/**
 * B-05 · 电子面单 UI 验收（2026-10-26）。
 *
 * 商家端「卖出订单 → 发货」弹窗新增：
 *  ① 快递公司下拉（服务端下发 7 家）与「获取电子面单」按钮；
 *  ② 生成后自动回填物流公司 / 运单号，并明确标注「模拟面单」；
 *  ③ 确认发货后订单进入 SHIPPED，物流字段落库；
 *  ④ 模拟轨迹推进后，订单详情时间线显示已揽收 → 运输中 → 派送中 → 已签收；
 *  ⑤ 全程 0 原生弹窗、0 pageerror；夹具零残留。
 *
 * 前置：容器在跑（8091 / 8080 / 8084）；fulfilment 与商家端已部署 B-05；
 *       卖家 13600003333 可用；模拟推进任务开启。
 * 用法：NO_PROXY="*" node scripts/e2e/waybill-b05-ui-check.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { internalHeaders } from '../lib/internal-token.mjs';
import { shotsDir } from '../lib/e2e-shots.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const MERCHANT = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const API = 'http://localhost:8080';
const FULFIL = 'http://localhost:8084';
const PWD = 'duoduo12345';
const BUYER_PWD = 'Passw0rd!23';
const SELLER = '13600003333';
const OPERATOR = '13684295254';
const PRODUCT_DB = 'duoduo_product';
const TRADE_DB = 'duoduo_trade';
const FULFIL_DB = 'duoduo_fulfilment';
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

function sql(db, query) {
  const out = execFileSync('docker', [
    'exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B',
    '--default-character-set=utf8mb4', '-D', db, '-e', query,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return out.split(/\r?\n/).filter((line) => line && !line.includes('Warning'));
}
const one = (db, query) => sql(db, query)[0] ?? '';
const count = (db, query) => Number(sql(db, query)[0] ?? 0);

async function call(method, url, body, token, entry) {
  const res = await fetch(API + url, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(entry ? { 'X-Duoduo-Entry': entry } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}
const tokenOf = async (phone, entry, pwd = PWD) =>
  (await call('POST', '/api/auth/login/token', { phone, password: pwd }, null, entry)).body?.data?.accessToken;

async function internalAdvance(orderNo) {
  const res = await fetch(`${FULFIL}/api/logistics/internal/waybills/${orderNo}/advance`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...internalHeaders('POST') },
    body: '{}',
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}

const stamp = String(Date.now()).slice(-7);
const title = `B05UI 面单验收 ${stamp}`;
const buyerPhone = `196${String(Date.now()).slice(-8)}`;
const buyerNick = `B05UI 买家 ${stamp}`;

const sellerToken = await tokenOf(SELLER, 'merchant');
const opToken = await tokenOf(OPERATOR, 'admin');
check('卖家 / 运营登录成功', Boolean(sellerToken) && Boolean(opToken));
if (!sellerToken || !opToken) process.exit(1);

// ---------------- 1. 夹具：商品 + 买家 + 已支付订单 ----------------
step('夹具：商品 + 临时买家 + 已支付订单');
const product = await call('POST', '/api/seller/products', {
  title,
  subtitle: 'B-05 UI 验收',
  categoryId: '101',
  brandId: '1',
  mainImage: '/img/products/dog-food.svg',
  detail: 'B-05 UI 验收商品',
  skus: [{ specName: 'B05UI-1kg', price: 2990, stock: 5, image: null }],
}, sellerToken);
const spuId = product.body?.data?.id;
let detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
if (detail?.auditStatus === 'PENDING') {
  await call('POST', `/api/admin/products/${spuId}/audit`, { action: 'APPROVE', version: detail.version }, opToken);
  detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
}
if (detail?.status !== 'ON_SALE') {
  await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, sellerToken);
  detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
}
const skuId = (detail?.skus ?? [])[0]?.id;
check('夹具商品在售且有规格', Boolean(spuId) && Boolean(skuId), detail);

await call('POST', '/api/auth/register', { phone: buyerPhone, password: BUYER_PWD, nickname: buyerNick });
const buyerToken = await tokenOf(buyerPhone, undefined, BUYER_PWD);
const buyerUserId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}'`);
const address = await call('POST', '/api/addresses', {
  receiverName: buyerNick, receiverPhone: buyerPhone,
  province: '广东省', city: '深圳市', district: '南山区',
  detail: '科技园路 66 号', isDefault: true,
}, buyerToken);
const addressId = address.body?.data?.id;
check('临时买家与地址就绪', Boolean(buyerToken) && Boolean(buyerUserId) && Boolean(addressId), address.body);

const placed = await call('POST', '/api/orders', {
  addressId, buySkuId: skuId, buyQuantity: 1, idempotentKey: `b05ui-${Date.now()}`,
}, buyerToken);
const orderNo = (placed.body?.data?.orders ?? [])[0]?.orderNo;
const orderId = one(TRADE_DB, `SELECT id FROM t_order WHERE order_no='${orderNo}'`);
const paid = await call('POST', `/api/orders/${orderNo}/pay`,
  { channel: 'MOCK', idempotentKey: `b05ui-pay-${Date.now()}` }, buyerToken);
check('订单已支付（PAID）', Boolean(orderNo) && paid.body?.data?.orderStatus === 'PAID', paid.body);

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
  // ---------------- 2. 商家端发货弹窗 ----------------
  step('商家端：待发货列表 → 发货弹窗 → 获取电子面单');
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
  const page = await ctx.newPage();
  watch(page);
  await page.goto(`${MERCHANT}/login`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('11 位手机号').fill(SELLER);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });

  await page.goto(`${MERCHANT}/seller/orders?status=PAID`, { waitUntil: 'networkidle' });
  const row = page.locator('.card', { hasText: orderNo });
  await row.waitFor({ timeout: 20000 });
  check('待发货列表出现该订单', await row.isVisible());
  await row.getByRole('button', { name: '立即发货', exact: true }).click();
  const dialog = page.locator('[role="dialog"]', { hasText: '发货' });
  await dialog.waitFor({ timeout: 10000 });
  check('发货弹窗出现电子面单入口与快递下拉',
    await dialog.getByRole('button', { name: '获取电子面单', exact: true }).isVisible()
      && await dialog.getByLabel('快递公司').isVisible());

  await dialog.getByLabel('快递公司').selectOption('YTO');
  await dialog.getByRole('button', { name: '获取电子面单', exact: true }).click();
  await dialog.getByText(/（模拟面单）/).waitFor({ timeout: 20000 });
  const waybillText = await dialog.innerText();
  check('生成后明示「模拟面单」并展示运单号',
    waybillText.includes('已生成 圆通速递') && /YTO\d{12}/.test(waybillText) && waybillText.includes('（模拟面单）'),
    waybillText);
  check('物流公司自动回填圆通速递',
    (await dialog.getByPlaceholder('如 顺丰速运').inputValue()) === '圆通速递');
  const filledNo = await dialog.getByPlaceholder('如 SF1234567890').inputValue();
  check('物流单号自动回填 YTO 运单号', /^YTO\d{12}$/.test(filledNo), filledNo);

  step('确认发货：订单进入 SHIPPED 且物流字段落库');
  await dialog.getByRole('button', { name: '确认发货', exact: true }).click();
  await dialog.waitFor({ state: 'detached', timeout: 20000 });
  const orderRow = one(TRADE_DB,
    `SELECT CONCAT(status,'|',IFNULL(logistics_company,'-'),'|',IFNULL(logistics_no,'-')) FROM t_order WHERE id=${orderId}`);
  check('回库：订单 SHIPPED + 圆通 + 面单号',
    orderRow === `SHIPPED|圆通速递|${filledNo}`, orderRow);
  const waybillRow = one(FULFIL_DB,
    `SELECT CONCAT(courier_code,'|',waybill_no,'|',mode,'|',status) FROM t_waybill WHERE order_no='${orderNo}'`);
  check('回库：面单 YTO / 模拟 / 已揽收或已生成',
    waybillRow.startsWith(`YTO|${filledNo}|SIMULATED|`), waybillRow);

  // ---------------- 3. 模拟轨迹推进与时间线 ----------------
  step('模拟推进到签收，订单详情时间线展示四节点');
  for (let i = 0; i < 3; i++) {
    const advanced = await internalAdvance(orderNo);
    check(`第 ${i + 1} 次推进成功`, advanced.status === 200, advanced.body);
  }
  const finalStatus = one(FULFIL_DB, `SELECT status FROM t_waybill WHERE order_no='${orderNo}'`);
  check('模拟轨迹已签收', finalStatus === 'SIGNED', finalStatus);

  await page.goto(`${MERCHANT}/seller/orders/${orderNo}`, { waitUntil: 'networkidle' });
  await page.getByText('物流轨迹', { exact: true }).waitFor({ timeout: 20000 });
  const bodyText = await page.locator('body').innerText();
  check('时间线展示四个节点（已揽收/运输中/派送中/已签收）',
    ['已揽收', '运输中', '派送中', '已签收'].every((label) => bodyText.includes(label)), bodyText.slice(0, 500));
  check('时间线展示运单号', bodyText.includes(filledNo));

  step('前端异常检查与截图');
  const shot = process.env.B05_UI_SHOT ?? path.join(shotsDir(), 'verify-b05-waybill.png');
  await page.screenshot({ path: shot, fullPage: true });
  console.log(`  [截图] ${shot}`);
  check('全程无原生 window.confirm/prompt/alert', nativeDialogs.length === 0, nativeDialogs);
  check('无页面级 JS 异常', pageErrors.length === 0, pageErrors);
} finally {
  await browser.close();
  // 收尾：面单 / 轨迹 / 订单链路 / 商品 / 临时买家
  sql(FULFIL_DB, `DELETE FROM t_waybill WHERE order_no='${orderNo}'`);
  sql(FULFIL_DB, `DELETE FROM t_logistics_track WHERE order_no='${orderNo}'`);
  sql(TRADE_DB, `DELETE FROM t_settlement WHERE order_id=${orderId}`);
  sql(TRADE_DB, `DELETE FROM t_payment WHERE order_id=${orderId}`);
  sql(TRADE_DB, `DELETE FROM t_stock_freeze WHERE order_id=${orderId}`);
  sql(PRODUCT_DB, `DELETE FROM t_sku_stock_reservation WHERE order_id=${orderId}`);
  sql(TRADE_DB, `DELETE FROM t_order_status_log WHERE order_id=${orderId}`);
  sql(TRADE_DB, `DELETE FROM t_order_item WHERE order_id=${orderId}`);
  sql(TRADE_DB, `DELETE FROM t_order WHERE id=${orderId}`);
  sql(TRADE_DB, `DELETE FROM t_cart_item WHERE user_id=${buyerUserId}`);
  sql(USER_DB, `DELETE FROM t_user_address WHERE user_id=${buyerUserId}`);
  sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${buyerUserId}`);
  sql(USER_DB, `DELETE FROM t_user WHERE id=${buyerUserId}`);
  sql(PRODUCT_DB, `DELETE FROM t_spu_image WHERE spu_id=${spuId}`);
  sql(PRODUCT_DB, `DELETE FROM t_sku WHERE spu_id=${spuId}`);
  sql(PRODUCT_DB, `DELETE FROM t_spu_audit_log WHERE spu_id=${spuId}`);
  sql(PRODUCT_DB, `DELETE FROM t_spu WHERE id=${spuId}`);
  check('夹具面单 / 轨迹 / 订单 / 商品 / 买家零残留',
    count(FULFIL_DB, `SELECT COUNT(*) FROM t_waybill WHERE order_no='${orderNo}'`) === 0
      && count(TRADE_DB, `SELECT COUNT(*) FROM t_order WHERE id=${orderId}`) === 0
      && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu WHERE id=${spuId}`) === 0
      && count(USER_DB, `SELECT COUNT(*) FROM t_user WHERE id=${buyerUserId}`) === 0);
}

console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exitCode = 1;
}

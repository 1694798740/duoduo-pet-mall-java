#!/usr/bin/env node
/**
 * D-05 · 售后按商品 / SKU 退款金额统计冒烟（2026-10-26）。
 *
 * 守四组事实：
 *  A. 夹具：一件商品两个规格，两张各买一个规格的已支付订单；
 *  B. 退款一张后，`GET /api/seller/product-refunds` 只出现被退规格，
 *     退款件数 / 退款金额（订单明细快照）/ 涉及订单数正确；
 *  C. 两件都退后按 SKU 各一行，金额与合计正确；
 *  D. 权限（别的店主看不到、买家 403、未登录 401）与收尾零残留。
 *
 * 前置：容器在跑；trade 已部署 D-05；卖家 13600003333、另一店主 13959064118 可用。
 * 用法：NO_PROXY="*" node scripts/product-refund-stats-d05-smoke.mjs
 */
import { execFileSync } from 'node:child_process';

const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OTHER_SELLER = '13959064118';
const PRODUCT_DB = 'duoduo_product';
const TRADE_DB = 'duoduo_trade';
const USER_DB = 'duoduo_user';

let pass = 0;
const failures = [];
function check(name, ok, detail) {
  if (ok) {
    pass++;
    console.log('  [PASS] ' + name);
  } else {
    failures.push(name);
    console.log('  [FAIL] ' + name + (detail === undefined ? '' : '  -> ' + JSON.stringify(detail).slice(0, 800)));
  }
}

async function call(method, path, body, token) {
  const res = await fetch(API + path, {
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
const buyerPhone = `186${suffix}`;
const buyerName = `D05退款买家${stamp}`;
const title = `D05 退款统计 ${stamp}`;

const sellerToken = await tokenOf(SELLER);
const otherToken = await tokenOf(OTHER_SELLER);
check('卖家 / 另一店主登录成功', Boolean(sellerToken) && Boolean(otherToken));
if (!sellerToken || !otherToken) process.exit(1);
const sellerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${SELLER}'`);

/** 夹具商品：两个规格 A/B */
const product = await call('POST', '/api/seller/products', {
  title,
  subtitle: 'D-05 退款统计',
  categoryId: '101',
  brandId: '1',
  mainImage: '/img/products/dog-food.svg',
  detail: 'D-05 退款统计商品',
  skus: [
    { specName: 'A-1kg', price: 1000, stock: 10, image: null },
    { specName: 'B-5kg', price: 2500, stock: 10, image: null },
  ],
}, sellerToken);
const spuId = product.body?.data?.id;
let detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
const opToken = await tokenOf('13684295254');
if (detail?.auditStatus === 'PENDING') {
  await call('POST', `/api/admin/products/${spuId}/audit`, { action: 'APPROVE', version: detail.version }, opToken);
  detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
}
if (detail?.status !== 'ON_SALE') {
  await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, sellerToken);
  detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
}
const skus = detail?.skus ?? [];
const skuA = skus.find((x) => x.specName === 'A-1kg');
const skuB = skus.find((x) => x.specName === 'B-5kg');
check('夹具商品两个规格就绪', Boolean(spuId) && Boolean(skuA?.id) && Boolean(skuB?.id), detail);

await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: buyerName });
const buyerToken = await tokenOf(buyerPhone);
const buyerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}'`);
const address = await call('POST', '/api/addresses', {
  receiverName: buyerName, receiverPhone: buyerPhone,
  province: '广东省', city: '深圳市', district: '南山区',
  detail: '科技园路 88 号', isDefault: true,
}, buyerToken);
const addressId = address.body?.data?.id;
check('临时买家与地址就绪', Boolean(buyerToken) && Boolean(buyerId) && Boolean(addressId), address.body);

const orders = [];
async function placePaidOrder(skuId, tag) {
  const placed = await call('POST', '/api/orders', {
    addressId, buySkuId: skuId, buyQuantity: 1, idempotentKey: `d05-${tag}-${Date.now()}`,
  }, buyerToken);
  const orderNo = (placed.body?.data?.orders ?? [])[0]?.orderNo;
  const paid = await call('POST', `/api/orders/${orderNo}/pay`,
    { channel: 'MOCK', idempotentKey: `d05-pay-${tag}-${Date.now()}` }, buyerToken);
  const orderId = one(TRADE_DB, `SELECT id FROM t_order WHERE order_no='${orderNo}'`);
  orders.push({ orderNo, orderId });
  return { orderNo, orderId, paid: paid.body?.data?.orderStatus };
}
const orderA = await placePaidOrder(skuA.id, 'a');
const orderB = await placePaidOrder(skuB.id, 'b');
check('两张已支付订单（规格 A / B）',
  orderA.paid === 'PAID' && orderB.paid === 'PAID' && orders.every((o) => o.orderNo && o.orderId), orders);

console.log('\n== 1. 退款规格 A，统计只出现 A ==');
const refundA = await call('POST', `/api/seller/orders/${orderA.orderNo}/refund`,
  { reason: 'D05 冒烟：规格 A 退款' }, sellerToken);
check('规格 A 订单退款成功', refundA.status === 200, refundA.body);
let stats = await call('GET', '/api/seller/product-refunds', null, sellerToken);
let rows = (stats.body?.data ?? []).filter((r) => String(r.spuId) === String(spuId));
check('统计接口返回本商品退款行', stats.status === 200 && rows.length === 1, stats.body?.data?.slice(0, 3));
check('只出现退款规格 A',
  rows[0]?.skuId === skuA.id && String(rows[0]?.specText ?? '').includes('A-1kg'), rows[0]);
check('退款件数 1 / 金额 1000 分 / 涉及订单 1',
  Number(rows[0]?.refundQty) === 1 && Number(rows[0]?.refundAmount) === 1000
    && Number(rows[0]?.refundOrderCount) === 1, rows[0]);
check('未退款规格 B 不出现', !rows.some((r) => r.skuId === skuB.id), rows);

console.log('\n== 2. 退款规格 B，按 SKU 各一行 ==');
const refundB = await call('POST', `/api/seller/orders/${orderB.orderNo}/refund`,
  { reason: 'D05 冒烟：规格 B 退款' }, sellerToken);
check('规格 B 订单退款成功', refundB.status === 200, refundB.body);
stats = await call('GET', '/api/seller/product-refunds', null, sellerToken);
rows = (stats.body?.data ?? []).filter((r) => String(r.spuId) === String(spuId));
const rowB = rows.find((r) => r.skuId === skuB.id);
check('两个规格各一行',
  rows.length === 2 && Boolean(rows.find((r) => r.skuId === skuA.id)) && Boolean(rowB), rows);
check('规格 B 金额 2500 / 件数 1 / 订单 1',
  Number(rowB?.refundAmount) === 2500 && Number(rowB?.refundQty) === 1
    && Number(rowB?.refundOrderCount) === 1, rowB);
check('商品维度金额合计 3500（1000 + 2500）',
  rows.reduce((sum, r) => sum + Number(r.refundAmount), 0) === 3500, rows);

console.log('\n== 3. 权限 ==');
const otherStats = await call('GET', '/api/seller/product-refunds', null, otherToken);
check('别的店主看不到本店退款行',
  !(otherStats.body?.data ?? []).some((r) => String(r.spuId) === String(spuId)), otherStats.body?.data?.slice(0, 3));
const buyerStats = await call('GET', '/api/seller/product-refunds', null, buyerToken);
check('买家访问 → 403', buyerStats.status === 403, buyerStats.body);
const anonStats = await call('GET', '/api/seller/product-refunds');
check('未登录 → 401', anonStats.status === 401, anonStats.body);

console.log('\n== 4. 收尾零残留 ==');
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

console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exitCode = 1;
}

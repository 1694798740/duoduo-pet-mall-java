#!/usr/bin/env node
/**
 * B-05 · 电子面单 / 模拟物流冒烟（2026-10-26）。
 *
 * 守五组事实：
 *  A. 电子面单：卖家申请（默认顺丰 / 指定圆通）、运单号格式、simulated=true、状态 CREATED；
 *  B. 幂等与权限：重复申请返回同一张；买家 / 别的卖家 403；运营可代申请；
 *     未支付订单 422、非法快递编码 400；
 *  C. 发货衔接：用面单号发货后，交易域补推「已揽收」，轨迹接口当事人可见；
 *  D. 模拟推进：定时任务自动推进到运输中；内部入口继续推进到派送 / 签收；
 *     重复推进到签收后不再新增；未知订单 404；
 *  E. 买家轨迹权限 + 收尾零残留（面单 / 轨迹 / 订单链路 / 商品 / 临时账号）。
 *
 * 前置：容器在跑；fulfilment 已部署 B-05；`incremental/57-waybill.sql` 已执行；
 *       卖家 13600003333、另一店主 13959064118、运营 13684295254 可用；
 *       模拟推进任务默认开启（simulate-enabled=true，步长 30s）。
 * 用法：NO_PROXY="*" node scripts/waybill-b05-smoke.mjs
 */
import { execFileSync } from 'node:child_process';
import { internalHeaders } from './lib/internal-token.mjs';

const API = 'http://localhost:8080';
/** 内部接口不经网关（网关只放行业务前缀），必须直连履约域 */
const FULFIL = 'http://localhost:8084';
const PWD = 'duoduo12345';
const BUYER_PWD = 'Passw0rd!23';
const SELLER = '13600003333';
const OTHER_SELLER = '13959064118';
const OPERATOR = '13684295254';
const PRODUCT_DB = 'duoduo_product';
const TRADE_DB = 'duoduo_trade';
const FULFIL_DB = 'duoduo_fulfilment';
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

async function call(method, path, body, token, entry, extraHeaders) {
  const res = await fetch(API + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(entry ? { 'X-Duoduo-Entry': entry } : {}),
      ...(extraHeaders ?? {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}
const tokenOf = async (phone, entry, pwd = PWD) =>
  (await call('POST', '/api/auth/login/token', { phone, password: pwd }, null, entry)).body?.data?.accessToken;

/** 直连履约域调用内部接口（带 HMAC 内部凭据；网关不路由 /internal/**） */
async function internalCall(path) {
  const res = await fetch(FULFIL + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...internalHeaders('POST') },
    body: '{}',
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}

function sql(db, query) {
  const out = execFileSync('docker', [
    'exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B',
    '--default-character-set=utf8mb4', '-D', db, '-e', query,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return out.split(/\r?\n/).filter((line) => line && !line.includes('Warning'));
}
const one = (db, query) => sql(db, query)[0] ?? '';
const count = (db, query) => Number(sql(db, query)[0] ?? 0);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const stamp = String(Date.now()).slice(-7);
const title = `B05 面单冒烟 ${stamp}`;
const buyerPhone = `197${String(Date.now()).slice(-8)}`;
const buyerNick = `B05 面单买家 ${stamp}`;

let sellerToken;
let otherToken;
let opToken;
let buyerToken;
let buyerUserId;
let addressId;
let spuId;
let skuId;
let orderNo;
let orderId;

function cleanupStale() {
  const staleOrders = sql(TRADE_DB, `SELECT order_no FROM t_order WHERE id IN (
    SELECT DISTINCT order_id FROM t_order_item WHERE spu_id IN
      (SELECT id FROM ${PRODUCT_DB}.t_spu WHERE title LIKE 'B05 面单冒烟%'))`);
  for (const no of staleOrders) {
    sql(FULFIL_DB, `DELETE FROM t_waybill WHERE order_no='${no}'`);
    sql(FULFIL_DB, `DELETE FROM t_logistics_track WHERE order_no='${no}'`);
  }
  const spuIds = sql(PRODUCT_DB, `SELECT id FROM t_spu WHERE title LIKE 'B05 面单冒烟%'`);
  if (spuIds.length) {
    const list = spuIds.join(',');
    const skuIds = sql(PRODUCT_DB, `SELECT id FROM t_sku WHERE spu_id IN (${list})`);
    if (skuIds.length) {
      const orderIds = sql(TRADE_DB, `SELECT DISTINCT order_id FROM t_order_item WHERE sku_id IN (${skuIds.join(',')})`);
      for (const oid of orderIds) {
        sql(TRADE_DB, `DELETE FROM t_settlement WHERE order_id=${oid}`);
        sql(TRADE_DB, `DELETE FROM t_payment WHERE order_id=${oid}`);
        sql(TRADE_DB, `DELETE FROM t_stock_freeze WHERE order_id=${oid}`);
        sql(TRADE_DB, `DELETE FROM t_order_status_log WHERE order_id=${oid}`);
        sql(TRADE_DB, `DELETE FROM t_order_item WHERE order_id=${oid}`);
        sql(TRADE_DB, `DELETE FROM t_order WHERE id=${oid}`);
      }
    }
    sql(PRODUCT_DB, `DELETE FROM t_sku_stock_reservation WHERE sku_id IN (${skuIds.join(',') || '0'})`);
    sql(PRODUCT_DB, `DELETE FROM t_spu_image WHERE spu_id IN (${list})`);
    sql(PRODUCT_DB, `DELETE FROM t_sku WHERE spu_id IN (${list})`);
    sql(PRODUCT_DB, `DELETE FROM t_spu_audit_log WHERE spu_id IN (${list})`);
    sql(PRODUCT_DB, `DELETE FROM t_spu WHERE id IN (${list})`);
  }
  const buyers = sql(USER_DB, `SELECT id FROM t_user WHERE nickname LIKE 'B05 面单买家%'`);
  for (const uid of buyers) {
    sql(USER_DB, `DELETE FROM t_user_address WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${uid}`);
    sql(TRADE_DB, `DELETE FROM t_cart_item WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_user WHERE id=${uid}`);
  }
}

async function ensureApprovedOnSale(spu) {
  let detail = (await call('GET', `/api/seller/products/${spu}`, null, sellerToken)).body?.data;
  if (detail?.auditStatus === 'PENDING') {
    await call('POST', `/api/admin/products/${spu}/audit`,
      { action: 'APPROVE', version: detail.version }, opToken);
    detail = (await call('GET', `/api/seller/products/${spu}`, null, sellerToken)).body?.data;
  }
  if (detail?.status !== 'ON_SALE') {
    await call('POST', `/api/seller/products/${spu}/status`, { action: 'ON_SALE' }, sellerToken);
    detail = (await call('GET', `/api/seller/products/${spu}`, null, sellerToken)).body?.data;
  }
  return detail;
}

const tracksOf = async (token) =>
  (await call('GET', `/api/logistics/orders/${orderNo}/tracks`, null, token)).body?.data;
const waybillOf = async (token) =>
  (await call('GET', `/api/logistics/orders/${orderNo}/waybill`, null, token)).body?.data;

async function main() {
  console.log('== 0. 登录、清理与夹具 ==');
  sellerToken = await tokenOf(SELLER, 'merchant');
  otherToken = await tokenOf(OTHER_SELLER, 'merchant');
  opToken = await tokenOf(OPERATOR, 'admin');
  check('卖家 / 另一店主 / 运营登录成功', Boolean(sellerToken) && Boolean(otherToken) && Boolean(opToken));
  if (!sellerToken || !otherToken || !opToken) return 1;
  cleanupStale();

  const product = await call('POST', '/api/seller/products', {
    title,
    subtitle: 'B-05 面单冒烟',
    categoryId: '101',
    brandId: '1',
    mainImage: '/img/products/dog-food.svg',
    detail: 'B-05 面单冒烟商品',
    skus: [{ specName: 'B05-1kg', price: 2990, stock: 10, image: null }],
  }, sellerToken);
  spuId = product.body?.data?.id;
  check('夹具商品创建成功', product.status === 200 && Boolean(spuId), product.body);
  if (!spuId) return 1;
  const detail = await ensureApprovedOnSale(spuId);
  skuId = (detail?.skus ?? [])[0]?.id;
  check('夹具商品已上架且 1 个规格', detail?.status === 'ON_SALE' && Boolean(skuId), detail);

  const reg = await call('POST', '/api/auth/register',
    { phone: buyerPhone, password: BUYER_PWD, nickname: buyerNick });
  buyerToken = await tokenOf(buyerPhone, undefined, BUYER_PWD);
  buyerUserId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}'`);
  check('临时买家注册并登录成功',
    (reg.status === 200 || reg.status === 201) && Boolean(buyerToken) && Boolean(buyerUserId));
  if (!buyerToken || !buyerUserId) return 1;
  const address = await call('POST', '/api/addresses', {
    receiverName: buyerNick, receiverPhone: buyerPhone,
    province: '广东省', city: '深圳市', district: '南山区',
    detail: '科技园路 22 号', isDefault: true,
  }, buyerToken);
  addressId = address.body?.data?.id;
  check('临时买家新建收货地址', Boolean(addressId), address.body);
  if (!addressId) return 1;

  console.log('\n== 1. 未支付订单不能申请面单 ==');
  const pending = await call('POST', '/api/orders', {
    addressId, buySkuId: skuId, buyQuantity: 1, idempotentKey: `b05-pending-${Date.now()}`,
  }, buyerToken);
  const pendingNo = (pending.body?.data?.orders ?? [])[0]?.orderNo;
  check('待付款订单创建成功', Boolean(pendingNo), pending.body);
  const pendingWaybill = await call('POST', '/api/logistics/waybills',
    { orderNo: pendingNo, courierCode: 'SF' }, sellerToken);
  check('未支付订单申请面单 → 422', pendingWaybill.status === 422, pendingWaybill.body);
  // 清掉待付款订单，避免残留
  if (pendingNo) {
    const pid = one(TRADE_DB, `SELECT id FROM t_order WHERE order_no='${pendingNo}'`);
    sql(TRADE_DB, `DELETE FROM t_order_item WHERE order_id=${pid}`);
    sql(TRADE_DB, `DELETE FROM t_order WHERE id=${pid}`);
  }

  console.log('\n== 2. 已支付订单申请电子面单 ==');
  const placed = await call('POST', '/api/orders', {
    addressId, buySkuId: skuId, buyQuantity: 1, idempotentKey: `b05-order-${Date.now()}`,
  }, buyerToken);
  orderNo = (placed.body?.data?.orders ?? [])[0]?.orderNo;
  check('订单创建成功', Boolean(orderNo), placed.body);
  if (!orderNo) return 1;
  orderId = one(TRADE_DB, `SELECT id FROM t_order WHERE order_no='${orderNo}'`);
  const paid = await call('POST', `/api/orders/${orderNo}/pay`,
    { channel: 'MOCK', idempotentKey: `b05-pay-${Date.now()}` }, buyerToken);
  check('订单支付成功（PAID）', paid.body?.data?.orderStatus === 'PAID', paid.body);

  const wb = await call('POST', '/api/logistics/waybills',
    { orderNo, courierCode: 'yto' }, sellerToken);
  const waybill = wb.body?.data;
  check('卖家申请电子面单成功', wb.status === 200 && Boolean(waybill?.waybillNo), wb.body);
  check('指定圆通：编码 / 中文名正确',
    waybill?.courierCode === 'YTO' && waybill?.courierName === '圆通速递', waybill);
  check('模拟面单标记与运单号格式', waybill?.simulated === true && /^YTO\d{12}$/.test(waybill?.waybillNo),
    waybill);
  check('初始状态 CREATED / 已生成 / 无轨迹',
    waybill?.status === 'CREATED' && waybill?.statusLabel === '已生成' && (waybill?.tracks ?? []).length === 0,
    waybill);

  const again = await call('POST', '/api/logistics/waybills',
    { orderNo, courierCode: 'SF' }, sellerToken);
  check('重复申请幂等（同一张面单，不换号）',
    again.status === 200 && again.body?.data?.waybillNo === waybill.waybillNo, again.body);
  check('回库：一个订单只有一张面单', count(FULFIL_DB, `SELECT COUNT(*) FROM t_waybill WHERE order_no='${orderNo}'`) === 1);

  const byBuyer = await call('POST', '/api/logistics/waybills', { orderNo }, buyerToken);
  check('买家申请面单 → 403', byBuyer.status === 403, byBuyer.body);
  const byOther = await call('POST', '/api/logistics/waybills', { orderNo }, otherToken);
  check('别的卖家申请面单 → 403', byOther.status === 403, byOther.body);
  const byOp = await call('POST', '/api/logistics/waybills', { orderNo, courierCode: 'SF' }, opToken);
  check('运营代申请返回同一张', byOp.status === 200 && byOp.body?.data?.waybillNo === waybill.waybillNo, byOp.body);
  const badCourier = await call('POST', '/api/logistics/waybills',
    { orderNo, courierCode: 'FAKE' }, sellerToken);
  check('非法快递编码 → 400', badCourier.status === 400, badCourier.body);
  const couriers = await call('GET', '/api/logistics/couriers', null, sellerToken);
  check('快递公司列表 7 家且含顺丰/圆通',
    couriers.status === 200 && couriers.body?.data?.length === 7
      && couriers.body.data.some((x) => x.code === 'SF') && couriers.body.data.some((x) => x.code === 'YTO'),
    couriers.body?.data);

  console.log('\n== 3. 发货衔接：面单号 → 已揽收 ==');
  const ship = await call('POST', `/api/seller/orders/${orderNo}/ship`,
    { logisticsCompany: waybill.courierName, logisticsNo: waybill.waybillNo }, sellerToken);
  check('用面单号发货成功', ship.status === 200 && ship.body?.data?.status === 'SHIPPED', ship.body);
  let tracks = null;
  for (let i = 0; i < 20; i++) {
    tracks = await tracksOf(sellerToken);
    if ((tracks?.tracks ?? []).length >= 1) break;
    await sleep(500);
  }
  check('交易域补推「已揽收」', (tracks?.tracks ?? []).some((t) => t.nodeCode === 'PICKED_UP'), tracks);
  const waybillAfterShip = await waybillOf(buyerToken);
  check('买家可查面单且轨迹带已揽收',
    waybillAfterShip?.waybillNo === waybill.waybillNo
      && (waybillAfterShip?.tracks ?? []).some((t) => t.nodeCode === 'PICKED_UP'), waybillAfterShip);

  console.log('\n== 4. 模拟轨迹推进（定时任务 + 内部入口） ==');
  let autoAdvanced = false;
  for (let i = 0; i < 70; i++) {
    tracks = await tracksOf(sellerToken);
    if ((tracks?.tracks ?? []).some((t) => t.nodeCode === 'IN_TRANSIT')) {
      autoAdvanced = true;
      break;
    }
    await sleep(1000);
  }
  check('定时任务在步长后自动推进到「运输中」', autoAdvanced, tracks);

  const advance1 = await internalCall(`/api/logistics/internal/waybills/${orderNo}/advance`);
  check('内部入口推进到「派送中」',
    advance1.status === 200 && advance1.body?.data?.status === 'OUT_FOR_DELIVERY', advance1.body);
  const advance2 = await internalCall(`/api/logistics/internal/waybills/${orderNo}/advance`);
  check('再次推进到「已签收」', advance2.status === 200 && advance2.body?.data?.status === 'SIGNED', advance2.body);
  const advance3 = await internalCall(`/api/logistics/internal/waybills/${orderNo}/advance`);
  check('签收后重复推进不再新增节点',
    advance3.status === 200 && (advance3.body?.data?.tracks ?? []).length === 4, advance3.body?.data);
  const finalTracks = await tracksOf(buyerToken);
  const codes = (finalTracks?.tracks ?? []).map((t) => t.nodeCode);
  check('买家看到完整四节点轨迹',
    codes.join(',') === 'PICKED_UP,IN_TRANSIT,OUT_FOR_DELIVERY,SIGNED', codes);
  check('节点中文文案由服务端下发',
    (finalTracks?.tracks ?? []).every((t) => Boolean(t.nodeLabel)), finalTracks?.tracks);
  check('回库：面单状态 SIGNED / 轨迹 4 条',
    one(FULFIL_DB, `SELECT status FROM t_waybill WHERE order_no='${orderNo}'`) === 'SIGNED'
      && count(FULFIL_DB, `SELECT COUNT(*) FROM t_logistics_track WHERE order_no='${orderNo}'`) === 4);

  const advanceUnknown = await internalCall('/api/logistics/internal/waybills/NO-SUCH-ORDER/advance');
  check('未知订单推进 → 404', advanceUnknown.status === 404, advanceUnknown.body);
  const tracksByOther = await call('GET', `/api/logistics/orders/${orderNo}/tracks`, null, otherToken);
  check('别的卖家看轨迹 → 403', tracksByOther.status === 403, tracksByOther.body);
  const tracksAnon = await call('GET', `/api/logistics/orders/${orderNo}/tracks`);
  check('未登录看轨迹 → 401', tracksAnon.status === 401, tracksAnon.body);

  console.log('\n== 5. 收尾零残留 ==');
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

  check('面单 / 轨迹零残留',
    count(FULFIL_DB, `SELECT COUNT(*) FROM t_waybill WHERE order_no='${orderNo}'`) === 0
      && count(FULFIL_DB, `SELECT COUNT(*) FROM t_logistics_track WHERE order_no='${orderNo}'`) === 0);
  check('订单链路零残留',
    count(TRADE_DB, `SELECT COUNT(*) FROM t_order WHERE id=${orderId}`) === 0
      && count(TRADE_DB, `SELECT COUNT(*) FROM t_order_item WHERE order_id=${orderId}`) === 0
      && count(TRADE_DB, `SELECT COUNT(*) FROM t_payment WHERE order_id=${orderId}`) === 0);
  check('商品 / 临时买家零残留',
    count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu WHERE id=${spuId}`) === 0
      && count(USER_DB, `SELECT COUNT(*) FROM t_user WHERE id=${buyerUserId}`) === 0);

  return failures.length ? 1 : 0;
}

try {
  const code = await main();
  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) {
    console.log('失败项：');
    for (const name of failures) console.log('  - ' + name);
  }
  process.exitCode = code;
} catch (err) {
  console.error('冒烟异常：', err);
  process.exitCode = 1;
}

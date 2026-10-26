#!/usr/bin/env node
/**
 * F-04 · 店员操作权限细分冒烟（2026-10-26）。
 *
 * 守六组事实：
 *  A. 邀请默认全量：新店员 6 项权限齐全，/users/me 与店主成员列表一致；
 *  B. 配置接口：只保留 ORDER_SHIP 后立即生效；未知键 / 缺字段 422；店员无权配置 403；
 *     店主行不可配置 422；
 *  C. 门禁（只有发货权）：改商品 / 改价 / 上下架 / 新建 / 退款 / 客服回复全部 403，
 *     发货 200，查看订单与商品仍 200；
 *  D. 仅查看（空数组）：读写分离 —— 查订单 / 商品 200，任何写操作 403；
 *  E. 恢复全量：改价 200、客服回复 200；审计表落 UPDATE_STAFF_PERMISSIONS；
 *  F. 收尾零残留：订单链路 / 商品 / 客服会话 / 成员行 / 临时账号全部清理。
 *
 * 前置：容器在跑；user/product/trade/social 已部署 F-04；
 *       `incremental/59-staff-permissions.sql` 已执行；卖家 13600003333 与运营 13684295254 可用。
 * 用法：NO_PROXY="*" node scripts/staff-permissions-f04-smoke.mjs
 */
import { execFileSync } from 'node:child_process';

const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OPERATOR = '13684295254';
const USER_DB = 'duoduo_user';
const PRODUCT_DB = 'duoduo_product';
const TRADE_DB = 'duoduo_trade';
const SOCIAL_DB = 'duoduo_social';

const ALL = ['PRODUCT_EDIT', 'PRODUCT_PRICE', 'PRODUCT_STATUS', 'ORDER_SHIP', 'ORDER_REFUND', 'SUPPORT_REPLY'];

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
const staffPhone = `196${suffix}`;
const buyerPhone = `197${suffix}`;
const staffName = `F04权限店员${stamp}`;
const buyerName = `F04权限买家${stamp}`;
const title = `F04 权限商品 ${stamp}`;

const ownerToken = await tokenOf(SELLER);
const opToken = await tokenOf(OPERATOR);
check('店主 / 运营登录成功', Boolean(ownerToken) && Boolean(opToken));
if (!ownerToken || !opToken) process.exit(1);
const ownerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${SELLER}'`);

// ---------------- A. 邀请默认全量 ----------------
console.log('\n== 0. 夹具：临时店员 + 买家 + 商品 + 已支付订单 + 客服会话 ==');
const regStaff = await call('POST', '/api/auth/register', { phone: staffPhone, password: PWD, nickname: staffName });
const regBuyer = await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: buyerName });
check('临时店员 / 买家注册成功', regStaff.status === 200 && regBuyer.status === 200, { regStaff, regBuyer });
const staffId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${staffPhone}'`);
const buyerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}'`);
// 该存量店铺没有 OWNER 成员行（早期入驻只写 t_merchant.owner_user_id），
// 本套件补一行用于验证「店主行不可配置权限」，收尾删除。
const ownerMemberId = String(BigInt(Date.now()) * 100n + 9n);
const ownerMerchantId = one(USER_DB,
  `SELECT id FROM t_merchant WHERE owner_user_id=(SELECT id FROM t_user WHERE phone='${SELLER}') LIMIT 1`);
sql(USER_DB, `INSERT INTO t_merchant_member (id, merchant_id, user_id, role, status, created_at, updated_at)
VALUES (${ownerMemberId}, ${ownerMerchantId}, ${ownerId}, 'OWNER', 'ACTIVE', UTC_TIMESTAMP(), UTC_TIMESTAMP())`);
const invited = await call('POST', '/api/seller/staff', { phone: staffPhone }, ownerToken);
const memberId = invited.body?.data?.memberId;
check('店主邀请店员成功', invited.status === 200 && Boolean(memberId), invited.body);
let staffToken = await tokenOf(staffPhone);
const buyerToken = await tokenOf(buyerPhone);
check('店员 / 买家登录成功', Boolean(staffToken) && Boolean(buyerToken));

const staffList0 = await call('GET', '/api/seller/staff', null, ownerToken);
const staffRow0 = (staffList0.body?.data ?? []).find((m) => m.memberId === memberId);
const ownerRow = (staffList0.body?.data ?? []).find((m) => m.role === 'OWNER');
check('新店员默认全量 6 项权限',
  JSON.stringify(staffRow0?.permissions ?? []) === JSON.stringify(ALL), staffRow0);
check('店主成员行恒为全量 6 项权限',
  JSON.stringify(ownerRow?.permissions ?? []) === JSON.stringify(ALL), ownerRow);
let me = await call('GET', '/api/users/me', null, staffToken);
check('/users/me 下发全量权限',
  JSON.stringify(me.body?.data?.merchant?.permissions ?? []) === JSON.stringify(ALL), me.body?.data?.merchant);

// 商品（店主建、运营过审、上架）
const product = await call('POST', '/api/seller/products', {
  title,
  subtitle: 'F-04 权限冒烟',
  categoryId: '101',
  brandId: '1',
  mainImage: '/img/products/dog-food.svg',
  detail: 'F-04 权限冒烟商品',
  skus: [{ specName: '默认规格', price: 990, stock: 10, image: null }],
}, ownerToken);
const spuId = product.body?.data?.id;
let detail = (await call('GET', `/api/seller/products/${spuId}`, null, ownerToken)).body?.data;
if (detail?.auditStatus === 'PENDING') {
  await call('POST', `/api/admin/products/${spuId}/audit`,
    { action: 'APPROVE', version: detail.version }, opToken);
  detail = (await call('GET', `/api/seller/products/${spuId}`, null, ownerToken)).body?.data;
}
if (detail?.status !== 'ON_SALE') {
  await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, ownerToken);
  detail = (await call('GET', `/api/seller/products/${spuId}`, null, ownerToken)).body?.data;
}
const skuId = detail?.skus?.[0]?.id;
check('夹具商品已上架', Boolean(spuId) && Boolean(skuId) && detail?.status === 'ON_SALE', detail);

// 买家地址 + 已支付订单
const address = await call('POST', '/api/addresses', {
  receiverName: buyerName, receiverPhone: buyerPhone,
  province: '广东省', city: '深圳市', district: '南山区',
  detail: '科技园路 66 号', isDefault: true,
}, buyerToken);
const addressId = address.body?.data?.id;
const placed = await call('POST', '/api/orders', {
  addressId, buySkuId: skuId, buyQuantity: 1, idempotentKey: `f04-${Date.now()}`,
}, buyerToken);
const orderNo = (placed.body?.data?.orders ?? [])[0]?.orderNo;
const paid = await call('POST', `/api/orders/${orderNo}/pay`,
  { channel: 'MOCK', idempotentKey: `f04-pay-${Date.now()}` }, buyerToken);
const orderId = one(TRADE_DB, `SELECT id FROM t_order WHERE order_no='${orderNo}'`);
check('夹具订单已支付', paid.body?.data?.orderStatus === 'PAID' && Boolean(orderId),
  { orderNo, paid: paid.body });

// 客服会话（买家发起）
const sessionCreated = await call('GET', `/api/support/session?sellerId=${ownerId}`, null, buyerToken);
const sessionId = sessionCreated.body?.data?.id;
await call('POST', '/api/support/messages',
  { sellerId: ownerId, content: `F04 权限冒烟 ${stamp} 咨询` }, buyerToken);
check('夹具客服会话就绪', sessionCreated.status === 200 && Boolean(sessionId), sessionCreated.body);

// ---------------- B. 配置接口 ----------------
console.log('\n== 1. 配置「只能发货」并立即生效 ==');
const onlyShip = await call('POST', `/api/seller/staff/${memberId}/permissions`,
  { permissions: ['ORDER_SHIP'] }, ownerToken);
check('配置为仅 ORDER_SHIP → 200 且视图回显',
  onlyShip.status === 200 && JSON.stringify(onlyShip.body?.data?.permissions ?? []) === '["ORDER_SHIP"]',
  onlyShip.body);
check('库内 CSV = ORDER_SHIP',
  one(USER_DB, `SELECT permissions FROM t_merchant_member WHERE id=${memberId}`) === 'ORDER_SHIP');
me = await call('GET', '/api/users/me', null, staffToken);
check('/users/me 即时反映新权限',
  JSON.stringify(me.body?.data?.merchant?.permissions ?? []) === '["ORDER_SHIP"]', me.body?.data?.merchant);

const unknownKey = await call('POST', `/api/seller/staff/${memberId}/permissions`,
  { permissions: ['ORDER_SHIPS'] }, ownerToken);
check('未知权限键 → 400 校验失败', unknownKey.status === 400, unknownKey.body);
const missing = await call('POST', `/api/seller/staff/${memberId}/permissions`, {}, ownerToken);
check('缺少 permissions 字段 → 400（不能解释成清空）', missing.status === 400, missing.body);
const staffConfigure = await call('POST', `/api/seller/staff/${memberId}/permissions`,
  { permissions: ALL }, staffToken);
check('店员自己改权限 → 403', staffConfigure.status === 403, staffConfigure.body);
const ownerConfigure = await call('POST', `/api/seller/staff/${ownerRow?.memberId}/permissions`,
  { permissions: ['ORDER_SHIP'] }, ownerToken);
check('配置店主行 → 422', ownerConfigure.status === 422, ownerConfigure.body);
const buyerConfigure = await call('POST', `/api/seller/staff/${memberId}/permissions`,
  { permissions: ALL }, buyerToken);
check('买家改权限 → 404（不泄露店铺存在性）', buyerConfigure.status === 404, buyerConfigure.body);
const anonConfigure = await fetch(`${API}/api/seller/staff/${memberId}/permissions`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ permissions: ALL }),
});
check('未登录改权限 → 401', anonConfigure.status === 401, anonConfigure.status);

// ---------------- C. 门禁：只有发货权 ----------------
console.log('\n== 2. 只有发货权：商品 / 退款 / 客服全部被拦，发货放行 ==');
const edit = await call('PUT', `/api/seller/products/${spuId}`, { title: `${title} 改` }, staffToken);
check('店员改商品 → 403', edit.status === 403, edit.body);
const price = await call('PUT', `/api/seller/products/${spuId}/skus/price-stock`,
  { skus: [{ skuId, price: 1090 }] }, staffToken);
check('店员改价 → 403', price.status === 403, price.body);
const status = await call('POST', `/api/seller/products/${spuId}/status`, { action: 'OFF_SHELF' }, staffToken);
check('店员上下架 → 403', status.status === 403, status.body);
const create = await call('POST', '/api/seller/products', {
  title: `F04 越权新建 ${stamp}`, categoryId: '101', brandId: '1',
  mainImage: '/img/products/dog-food.svg',
  skus: [{ specName: '默认', price: 100, stock: 1 }],
}, staffToken);
check('店员新建商品 → 403', create.status === 403, create.body);
const refund = await call('POST', `/api/seller/orders/${orderNo}/refund`,
  { reason: 'F04 无退款权' }, staffToken);
check('店员退款 → 403', refund.status === 403, refund.body);
const reply = await call('POST', `/api/seller/support/sessions/${sessionId}/messages`,
  { content: 'F04 无客服回复权' }, staffToken);
check('店员回复客服 → 403', reply.status === 403, reply.body);
const staffOrders = await call('GET', '/api/seller/orders?page=1&pageSize=10', null, staffToken);
check('店员查看订单 → 200（只读不受限）', staffOrders.status === 200, staffOrders.body);
const staffProducts = await call('GET', '/api/seller/products?page=1&pageSize=10', null, staffToken);
check('店员查看商品 → 200', staffProducts.status === 200, staffProducts.body);
const ship = await call('POST', `/api/seller/orders/${orderNo}/ship`,
  { logisticsCompany: '顺丰速运', logisticsNo: 'SF-F04-0001' }, staffToken);
check('★ 店员发货 → 200（这正是被授予的权限）', ship.status === 200, ship.body);

// ---------------- D. 仅查看 ----------------
console.log('\n== 3. 空数组 = 仅查看 ==');
const readOnly = await call('POST', `/api/seller/staff/${memberId}/permissions`,
  { permissions: [] }, ownerToken);
check('配置为空数组 → 200 且权限为空',
  readOnly.status === 200 && (readOnly.body?.data?.permissions ?? []).length === 0, readOnly.body);
const roOrders = await call('GET', '/api/seller/orders?page=1&pageSize=10', null, staffToken);
const roProducts = await call('GET', '/api/seller/products?page=1&pageSize=10', null, staffToken);
check('仅查看：订单 / 商品读取仍 200', roOrders.status === 200 && roProducts.status === 200,
  { roOrders: roOrders.status, roProducts: roProducts.status });
const roCreate = await call('POST', '/api/seller/products', {
  title: `F04 只读新建 ${stamp}`, categoryId: '101', brandId: '1',
  mainImage: '/img/products/dog-food.svg',
  skus: [{ specName: '默认', price: 100, stock: 1 }],
}, staffToken);
check('仅查看：新建商品 403', roCreate.status === 403, roCreate.body);

// ---------------- E. 恢复全量 + 审计 ----------------
console.log('\n== 4. 恢复全量：改价 / 客服回复放行，审计留痕 ==');
const restore = await call('POST', `/api/seller/staff/${memberId}/permissions`,
  { permissions: ALL }, ownerToken);
check('恢复全量 → 200', restore.status === 200
  && JSON.stringify(restore.body?.data?.permissions ?? []) === JSON.stringify(ALL), restore.body);
const priceOk = await call('PUT', `/api/seller/products/${spuId}/skus/price-stock`,
  { skus: [{ id: skuId, price: 1090 }] }, staffToken);
check('恢复后改价 → 200', priceOk.status === 200, priceOk.body);
const replyOk = await call('POST', `/api/seller/support/sessions/${sessionId}/messages`,
  { content: 'F04 已恢复客服回复权限' }, staffToken);
check('恢复后客服回复 → 200', replyOk.status === 200, replyOk.body);
const auditRows = count(USER_DB,
  `SELECT COUNT(*) FROM t_audit_log WHERE action='UPDATE_STAFF_PERMISSIONS' AND target_id=${staffId}`);
check('审计落 UPDATE_STAFF_PERMISSIONS（至少 3 次变更）', auditRows >= 3, auditRows);
const auditTenant = one(USER_DB,
  `SELECT tenant_id FROM t_audit_log WHERE action='UPDATE_STAFF_PERMISSIONS' AND target_id=${staffId} ORDER BY created_at DESC LIMIT 1`);
check('审计租户 = 店铺主体 userId', auditTenant === String(ownerId), { auditTenant, ownerId });

// ---------------- F. 收尾 ----------------
console.log('\n== 5. 收尾零残留 ==');
sql(SOCIAL_DB, `DELETE FROM t_support_ticket_log WHERE session_id=${sessionId}`);
sql(SOCIAL_DB, `DELETE FROM t_support_message WHERE session_id=${sessionId}`);
sql(SOCIAL_DB, `DELETE FROM t_support_session WHERE id=${sessionId}`);
sql(TRADE_DB, `DELETE FROM t_settlement WHERE order_id=${orderId}`);
sql(TRADE_DB, `DELETE FROM t_payment WHERE order_id=${orderId}`);
sql(TRADE_DB, `DELETE FROM t_stock_freeze WHERE order_id=${orderId}`);
sql(PRODUCT_DB, `DELETE FROM t_sku_stock_reservation WHERE order_id=${orderId}`);
sql(TRADE_DB, `DELETE FROM t_order_status_log WHERE order_id=${orderId}`);
sql(TRADE_DB, `DELETE FROM t_order_item WHERE order_id=${orderId}`);
sql(TRADE_DB, `DELETE FROM t_order WHERE id=${orderId}`);
sql(TRADE_DB, `DELETE FROM t_cart_item WHERE user_id=${buyerId}`);
sql(USER_DB, `DELETE FROM t_user_address WHERE user_id=${buyerId}`);
sql(PRODUCT_DB, `DELETE FROM t_spu_image WHERE spu_id=${spuId}`);
sql(PRODUCT_DB, `DELETE FROM t_sku WHERE spu_id=${spuId}`);
sql(PRODUCT_DB, `DELETE FROM t_spu_audit_log WHERE spu_id=${spuId}`);
sql(PRODUCT_DB, `DELETE FROM t_spu WHERE id=${spuId}`);
sql(USER_DB, `DELETE FROM t_merchant_member WHERE id=${memberId}`);
sql(USER_DB, `DELETE FROM t_merchant_member WHERE id=${ownerMemberId}`);
for (const uid of [staffId, buyerId]) {
  sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${uid}`);
  sql(USER_DB, `DELETE FROM t_user WHERE id=${uid}`);
}
check('订单链路 / 商品 / 客服会话 / 成员行 / 临时账号零残留',
  count(TRADE_DB, `SELECT COUNT(*) FROM t_order WHERE id=${orderId}`) === 0
    && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu WHERE id=${spuId}`) === 0
    && count(SOCIAL_DB, `SELECT COUNT(*) FROM t_support_session WHERE id=${sessionId}`) === 0
    && count(USER_DB, `SELECT COUNT(*) FROM t_merchant_member WHERE id=${memberId}`) === 0
    && count(USER_DB, `SELECT COUNT(*) FROM t_user WHERE id IN (${staffId},${buyerId})`) === 0);

console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exitCode = 1;
}

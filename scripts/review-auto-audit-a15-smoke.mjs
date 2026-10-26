#!/usr/bin/env node
/**
 * A-15 · 评价机审接口冒烟（2026-10-26）。
 *
 * 守七组事实：
 *  A. 夹具：第三方商品 + 三张真实订单（下单 → 支付 → 发货 → 确认收货）；
 *  B. 干净内容：提交即 APPROVED，audit_source=AUTO，审核时间落库，公开口径可见；
 *  C. 命中手机号：转 PENDING（不自动下架），公开评价区 / 评分汇总 / 商品评分都不含它，
 *     且审核原因里的手机号已脱敏；
 *  D. 人工通过机审转人工的评价：APPROVED + audit_source=MANUAL + 原因清空，公开口径与评分恢复；
 *  E. 追评机审：干净追评不重审；命中导流的追评把整条评价拉回 PENDING 并反向重算评分；
 *  F. 人工下架机审转人工的评价：REJECTED；已下架评价不能再追评（422）；
 *  G. 边界与收尾：后台待审 / 已下架筛选、买家我的评价带审核态、审计留痕、夹具零残留。
 *
 * 前置：容器在跑；fulfilment 已部署 A-15；卖家 13600003333、运营 13684295254 可用。
 * 用法：NO_PROXY="*" node scripts/review-auto-audit-a15-smoke.mjs
 */
import { execFileSync } from 'node:child_process';
import { internalHeaders } from './lib/internal-token.mjs';

const API = 'http://localhost:8080';
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
function check(name, ok, detail) {
  if (ok) {
    pass++;
    console.log('  [PASS] ' + name);
  } else {
    failures.push(name);
    console.log('  [FAIL] ' + name + (detail === undefined ? '' : '  -> ' + JSON.stringify(detail).slice(0, 600)));
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

const tokenOf = async (phone, entry) =>
  (await call('POST', '/api/auth/login/token', { phone, password: PWD }, null, entry)).body?.data?.accessToken;

/** 回库断言：只读 */
function sql(db, query) {
  const out = execFileSync('docker', [
    'exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B',
    '--default-character-set=utf8mb4', '-D', db, '-e', query,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return out.split(/\r?\n/).filter((line) => line && !line.includes('Warning'));
}
const one = (db, query) => sql(db, query)[0];
const count = (db, query) => Number(sql(db, query)[0] ?? 0);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitFor(label, fn, timeout = 12000) {
  const deadline = Date.now() + timeout;
  let last;
  while (Date.now() < deadline) {
    last = fn();
    if (last) return true;
    await sleep(400);
  }
  console.log(`  [WAIT] ${label} 超时，最后值=${JSON.stringify(last)}`);
  return false;
}

const detailOf = async (token, spuId) =>
  (await call('GET', `/api/seller/products/${spuId}`, null, token)).body?.data;
const publicSummary = async (spuId) =>
  (await call('GET', `/api/reviews/product/${spuId}/summary`)).body?.data;
const publicList = async (spuId) =>
  (await call('GET', `/api/reviews/product/${spuId}?page=1&pageSize=50`)).body?.data;
const adminList = (token, query) =>
  call('GET', '/api/admin/reviews?' + new URLSearchParams(Object.entries(query).filter(([, v]) => v !== undefined)), null, token, 'admin');
const myReviews = async (token) =>
  (await call('GET', '/api/reviews/mine?page=1&pageSize=50', null, token)).body?.data;

const skuOf = (detail) => (detail?.skus ?? [])[0];

/** 商品评分（DECIMAL 返回 5.00 / 4.67，必须按数值比） */
const spuRating = () => {
  const row = one(PRODUCT_DB, `SELECT CONCAT(IFNULL(rating,0),'|',IFNULL(rating_count,0)) FROM t_spu WHERE id=${spuId}`);
  if (!row) return null;
  const [rating, amount] = row.split('|').map(Number);
  return { rating, count: amount, raw: row };
};
const ratingIs = (rating, amount) => {
  const v = spuRating();
  return v && Math.abs(v.rating - rating) < 0.01 && v.count === amount ? v.raw : null;
};

/** 评价审核台账快照：状态 | 来源 | 原因 | 操作人 | 审核时间是否存在 */
const reviewRow = (id) => one(FULFIL_DB,
  `SELECT CONCAT(audit_status,'|',IFNULL(audit_source,'NULL'),'|',IFNULL(audit_reason,'-'),'|',IFNULL(audited_by,'-'),'|',IF(audited_at IS NULL,'-','SET')) FROM t_review WHERE id=${id}`);

/**
 * 开机清扫：上一轮中途崩溃时按标题前缀 / 买家昵称定位本套件夹具先清掉。
 */
function cleanupStale() {
  const spuIds = sql(PRODUCT_DB, `SELECT id FROM t_spu WHERE title LIKE 'A15 评价机审冒烟%'`);
  if (spuIds.length) {
    const spuList = spuIds.join(',');
    const skuIds = sql(PRODUCT_DB, `SELECT id FROM t_sku WHERE spu_id IN (${spuList})`);
    if (skuIds.length) {
      const orderIds = sql(TRADE_DB, `SELECT DISTINCT order_id FROM t_order_item WHERE sku_id IN (${skuIds.join(',')})`);
      for (const oid of orderIds) {
        sql(TRADE_DB, `DELETE FROM t_settlement WHERE order_id=${oid}`);
        sql(PRODUCT_DB, `DELETE FROM t_sku_stock_reservation WHERE order_id=${oid}`);
        sql(TRADE_DB, `DELETE FROM t_stock_freeze WHERE order_id=${oid}`);
        sql(TRADE_DB, `DELETE FROM t_payment WHERE order_id=${oid}`);
        sql(TRADE_DB, `DELETE FROM t_order_status_log WHERE order_id=${oid}`);
        sql(TRADE_DB, `DELETE FROM t_order_item WHERE order_id=${oid}`);
        sql(TRADE_DB, `DELETE FROM t_order WHERE id=${oid}`);
      }
    }
    sql(FULFIL_DB, `DELETE FROM t_review WHERE spu_id IN (${spuList})`);
    sql(PRODUCT_DB, `DELETE FROM t_spu_image WHERE spu_id IN (${spuList})`);
    sql(PRODUCT_DB, `DELETE FROM t_sku WHERE spu_id IN (${spuList})`);
    sql(PRODUCT_DB, `DELETE FROM t_spu_audit_log WHERE spu_id IN (${spuList})`);
    sql(PRODUCT_DB, `DELETE FROM t_spu WHERE id IN (${spuList})`);
    console.log(`  [CLEAN] 历史 A15 商品夹具 ${spuIds.length} 件已清理`);
  }
  sql(FULFIL_DB, `DELETE FROM t_review WHERE content LIKE 'A15 评价机审冒烟-%'`);
  const buyers = sql(USER_DB, `SELECT id FROM t_user WHERE nickname='A15 评价买家'`);
  for (const uid of buyers) {
    sql(USER_DB, `DELETE FROM t_user_address WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${uid}`);
    sql(TRADE_DB, `DELETE FROM t_cart_item WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_user WHERE id=${uid}`);
  }
  if (buyers.length) {
    console.log(`  [CLEAN] 历史 A15 临时买家 ${buyers.length} 个已清理`);
  }
}

async function ensureApprovedOnSale(token, opToken, spuId) {
  let detail = await detailOf(token, spuId);
  if (detail?.auditStatus === 'PENDING') {
    await call('POST', `/api/admin/products/${spuId}/audit`,
      { action: 'APPROVE', version: detail.version }, opToken, 'admin');
    detail = await detailOf(token, spuId);
  }
  if (detail?.status !== 'ON_SALE') {
    await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, token);
    detail = await detailOf(token, spuId);
  }
  return detail;
}

const stamp = String(Date.now()).slice(-7);
const title = `A15 评价机审冒烟 ${stamp}`;
const buyerPhone = '199' + String(Date.now()).slice(-8);
const cleanText = `A15 评价机审冒烟-${stamp}-干净内容，猫粮很香`;
const riskyText = `A15 评价机审冒烟-${stamp}-同款加微信 13800138000`;
const appendRiskyText = '追评：加微信领试吃券 13800138000';

let sellerToken;
let opToken;
let buyerToken;
let buyerUserId;
let opUserId;
let spuId;
let skuId;
let addressId;
const orders = [];
const reviews = {};

async function cleanup() {
  // 评价 / 订单 / 商品全部物理清理；审计台账按 append-only 纪律保留
  for (const { orderId } of orders) {
    sql(TRADE_DB, `DELETE FROM t_settlement WHERE order_id=${orderId}`);
    // 预占台账在商品库（B-03：不跨库）
    sql(PRODUCT_DB, `DELETE FROM t_sku_stock_reservation WHERE order_id=${orderId}`);
    sql(TRADE_DB, `DELETE FROM t_stock_freeze WHERE order_id=${orderId}`);
    sql(TRADE_DB, `DELETE FROM t_payment WHERE order_id=${orderId}`);
    sql(TRADE_DB, `DELETE FROM t_order_status_log WHERE order_id=${orderId}`);
    sql(TRADE_DB, `DELETE FROM t_order_item WHERE order_id=${orderId}`);
    sql(TRADE_DB, `DELETE FROM t_order WHERE id=${orderId}`);
  }
  sql(FULFIL_DB, `DELETE FROM t_review WHERE content LIKE 'A15 评价机审冒烟-${stamp}%'`);
  if (spuId) {
    // 评价删掉后把商品评分显式复位（recompute 是「信任补丁」语义，传什么写什么）
    await call('POST', '/api/products/internal/ratings/recompute',
      [{ spuId, avgRating: 0, ratingCount: 0 }], null, null, internalHeaders('POST'));
    sql(PRODUCT_DB, `DELETE FROM t_spu_image WHERE spu_id=${spuId}`);
    sql(PRODUCT_DB, `DELETE FROM t_sku WHERE spu_id=${spuId}`);
    sql(PRODUCT_DB, `DELETE FROM t_spu_audit_log WHERE spu_id=${spuId}`);
    sql(PRODUCT_DB, `DELETE FROM t_spu WHERE id=${spuId}`);
  }
  if (buyerUserId) {
    sql(USER_DB, `DELETE FROM t_user_address WHERE user_id=${buyerUserId}`);
    sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${buyerUserId}`);
    sql(TRADE_DB, `DELETE FROM t_cart_item WHERE user_id=${buyerUserId}`);
    sql(USER_DB, `DELETE FROM t_user WHERE id=${buyerUserId}`);
  }
}

/** 建一张已完成订单并返回订单号（同一买家、同一 SKU，库存 10 足够） */
async function placeCompletedOrder(tag) {
  const placed = await call('POST', '/api/orders', {
    addressId, buySkuId: skuId, buyQuantity: 1, idempotentKey: `a15-${tag}-${Date.now()}`,
  }, buyerToken);
  const orderNo = (placed.body?.data?.orders ?? [])[0]?.orderNo;
  if (!orderNo) throw new Error(`下单失败（${tag}）：${JSON.stringify(placed.body)}`);
  const orderId = one(TRADE_DB, `SELECT id FROM t_order WHERE order_no='${orderNo}'`);
  orders.push({ tag, orderNo, orderId });
  const paid = await call('POST', `/api/orders/${orderNo}/pay`,
    { channel: 'MOCK', idempotentKey: `a15-pay-${tag}-${Date.now()}` }, buyerToken);
  const shipped = await call('POST', `/api/seller/orders/${orderNo}/ship`,
    { logisticsCompany: '顺丰速运', logisticsNo: 'SF' + Date.now() }, sellerToken);
  const confirmed = await call('POST', `/api/orders/${orderNo}/confirm`, undefined, buyerToken);
  check(`订单 ${tag} 全链路完成`,
    paid.body?.data?.orderStatus === 'PAID'
      && shipped.status === 200
      && confirmed.body?.data?.status === 'COMPLETED',
    { paid: paid.body, shipped: shipped.body, confirmed: confirmed.body });
  return orderNo;
}

async function submitReview(orderNo, rating, content) {
  return call('POST', '/api/reviews', {
    orderNo, anonymous: false, items: [{ spuId, skuId, rating, content }],
  }, buyerToken);
}

async function main() {
  try {
    console.log('== 0. 登录与夹具商品 ==');
    cleanupStale();
    sellerToken = await tokenOf(SELLER, 'merchant');
    opToken = await tokenOf(OPERATOR, 'admin');
    check('卖家登录成功', Boolean(sellerToken));
    check('运营登录成功（admin 入口）', Boolean(opToken));
    if (!sellerToken || !opToken) return 1;
    opUserId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${OPERATOR}'`);

    const created = await call('POST', '/api/seller/products', {
      title,
      subtitle: 'A-15 评价机审冒烟',
      categoryId: '101',
      brandId: '1',
      mainImage: '/img/products/dog-food.svg',
      detail: 'A-15 评价机审冒烟商品',
      skus: [{ specName: 'A15-1kg', price: 2990, stock: 10, image: null }],
    }, sellerToken);
    spuId = created.body?.data?.id;
    check('夹具商品创建成功', created.status === 200 && Boolean(spuId), created.body);
    if (!spuId) return 1;
    const detail = await ensureApprovedOnSale(sellerToken, opToken, spuId);
    skuId = skuOf(detail)?.id;
    check('夹具商品已上架且有 1 个规格', detail?.status === 'ON_SALE' && Boolean(skuId), {
      status: detail?.status, skus: detail?.skus,
    });
    if (!skuId) return 1;

    console.log('\n== A. 临时买家与三张已完成订单 ==');
    const reg = await call('POST', '/api/auth/register',
      { phone: buyerPhone, password: BUYER_PWD, nickname: 'A15 评价买家' });
    check('临时买家注册成功', reg.status === 200 || reg.status === 201, reg.body);
    buyerToken = (await call('POST', '/api/auth/login/token',
      { phone: buyerPhone, password: BUYER_PWD })).body?.data?.accessToken;
    buyerUserId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}'`);
    check('临时买家登录成功', Boolean(buyerToken) && Boolean(buyerUserId));
    if (!buyerToken || !buyerUserId) return 1;
    const address = await call('POST', '/api/addresses', {
      receiverName: 'A15 评价买家', receiverPhone: buyerPhone,
      province: '广东省', city: '深圳市', district: '南山区',
      detail: '科技园路 20 号', isDefault: true,
    }, buyerToken);
    addressId = address.body?.data?.id;
    check('临时买家新建收货地址', Boolean(addressId), address.body);
    if (!addressId) return 1;

    const orderClean = await placeCompletedOrder('clean');
    const orderRisky = await placeCompletedOrder('risky');
    const orderAppend = await placeCompletedOrder('append');

    console.log('\n== B. 干净内容：机审自动放行 ==');
    const cleanCreated = await submitReview(orderClean, 5, cleanText);
    reviews.clean = cleanCreated.body?.data?.reviews?.[0]?.id;
    check('干净评价提交成功且立即 APPROVED',
      cleanCreated.status === 200 && cleanCreated.body?.data?.reviews?.[0]?.auditStatus === 'APPROVED',
      cleanCreated.body);
    if (!reviews.clean) return 1;
    check('回库：APPROVED + 来源 AUTO + 无原因/操作人 + 审核时间已落',
      reviewRow(reviews.clean) === 'APPROVED|AUTO|-|-|SET', reviewRow(reviews.clean));
    check('公开评分汇总：1 条 / 5.0 分',
      (await publicSummary(spuId))?.total === 1 && (await publicSummary(spuId))?.avgRating === '5.0');
    check('商品评分回写为 5.0 / 1 条',
      await waitFor('rating=5/count=1', () => ratingIs(5, 1)), spuRating());

    console.log('\n== C. 命中手机号：转 PENDING，不自动下架 ==');
    const riskyCreated = await submitReview(orderRisky, 4, riskyText);
    reviews.risky = riskyCreated.body?.data?.reviews?.[0]?.id;
    check('命中风险的评价提交成功但为 PENDING',
      riskyCreated.status === 200 && riskyCreated.body?.data?.reviews?.[0]?.auditStatus === 'PENDING',
      riskyCreated.body);
    if (!reviews.risky) return 1;
    const riskyRow = reviewRow(reviews.risky);
    check('回库：PENDING + 来源 AUTO + 原因以「机审命中」开头 + 无人工操作人',
      riskyRow?.startsWith('PENDING|AUTO|机审命中') && riskyRow.endsWith('|-|SET'), riskyRow);
    const riskyReason = one(FULFIL_DB, `SELECT audit_reason FROM t_review WHERE id=${reviews.risky}`);
    check('审核原因里的手机号已脱敏', riskyReason.includes('138****8000') && !riskyReason.includes('13800138000'), riskyReason);
    const summaryHeld = await publicSummary(spuId);
    const listHeld = await publicList(spuId);
    check('公开评分汇总仍只有 1 条 / 5.0（待审不参与）',
      summaryHeld?.total === 1 && summaryHeld?.avgRating === '5.0', summaryHeld);
    check('公开评价区不含待审评价', !(listHeld?.items ?? []).some((x) => String(x.id) === String(reviews.risky)));
    check('商品评分仍为 5.0 / 1 条（待审不参与）', ratingIs(5, 1), spuRating());
    const pendingList = await adminList(opToken, { status: 'PENDING', keyword: stamp });
    const pendingItem = (pendingList.body?.data?.items ?? []).find((x) => String(x.id) === String(reviews.risky));
    check('后台待审核筛选命中且来源为 AUTO',
      Boolean(pendingItem) && pendingItem.auditSource === 'AUTO', pendingList.body);

    console.log('\n== D. 人工通过机审转人工的评价 ==');
    const approved = await call('POST', `/api/admin/reviews/${reviews.risky}/audit`,
      { action: 'APPROVE' }, opToken, 'admin');
    check('人工通过成功且来源变为 MANUAL',
      approved.status === 200 && approved.body?.data?.auditStatus === 'APPROVED'
        && approved.body?.data?.auditSource === 'MANUAL', approved.body);
    check('回库：APPROVED + 来源 MANUAL + 原因清空 + 操作人落库',
      reviewRow(reviews.risky) === `APPROVED|MANUAL|-|${opUserId}|SET`, reviewRow(reviews.risky));
    const summaryAfterApprove = await publicSummary(spuId);
    check('公开汇总变为 2 条 / 4.5 分',
      summaryAfterApprove?.total === 2 && summaryAfterApprove?.avgRating === '4.5', summaryAfterApprove);
    check('商品评分回写为 4.5 / 2 条',
      await waitFor('rating=4.5/count=2', () => ratingIs(4.5, 2)), spuRating());

    console.log('\n== E. 追评机审 ==');
    const appendCreated = await submitReview(orderAppend, 5, `A15 评价机审冒烟-${stamp}-追评夹具，猫很爱吃`);
    reviews.append = appendCreated.body?.data?.reviews?.[0]?.id;
    check('第三张订单的干净评价为 APPROVED',
      appendCreated.body?.data?.reviews?.[0]?.auditStatus === 'APPROVED', appendCreated.body);
    if (!reviews.append) return 1;
    const summaryThree = await publicSummary(spuId);
    check('公开汇总回到 3 条 / 4.7 分',
      summaryThree?.total === 3 && summaryThree?.avgRating === '4.7', summaryThree);
    const cleanAppend = await call('POST', `/api/reviews/${reviews.append}/append`,
      { content: '用了两周，依然爱吃' }, buyerToken);
    check('干净追评成功且不改变审核态/来源',
      cleanAppend.status === 200 && cleanAppend.body?.data?.auditStatus === 'APPROVED'
        && reviewRow(reviews.append) === 'APPROVED|AUTO|-|-|SET', cleanAppend.body);
    // 再用第二张订单的干净评价验证「命中导流的追评」：它已人工通过、尚未追评
    const riskyAppend = await call('POST', `/api/reviews/${reviews.risky}/append`,
      { content: appendRiskyText }, buyerToken);
    check('命中导流的追评提交成功但整条评价转 PENDING',
      riskyAppend.status === 200 && riskyAppend.body?.data?.auditStatus === 'PENDING', riskyAppend.body);
    const afterAppendRow = reviewRow(reviews.risky);
    check('回库：PENDING + 来源 AUTO + 机审命中原因 + 无人工操作人',
      afterAppendRow?.startsWith('PENDING|AUTO|机审命中') && afterAppendRow.endsWith('|-|SET'), afterAppendRow);
    const summaryAfterAppend = await publicSummary(spuId);
    check('公开汇总反向回到 2 条 / 5.0 分',
      summaryAfterAppend?.total === 2 && summaryAfterAppend?.avgRating === '5.0', summaryAfterAppend);
    check('商品评分反向重算为 5.0 / 2 条',
      await waitFor('rating=5/count=2', () => ratingIs(5, 2)), spuRating());

    console.log('\n== F. 人工下架与禁止追评 ==');
    const rejected = await call('POST', `/api/admin/reviews/${reviews.risky}/audit`,
      { action: 'REJECT', reason: '追评含微信号与手机号导流' }, opToken, 'admin');
    check('人工下架成功', rejected.status === 200 && rejected.body?.data?.auditStatus === 'REJECTED', rejected.body);
    check('回库：REJECTED + 来源 MANUAL + 原因与操作人落库',
      reviewRow(reviews.risky).startsWith(`REJECTED|MANUAL|追评含微信号与手机号导流|${opUserId}|`), reviewRow(reviews.risky));
    const mine = await myReviews(buyerToken);
    const mineRisky = (mine?.items ?? []).find((x) => String(x.id) === String(reviews.risky));
    check('买家「我的评价」带审核态 REJECTED 且不可追评',
      mineRisky?.auditStatus === 'REJECTED' && mineRisky?.appendable === false, mineRisky);
    const appendRejected = await call('POST', `/api/reviews/${reviews.risky}/append`,
      { content: '再补一句' }, buyerToken);
    check('已下架评价再追评 → 422', appendRejected.status === 422, appendRejected.body);
    const rejectedList = await adminList(opToken, { status: 'REJECTED', keyword: stamp });
    check('后台已下架筛选命中', (rejectedList.body?.data?.items ?? []).some((x) => String(x.id) === String(reviews.risky)));
    const auditCount = count(USER_DB, `SELECT COUNT(*) FROM t_audit_log WHERE action='REVIEW_AUDIT'
      AND target_type='REVIEW' AND actor_id=${opUserId} AND target_id IN (${reviews.risky})`);
    check('审计留痕：人工通过 + 下架两次 REVIEW_AUDIT', auditCount >= 2, auditCount);

    console.log('\n== G. 边界 ==');
    const badStatus = await adminList(opToken, { status: 'FOO' });
    check('非法状态筛选 → 400', badStatus.status === 400, badStatus.body);
    const unknown = await call('POST', '/api/admin/reviews/999999999999999999/audit',
      { action: 'APPROVE' }, opToken, 'admin');
    check('未知评价 → 404', unknown.status === 404, unknown.body);
    check('错误请求未改变已下架状态',
      reviewRow(reviews.risky).startsWith('REJECTED|MANUAL|追评含微信号与手机号导流|'), reviewRow(reviews.risky));
  } finally {
    console.log('\n== H. 收尾：零残留 ==');
    await cleanup();
    check('评价夹具已物理删除',
      count(FULFIL_DB, `SELECT COUNT(*) FROM t_review WHERE content LIKE 'A15 评价机审冒烟-${stamp}%'`) === 0);
    if (spuId) {
      check('商品 / SKU / 图片 / 审核历史零残留',
        count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu WHERE id=${spuId}`) === 0
        && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_sku WHERE spu_id=${spuId}`) === 0
        && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu_image WHERE spu_id=${spuId}`) === 0
        && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu_audit_log WHERE spu_id=${spuId}`) === 0);
    }
    check('订单 / 明细 / 支付 / 托管 / 预占台账零残留',
      orders.every(({ orderId }) =>
        count(TRADE_DB, `SELECT COUNT(*) FROM t_order WHERE id=${orderId}`) === 0
        && count(TRADE_DB, `SELECT COUNT(*) FROM t_order_item WHERE order_id=${orderId}`) === 0
        && count(TRADE_DB, `SELECT COUNT(*) FROM t_payment WHERE order_id=${orderId}`) === 0
        && count(TRADE_DB, `SELECT COUNT(*) FROM t_settlement WHERE order_id=${orderId}`) === 0
        && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_sku_stock_reservation WHERE order_id=${orderId}`) === 0));
    check('临时买家与地址零残留',
      buyerUserId ? count(USER_DB, `SELECT COUNT(*) FROM t_user WHERE id=${buyerUserId}`) === 0
        && count(USER_DB, `SELECT COUNT(*) FROM t_user_address WHERE user_id=${buyerUserId}`) === 0 : true);
  }

  console.log(`\n===== A-15 评价机审冒烟：${pass} 通过 / ${failures.length} 失败 =====`);
  if (failures.length) {
    console.log('失败项：');
    failures.forEach((f) => console.log('  - ' + f));
    return 1;
  }
  return 0;
}

const code = await main();
process.exit(code);

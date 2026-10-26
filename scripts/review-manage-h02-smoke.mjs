#!/usr/bin/env node
/**
 * H-02 · 运营后台「评价管理」接口冒烟（2026-10-26）。
 *
 * 守七组事实：
 *  A. 夹具：第三方商品 + 真实订单（下单 → 支付 → 发货 → 确认收货）→ 提交评价；
 *  B. 公开口径：评价创建后评价区 / 评分汇总可见，商品评分回写为 5.0 / 1 条；
 *  C. 后台列表：keyword / status / rating 三种筛选命中，非运营 403、未登录 401；
 *  D. 下架：缺原因 422 且零写库；带原因成功，回库 audit_status/audit_reason/audited_by/audited_at 正确；
 *  E. 下架副作用：公开评价区与评分汇总立即不含该条，商品评分反向重算为 0 / 0 条；
 *  F. 幂等与恢复：重复下架零写库（审核时间不变）；恢复写回 APPROVED、原因清空、评分恢复；
 *  G. 边界与收尾：未知评价 404、非法动作 400、非法筛选 400、审计留痕 REVIEW_AUDIT；夹具零残留。
 *
 * 前置：容器在跑；fulfilment 已部署 H-02；卖家 13600003333、运营 13684295254 可用。
 * 用法：NO_PROXY="*" node scripts/review-manage-h02-smoke.mjs
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

/** 轮询等待回库条件（评分回写是 AFTER_COMMIT 跨域 best-effort，固定 sleep 会假红） */
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

const skuOf = (detail) => (detail?.skus ?? [])[0];

/** 商品评分回写的当前值（DECIMAL 列会返回 5.00 / 0.00，必须按数值比，不能比字符串） */
const spuRating = () => {
  const row = one(PRODUCT_DB, `SELECT CONCAT(IFNULL(rating,0),'|',IFNULL(rating_count,0)) FROM t_spu WHERE id=${spuId}`);
  if (!row) return null;
  const [rating, count] = row.split('|').map(Number);
  return { rating, count, raw: row };
};
const ratingIs = (rating, count) => {
  const v = spuRating();
  return v && Math.abs(v.rating - rating) < 0.001 && v.count === count ? v.raw : null;
};

/**
 * 开机清扫：上一轮若中途崩溃（如收尾 SQL 报错），夹具会留在库里。
 * 按「商品标题前缀 / 买家昵称」定位本套件的专属夹具，先删订单链路再删商品，避免下一轮撞车。
 */
function cleanupStale() {
  const spuIds = sql(PRODUCT_DB, `SELECT id FROM t_spu WHERE title LIKE 'H02 评价管理冒烟%'`);
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
    console.log(`  [CLEAN] 历史 H02 商品夹具 ${spuIds.length} 件已清理`);
  }
  sql(FULFIL_DB, `DELETE FROM t_review WHERE content LIKE 'H02 评价下架冒烟-%'`);
  const buyers = sql(USER_DB, `SELECT id FROM t_user WHERE nickname='H02 评价买家'`);
  for (const uid of buyers) {
    sql(USER_DB, `DELETE FROM t_user_address WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${uid}`);
    sql(TRADE_DB, `DELETE FROM t_cart_item WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_user WHERE id=${uid}`);
  }
  if (buyers.length) {
    console.log(`  [CLEAN] 历史 H02 临时买家 ${buyers.length} 个已清理`);
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
const title = `H02 评价管理冒烟 ${stamp}`;
const content = `H02 评价下架冒烟-${stamp}-猫粮很香`;
const buyerPhone = '198' + String(Date.now()).slice(-8);

let sellerToken;
let opToken;
let buyerToken;
let buyerUserId;
let opUserId;
let spuId;
let skuId;
let orderNo;
let orderId;
let reviewId;

async function cleanup() {
  // 评价 / 订单 / 商品全部物理清理；审计台账按 append-only 纪律保留（本轮会先断言它存在）
  if (orderId) {
    sql(TRADE_DB, `DELETE FROM t_settlement WHERE order_id=${orderId}`);
    // 预占台账在商品库（B-03：不跨库），不是交易库
    sql(PRODUCT_DB, `DELETE FROM t_sku_stock_reservation WHERE order_id=${orderId}`);
    sql(TRADE_DB, `DELETE FROM t_stock_freeze WHERE order_id=${orderId}`);
    sql(TRADE_DB, `DELETE FROM t_payment WHERE order_id=${orderId}`);
    sql(TRADE_DB, `DELETE FROM t_order_status_log WHERE order_id=${orderId}`);
    sql(TRADE_DB, `DELETE FROM t_order_item WHERE order_id=${orderId}`);
    sql(TRADE_DB, `DELETE FROM t_order WHERE id=${orderId}`);
  } else if (orderNo) {
    sql(TRADE_DB, `DELETE FROM t_order WHERE order_no='${orderNo}'`);
  }
  sql(FULFIL_DB, `DELETE FROM t_review WHERE content LIKE 'H02 评价下架冒烟-${stamp}%'`);
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
      subtitle: 'H-02 评价管理冒烟',
      categoryId: '101',
      brandId: '1',
      mainImage: '/img/products/dog-food.svg',
      detail: 'H-02 评价管理冒烟商品',
      skus: [{ specName: 'H02-1kg', price: 2990, stock: 5, image: null }],
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

    console.log('\n== A. 真实订单（下单 → 支付 → 发货 → 确认收货） ==');
    const reg = await call('POST', '/api/auth/register',
      { phone: buyerPhone, password: BUYER_PWD, nickname: 'H02 评价买家' });
    check('临时买家注册成功', reg.status === 200 || reg.status === 201, reg.body);
    buyerToken = (await call('POST', '/api/auth/login/token',
      { phone: buyerPhone, password: BUYER_PWD })).body?.data?.accessToken;
    buyerUserId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}'`);
    check('临时买家登录成功', Boolean(buyerToken) && Boolean(buyerUserId));
    if (!buyerToken || !buyerUserId) return 1;
    const address = await call('POST', '/api/addresses', {
      receiverName: 'H02 评价买家', receiverPhone: buyerPhone,
      province: '广东省', city: '深圳市', district: '南山区',
      detail: '科技园路 20 号', isDefault: true,
    }, buyerToken);
    const addressId = address.body?.data?.id;
    check('临时买家新建收货地址', Boolean(addressId), address.body);
    if (!addressId) return 1;

    const placed = await call('POST', '/api/orders', {
      addressId, buySkuId: skuId, buyQuantity: 1, idempotentKey: 'h02-' + Date.now(),
    }, buyerToken);
    orderNo = (placed.body?.data?.orders ?? [])[0]?.orderNo;
    orderId = orderNo ? one(TRADE_DB, `SELECT id FROM t_order WHERE order_no='${orderNo}'`) : null;
    check('真实下单成功', placed.status === 200 && Boolean(orderNo) && Boolean(orderId), placed.body);
    if (!orderNo || !orderId) return 1;
    const paid = await call('POST', `/api/orders/${orderNo}/pay`,
      { channel: 'MOCK', idempotentKey: 'h02-pay-' + Date.now() }, buyerToken);
    check('支付成功（MOCK 即时）', paid.body?.data?.orderStatus === 'PAID', paid.body);
    const shipped = await call('POST', `/api/seller/orders/${orderNo}/ship`,
      { logisticsCompany: '顺丰速运', logisticsNo: 'SF' + Date.now() }, sellerToken);
    check('卖家发货成功', shipped.status === 200 && shipped.body?.data?.status === 'SHIPPED', shipped.body);
    const confirmed = await call('POST', `/api/orders/${orderNo}/confirm`, undefined, buyerToken);
    check('确认收货 → COMPLETED', confirmed.body?.data?.status === 'COMPLETED', confirmed.body);

    console.log('\n== B. 提交评价：公开口径与评分回写 ==');
    const createdReview = await call('POST', '/api/reviews', {
      orderNo,
      anonymous: false,
      items: [{ spuId, skuId, rating: 5, content }],
    }, buyerToken);
    reviewId = createdReview.body?.data?.reviews?.[0]?.id;
    check('评价提交成功', createdReview.status === 200 && Boolean(reviewId), createdReview.body);
    if (!reviewId) return 1;
    const reviewRow = () => one(FULFIL_DB,
      `SELECT CONCAT(audit_status,'|',IFNULL(audit_reason,'-'),'|',IFNULL(audited_by,'-'),'|',IFNULL(audited_at,'-')) FROM t_review WHERE id=${reviewId}`);
    check('回库：新建评价为 APPROVED 且无审核原因', reviewRow() === 'APPROVED|-|-|-', reviewRow());

    const summaryBefore = await publicSummary(spuId);
    const listBefore = await publicList(spuId);
    check('公开评分汇总：1 条 / 5.0 分',
      summaryBefore?.total === 1 && summaryBefore?.avgRating === '5.0', summaryBefore);
    check('公开评价区可查到该条', (listBefore?.items ?? []).some((x) => String(x.id) === String(reviewId)), listBefore);
    check('商品评分已回写为 5.0 / 1 条',
      await waitFor('rating=5/count=1', () => ratingIs(5, 1)), spuRating());

    console.log('\n== C. 后台列表与筛选 ==');
    const byKeyword = await adminList(opToken, { keyword: stamp });
    check('关键词命中评价正文', (byKeyword.body?.data?.items ?? []).some((x) => String(x.id) === String(reviewId))
      && byKeyword.body?.data?.items?.[0]?.auditStatus === 'APPROVED', byKeyword.body);
    const byStatus = await adminList(opToken, { status: 'APPROVED', keyword: stamp });
    check('按状态 APPROVED 命中', (byStatus.body?.data?.items ?? []).length >= 1, byStatus.body);
    const byRating = await adminList(opToken, { rating: 5, keyword: stamp });
    check('按 5 星命中', (byRating.body?.data?.items ?? []).some((x) => String(x.id) === String(reviewId)), byRating.body);
    const byWrongRating = await adminList(opToken, { rating: 1, keyword: stamp });
    check('按 1 星不命中', (byWrongRating.body?.data?.items ?? []).length === 0, byWrongRating.body);
    const sellerList = await adminList(sellerToken, { keyword: stamp });
    check('卖家调后台评价列表 → 403（运营准入）', sellerList.status === 403, sellerList.body);
    const anonList = await call('GET', '/api/admin/reviews', null, null, 'admin');
    check('未登录调后台评价列表 → 401', anonList.status === 401, anonList.body);

    console.log('\n== D. 下架：缺原因 422，带原因成功且回库 ==');
    const noReason = await call('POST', `/api/admin/reviews/${reviewId}/audit`,
      { action: 'REJECT', reason: '   ' }, opToken, 'admin');
    check('下架缺原因 → 422', noReason.status === 422, noReason.body);
    check('缺原因被拒后零写库', reviewRow() === 'APPROVED|-|-|-', reviewRow());

    const rejected = await call('POST', `/api/admin/reviews/${reviewId}/audit`,
      { action: 'REJECT', reason: '评价正文含联系方式，属站外导流' }, opToken, 'admin');
    check('下架成功', rejected.status === 200 && rejected.body?.data?.auditStatus === 'REJECTED', rejected.body);
    const rejectedRow = () => one(FULFIL_DB,
      `SELECT CONCAT(audit_status,'|',audit_reason,'|',audited_by,'|',DATE_FORMAT(audited_at,'%Y-%m-%d %H:%i:%s')) FROM t_review WHERE id=${reviewId}`);
    const rowD = rejectedRow();
    check('回库：REJECTED + 原因 + 操作人已落',
      rowD?.startsWith('REJECTED|评价正文含联系方式，属站外导流|' + opUserId + '|'), rowD);

    console.log('\n== E. 下架副作用：公开口径与商品评分 ==');
    const summaryAfter = await publicSummary(spuId);
    const listAfter = await publicList(spuId);
    check('公开评分汇总清零', summaryAfter?.total === 0, summaryAfter);
    check('公开评价区不再含该条', !(listAfter?.items ?? []).some((x) => String(x.id) === String(reviewId)), listAfter);
    check('商品评分反向重算为 0 / 0 条',
      await waitFor('rating=0/count=0', () => ratingIs(0, 0)), spuRating());
    const rejectedList = await adminList(opToken, { status: 'REJECTED', keyword: stamp });
    check('后台按 REJECTED 可筛到', (rejectedList.body?.data?.items ?? []).some((x) => String(x.id) === String(reviewId)), rejectedList.body);

    console.log('\n== F. 幂等与恢复 ==');
    const auditedAtD = one(FULFIL_DB, `SELECT DATE_FORMAT(audited_at,'%Y-%m-%d %H:%i:%s.%f') FROM t_review WHERE id=${reviewId}`);
    await sleep(1200);
    const repeat = await call('POST', `/api/admin/reviews/${reviewId}/audit`,
      { action: 'REJECT', reason: '重复点击' }, opToken, 'admin');
    const auditedAtRepeat = one(FULFIL_DB, `SELECT DATE_FORMAT(audited_at,'%Y-%m-%d %H:%i:%s.%f') FROM t_review WHERE id=${reviewId}`);
    check('重复下架幂等成功且审核时间不变',
      repeat.status === 200 && auditedAtRepeat === auditedAtD, { auditedAtD, auditedAtRepeat });

    const restored = await call('POST', `/api/admin/reviews/${reviewId}/audit`,
      { action: 'APPROVE' }, opToken, 'admin');
    check('恢复成功且原因清空',
      restored.status === 200 && restored.body?.data?.auditStatus === 'APPROVED'
        && restored.body?.data?.auditReason === null, restored.body);
    check('回库：APPROVED + 原因 NULL', one(FULFIL_DB,
      `SELECT CONCAT(audit_status,'|',IFNULL(audit_reason,'NULL')) FROM t_review WHERE id=${reviewId}`) === 'APPROVED|NULL');
    const summaryRestored = await publicSummary(spuId);
    check('恢复后公开汇总回到 1 条 / 5.0 分',
      summaryRestored?.total === 1 && summaryRestored?.avgRating === '5.0', summaryRestored);
    check('恢复后商品评分回到 5.0 / 1 条',
      await waitFor('rating=5/count=1', () => ratingIs(5, 1)), spuRating());
    const historyRows = await waitFor('审计留痕 REVIEW_AUDIT', () => {
      const n = count(USER_DB, `SELECT COUNT(*) FROM t_audit_log WHERE action='REVIEW_AUDIT'
        AND target_type='REVIEW' AND target_id=${reviewId} AND actor_id=${opUserId}`);
      return n > 0 ? n : null;
    });
    check('审计留痕：REVIEW_AUDIT / target=REVIEW / 操作人正确', Boolean(historyRows));
    const auditDetail = one(USER_DB, `SELECT CONCAT(outcome,'|',target_type,'|',actor_phone) FROM t_audit_log
      WHERE action='REVIEW_AUDIT' AND target_id=${reviewId} ORDER BY id ASC LIMIT 1`);
    check('审计详情：SUCCESS + 目标类型 REVIEW + 操作人手机号', auditDetail === `SUCCESS|REVIEW|${OPERATOR}`, auditDetail);

    console.log('\n== G. 边界 ==');
    const unknown = await call('POST', '/api/admin/reviews/999999999999999999/audit',
      { action: 'REJECT', reason: 'x' }, opToken, 'admin');
    check('未知评价 → 404', unknown.status === 404, unknown.body);
    const badAction = await call('POST', `/api/admin/reviews/${reviewId}/audit`,
      { action: 'DELETE' }, opToken, 'admin');
    check('非法动作 → 400', badAction.status === 400, badAction.body);
    const badStatus = await adminList(opToken, { status: 'FOO' });
    check('非法状态筛选 → 400', badStatus.status === 400, badStatus.body);
    const badRating = await adminList(opToken, { rating: 6 });
    check('非法星级筛选 → 400', badRating.status === 400, badRating.body);
    const longKeyword = await adminList(opToken, { keyword: 'x'.repeat(101) });
    check('超长关键词 → 400', longKeyword.status === 400, longKeyword.body);
    check('以上失败请求均未改变审核态',
      one(FULFIL_DB, `SELECT CONCAT(audit_status,'|',IFNULL(audit_reason,'NULL')) FROM t_review WHERE id=${reviewId}`) === 'APPROVED|NULL');
  } finally {
    console.log('\n== H. 收尾：零残留 ==');
    await cleanup();
    check('评价夹具已物理删除', count(FULFIL_DB, `SELECT COUNT(*) FROM t_review WHERE content LIKE 'H02 评价下架冒烟-${stamp}%'`) === 0);
    if (spuId) {
      check('商品 / SKU / 图片 / 审核历史零残留',
        count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu WHERE id=${spuId}`) === 0
        && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_sku WHERE spu_id=${spuId}`) === 0
        && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu_image WHERE spu_id=${spuId}`) === 0
        && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu_audit_log WHERE spu_id=${spuId}`) === 0);
    }
    if (orderId) {
      check('订单 / 明细 / 支付 / 托管 / 预占台账零残留',
        count(TRADE_DB, `SELECT COUNT(*) FROM t_order WHERE id=${orderId}`) === 0
        && count(TRADE_DB, `SELECT COUNT(*) FROM t_order_item WHERE order_id=${orderId}`) === 0
        && count(TRADE_DB, `SELECT COUNT(*) FROM t_payment WHERE order_id=${orderId}`) === 0
        && count(TRADE_DB, `SELECT COUNT(*) FROM t_settlement WHERE order_id=${orderId}`) === 0
        && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_sku_stock_reservation WHERE order_id=${orderId}`) === 0);
    }
    check('临时买家与地址零残留',
      count(USER_DB, `SELECT COUNT(*) FROM t_user WHERE phone='${buyerPhone}'`) === 0
      && count(USER_DB, `SELECT COUNT(*) FROM t_user_address WHERE user_id=${buyerUserId}`) === 0);
  }

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) {
    console.log('失败项：\n - ' + failures.join('\n - '));
    return 1;
  }
  return 0;
}

process.exitCode = await main();

#!/usr/bin/env node
/**
 * A-16 · 商家回复评价接口冒烟（2026-10-26）。
 *
 * 守七组事实：
 *  A. 夹具：第三方商品 + 三张真实订单（下单 → 支付 → 发货 → 确认收货），三条评价；
 *  B. 商家列表：只出现 APPROVED、按 replied 分流、字段无 userId / avatar；
 *  C. 匿名评价：列表昵称「匿名用户」，响应 JSON 不含买家 userId，回复后仍不泄露；
 *  D. 回复：一次成功，内容 / 时间 / 操作人落库；公开评价区与买家「我的评价」都能看到；
 *  E. 唯一性：重复回复 409 且不覆盖；待审 / 已下架评价回复 422；空白 / 超长 400；
 *  F. 权限：非本店卖家看不到/回不了别家评价（租户隔离 404 + 服务层 403 双层）、
 *    普通买家 403、未登录 401；回复内容过机审（命中手机号 422 且原因脱敏）；
 *  G. 审计留痕、参数边界（未知 id 404）、夹具零残留。
 *
 * 前置：容器在跑；fulfilment 已部署 A-16；卖家 13600003333、运营 13684295254 可用。
 * 用法：NO_PROXY="*" node scripts/review-seller-reply-a16-smoke.mjs
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
    console.log('  [FAIL] ' + name + (detail === undefined ? '' : '  -> ' + JSON.stringify(detail).slice(0, 700)));
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

const publicList = async (spuId) =>
  (await call('GET', `/api/reviews/product/${spuId}?page=1&pageSize=50`)).body?.data;
const myReviews = async (token) =>
  (await call('GET', '/api/reviews/mine?page=1&pageSize=50', null, token)).body?.data;
const sellerList = (token, query) =>
  call('GET', '/api/reviews/seller?' + new URLSearchParams(
    Object.entries(query).filter(([, v]) => v !== undefined)), null, token, 'merchant');

const detailOf = async (token, spuId) =>
  (await call('GET', `/api/seller/products/${spuId}`, null, token)).body?.data;
const skuOf = (detail) => (detail?.skus ?? [])[0];

/** 评价回复台账快照：回复内容 | 回复时间是否存在 | 操作人 */
const replyRow = (id) => one(FULFIL_DB,
  `SELECT CONCAT(IFNULL(reply_content,'NULL'),'|',IF(reply_at IS NULL,'-','SET'),'|',IFNULL(replied_by,'-'))
   FROM t_review WHERE id=${id}`);

/** 开机清扫：上一轮中途崩溃时按标题前缀 / 内容前缀定位本套件夹具先清掉 */
function cleanupStale() {
  const spuIds = sql(PRODUCT_DB, `SELECT id FROM t_spu WHERE title LIKE 'A16 商家回复冒烟%'`);
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
    console.log(`  [CLEAN] 历史 A16 商品夹具 ${spuIds.length} 件已清理`);
  }
  sql(FULFIL_DB, `DELETE FROM t_review WHERE content LIKE 'A16 商家回复冒烟-%'`);
  const buyers = sql(USER_DB, `SELECT id FROM t_user WHERE nickname='A16 商家回复买家'`);
  for (const uid of buyers) {
    sql(USER_DB, `DELETE FROM t_user_address WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${uid}`);
    sql(TRADE_DB, `DELETE FROM t_cart_item WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_user WHERE id=${uid}`);
  }
  if (buyers.length) {
    console.log(`  [CLEAN] 历史 A16 临时买家 ${buyers.length} 个已清理`);
  }
  const otherSellers = sql(USER_DB, `SELECT id FROM t_user WHERE nickname='A16 非本店卖家'`);
  for (const uid of otherSellers) {
    sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_user WHERE id=${uid}`);
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
const title = `A16 商家回复冒烟 ${stamp}`;
const buyerPhone = '199' + String(Date.now()).slice(-8);
const otherSellerPhone = '198' + String(Date.now()).slice(-8);
const reviewText = {
  normal: `A16 商家回复冒烟-${stamp}-首次评价，猫粮很香`,
  anonymous: `A16 商家回复冒烟-${stamp}-匿名评价，包装完好`,
  risky: `A16 商家回复冒烟-${stamp}-同款加微信 13800138000`,
};
const replyText = {
  normal: `A16 商家回复冒烟-${stamp}-感谢反馈，已为您安排补发`,
  anonymous: `A16 商家回复冒烟-${stamp}-感谢您对包装的认可`,
  risky: '加微信 13900139000 给您补偿',
};

let sellerToken;
let opToken;
let buyerToken;
let buyerUserId;
let otherSellerToken;
let otherSellerUserId;
let sellerUserId;
let spuId;
let skuId;
let addressId;
const orders = [];
const reviews = {};

async function cleanup() {
  for (const { orderId } of orders) {
    sql(TRADE_DB, `DELETE FROM t_settlement WHERE order_id=${orderId}`);
    sql(PRODUCT_DB, `DELETE FROM t_sku_stock_reservation WHERE order_id=${orderId}`);
    sql(TRADE_DB, `DELETE FROM t_stock_freeze WHERE order_id=${orderId}`);
    sql(TRADE_DB, `DELETE FROM t_payment WHERE order_id=${orderId}`);
    sql(TRADE_DB, `DELETE FROM t_order_status_log WHERE order_id=${orderId}`);
    sql(TRADE_DB, `DELETE FROM t_order_item WHERE order_id=${orderId}`);
    sql(TRADE_DB, `DELETE FROM t_order WHERE id=${orderId}`);
  }
  sql(FULFIL_DB, `DELETE FROM t_review WHERE content LIKE 'A16 商家回复冒烟-${stamp}%'`);
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
  if (otherSellerUserId) {
    sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${otherSellerUserId}`);
    sql(USER_DB, `DELETE FROM t_user WHERE id=${otherSellerUserId}`);
  }
}

/** 建一张已完成订单并返回订单号 */
async function placeCompletedOrder(tag) {
  const placed = await call('POST', '/api/orders', {
    addressId, buySkuId: skuId, buyQuantity: 1, idempotentKey: `a16-${tag}-${Date.now()}`,
  }, buyerToken);
  const orderNo = (placed.body?.data?.orders ?? [])[0]?.orderNo;
  if (!orderNo) throw new Error(`下单失败（${tag}）：${JSON.stringify(placed.body)}`);
  const orderId = one(TRADE_DB, `SELECT id FROM t_order WHERE order_no='${orderNo}'`);
  orders.push({ tag, orderNo, orderId });
  const paid = await call('POST', `/api/orders/${orderNo}/pay`,
    { channel: 'MOCK', idempotentKey: `a16-pay-${tag}-${Date.now()}` }, buyerToken);
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

async function submitReview(orderNo, rating, content, anonymous) {
  return call('POST', '/api/reviews', {
    orderNo, anonymous, items: [{ spuId, skuId, rating, content }],
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
    sellerUserId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${SELLER}'`);

    const created = await call('POST', '/api/seller/products', {
      title,
      subtitle: 'A-16 商家回复冒烟',
      categoryId: '101',
      brandId: '1',
      mainImage: '/img/products/dog-food.svg',
      detail: 'A-16 商家回复冒烟商品',
      skus: [{ specName: 'A16-1kg', price: 2990, stock: 10, image: null }],
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
      { phone: buyerPhone, password: BUYER_PWD, nickname: 'A16 商家回复买家' });
    check('临时买家注册成功', reg.status === 200 || reg.status === 201, reg.body);
    buyerToken = await tokenOf(buyerPhone, undefined, BUYER_PWD);
    buyerUserId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}'`);
    check('临时买家登录成功', Boolean(buyerToken) && Boolean(buyerUserId));
    if (!buyerToken || !buyerUserId) return 1;
    const address = await call('POST', '/api/addresses', {
      receiverName: 'A16 商家回复买家', receiverPhone: buyerPhone,
      province: '广东省', city: '深圳市', district: '南山区',
      detail: '科技园路 22 号', isDefault: true,
    }, buyerToken);
    addressId = address.body?.data?.id;
    check('临时买家新建收货地址', Boolean(addressId), address.body);
    if (!addressId) return 1;

    const orderNormal = await placeCompletedOrder('normal');
    const orderAnon = await placeCompletedOrder('anon');
    const orderRisky = await placeCompletedOrder('risky');

    console.log('\n== B. 提交三条评价（正常 / 匿名 / 机审转人工）==');
    const normalCreated = await submitReview(orderNormal, 5, reviewText.normal, false);
    reviews.normal = normalCreated.body?.data?.reviews?.[0]?.id;
    check('正常评价提交成功且 APPROVED',
      normalCreated.status === 200 && normalCreated.body?.data?.reviews?.[0]?.auditStatus === 'APPROVED',
      normalCreated.body);
    const anonCreated = await submitReview(orderAnon, 4, reviewText.anonymous, true);
    reviews.anonymous = anonCreated.body?.data?.reviews?.[0]?.id;
    check('匿名评价提交成功且 APPROVED',
      anonCreated.status === 200 && anonCreated.body?.data?.reviews?.[0]?.auditStatus === 'APPROVED',
      anonCreated.body);
    const riskyCreated = await submitReview(orderRisky, 2, reviewText.risky, false);
    reviews.risky = riskyCreated.body?.data?.reviews?.[0]?.id;
    check('命中手机号的评价转 PENDING',
      riskyCreated.status === 200 && riskyCreated.body?.data?.reviews?.[0]?.auditStatus === 'PENDING',
      riskyCreated.body);
    if (!reviews.normal || !reviews.anonymous || !reviews.risky) return 1;

    console.log('\n== C. 商家列表：只含 APPROVED、按 replied 分流、匿名脱敏 ==');
    const pendingList = await sellerList(sellerToken, { replied: false, keyword: stamp, page: 1, pageSize: 50 });
    const pendingItems = pendingList.body?.data?.items ?? [];
    check('待回复列表命中 2 条（正常 + 匿名）', pendingList.status === 200 && pendingList.body?.data?.total === 2,
      pendingList.body);
    check('待审评价不出现在商家列表',
      !pendingItems.some((x) => String(x.id) === String(reviews.risky)), pendingItems);
    check('商家端条目没有 userId / avatar / sellerId 字段',
      pendingItems.length > 0 && pendingItems.every((x) =>
        !('userId' in x) && !('avatar' in x) && !('sellerId' in x)), pendingItems[0]);
    const anonItem = pendingItems.find((x) => String(x.id) === String(reviews.anonymous));
    check('匿名评价昵称为「匿名用户」且标记 anonymous',
      anonItem?.nickname === '匿名用户' && anonItem?.anonymous === true, anonItem);
    check('匿名条目 JSON 不含买家 userId',
      anonItem && !JSON.stringify(anonItem).includes(String(buyerUserId)), anonItem);
    check('待回复条目 replyable 均为 true，且无回复内容',
      pendingItems.every((x) => x.replyable === true && x.replyContent === null), pendingItems);

    console.log('\n== D. 回复正常评价：落库 + 公开可见 ==');
    const replied = await call('POST', `/api/reviews/${reviews.normal}/reply`, { content: replyText.normal }, sellerToken, 'merchant');
    check('商家回复成功且 replyable 变 false',
      replied.status === 200 && replied.body?.data?.replyContent === replyText.normal
        && replied.body?.data?.replyable === false, replied.body);
    check('回库：回复内容 + 回复时间 + 操作人 = 卖家 userId',
      replyRow(reviews.normal) === `${replyText.normal}|SET|${sellerUserId}`, replyRow(reviews.normal));
    const publicAfterReply = await publicList(spuId);
    const publicNormal = (publicAfterReply?.items ?? []).find((x) => String(x.id) === String(reviews.normal));
    check('公开评价区能看到商家回复与回复时间',
      publicNormal?.replyContent === replyText.normal && Boolean(publicNormal?.replyAt), publicNormal);
    const mine = await myReviews(buyerToken);
    const mineNormal = (mine?.items ?? []).find((x) => String(x.id) === String(reviews.normal));
    check('买家「我的评价」能看到商家回复', mineNormal?.replyContent === replyText.normal, mineNormal);

    console.log('\n== E. 唯一性与状态门禁 ==');
    const duplicate = await call('POST', `/api/reviews/${reviews.normal}/reply`, { content: '再回一次' }, sellerToken, 'merchant');
    check('重复回复 → 409', duplicate.status === 409, duplicate.body);
    check('重复回复没有覆盖原内容', replyRow(reviews.normal) === `${replyText.normal}|SET|${sellerUserId}`, replyRow(reviews.normal));
    const pendingReply = await call('POST', `/api/reviews/${reviews.risky}/reply`, { content: '提前回复' }, sellerToken, 'merchant');
    check('待审评价回复 → 422（提示待审核）',
      pendingReply.status === 422 && String(pendingReply.body?.message ?? '').includes('待审核'), pendingReply.body);
    check('待审评价回复未写库', replyRow(reviews.risky) === 'NULL|-|-', replyRow(reviews.risky));

    console.log('\n== F. 输入校验（在尚未回复的匿名评价上验证）==');
    const blank = await call('POST', `/api/reviews/${reviews.anonymous}/reply`, { content: '   ' }, sellerToken, 'merchant');
    check('空白回复 → 400', blank.status === 400, blank.body);
    const tooLong = await call('POST', `/api/reviews/${reviews.anonymous}/reply`, { content: 'a'.repeat(501) }, sellerToken, 'merchant');
    check('超长回复（501 字）→ 400', tooLong.status === 400, tooLong.body);
    check('校验失败未写库', replyRow(reviews.anonymous) === 'NULL|-|-', replyRow(reviews.anonymous));

    console.log('\n== G. 匿名评价回复后仍不泄身份 ==');
    const anonReplied = await call('POST', `/api/reviews/${reviews.anonymous}/reply`, { content: replyText.anonymous }, sellerToken, 'merchant');
    check('匿名评价回复成功', anonReplied.status === 200 && anonReplied.body?.data?.replyContent === replyText.anonymous,
      anonReplied.body);
    check('回复响应仍为「匿名用户」且不含买家 userId',
      anonReplied.body?.data?.nickname === '匿名用户'
        && !JSON.stringify(anonReplied.body?.data).includes(String(buyerUserId)), anonReplied.body?.data);
    const publicAnon = (await publicList(spuId))?.items?.find((x) => String(x.id) === String(reviews.anonymous));
    check('公开评价区匿名昵称未被回复改写，且带商家回复',
      publicAnon?.nickname === '匿名用户' && publicAnon?.replyContent === replyText.anonymous, publicAnon);

    console.log('\n== H. 已回复筛选 + 机审 + 人工下架 ==');
    const repliedList = await sellerList(sellerToken, { replied: true, keyword: stamp, page: 1, pageSize: 50 });
    check('已回复筛选命中 2 条', repliedList.body?.data?.total === 2, repliedList.body);
    const unrepliedNow = await sellerList(sellerToken, { replied: false, keyword: stamp, page: 1, pageSize: 50 });
    check('待回复筛选已清零', unrepliedNow.body?.data?.total === 0, unrepliedNow.body);

    const approved = await call('POST', `/api/admin/reviews/${reviews.risky}/audit`,
      { action: 'APPROVE' }, opToken, 'admin');
    check('运营通过机审转人工的评价', approved.status === 200 && approved.body?.data?.auditStatus === 'APPROVED', approved.body);
    const riskyReply = await call('POST', `/api/reviews/${reviews.risky}/reply`, { content: replyText.risky }, sellerToken, 'merchant');
    check('回复命中手机号 → 422，原因已脱敏',
      riskyReply.status === 422 && String(riskyReply.body?.message ?? '').includes('139****9000')
        && !String(riskyReply.body?.message ?? '').includes('13900139000'), riskyReply.body);
    check('机审拦截后未写库', replyRow(reviews.risky) === 'NULL|-|-', replyRow(reviews.risky));
    const riskyReplied = await call('POST', `/api/reviews/${reviews.risky}/reply`, { content: '感谢反馈，我们会改进' }, sellerToken, 'merchant');
    check('改为干净内容后回复成功', riskyReplied.status === 200 && riskyReplied.body?.data?.replyContent === '感谢反馈，我们会改进',
      riskyReplied.body);

    const rejected = await call('POST', `/api/admin/reviews/${reviews.risky}/audit`,
      { action: 'REJECT', reason: 'A16 冒烟：人工下架' }, opToken, 'admin');
    check('运营下架该评价', rejected.status === 200 && rejected.body?.data?.auditStatus === 'REJECTED', rejected.body);
    const afterRejectList = await sellerList(sellerToken, { keyword: stamp, page: 1, pageSize: 50 });
    check('已下架评价不再出现在商家列表',
      !(afterRejectList.body?.data?.items ?? []).some((x) => String(x.id) === String(reviews.risky)), afterRejectList.body);
    const replyAfterReject = await call('POST', `/api/reviews/${reviews.risky}/reply`, { content: '下架后回复' }, sellerToken, 'merchant');
    check('已下架评价再回复 → 422（提示已下架）',
      replyAfterReject.status === 422 && String(replyAfterReject.body?.message ?? '').includes('已下架'), replyAfterReject.body);

    console.log('\n== I. 权限：非本店买家 / 普通买家 / 未登录 ==');
    const otherReg = await call('POST', '/api/auth/register',
      { phone: otherSellerPhone, password: BUYER_PWD, nickname: 'A16 非本店卖家' });
    check('第二个卖家账号注册成功', otherReg.status === 200 || otherReg.status === 201, otherReg.body);
    otherSellerUserId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${otherSellerPhone}'`);
    sql(USER_DB, `UPDATE t_user SET role='SELLER' WHERE id=${otherSellerUserId}`);
    otherSellerToken = await tokenOf(otherSellerPhone, 'merchant', BUYER_PWD);
    check('第二个卖家登录成功', Boolean(otherSellerToken));
    const otherList = await sellerList(otherSellerToken, { keyword: stamp, page: 1, pageSize: 50 });
    check('非本店卖家列表看不到别家评价（0 条）', otherList.body?.data?.total === 0, otherList.body);
    const otherReply = await call('POST', `/api/reviews/${reviews.normal}/reply`, { content: '别家回复' }, otherSellerToken, 'merchant');
    // E20 租户隔离先于服务层归属校验生效：别家店的评价在 selectById 阶段就被
    // `seller_id = 当前租户` 过滤掉，返回 404（不泄露「这条评价存在」）。
    // 服务层的 403 是第二层防线（无租户上下文时兜底，见 ReviewReplyTest）。
    check('非本店卖家回复 → 404（租户隔离：别家评价不可见）', otherReply.status === 404, otherReply.body);
    const buyerList = await sellerList(buyerToken, { page: 1, pageSize: 10 });
    check('普通买家访问商家列表 → 403', buyerList.status === 403, buyerList.body);
    const anonymousList = await call('GET', '/api/reviews/seller?page=1&pageSize=10');
    check('未登录访问商家列表 → 401', anonymousList.status === 401, anonymousList.body);

    console.log('\n== J. 审计与参数边界 ==');
    const auditCount = count(USER_DB, `SELECT COUNT(*) FROM t_audit_log WHERE action='REVIEW_REPLY'
      AND target_type='REVIEW' AND actor_id=${sellerUserId} AND target_id IN (${reviews.normal},${reviews.anonymous},${reviews.risky})`);
    check('审计留痕：三次成功回复各一条 REVIEW_REPLY', auditCount === 3, auditCount);
    const unknown = await call('POST', '/api/reviews/999999999999999999/reply', { content: '不存在' }, sellerToken, 'merchant');
    check('未知评价 → 404', unknown.status === 404, unknown.body);
    const badId = await call('POST', '/api/reviews/abc/reply', { content: '非法 id' }, sellerToken, 'merchant');
    check('非法评价 id → 404（不匹配路由）', badId.status === 404, badId.body);
  } finally {
    console.log('\n== K. 收尾：零残留 ==');
    await cleanup();
    check('评价夹具已物理删除',
      count(FULFIL_DB, `SELECT COUNT(*) FROM t_review WHERE content LIKE 'A16 商家回复冒烟-${stamp}%'`) === 0);
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
    check('临时买家 / 第二个卖家 / 地址零残留',
      buyerUserId ? count(USER_DB, `SELECT COUNT(*) FROM t_user WHERE id=${buyerUserId}`) === 0
        && count(USER_DB, `SELECT COUNT(*) FROM t_user_address WHERE user_id=${buyerUserId}`) === 0 : true,
      otherSellerUserId ? count(USER_DB, `SELECT COUNT(*) FROM t_user WHERE id=${otherSellerUserId}`) === 0 : true);
  }

  console.log(`\n===== A-16 商家回复评价冒烟：${pass} 通过 / ${failures.length} 失败 =====`);
  if (failures.length) {
    console.log('失败项：');
    failures.forEach((f) => console.log('  - ' + f));
    return 1;
  }
  return 0;
}

const code = await main();
process.exit(code);

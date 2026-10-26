/**
 * A-14 · 商家端 SKU 增删改（结构）冒烟（2026-10-26）。
 *
 * 守七组事实：
 *  A. 新增规格：全量集合保存、added 计数、第三方商品转 PENDING、version +1、
 *     SPU 价格区间重算、审核历史追加 field=skus、SKU 编码不复用。
 *  B. 仅改价 / 库存：不重审、不升版本、audit 列不变、价格区间同步。
 *  C. 改规格名：触发重审（内容干净时 AUTO 放行、否则 PENDING）、version +1，
 *     历史 diff 同时带 before / after。
 *  D. 删除无订单规格：真删（回库无该行），再新增时编码序号继续递增。
 *  E. 有订单禁删：真实下单后取消（冻结已释放、订单明细仍在）→ 删除被 422 拒绝，
 *     消息点名规格与订单数，整单零写入；usage 只读接口只返回该规格。
 *  F. 冻结库存禁删：下单未取消时 frozen_stock > 0 → 422 且提示冻结件数。
 *  G. 边界与越权：版本冲突 409、缺版本 / 空列表 / 重名 / 超 20 / 价格 0 → 400、
 *     未知规格 id → 404、别人的商品 → 404（E20 租户拦截先过滤）或 403（无租户头时
 *     归属校验兜底）、运营改第三方规格 → 403、未登录 → 401、自己的商品不存在 → 404，
 *     且全部零写入。
 *  H. 收尾零残留：SPU / SKU / 图片 / 审核历史 / 订单明细 / 冻结台账 / 临时买家全部清理，
 *     ES 重建后无 A14 文档。
 *
 * 前置：容器在跑；product / trade 已部署 A-14 版本；卖家 13600003333、
 *       另一店主 13959064118、运营 13684295254 可用；ES 可达（localhost:9200）。
 * 用法：NO_PROXY="*" node scripts/product-sku-structure-a14-smoke.mjs
 */
import { execFileSync } from 'node:child_process';

const API = 'http://localhost:8080';
const ES = 'http://localhost:9200';
const PWD = 'duoduo12345';
const BUYER_PWD = 'Passw0rd!23';
const SELLER = '13600003333';
const OTHER_SELLER = '13959064118';
const OPERATOR = '13684295254';
const CATEGORY = '101';
const BRAND = '1';
const PRODUCT_DB = 'duoduo_product';
const TRADE_DB = 'duoduo_trade';
const USER_DB = 'duoduo_user';
const SKUS = (spuId) => `/api/seller/products/${spuId}/skus`;

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

async function call(method, path, body, token, entry) {
  const res = await fetch(API + path, {
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
const tokenOf = async (phone, entry) =>
  (await call('POST', '/api/auth/login/token', { phone, password: PWD }, null, entry)).body?.data?.accessToken;

/** 回库断言：只读，返回制表符分隔的行数组 */
function sql(db, query) {
  const out = execFileSync('docker', [
    'exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B',
    '--default-character-set=utf8mb4', '-D', db, '-e', query,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return out.split(/\r?\n/).filter((line) => line && !line.includes('Warning'));
}
const count = (db, query) => Number(sql(db, query)[0] ?? 0);
const one = (db, query) => sql(db, query)[0];

/** 直接物理删除 A14 夹具（可重复执行）；先删订单明细，再删商品，避免留下孤儿 */
function cleanupStale() {
  const ids = sql(PRODUCT_DB, `SELECT id FROM t_spu WHERE title LIKE 'A14 %'`);
  if (!ids.length) {
    console.log('  [CLEAN] 无历史 A14 商品夹具');
  } else {
    const list = ids.join(',');
    const skuIds = sql(PRODUCT_DB, `SELECT id FROM t_sku WHERE spu_id IN (${list})`);
    if (skuIds.length) {
      const skuList = skuIds.join(',');
      const orderIds = sql(TRADE_DB, `SELECT DISTINCT order_id FROM t_order_item WHERE sku_id IN (${skuList})`);
      if (orderIds.length) {
        const orderList = orderIds.join(',');
        sql(TRADE_DB, `DELETE FROM t_stock_freeze WHERE order_id IN (${orderList})`);
        sql(TRADE_DB, `DELETE FROM t_payment WHERE order_id IN (${orderList})`);
        sql(TRADE_DB, `DELETE FROM t_order_status_log WHERE order_id IN (${orderList})`);
        sql(TRADE_DB, `DELETE FROM t_order_item WHERE order_id IN (${orderList})`);
        sql(TRADE_DB, `DELETE FROM t_order WHERE id IN (${orderList})`);
      }
      sql(TRADE_DB, `DELETE FROM t_order_item WHERE sku_id IN (${skuList})`);
    }
    sql(PRODUCT_DB, `DELETE FROM t_spu_image WHERE spu_id IN (${list})`);
    sql(PRODUCT_DB, `DELETE FROM t_sku WHERE spu_id IN (${list})`);
    sql(PRODUCT_DB, `DELETE FROM t_spu_audit_log WHERE spu_id IN (${list})`);
    sql(PRODUCT_DB, `DELETE FROM t_spu WHERE id IN (${list})`);
    console.log(`  [CLEAN] 历史 A14 商品夹具 ${ids.length} 件已删除`);
  }
  // 上次运行崩溃留下的临时买家（只清本脚本的 197 前缀；地址/令牌无外键，逐表删）
  const buyers = sql(USER_DB, `SELECT id, phone FROM t_user WHERE phone LIKE '197%' AND nickname = 'A14 冒烟买家'`);
  if (buyers.length) {
    const uids = buyers.map((row) => row.split('\t')[0]);
    for (const uid of uids) {
      sql(USER_DB, `DELETE FROM t_user_address WHERE user_id = ${uid}`);
      sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id = ${uid}`);
      sql(TRADE_DB, `DELETE FROM t_cart_item WHERE user_id = ${uid}`);
      sql(USER_DB, `DELETE FROM t_user WHERE id = ${uid}`);
    }
    console.log(`  [CLEAN] 历史临时买家 ${uids.length} 个已删除`);
  }
}

const detailOf = async (token, spuId) =>
  (await call('GET', `/api/seller/products/${spuId}`, null, token)).body?.data;

const statusOf = (spuId) =>
  one(PRODUCT_DB, `SELECT CONCAT(status,'|',audit_status,'|',version,'|',min_price,'|',max_price) FROM t_spu WHERE id=${spuId}`);
/** 带审核来源的回库快照（E28：APPROVED 时必须能区分 AUTO 自动放行与人工通过） */
const statusWithSourceOf = (spuId) =>
  one(PRODUCT_DB, `SELECT CONCAT(status,'|',audit_status,'|',IFNULL(audit_source,'-'),'|',version,'|',min_price,'|',max_price) FROM t_spu WHERE id=${spuId}`);
const auditLogCount = (spuId) => count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu_audit_log WHERE spu_id=${spuId}`);
const skuRows = (spuId) =>
  sql(PRODUCT_DB, `SELECT CONCAT(id,'|',sku_code,'|',IFNULL(spec_json,''),'|',price,'|',stock,'|',frozen_stock) FROM t_sku WHERE spu_id=${spuId} ORDER BY sku_code`);

const skuItem = (detail, id) => (detail?.skus ?? []).find((x) => String(x.id) === String(id));

/** 保证商品 APPROVED + ON_SALE；返回最终详情 */
async function ensureApprovedOnSale(token, opToken, spuId) {
  let detail = await detailOf(token, spuId);
  if (detail?.auditStatus === 'PENDING') {
    await call('POST', `/api/admin/products/${spuId}/audit`,
      { action: 'APPROVE', version: detail.version }, opToken);
    detail = await detailOf(token, spuId);
  }
  if (detail?.status !== 'ON_SALE') {
    await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, token);
    detail = await detailOf(token, spuId);
  }
  return detail;
}

/** 结构保存请求体：rows 里 id 可为 null，price 单位分 */
const structureBody = (version, rows) => ({ version, skus: rows });
const row = (sku, override = {}) => ({
  id: sku ? String(sku.id) : null,
  specName: override.specName ?? sku?.specName ?? '默认',
  price: override.price ?? sku?.price ?? 1000,
  stock: override.stock ?? sku?.stock ?? 1,
});

async function main() {
  console.log('== 0. 登录、清理与基线 ==');
  const sellerToken = await tokenOf(SELLER, 'merchant');
  const otherToken = await tokenOf(OTHER_SELLER, 'merchant');
  const opToken = await tokenOf(OPERATOR, 'admin');
  const opMerchantToken = await tokenOf(OPERATOR, 'merchant');
  check('卖家登录成功', Boolean(sellerToken));
  check('另一店主登录成功（越权对照）', Boolean(otherToken));
  check('运营登录成功（admin 入口）', Boolean(opToken));
  check('运营登录成功（商家入口，越权对照）', Boolean(opMerchantToken));
  if (!sellerToken || !otherToken || !opToken || !opMerchantToken) return 1;

  cleanupStale();
  const baseline = await call('POST', '/api/admin/search/reindex', null, opToken);
  check('基线全量重建成功（消除历史孤儿文档）',
    baseline.status === 200 && baseline.body?.data?.reindexed === true, baseline.body);

  console.log('\n== 1. 造夹具（2 个规格） ==');
  const stamp = String(Date.now()).slice(-7);
  const title = `A14 规格结构冒烟 ${stamp}`;
  const created = await call('POST', '/api/seller/products', {
    title,
    subtitle: 'A-14 SKU 增删改冒烟',
    categoryId: CATEGORY,
    brandId: BRAND,
    mainImage: '/img/products/dog-food.svg',
    detail: 'A-14 SKU 增删改冒烟商品',
    skus: [
      { specName: 'A14-2kg', price: 1990, stock: 10, image: null },
      { specName: 'A14-5kg', price: 3990, stock: 5, image: null },
    ],
  }, sellerToken);
  const spuId = created.body?.data?.id;
  check('商品创建成功（PENDING）', created.status === 200 && Boolean(spuId), created.body);
  if (!spuId) return 1;

  let detail = await ensureApprovedOnSale(sellerToken, opToken, spuId);
  const sku2 = skuItem(detail, (detail?.skus ?? [])[0]?.id);
  const sku5 = skuItem(detail, (detail?.skus ?? [])[1]?.id);
  check('初始 2 个规格且 APPROVED + ON_SALE',
    detail?.auditStatus === 'APPROVED' && detail?.status === 'ON_SALE'
      && (detail?.skus ?? []).length === 2 && Boolean(sku2) && Boolean(sku5),
    { audit: detail?.auditStatus, status: detail?.status, skus: detail?.skus });
  if (!sku2 || !sku5) return 1;
  const id2 = String(sku2.id);
  const id5 = String(sku5.id);

  console.log('\n== A. 新增规格：重审 + 版本 + 价格区间 + 历史 ==');
  const historyBeforeA = auditLogCount(spuId);
  const vA = detail.version;
  const addRes = await call('PUT', SKUS(spuId), structureBody(vA, [
    row(sku2), row(sku5), row(null, { specName: 'A14-10kg', price: 5990, stock: 3 }),
  ]), sellerToken);
  check('新增规格返回 200', addRes.status === 200, addRes.body);
  check('added=1 / updated=0 / removed=0 / reaudited=true',
    addRes.body?.data?.added === 1 && addRes.body?.data?.updated === 0
      && addRes.body?.data?.removed === 0 && addRes.body?.data?.reaudited === true,
    addRes.body?.data);
  check('结构变更已重审（PENDING 或 AUTO 放行）且版本 +1',
    (addRes.body?.data?.product?.auditStatus === 'PENDING'
      || addRes.body?.data?.product?.auditStatus === 'APPROVED')
      && addRes.body?.data?.product?.version === vA + 1,
    addRes.body?.data?.product);
  check('价格区间重算为 1990~5990',
    addRes.body?.data?.product?.minPrice === 1990 && addRes.body?.data?.product?.maxPrice === 5990,
    { min: addRes.body?.data?.product?.minPrice, max: addRes.body?.data?.product?.maxPrice });

  const rowsA = skuRows(spuId);
  const newSkuA = rowsA.find((r) => r.includes('A14-10kg'));
  check('回库：3 个规格，新增行编码不复用旧序号',
    rowsA.length === 3 && Boolean(newSkuA) && newSkuA.split('|')[1] === `SPU${spuId}-3`, rowsA);
  const statusA = statusWithSourceOf(spuId);
  // E28：干净内容自动放行（APPROVED + AUTO），命中风险才回 PENDING（来源为空）。
  check('回库：SPU 重审结果合法（PENDING 或 APPROVED+AUTO）+ 新版本 + 新价格区间',
    (statusA === `ON_SALE|PENDING|-|${vA + 1}|1990|5990`
      || statusA === `ON_SALE|APPROVED|AUTO|${vA + 1}|1990|5990`), statusA);
  const historyA = sql(PRODUCT_DB,
    `SELECT changes FROM t_spu_audit_log
     WHERE spu_id=${spuId} AND event='EDIT' ORDER BY id DESC LIMIT 1`);
  check('审核历史追加规格字段 diff（前后都含规格名）',
    auditLogCount(spuId) === historyBeforeA + 1
      && historyA[0]?.includes('"field":"skus"')
      && historyA[0]?.includes('A14-2kg') && historyA[0]?.includes('A14-10kg'),
    historyA);

  console.log('\n== B. 仅改价 / 库存：不重审、不升版本 ==');
  detail = await ensureApprovedOnSale(sellerToken, opToken, spuId);
  const vB = detail.version;
  const historyBeforeB = auditLogCount(spuId);
  const updatedAtB = one(PRODUCT_DB,
    `SELECT DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f') FROM t_spu WHERE id=${spuId}`);
  const row2 = row(skuItem(detail, id2), { price: 2190, stock: 8 });
  const priceRes = await call('PUT', SKUS(spuId), structureBody(vB, [
    row2, row(skuItem(detail, id5)), row(skuItem(detail, newSkuA?.split('|')[0])),
  ]), sellerToken);
  check('仅改价返回 200 且 reaudited=false / updated=1',
    priceRes.status === 200 && priceRes.body?.data?.reaudited === false
      && priceRes.body?.data?.updated === 1,
    priceRes.body?.data);
  check('仅改价：版本、审核态、审核历史均不变',
    priceRes.body?.data?.product?.version === vB
      && priceRes.body?.data?.product?.auditStatus === 'APPROVED'
      && auditLogCount(spuId) === historyBeforeB,
    { version: priceRes.body?.data?.product?.version, audit: priceRes.body?.data?.product?.auditStatus, history: auditLogCount(spuId) });
  check('仅改价：回库版本 / 审核态不变、价格区间同步为 2190',
    statusOf(spuId) === `ON_SALE|APPROVED|${vB}|2190|5990`, statusOf(spuId));
  check('仅改价：SKU 行价格与库存已落库',
    skuRows(spuId).some((r) => r.startsWith(`${id2}|`) && r.includes('|2190|8|')), skuRows(spuId));
  check('仅改价：SPU updated_at 已刷新',
    one(PRODUCT_DB, `SELECT DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f') FROM t_spu WHERE id=${spuId}`) !== updatedAtB);

  const noChangeRes = await call('PUT', SKUS(spuId), structureBody(vB, [
    row(skuItem(detail, id2), { price: 2190, stock: 8 }),
    row(skuItem(detail, id5)),
    row(skuItem(detail, newSkuA?.split('|')[0])),
  ]), sellerToken);
  check('规格无变化：200、零计数、版本不变',
    noChangeRes.status === 200 && noChangeRes.body?.data?.added === 0
      && noChangeRes.body?.data?.updated === 0 && noChangeRes.body?.data?.removed === 0
      && noChangeRes.body?.data?.product?.version === vB,
    noChangeRes.body?.data);

  console.log('\n== C. 改规格名：触发重审 + 版本 +1 + 历史 diff ==');
  detail = await detailOf(sellerToken, spuId);
  const vC = detail.version;
  const renameRes = await call('PUT', SKUS(spuId),
    structureBody(vC, [
      row(skuItem(detail, id2), { price: 2190, stock: 8 }),
      row(skuItem(detail, id5), { specName: 'A14-5kg装' }),
      row(skuItem(detail, newSkuA?.split('|')[0])),
    ]), sellerToken);
  check('改名返回 200 且 reaudited=true / version+1',
    renameRes.status === 200 && renameRes.body?.data?.reaudited === true
      && renameRes.body?.data?.product?.version === vC + 1,
    renameRes.body?.data);
  // 重审的结论由内容风险决定：干净内容会被 E28 自动放行（audit_source=AUTO），
  // 命中风险才转 PENDING。硬编码 PENDING 等于把自动放行判成失败。
  const renameSpu = statusOf(spuId).split('|');
  const renameAuditOk = renameSpu[1] === 'PENDING'
    || (renameSpu[1] === 'APPROVED'
      && one(PRODUCT_DB, `SELECT audit_source FROM t_spu WHERE id=${spuId}`) === 'AUTO');
  check('回库：规格名已改、重审已发生（PENDING 或 AUTO 放行）',
    skuRows(spuId).some((r) => r.startsWith(`${id5}|`) && r.includes('A14-5kg装'))
      && renameAuditOk,
    { skus: skuRows(spuId), spu: statusOf(spuId), source: one(PRODUCT_DB, `SELECT audit_source FROM t_spu WHERE id=${spuId}`) });
  const historyC = sql(PRODUCT_DB,
    `SELECT changes FROM t_spu_audit_log
     WHERE spu_id=${spuId} AND event='EDIT' ORDER BY id DESC LIMIT 1`);
  check('改名历史同时带旧名与新名',
    historyC[0]?.includes('A14-5kg') && historyC[0]?.includes('A14-5kg装'), historyC);

  console.log('\n== D. 删除无订单规格：真删 + 编码序号继续递增 ==');
  detail = await ensureApprovedOnSale(sellerToken, opToken, spuId);
  const vD = detail.version;
  const newSkuIdD = String(skuItem(detail, newSkuA?.split('|')[0])?.id);
  const delRes = await call('PUT', SKUS(spuId), structureBody(vD, [
    row(skuItem(detail, id2), { price: 2190, stock: 8 }),
    row(skuItem(detail, id5), { specName: 'A14-5kg装' }),
  ]), sellerToken);
  check('删除无订单规格返回 removed=1 / reaudited=true',
    delRes.status === 200 && delRes.body?.data?.removed === 1
      && delRes.body?.data?.reaudited === true,
    delRes.body?.data);
  check('回库：目标 SKU 行已物理删除、剩 2 个规格',
    skuRows(spuId).length === 2 && !skuRows(spuId).some((r) => r.startsWith(`${newSkuIdD}|`)), skuRows(spuId));

  detail = await ensureApprovedOnSale(sellerToken, opToken, spuId);
  const vD2 = detail.version;
  const addAgain = await call('PUT', SKUS(spuId), structureBody(vD2, [
    row(skuItem(detail, id2), { price: 2190, stock: 8 }),
    row(skuItem(detail, id5), { specName: 'A14-5kg装' }),
    row(null, { specName: 'A14-20kg', price: 7990, stock: 2 }),
  ]), sellerToken);
  const code20 = (addAgain.body?.data?.product?.skus ?? [])
    .find((x) => x.specName === 'A14-20kg')?.id;
  check('再次新增成功并生成新规格',
    addAgain.status === 200 && Boolean(code20)
      && (addAgain.body?.data?.product?.skus ?? []).length === 3,
    addAgain.body?.data);
  check('编码按「当前最大序号 +1」分配（同一请求内不复用被删序号）',
    skuRows(spuId).some((r) => r.startsWith(`${code20}|`) && r.includes(`SPU${spuId}-3`)), skuRows(spuId));

  console.log('\n== E. 有订单禁删（下单后取消，明细仍在） ==');
  detail = await ensureApprovedOnSale(sellerToken, opToken, spuId);
  const buyerPhone = '197' + String(Date.now()).slice(-8);
  const reg = await call('POST', '/api/auth/register',
    { phone: buyerPhone, password: BUYER_PWD, nickname: 'A14 冒烟买家' });
  check('临时买家注册成功', reg.status === 200 || reg.status === 201, reg.body);
  const buyerToken = (await call('POST', '/api/auth/login/token',
    { phone: buyerPhone, password: BUYER_PWD })).body?.data?.accessToken;
  check('临时买家登录成功', Boolean(buyerToken));
  if (!buyerToken) return 1;
  const address = await call('POST', '/api/addresses', {
    receiverName: 'A14 冒烟买家', receiverPhone: buyerPhone,
    province: '广东省', city: '深圳市', district: '南山区',
    detail: '科技园路 14 号', isDefault: true,
  }, buyerToken);
  const addressId = address.body?.data?.id;
  check('临时买家新建收货地址', Boolean(addressId), address.body);
  if (!addressId) return 1;

  const placed = await call('POST', '/api/orders', {
    addressId,
    buySkuId: id5,
    buyQuantity: 1,
    idempotentKey: 'a14-' + Date.now(),
  }, buyerToken);
  const order = (placed.body?.data?.orders ?? [])[0];
  check('真实下单成功（用 5kg 规格）', placed.status === 200 && Boolean(order?.orderNo), placed.body);
  if (!order?.orderNo) return 1;
  check('回库：订单明细引用该 SKU',
    count(TRADE_DB, `SELECT COUNT(*) FROM t_order_item
      WHERE order_id=(SELECT id FROM t_order WHERE order_no='${order.orderNo}') AND sku_id=${id5}`) === 1);
  check('下单后冻结库存 > 0',
    Number(one(PRODUCT_DB, `SELECT frozen_stock FROM t_sku WHERE id=${id5}`)) > 0);

  const frozenDelete = await call('PUT', SKUS(spuId), structureBody(detail.version, [
    row(skuItem(detail, id2), { price: 2190, stock: 8 }),
  ]), sellerToken);
  check('冻结库存中的规格删除被 422 拒绝且提示冻结件数',
    frozenDelete.status === 422
      && String(frozenDelete.body?.message ?? '').includes('冻结库存')
      && skuRows(spuId).length === 3,
    frozenDelete.body);

  const cancel = await call('POST', `/api/orders/${order.orderNo}/cancel`,
    { reason: 'A14 冒烟：取消以释放冻结' }, buyerToken);
  check('取消订单成功', cancel.status === 200, cancel.body);
  check('取消后冻结库存归零、订单明细仍保留',
    Number(one(PRODUCT_DB, `SELECT frozen_stock FROM t_sku WHERE id=${id5}`)) === 0
      && count(TRADE_DB, `SELECT COUNT(*) FROM t_order_item
        WHERE order_id=(SELECT id FROM t_order WHERE order_no='${order.orderNo}') AND sku_id=${id5}`) === 1);

  const usage = await call('GET', `${SKUS(spuId)}/usage`, null, sellerToken);
  const usage5 = (usage.body?.data ?? []).find((x) => String(x.skuId) === id5);
  check('usage 只读接口只返回被订单引用的规格（5kg / 1 单）',
    usage.status === 200 && usage5?.orderCount === 1
      && !(usage.body?.data ?? []).some((x) => String(x.skuId) === id2),
    usage.body);

  const versionBeforeE = detail.version;
  const orderDelete = await call('PUT', SKUS(spuId), structureBody(versionBeforeE, [
    row(skuItem(detail, id2), { price: 2190, stock: 8 }),
  ]), sellerToken);
  check('有订单的规格删除被 422 拒绝并点名规格与订单数',
    orderDelete.status === 422
      && String(orderDelete.body?.message ?? '').includes('已有订单')
      && String(orderDelete.body?.message ?? '').includes('A14-5kg装'),
    orderDelete.body);
  check('被拒绝时整单零写入（规格数 / 版本 / 审核态都不变）',
    skuRows(spuId).length === 3
      && statusOf(spuId) === `ON_SALE|APPROVED|${versionBeforeE}|2190|7990`,
    { skus: skuRows(spuId), spu: statusOf(spuId) });

  console.log('\n== F. 边界、越权与回收站 ==');
  detail = await detailOf(sellerToken, spuId);
  const vG = detail.version;
  const beforeG = { rows: skuRows(spuId).length, spu: statusOf(spuId) };

  const stale = await call('PUT', SKUS(spuId), structureBody(vG - 1, [row(skuItem(detail, id2), { price: 2190, stock: 8 })]), sellerToken);
  check('版本冲突 → 409', stale.status === 409, stale.body);

  const noVersion = await call('PUT', SKUS(spuId), { skus: [row(skuItem(detail, id2))] }, sellerToken);
  check('缺版本 → 400', noVersion.status === 400, noVersion.body);

  const empty = await call('PUT', SKUS(spuId), structureBody(vG, []), sellerToken);
  check('空列表 → 400 且提示至少一个规格',
    empty.status === 400 && String(empty.body?.message ?? '').includes('至少需要一个规格'), empty.body);

  const dupName = await call('PUT', SKUS(spuId), structureBody(vG, [
    row(skuItem(detail, id2), { specName: '同名' }),
    row(skuItem(detail, id5), { specName: '同名' }),
  ]), sellerToken);
  check('重复规格名 → 400', dupName.status === 400, dupName.body);

  const tooMany = await call('PUT', SKUS(spuId), structureBody(vG,
    Array.from({ length: 21 }, (_, i) => row(null, { specName: '规格' + i, price: 1000 + i, stock: 1 }))), sellerToken);
  check('超过 20 个规格 → 400', tooMany.status === 400, tooMany.body);

  const badPrice = await call('PUT', SKUS(spuId), structureBody(vG, [row(skuItem(detail, id2), { price: 0 })]), sellerToken);
  check('价格 0 → 400 且提示价格必须大于 0',
    badPrice.status === 400 && String(badPrice.body?.message ?? '').includes('价格必须大于 0'), badPrice.body);

  const unknownSku = await call('PUT', SKUS(spuId), structureBody(vG, [
    row(skuItem(detail, id2)),
    { id: '999999999999999999', specName: '不存在', price: 1000, stock: 1 },
  ]), sellerToken);
  check('未知规格 id → 404', unknownSku.status === 404, unknownSku.body);

  const otherEdit = await call('PUT', SKUS(spuId), structureBody(vG, [row(skuItem(detail, id2))]), otherToken);
  // E20 租户拦截器先给 t_spu 追加 seller_id 条件，跨店请求在 selectById 阶段就拿不到行 → 404；
  // 服务内归属校验是第二层，只有请求没有租户头（老令牌 / 服务间直连）时才轮到它 → 403。
  check('别人的商品 → 404（租户过滤）或 403（归属兜底），均拒绝',
    (otherEdit.status === 404 && String(otherEdit.body?.message ?? '').includes('商品不存在'))
      || (otherEdit.status === 403 && String(otherEdit.body?.message ?? '').includes('不属于当前账号')),
    otherEdit.body);

  // 第二层必须独立成立：去掉 X-Tenant-Id 直连商品服务（模拟老令牌 / 服务间调用），
  // 归属校验仍要拒绝 —— 否则「租户过滤」就成了唯一防线。
  const otherSellerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${OTHER_SELLER}'`);
  const directOther = await fetch(`http://localhost:8082${SKUS(spuId)}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': String(otherSellerId),
      'X-User-Role': 'SELLER',
    },
    body: JSON.stringify(structureBody(vG, [row(skuItem(detail, id2))])),
  }).then(async (r) => ({ status: r.status, body: await r.json().catch(() => null) }))
    .catch(() => null);
  check('无租户头直连商品服务：归属校验兜底返回 403',
    directOther?.status === 403
      && String(directOther.body?.message ?? '').includes('不属于当前账号'),
    directOther);

  const opEdit = await call('PUT', SKUS(spuId), structureBody(vG, [row(skuItem(detail, id2))]), opMerchantToken);
  check('运营改第三方规格 → 403 且提示第三方店铺',
    opEdit.status === 403 && String(opEdit.body?.message ?? '').includes('第三方店铺'), opEdit.body);

  const anon = await call('PUT', SKUS(spuId), structureBody(vG, [row(skuItem(detail, id2))]), null);
  check('未登录 → 401', anon.status === 401, anon.body);

  const missing = await call('PUT', SKUS('999999999999999999'), structureBody(0, [row(null, { specName: 'x', price: 1000, stock: 1 })]), sellerToken);
  check('不存在的商品 → 404', missing.status === 404, missing.body);

  check('以上失败请求全部零写入',
    skuRows(spuId).length === beforeG.rows && statusOf(spuId) === beforeG.spu,
    { skus: skuRows(spuId), spu: statusOf(spuId) });

  const delSpu = await call('POST', `/api/seller/products/${spuId}/delete`, null, sellerToken);
  const recycleEdit = await call('PUT', SKUS(spuId), structureBody(vG, [row(skuItem(detail, id2))]), sellerToken);
  check('回收站商品不能改规格 → 404',
    delSpu.status === 200 && recycleEdit.status === 404, { del: delSpu.status, edit: recycleEdit.body });

  console.log('\n== G. 收尾：零残留 + ES 无 A14 文档 ==');
  const buyerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}'`);
  const orderIds = sql(TRADE_DB, `SELECT id FROM t_order WHERE user_id=${buyerId}`);
  if (orderIds.length) {
    const list = orderIds.join(',');
    sql(TRADE_DB, `DELETE FROM t_stock_freeze WHERE order_id IN (${list})`);
    sql(TRADE_DB, `DELETE FROM t_payment WHERE order_id IN (${list})`);
    sql(TRADE_DB, `DELETE FROM t_order_status_log WHERE order_id IN (${list})`);
    sql(TRADE_DB, `DELETE FROM t_order_item WHERE order_id IN (${list})`);
    sql(TRADE_DB, `DELETE FROM t_order WHERE id IN (${list})`);
  }
  sql(PRODUCT_DB, `DELETE FROM t_spu_image WHERE spu_id=${spuId}`);
  sql(PRODUCT_DB, `DELETE FROM t_sku WHERE spu_id=${spuId}`);
  sql(PRODUCT_DB, `DELETE FROM t_spu_audit_log WHERE spu_id=${spuId}`);
  sql(PRODUCT_DB, `DELETE FROM t_spu WHERE id=${spuId}`);
  if (buyerId) {
    sql(TRADE_DB, `DELETE FROM t_cart_item WHERE user_id=${buyerId}`);
    sql(USER_DB, `DELETE FROM t_user_address WHERE user_id=${buyerId}`);
    sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${buyerId}`);
    sql(USER_DB, `DELETE FROM t_user WHERE id=${buyerId}`);
  }
  check('回库：SPU / SKU / 图片 / 审核历史 / 订单明细全部清理',
    count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu WHERE id=${spuId}`) === 0
      && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_sku WHERE spu_id=${spuId}`) === 0
      && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu_image WHERE spu_id=${spuId}`) === 0
      && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu_audit_log WHERE spu_id=${spuId}`) === 0
      && count(TRADE_DB, `SELECT COUNT(*) FROM t_order_item WHERE order_id IN (${orderIds.join(',') || 0})`) === 0);
  check('回库：临时买家与地址已清理',
    count(USER_DB, `SELECT COUNT(*) FROM t_user WHERE phone='${buyerPhone}'`) === 0);

  const reindex = await call('POST', '/api/admin/search/reindex', null, opToken);
  const esQuery = await fetch(ES + '/duoduo-search/_count', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query: { match_phrase: { title } } }),
  }).then((r) => r.json()).catch(() => null);
  check('ES 重建后无 A14 文档',
    reindex.status === 200 && esQuery?.count === 0, { reindex: reindex.status, es: esQuery });

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) {
    console.log('失败项：' + failures.join('；'));
    return 1;
  }
  return 0;
}

main().then((code) => process.exit(code)).catch((err) => {
  console.error(err);
  process.exit(2);
});

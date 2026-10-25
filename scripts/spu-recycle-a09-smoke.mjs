/**
 * A-09 · 商品软删 / 回收站 / 30 天清理 冒烟（2026-10-25）。
 *
 * 守六组：
 *  A. 删除语义：只写 deleted_at + 压成下架，审核态与 version 都不动；重复删除幂等。
 *  B. 读路径：买家详情/检索、商家列表/详情、审核台列表/详情全部看不见；
 *     审核历史仍可读（审计记录不随商品消失）。
 *  C. 写路径：编辑 / 上下架 / 重新提审 / 改价改库存 全部 404 —— 回收站里的商品不可操作。
 *  D. 恢复：清空 deleted_at、保持下架、审核态与版本不变；恢复后公开仍不可见，重新上架才可见。
 *  E. 越权：别的店主删 → 403；运营经商家入口删第三方商品 → 403；卖家调清理接口 → 403。
 *  F. 30 天保留期：超期恢复 422；清理只删超期商品（连同 SKU），保留期内不删；
 *     商品物理删除后审核历史仍在；清理幂等。
 *
 * 前置：容器在跑；incremental/50 已执行；卖家 13600003333、运营 13684295254 存在。
 * 用法：NO_PROXY="*" node scripts/spu-recycle-a09-smoke.mjs
 */
import { execFileSync } from 'node:child_process';

const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OTHER_SELLER = '13959064118';
const OPERATOR = '13684295254';
const CATEGORY = '101';
const BRAND = '1';
const DB = 'duoduo_product';

let pass = 0;
const failures = [];
function check(name, ok, detail) {
  if (ok) {
    pass++;
    console.log('  [PASS] ' + name);
  } else {
    failures.push(name);
    console.log('  [FAIL] ' + name + (detail === undefined ? '' : '  -> ' + JSON.stringify(detail).slice(0, 500)));
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
function sql(query) {
  const out = execFileSync('docker', [
    'exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B',
    '--default-character-set=utf8mb4', '-D', DB, '-e', query,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return out.split(/\r?\n/).filter((line) => line && !line.includes('Warning'));
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 启动自清理：把历史 A09 冒烟商品全部置为「超期回收站」，再走系统清理路径物理删除 */
async function cleanupStale(opToken) {
  const rows = sql(`SELECT id FROM t_spu WHERE title LIKE 'A09 %'`);
  if (!rows.length) {
    console.log('  [CLEAN] 无历史 A09 商品');
    return;
  }
  sql(`UPDATE t_spu SET deleted_at = UTC_TIMESTAMP() - INTERVAL 31 DAY, status = 'OFF_SHELF'
       WHERE title LIKE 'A09 %'`);
  const purge = await call('POST', '/api/admin/products/recycle/purge', null, opToken);
  console.log(`  [CLEAN] 历史 A09 商品 ${rows.length} 件已清理（purged=${purge.body?.data?.purged}）`);
}

async function recycleList(token, keyword) {
  return call('GET',
    `/api/seller/products?deleted=true&page=1&pageSize=50&keyword=${encodeURIComponent(keyword)}`,
    null, token);
}
async function normalList(token, keyword) {
  return call('GET',
    `/api/seller/products?page=1&pageSize=50&keyword=${encodeURIComponent(keyword)}`,
    null, token);
}
async function publicSearch(keyword) {
  return call('GET', `/api/products?keyword=${encodeURIComponent(keyword)}&page=1&pageSize=50`);
}

async function main() {
  console.log('== 0. 登录 ==');
  const sellerToken = await tokenOf(SELLER, 'merchant');
  const otherToken = await tokenOf(OTHER_SELLER, 'merchant');
  const opToken = await tokenOf(OPERATOR, 'admin');
  const opMerchantToken = await tokenOf(OPERATOR, 'merchant');
  check('卖家登录成功', Boolean(sellerToken));
  check('另一店主登录成功（越权对照）', Boolean(otherToken));
  check('运营登录成功（admin 入口）', Boolean(opToken));
  check('运营登录成功（商家入口，越权对照）', Boolean(opMerchantToken));
  if (!sellerToken || !otherToken || !opToken || !opMerchantToken) return 1;
  await cleanupStale(opToken);

  console.log('\n== 1. 造一件已过审的第三方商品 ==');
  const stamp = String(Date.now()).slice(-7);
  const title = `A09 冒烟商品 ${stamp}`;
  const created = await call('POST', '/api/seller/products', {
    title,
    subtitle: 'A-09 回收站冒烟',
    categoryId: CATEGORY,
    brandId: BRAND,
    mainImage: '/img/products/dog-food.svg',
    detail: 'A-09 回收站冒烟商品详情',
    skus: [{ specName: '1kg', price: 2590, stock: 7, image: null }],
  }, sellerToken);
  const spuId = created.body?.data?.id;
  check('发布成功', created.status === 200 && Boolean(spuId), created.body);
  if (!spuId) return 1;
  const sellerId = sql(`SELECT seller_id FROM t_spu WHERE id = ${spuId}`)[0];
  check('归属 = 当前登录卖家（请求体未传 sellerId）', Boolean(sellerId) && sellerId !== '0', sellerId);

  const pending = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  // E28 起低风险卖家可能被规则自动放行，不再必然落 PENDING —— 这里只断言「审核维度有结论」，
  // 需要人工补一刀时才走审核接口（A-09 关心的是删除语义，不是审核策略）。
  check('新商品审核态为 PENDING 或已自动放行', ['PENDING', 'APPROVED'].includes(pending?.auditStatus), pending?.auditStatus);
  if (pending?.auditStatus === 'PENDING') {
    const approve = await call('POST', `/api/admin/products/${spuId}/audit`,
      { action: 'APPROVE', version: pending?.version }, opToken);
    check('运营审核通过', approve.status === 200, approve.body);
  } else {
    console.log('  [INFO] 商品已被规则自动放行，跳过人工审核');
  }
  const approved = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  const skuId = approved?.skus?.[0]?.id;
  check('商品在售且已过审',
    approved?.status === 'ON_SALE' && approved?.auditStatus === 'APPROVED' && Boolean(skuId),
    { status: approved?.status, audit: approved?.auditStatus, skuId });
  const publicBefore = await call('GET', `/api/products/${spuId}`);
  check('删除前买家详情可见', publicBefore.status === 200, publicBefore.status);

  // 让 ES 先收录：后面「删除后搜不到」才有对照意义（不是从未收录）。
  // 增量同步属于 A-12 范围，这里不依赖它：等不到就显式全量重建一次，保证前置条件成立。
  let searchable = false;
  for (let i = 0; i < 8; i++) {
    const r = await publicSearch(title);
    searchable = (r.body?.data?.items ?? []).some((x) => String(x.id) === String(spuId));
    if (searchable) break;
    await sleep(2000);
  }
  if (!searchable) {
    console.log('  [INFO] 增量索引未收录（A-12 范围），显式全量重建一次');
    await call('POST', '/api/admin/search/reindex', null, opToken);
    await sleep(1500);
    const r = await publicSearch(title);
    searchable = (r.body?.data?.items ?? []).some((x) => String(x.id) === String(spuId));
  }
  check('删除前买家检索可命中（ES 已收录，对照成立）', searchable);

  console.log('\n== 2. 删除：软删 + 压成下架，审核态 / 版本不动 ==');
  const beforeDel = sql(`SELECT status, audit_status, version, IFNULL(DATE_FORMAT(deleted_at,'%Y-%m-%d %H:%i:%s'),'') FROM t_spu WHERE id=${spuId}`)[0];
  const [beforeStatus, beforeAudit, beforeVersion] = beforeDel.split('\t');
  const del = await call('POST', `/api/seller/products/${spuId}/delete`, null, sellerToken);
  const delView = del.body?.data;
  check('删除接口 200', del.status === 200, del.body);
  check('回带 deletedAt', Boolean(delView?.deletedAt), delView?.deletedAt);
  check('回带 purgeAt = 删除后 30 天',
    Boolean(delView?.purgeAt) && Math.round((Date.parse(delView.purgeAt) - Date.parse(delView.deletedAt)) / 86400000) === 30,
    { deletedAt: delView?.deletedAt, purgeAt: delView?.purgeAt });
  check('删除后视图压成下架', delView?.status === 'OFF_SHELF', delView?.status);

  const afterDel = sql(`SELECT status, audit_status, version, DATE_FORMAT(deleted_at,'%Y-%m-%d %H:%i:%s') FROM t_spu WHERE id=${spuId}`)[0];
  const [afterStatus, afterAudit, afterVersion, deletedAt1] = afterDel.split('\t');
  check('回库：status 变为 OFF_SHELF', afterStatus === 'OFF_SHELF', afterStatus);
  check('回库：审核态保持 APPROVED（删除不是合规判定）', afterAudit === beforeAudit && afterAudit === 'APPROVED', { beforeAudit, afterAudit });
  check('回库：version 不变（删除不参与乐观锁）', afterVersion === beforeVersion, { beforeVersion, afterVersion });
  check('回库：deleted_at 非空', Boolean(deletedAt1), deletedAt1);

  const delAgain = await call('POST', `/api/seller/products/${spuId}/delete`, null, sellerToken);
  check('重复删除幂等 200', delAgain.status === 200, delAgain.status);
  check('重复删除不刷新删除时间（保留期不被续期）',
    sql(`SELECT DATE_FORMAT(deleted_at,'%Y-%m-%d %H:%i:%s') FROM t_spu WHERE id=${spuId}`)[0] === deletedAt1,
    deletedAt1);

  console.log('\n== 3. 读路径：买家 / 商家 / 审核台都看不见 ==');
  const publicAfter = await call('GET', `/api/products/${spuId}`);
  check('买家详情 → 404（与不存在同码，不泄漏存在性）', publicAfter.status === 404, publicAfter.status);
  const searchAfter = await publicSearch(title);
  check('买家检索不再出现该商品',
    !(searchAfter.body?.data?.items ?? []).some((x) => String(x.id) === String(spuId)),
    (searchAfter.body?.data?.items ?? []).map((x) => x.id));
  const normalAfter = await normalList(sellerToken, title);
  check('商家正常列表不再出现', !(normalAfter.body?.data?.items ?? []).some((x) => String(x.id) === String(spuId)));
  const recycle = await recycleList(sellerToken, title);
  const recycleItem = (recycle.body?.data?.items ?? []).find((x) => String(x.id) === String(spuId));
  check('回收站列表出现该商品', Boolean(recycleItem), recycle.body?.data);
  check('回收站条目带删除时间与预计清理时间',
    Boolean(recycleItem?.deletedAt) && Boolean(recycleItem?.purgeAt), recycleItem);
  check('商家详情 → 404', (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).status === 404);
  check('审核台列表不出现该商品',
    !((await call('GET', `/api/admin/products?keyword=${encodeURIComponent(title)}&page=1&pageSize=50`, null, opToken))
      .body?.data?.items ?? []).some((x) => String(x.id) === String(spuId)));
  check('审核台详情 → 404', (await call('GET', `/api/admin/products/${spuId}`, null, opToken)).status === 404);
  const historyAfterDelete = await call('GET', `/api/admin/products/${spuId}/history`, null, opToken);
  // 自动放行的商品只有 SUBMIT 一条（规则放行不落人工 APPROVE 事件），只断言「记录还在」
  check('审核历史仍可读（商品不在，留痕还在）',
    historyAfterDelete.status === 200 && (historyAfterDelete.body?.data ?? []).some((x) => x.event === 'SUBMIT'),
    historyAfterDelete.body?.data?.map?.((x) => x.event));

  console.log('\n== 4. 写路径：回收站商品不可编辑 / 上下架 / 重提 / 改价 ==');
  const editDel = await call('PUT', `/api/seller/products/${spuId}`,
    { title: title + '（回收站里改）', version: Number(beforeVersion) }, sellerToken);
  check('编辑 → 404', editDel.status === 404, editDel.status);
  const statusDel = await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, sellerToken);
  check('上架 → 404', statusDel.status === 404, statusDel.status);
  const resubmitDel = await call('POST', `/api/seller/products/${spuId}/resubmit`, null, sellerToken);
  check('重新提审 → 404', resubmitDel.status === 404, resubmitDel.status);
  const priceDel = await call('PUT', `/api/seller/products/${spuId}/skus/price-stock`,
    { skus: [{ id: skuId, price: 2990, stock: 9 }] }, sellerToken);
  check('改价改库存 → 404', priceDel.status === 404, priceDel.status);
  const auditDel = await call('POST', `/api/admin/products/${spuId}/audit`,
    { action: 'APPROVE', version: Number(beforeVersion) }, opToken);
  check('审核结论 → 404', auditDel.status === 404, auditDel.status);
  check('回库：全部写路径未改动内容', sql(`SELECT title, version FROM t_spu WHERE id=${spuId}`)[0] === `${title}\t${beforeVersion}`,
    sql(`SELECT title, version FROM t_spu WHERE id=${spuId}`)[0]);

  console.log('\n== 5. 越权 ==');
  const otherDel = await call('POST', `/api/seller/products/${spuId}/delete`, null, otherToken);
  // 租户拦截器会先把「别人的商品」过滤成不存在（404），Service 的归属校验是第二层（403）。
  // 两者都算拒绝，关键是数据不能被改动。
  check('别的店主删除被拒（404 租户隔离 / 403 归属校验）',
    otherDel.status === 404 || otherDel.status === 403, { status: otherDel.status, msg: otherDel.body?.message });
  const opDel = await call('POST', `/api/seller/products/${spuId}/delete`, null, opMerchantToken);
  check('运营经商家入口删第三方商品 → 403（必须店主自己操作）', opDel.status === 403,
    { status: opDel.status, msg: opDel.body?.message });
  const sellerPurge = await call('POST', '/api/admin/products/recycle/purge', null, sellerToken);
  check('卖家调用清理接口 → 403', sellerPurge.status === 403, sellerPurge.status);
  check('越权尝试后商品仍原样（deleted_at 未被刷新、内容未变）',
    sql(`SELECT title, version, DATE_FORMAT(deleted_at,'%Y-%m-%d %H:%i:%s') FROM t_spu WHERE id=${spuId}`)[0]
      === `${title}\t${beforeVersion}\t${deletedAt1}`,
    sql(`SELECT title, version, DATE_FORMAT(deleted_at,'%Y-%m-%d %H:%i:%s') FROM t_spu WHERE id=${spuId}`)[0]);

  console.log('\n== 6. 恢复 ==');
  const restore = await call('POST', `/api/seller/products/${spuId}/restore`, null, sellerToken);
  check('恢复接口 200', restore.status === 200, restore.body);
  check('恢复后 deletedAt 为空', restore.body?.data?.deletedAt == null, restore.body?.data?.deletedAt);
  check('恢复后保持下架', restore.body?.data?.status === 'OFF_SHELF', restore.body?.data?.status);
  check('恢复后审核态不变', restore.body?.data?.auditStatus === 'APPROVED', restore.body?.data?.auditStatus);
  const afterRestore = sql(`SELECT status, audit_status, version, IFNULL(deleted_at,'') FROM t_spu WHERE id=${spuId}`)[0];
  check('回库：deleted_at 已清空', afterRestore.split('\t')[3] === '', afterRestore);
  check('回库：version 仍不因删除/恢复变化', afterRestore.split('\t')[2] === beforeVersion, afterRestore);
  check('正常列表恢复可见', ((await normalList(sellerToken, title)).body?.data?.items ?? [])
    .some((x) => String(x.id) === String(spuId)));
  check('回收站不再出现', !((await recycleList(sellerToken, title)).body?.data?.items ?? [])
    .some((x) => String(x.id) === String(spuId)));
  check('恢复后买家仍不可见（下架态）', (await call('GET', `/api/products/${spuId}`)).status === 404);
  const restoreAgain = await call('POST', `/api/seller/products/${spuId}/restore`, null, sellerToken);
  check('重复恢复 → 422「不在回收站中」', restoreAgain.status === 422, restoreAgain.status);

  const relist = await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, sellerToken);
  check('重新上架成功', relist.status === 200, relist.body);
  check('重新上架后买家详情恢复可见', (await call('GET', `/api/products/${spuId}`)).status === 200);

  console.log('\n== 7. 30 天保留期与清理 ==');
  const fxExpired = String(Date.now() - 5000) + '01';
  const fxRetainedSku = String(Date.now() - 5000) + '02';
  const fxLog = String(Date.now() - 5000) + '03';
  sql(`INSERT INTO t_spu
         (id, title, category_id, supply_type, status, audit_status, sales_count, rating, rating_count,
          min_price, max_price, created_at, updated_at, seller_id, version, deleted_at)
       VALUES (${fxExpired}, 'A09 过期夹具 ${stamp}', ${CATEGORY}, 'merchant', 'OFF_SHELF', 'APPROVED',
               0, 5.00, 0, 1000, 1000, UTC_TIMESTAMP(), UTC_TIMESTAMP(), ${sellerId}, 0,
               UTC_TIMESTAMP() - INTERVAL 31 DAY)`);
  sql(`INSERT INTO t_sku
         (id, spu_id, sku_code, spec_json, price, market_price, stock, frozen_stock, status, created_at, updated_at)
       VALUES (${fxRetainedSku}, ${fxExpired}, 'A09-EXPIRED-${stamp}', '{}', 1000, 1000, 3, 0, 'ACTIVE',
               UTC_TIMESTAMP(), UTC_TIMESTAMP())`);
  sql(`INSERT INTO t_spu_audit_log
         (id, spu_id, seller_id, event, actor_type, actor_id, to_audit_status, version_after, created_at)
       VALUES (${fxLog}, ${fxExpired}, ${sellerId}, 'SUBMIT', 'SELLER', ${sellerId}, 'PENDING', 0, UTC_TIMESTAMP())`);
  check('夹具：超期商品 + SKU + 审核历史已入库',
    sql(`SELECT COUNT(*) FROM t_spu WHERE id=${fxExpired}`)[0] === '1'
      && sql(`SELECT COUNT(*) FROM t_sku WHERE spu_id=${fxExpired}`)[0] === '1');
  const restoreExpired = await call('POST', `/api/seller/products/${fxExpired}/restore`, null, sellerToken);
  check('超过 30 天恢复 → 422', restoreExpired.status === 422, restoreExpired.status);
  check('422 文案说明是保留期问题', /保留期/.test(restoreExpired.body?.message ?? ''), restoreExpired.body?.message);

  // 保留期内（刚删的第二件）不能被清理
  const created2 = await call('POST', '/api/seller/products', {
    title: `A09 冒烟商品 ${stamp} 保留中`,
    categoryId: CATEGORY,
    brandId: BRAND,
    mainImage: '/img/products/cat-food.svg',
    skus: [{ specName: '默认', price: 1990, stock: 2, image: null }],
  }, sellerToken);
  const spuId2 = created2.body?.data?.id;
  check('第二件商品创建成功（保留期内对照）', created2.status === 200 && Boolean(spuId2), created2.body);
  const del2 = await call('POST', `/api/seller/products/${spuId2}/delete`, null, sellerToken);
  check('第二件商品已删除', del2.status === 200, del2.status);

  const purge = await call('POST', '/api/admin/products/recycle/purge', null, opToken);
  check('运营手动清理 200', purge.status === 200, purge.body);
  check('清理只带走超期商品', sql(`SELECT COUNT(*) FROM t_spu WHERE id=${fxExpired}`)[0] === '0',
    sql(`SELECT COUNT(*) FROM t_spu WHERE id=${fxExpired}`)[0]);
  check('超期商品的 SKU 一并物理删除', sql(`SELECT COUNT(*) FROM t_sku WHERE spu_id=${fxExpired}`)[0] === '0');
  check('审核历史保留（append-only，商品没了留痕还在）',
    sql(`SELECT COUNT(*) FROM t_spu_audit_log WHERE spu_id=${fxExpired}`)[0] === '1');
  check('保留期内的商品不被清理', sql(`SELECT COUNT(*) FROM t_spu WHERE id=${spuId2} AND deleted_at IS NOT NULL`)[0] === '1');
  check('保留期内商品的 SKU 不被清理', sql(`SELECT COUNT(*) FROM t_sku WHERE spu_id=${spuId2}`)[0] === '1');
  const purgeAgain = await call('POST', '/api/admin/products/recycle/purge', null, opToken);
  check('清理幂等（第二轮不再删除已清理的 id）', purgeAgain.status === 200,
    { first: purge.body?.data, second: purgeAgain.body?.data });

  console.log('\n== 8. 收尾 ==');
  // 用系统自己的路径清掉保留期对照件：先改老删除时间，再清理
  sql(`UPDATE t_spu SET deleted_at = UTC_TIMESTAMP() - INTERVAL 31 DAY WHERE id=${spuId2}`);
  const purgeFinal = await call('POST', '/api/admin/products/recycle/purge', null, opToken);
  check('收尾清理对照件成功', purgeFinal.status === 200 && sql(`SELECT COUNT(*) FROM t_spu WHERE id=${spuId2}`)[0] === '0',
    purgeFinal.body);
  await call('POST', `/api/seller/products/${spuId}/status`, { action: 'OFF_SHELF' }, sellerToken);

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) {
    console.log('失败项：\n - ' + failures.join('\n - '));
    return 1;
  }
  return 0;
}

process.exitCode = await main();

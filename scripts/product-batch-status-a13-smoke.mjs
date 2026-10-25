/**
 * A-13 · 商家端商品批量上下架冒烟（2026-10-25）。
 *
 * 守六组事实：
 *  A. 全成功：批量下架 / 上架逐条返回目标状态，整体 200；version 不因上下架变化，
 *     买家可见性随状态实时变化；id 以字符串下发（雪花精度）。
 *  B. 部分失败不回滚：混入「待审」「已删除」「别人的商品」时，成功条目照常落库，
 *     失败条目逐条带回 errorCode + message，items 顺序与去重后的请求一致。
 *  C. 门禁复用单品语义：批量上架对 PENDING 商品失败（BUSINESS_RULE），
 *     运营经商家入口批量操作第三方商品逐条 FORBIDDEN，店铺外商品失败且零改动。
 *  D. 幂等与去重：重复 id 只执行一次；已处于目标态再次批量操作成功且不写库（updated_at 不变）。
 *  E. 参数边界：空 ids / null 元素 / 超过 100 件 / 非法 action → 400 VALIDATION_ERROR；
 *     未登录 → 401。
 *  F. 收尾零残留：夹具 SPU / SKU / 图片 / 审核历史全部清理，ES 重建后无 A13 文档。
 *
 * 前置：容器在跑；product 已部署 A-13 版本；卖家 13600003333、另一店主 13959064118、
 *       运营 13684295254 可用；ES 可达（localhost:9200）。
 * 用法：NO_PROXY="*" node scripts/product-batch-status-a13-smoke.mjs
 */
import { execFileSync } from 'node:child_process';

const API = 'http://localhost:8080';
const ES = 'http://localhost:9200';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OTHER_SELLER = '13959064118';
const OPERATOR = '13684295254';
const CATEGORY = '101';
const BRAND = '1';
const DB = 'duoduo_product';
const BATCH = '/api/seller/products/batch-status';

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
const count = (query) => Number(sql(query)[0]);

/** 直接物理删除 A13 夹具：可重复执行，ES 由收尾 reindex 清孤儿 */
function cleanupStale() {
  const ids = sql(`SELECT id FROM t_spu WHERE title LIKE 'A13 %'`);
  if (!ids.length) {
    console.log('  [CLEAN] 无历史 A13 夹具');
    return;
  }
  const list = ids.join(',');
  sql(`DELETE FROM t_spu_image WHERE spu_id IN (${list})`);
  sql(`DELETE FROM t_sku WHERE spu_id IN (${list})`);
  sql(`DELETE FROM t_spu_audit_log WHERE spu_id IN (${list})`);
  sql(`DELETE FROM t_spu WHERE id IN (${list})`);
  console.log(`  [CLEAN] 历史 A13 夹具 ${ids.length} 件已删除`);
}

const detailOf = async (token, spuId) =>
  (await call('GET', `/api/seller/products/${spuId}`, null, token)).body?.data;

async function createProduct(token, title, suffix) {
  return call('POST', '/api/seller/products', {
    title,
    subtitle: 'A-13 批量上下架冒烟' + suffix,
    categoryId: CATEGORY,
    brandId: BRAND,
    mainImage: '/img/products/dog-food.svg',
    detail: 'A-13 批量上下架冒烟商品',
    skus: [{ specName: '1kg', price: 2590, stock: 6, image: null }],
  }, token);
}

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

const statusOf = (spuId) => sql(`SELECT status, version FROM t_spu WHERE id=${spuId}`)[0];
const updatedAtOf = (spuId) =>
  sql(`SELECT DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f') FROM t_spu WHERE id=${spuId}`)[0];
const itemOf = (result, id) => (result?.items ?? []).find((x) => String(x.id) === String(id));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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

  console.log('\n== 1. 造夹具 ==');
  const stamp = String(Date.now()).slice(-7);
  const titleA = `A13 冒烟商品 ${stamp} A`;
  const titleB = `A13 冒烟商品 ${stamp} B`;
  const titleC = `A13 冒烟商品 ${stamp} C`;
  const titleD = `A13 冒烟商品 ${stamp} D`;
  const titleE = `A13 冒烟商品 ${stamp} E`;

  const a = await createProduct(sellerToken, titleA, ' A');
  const b = await createProduct(sellerToken, titleB, ' B');
  const c = await createProduct(sellerToken, titleC, ' C');
  const d = await createProduct(sellerToken, titleD, ' D');
  const e = await createProduct(otherToken, titleE, ' E');
  const idA = a.body?.data?.id;
  const idB = b.body?.data?.id;
  const idC = c.body?.data?.id;
  const idD = d.body?.data?.id;
  const idE = e.body?.data?.id;
  check('5 件夹具创建成功（A/B/C/D 归本店，E 归另一店主）',
    [a, b, c, d, e].every((r) => r.status === 200) && [idA, idB, idC, idD, idE].every(Boolean),
    { a: a.body, b: b.body, c: c.body, d: d.body, e: e.body });
  if (![idA, idB, idC, idD, idE].every(Boolean)) return 1;

  const detailA = await ensureApprovedOnSale(sellerToken, opToken, idA);
  const detailB = await ensureApprovedOnSale(sellerToken, opToken, idB);
  const detailC0 = await ensureApprovedOnSale(sellerToken, opToken, idC);
  const detailD = await ensureApprovedOnSale(sellerToken, opToken, idD);
  const detailE = await ensureApprovedOnSale(otherToken, opToken, idE);
  check('A/B/D/E 均为 APPROVED + ON_SALE',
    [detailA, detailB, detailD, detailE].every((x) => x?.auditStatus === 'APPROVED' && x?.status === 'ON_SALE'),
    [detailA, detailB, detailD, detailE].map((x) => ({ status: x?.status, audit: x?.auditStatus })));

  // C：驳回 → 重提 = PENDING + ON_SALE（可稳定复现「审核未通过」的批量失败条目）
  const rej = await call('POST', `/api/admin/products/${idC}/audit`,
    { action: 'REJECT', reason: 'A13 冒烟：需要人工复核', version: detailC0.version }, opToken);
  const resub = await call('POST', `/api/seller/products/${idC}/resubmit`, null, sellerToken);
  const detailC = await detailOf(sellerToken, idC);
  check('C 已构造为 PENDING（驳回后重提）',
    rej.status === 200 && resub.status === 200 && detailC?.auditStatus === 'PENDING',
    { rej: rej.status, resub: resub.status, audit: detailC?.auditStatus, status: detailC?.status });

  const delD = await call('POST', `/api/seller/products/${idD}/delete`, null, sellerToken);
  check('D 已软删（回收站夹具）', delD.status === 200, delD.body);

  const foreignStatusBefore = statusOf(idE);
  check('夹具回库：A/B/C/E 在售，D 已在回收站',
    sql(`SELECT status FROM t_spu WHERE id=${idA}`)[0] === 'ON_SALE'
      && sql(`SELECT status FROM t_spu WHERE id=${idB}`)[0] === 'ON_SALE'
      && sql(`SELECT status FROM t_spu WHERE id=${idC}`)[0] === 'ON_SALE'
      && sql(`SELECT status FROM t_spu WHERE id=${idE}`)[0] === 'ON_SALE'
      && sql(`SELECT deleted_at IS NOT NULL FROM t_spu WHERE id=${idD}`)[0] === '1',
    { A: statusOf(idA), B: statusOf(idB), C: statusOf(idC), D: statusOf(idD), E: foreignStatusBefore });

  console.log('\n== 2. 批量下架：全成功 + 逐条结果 ==');
  const versionA0 = String(detailA.version);
  const versionB0 = String(detailB.version);
  const updatedA0 = updatedAtOf(idA);
  const updatedB0 = updatedAtOf(idB);
  await sleep(1200); // 让 updated_at 能与操作前区分（MySQL DATETIME 秒级）
  const off = await call('POST', BATCH, { ids: [idA, idB], action: 'OFF_SHELF' }, sellerToken);
  const offData = off.body?.data;
  check('批量下架整体 200', off.status === 200, off.body);
  check('汇总 total=2 / success=2 / failed=0',
    offData?.total === 2 && offData?.successCount === 2 && offData?.failedCount === 2 - offData?.successCount,
    { total: offData?.total, success: offData?.successCount, failed: offData?.failedCount });
  check('items 顺序与请求一致', (offData?.items ?? []).map((x) => String(x.id)).join(',') === `${idA},${idB}`,
    (offData?.items ?? []).map((x) => x.id));
  check('成功条目带目标状态与中文文案', offData?.items?.[0]?.success === true
    && offData?.items?.[0]?.status === 'OFF_SHELF' && offData?.items?.[0]?.statusLabel === '已下架'
    && offData?.items?.[0]?.errorCode == null && offData?.items?.[0]?.message == null, offData?.items?.[0]);
  check('响应 id 为字符串（雪花精度不丢）', (offData?.items ?? []).every((x) => typeof x.id === 'string'),
    (offData?.items ?? []).map((x) => typeof x.id));
  const statusA1 = statusOf(idA);
  const statusB1 = statusOf(idB);
  check('回库：A/B 均已下架', statusA1.startsWith('OFF_SHELF\t') && statusB1.startsWith('OFF_SHELF\t'),
    { A: statusA1, B: statusB1 });
  check('回库：上下架不改 version（A/B）',
    statusA1.split('\t')[1] === versionA0 && statusB1.split('\t')[1] === versionB0,
    { A: statusA1, B: statusB1, versionA0, versionB0 });
  check('回库：updated_at 确实被写新（不是假成功）',
    updatedAtOf(idA) !== updatedA0 && updatedAtOf(idB) !== updatedB0,
    { beforeA: updatedA0, afterA: updatedAtOf(idA) });
  check('下架后买家详情 404', (await call('GET', `/api/products/${idA}`)).status === 404
    && (await call('GET', `/api/products/${idB}`)).status === 404);

  console.log('\n== 3. 批量上架：全成功 ==');
  const on = await call('POST', BATCH, { ids: [idA, idB], action: 'ON_SALE' }, sellerToken);
  const onData = on.body?.data;
  check('批量上架整体 200 且 2 件全成功',
    on.status === 200 && onData?.successCount === 2 && onData?.failedCount === 0,
    { status: on.status, data: onData });
  check('成功条目状态为 ON_SALE / 在售',
    (onData?.items ?? []).every((x) => x.success === true && x.status === 'ON_SALE' && x.statusLabel === '在售'),
    onData?.items);
  check('回库：A/B 重新在售且 version 仍不变',
    statusOf(idA) === `${'ON_SALE'}\t${versionA0}` && statusOf(idB) === `ON_SALE\t${versionB0}`,
    { A: statusOf(idA), B: statusOf(idB) });
  check('上架后买家详情可见', (await call('GET', `/api/products/${idA}`)).status === 200
    && (await call('GET', `/api/products/${idB}`)).status === 200);

  console.log('\n== 4. 部分失败不回滚 ==');
  const offPartial = await call('POST', BATCH,
    { ids: [idA, idB, idD, idE], action: 'OFF_SHELF' }, sellerToken);
  const offPartialData = offPartial.body?.data;
  check('部分失败整体仍 200（前端能拿到逐条原因）', offPartial.status === 200, offPartial.body);
  check('汇总 4 件：成功 2 / 失败 2',
    offPartialData?.total === 4 && offPartialData?.successCount === 2 && offPartialData?.failedCount === 2,
    offPartialData);
  check('items 保持请求顺序（含失败条目）',
    (offPartialData?.items ?? []).map((x) => String(x.id)).join(',') === `${idA},${idB},${idD},${idE}`,
    (offPartialData?.items ?? []).map((x) => x.id));
  check('已删除商品失败并带 NOT_FOUND 与可读原因',
    itemOf(offPartialData, idD)?.success === false
      && itemOf(offPartialData, idD)?.errorCode === 'NOT_FOUND'
      && /不存在/.test(itemOf(offPartialData, idD)?.message ?? ''),
    itemOf(offPartialData, idD));
  check('别人的商品失败且不泄漏存在性（NOT_FOUND / FORBIDDEN）',
    itemOf(offPartialData, idE)?.success === false
      && ['NOT_FOUND', 'FORBIDDEN'].includes(itemOf(offPartialData, idE)?.errorCode),
    itemOf(offPartialData, idE));
  check('成功的 A/B 未因同批失败而回滚（仍落库为 OFF_SHELF）',
    sql(`SELECT status FROM t_spu WHERE id=${idA}`)[0] === 'OFF_SHELF'
      && sql(`SELECT status FROM t_spu WHERE id=${idB}`)[0] === 'OFF_SHELF',
    { A: sql(`SELECT status FROM t_spu WHERE id=${idA}`)[0], B: sql(`SELECT status FROM t_spu WHERE id=${idB}`)[0] });
  check('失败的 D/E 零改动（D 仍在回收站、E 仍在售）',
    sql(`SELECT status FROM t_spu WHERE id=${idD}`)[0] === 'OFF_SHELF'
      && sql(`SELECT deleted_at IS NOT NULL FROM t_spu WHERE id=${idD}`)[0] === '1'
      && statusOf(idE) === foreignStatusBefore,
    { D: sql(`SELECT status, deleted_at IS NOT NULL FROM t_spu WHERE id=${idD}`)[0], E: statusOf(idE) });

  console.log('\n== 5. 批量上架门禁：待审商品逐条失败 ==');
  const onPartial = await call('POST', BATCH, { ids: [idA, idC, idD], action: 'ON_SALE' }, sellerToken);
  const onPartialData = onPartial.body?.data;
  check('整体 200，汇总 3 件：成功 1 / 失败 2',
    onPartial.status === 200 && onPartialData?.total === 3
      && onPartialData?.successCount === 1 && onPartialData?.failedCount === 2, onPartialData);
  check('A 成功回到在售', itemOf(onPartialData, idA)?.success === true
    && itemOf(onPartialData, idA)?.status === 'ON_SALE', itemOf(onPartialData, idA));
  check('PENDING 商品失败并说明审核原因',
    itemOf(onPartialData, idC)?.success === false
      && itemOf(onPartialData, idC)?.errorCode === 'BUSINESS_RULE'
      && /审核/.test(itemOf(onPartialData, idC)?.message ?? ''),
    itemOf(onPartialData, idC));
  check('回库：A 上架成功、C 保持原状态且仍待审、D 仍在回收站',
    sql(`SELECT status FROM t_spu WHERE id=${idA}`)[0] === 'ON_SALE'
      && sql(`SELECT status, audit_status FROM t_spu WHERE id=${idC}`)[0] === 'ON_SALE\tPENDING'
      && sql(`SELECT deleted_at IS NOT NULL FROM t_spu WHERE id=${idD}`)[0] === '1',
    { A: statusOf(idA), C: statusOf(idC), D: statusOf(idD) });

  console.log('\n== 6. 幂等、去重与动作归一 ==');
  const dedup = await call('POST', BATCH, { ids: [idA, idA, idB], action: 'OFF_SHELF' }, sellerToken);
  const dedupData = dedup.body?.data;
  check('重复 id 去重：total=2、items=2', dedupData?.total === 2 && dedupData?.items?.length === 2
    && dedupData?.successCount === 2, dedupData);
  const updatedA1 = updatedAtOf(idA);
  const updatedB1 = updatedAtOf(idB);
  await sleep(1200);
  const again = await call('POST', BATCH, { ids: [idA, idB], action: 'OFF_SHELF' }, sellerToken);
  check('已处于目标态再次下架：逐条成功（幂等）',
    again.status === 200 && again.body?.data?.successCount === 2 && again.body?.data?.failedCount === 0,
    again.body?.data);
  check('幂等重放不写库（updated_at 不变）',
    updatedAtOf(idA) === updatedA1 && updatedAtOf(idB) === updatedB1,
    { beforeA: updatedA1, afterA: updatedAtOf(idA), beforeB: updatedB1, afterB: updatedAtOf(idB) });
  const lower = await call('POST', BATCH, { ids: [idA], action: ' on_sale ' }, sellerToken);
  check('action 大小写/空白归一为 ON_SALE',
    lower.status === 200 && lower.body?.data?.items?.[0]?.status === 'ON_SALE'
      && sql(`SELECT status FROM t_spu WHERE id=${idA}`)[0] === 'ON_SALE',
    lower.body?.data);
  await call('POST', BATCH, { ids: [idA], action: 'OFF_SHELF' }, sellerToken);

  console.log('\n== 7. 参数边界与未登录 ==');
  const empty = await call('POST', BATCH, { ids: [], action: 'OFF_SHELF' }, sellerToken);
  check('空 ids → 400 VALIDATION_ERROR',
    empty.status === 400 && empty.body?.code === 'VALIDATION_ERROR', empty.body);
  const nullElem = await call('POST', BATCH, { ids: [idA, null], action: 'OFF_SHELF' }, sellerToken);
  check('ids 含 null → 400 VALIDATION_ERROR',
    nullElem.status === 400 && nullElem.body?.code === 'VALIDATION_ERROR', nullElem.body);
  const tooMany = await call('POST', BATCH, {
    ids: Array.from({ length: 101 }, (_, i) => String(900000000000000000n + BigInt(i))),
    action: 'OFF_SHELF',
  }, sellerToken);
  check('超过 100 件 → 400 且提示上限',
    tooMany.status === 400 && /100/.test(tooMany.body?.message ?? ''), tooMany.body);
  const badAction = await call('POST', BATCH, { ids: [idA], action: 'DELETE_ALL' }, sellerToken);
  check('非法 action → 400 VALIDATION_ERROR',
    badAction.status === 400 && badAction.body?.code === 'VALIDATION_ERROR', badAction.body);
  const noAction = await call('POST', BATCH, { ids: [idA] }, sellerToken);
  check('缺少 action → 400 VALIDATION_ERROR',
    noAction.status === 400 && noAction.body?.code === 'VALIDATION_ERROR', noAction.body);
  const anon = await call('POST', BATCH, { ids: [idA], action: 'OFF_SHELF' });
  check('未登录 → 401', anon.status === 401, anon.status);
  check('边界请求均未改动商品状态', sql(`SELECT status FROM t_spu WHERE id=${idA}`)[0] === 'OFF_SHELF',
    sql(`SELECT status FROM t_spu WHERE id=${idA}`)[0]);

  console.log('\n== 8. 越权：运营批量操作第三方商品逐条拒绝 ==');
  const opBatch = await call('POST', BATCH, { ids: [idA, idB], action: 'ON_SALE' }, opMerchantToken);
  const opData = opBatch.body?.data;
  check('运营批量整体 200（不是整批 403，原因可逐条展示）', opBatch.status === 200, opBatch.body);
  check('两条均失败且错误码 FORBIDDEN',
    opData?.failedCount === 2 && (opData?.items ?? []).every((x) => x.errorCode === 'FORBIDDEN'), opData?.items);
  check('失败原因指向代运营工单，而不是含糊的“无权限”',
    (opData?.items ?? []).every((x) => /代运营|工单/.test(x.message ?? '')), opData?.items?.map((x) => x.message));
  check('运营越权后 A/B 状态零改动',
    sql(`SELECT status FROM t_spu WHERE id=${idA}`)[0] === 'OFF_SHELF'
      && sql(`SELECT status FROM t_spu WHERE id=${idB}`)[0] === 'OFF_SHELF',
    { A: statusOf(idA), B: statusOf(idB) });

  console.log('\n== 9. 收尾：零残留 ==');
  check('A13 夹具齐备（5 件）', count(`SELECT COUNT(*) FROM t_spu WHERE title LIKE 'A13 %'`) === 5,
    count(`SELECT COUNT(*) FROM t_spu WHERE title LIKE 'A13 %'`));
  sql(`DELETE FROM t_spu_image WHERE spu_id IN (${[idA, idB, idC, idD, idE].join(',')})`);
  sql(`DELETE FROM t_sku WHERE spu_id IN (${[idA, idB, idC, idD, idE].join(',')})`);
  sql(`DELETE FROM t_spu_audit_log WHERE spu_id IN (${[idA, idB, idC, idD, idE].join(',')})`);
  sql(`DELETE FROM t_spu WHERE id IN (${[idA, idB, idC, idD, idE].join(',')})`);
  check('SPU / SKU / 图片 / 审核历史零残留',
    count(`SELECT COUNT(*) FROM t_spu WHERE title LIKE 'A13 %'`) === 0
      && count(`SELECT COUNT(*) FROM t_sku WHERE spu_id IN (${[idA, idB, idC, idD, idE].join(',')})`) === 0
      && count(`SELECT COUNT(*) FROM t_spu_image WHERE spu_id IN (${[idA, idB, idC, idD, idE].join(',')})`) === 0
      && count(`SELECT COUNT(*) FROM t_spu_audit_log WHERE spu_id IN (${[idA, idB, idC, idD, idE].join(',')})`) === 0);

  const reindex = await call('POST', '/api/admin/search/reindex', null, opToken);
  check('收尾全量重建成功', reindex.status === 200 && reindex.body?.data?.reindexed === true, reindex.body);
  const esRes = await fetch(ES + '/duoduo-search/_search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ size: 0, query: { prefix: { title: 'A13 ' } }, track_total_hits: true }),
  });
  const esBody = await esRes.json().catch(() => null);
  check('ES 中无 A13 标题文档', esRes.status === 200 && esBody?.hits?.total?.value === 0,
    { status: esRes.status, total: esBody?.hits?.total?.value });

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) {
    console.log('失败项：\n - ' + failures.join('\n - '));
    return 1;
  }
  return 0;
}

process.exitCode = await main();

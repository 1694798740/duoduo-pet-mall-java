#!/usr/bin/env node
/**
 * A-17 · 商品复制 / CSV 导入导出 / 曝光-点击-诊断冒烟（2026-10-26）。
 *
 * 守六组事实：
 *  A. 复制：新 SPU、标题追加「（副本）」、规格全量复制、默认下架；原商品零改动；
 *     别人（店主）复制 403、平台运营复制第三方商品 403（代运营门禁）、回收站商品 404。
 *  B. 导出：UTF-8 BOM + 16 列表头 + 一个规格一行；普通卖家只看到自己的商品；
 *     公式注入标题以 `'=` 导出（Excel 不执行）。
 *  C. 模板：表头 + 一行示例，可直接下载。
 *  D. 导入：一个分组键 = 一个商品，多规格聚合；3 组里 1 组类目不存在 → 部分成功；
 *     表头错 / 空内容 / 非法库存 → 4xx 或逐组失败原因；非卖家 403。
 *  E. 诊断：公开搜索记曝光、详情记点击；接口返回与 DB 台账一致；days 越界被夹紧；
 *     买家 403、未登录 401；别的店主看不到本店商品。
 *  F. 收尾零残留：夹具 SPU / SKU / 图片 / 审核历史 / 统计行全部清理。
 *
 * 前置：容器在跑；product/trade 已部署 A-17；卖家 13600003333、另一店主 13959064118、
 *       运营 13684295254、买家 13600001111 可用；ES 可达（搜索曝光需要）。
 * 用法：NO_PROXY="*" node scripts/product-csv-diagnostics-a17-smoke.mjs
 */
import { execFileSync } from 'node:child_process';

const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OTHER_SELLER = '13959064118';
const OPERATOR = '13684295254';
const BUYER = '13600001111';
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
    console.log('  [FAIL] ' + name + (detail === undefined ? '' : '  -> ' + JSON.stringify(detail).slice(0, 800)));
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

function sql(query) {
  const out = execFileSync('docker', [
    'exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B',
    '--default-character-set=utf8mb4', '-D', DB, '-e', query,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return out.split(/\r?\n/).filter((line) => line && !line.includes('Warning'));
}
const one = (query) => sql(query)[0];

/** 物理删除 A17 夹具（含统计行）；ES 由收尾 reindex 清孤儿 */
function cleanupStale() {
  const ids = sql(`SELECT id FROM t_spu WHERE title LIKE '%A17%'`);
  if (!ids.length) {
    console.log('  [CLEAN] 无历史 A17 夹具');
    return;
  }
  const list = ids.join(',');
  sql(`DELETE FROM t_product_daily_stat WHERE spu_id IN (${list})`);
  sql(`DELETE FROM t_spu_image WHERE spu_id IN (${list})`);
  sql(`DELETE FROM t_sku WHERE spu_id IN (${list})`);
  sql(`DELETE FROM t_spu_audit_log WHERE spu_id IN (${list})`);
  sql(`DELETE FROM t_spu WHERE id IN (${list})`);
  console.log(`  [CLEAN] 历史 A17 夹具 ${ids.length} 件已删除`);
}

const detailOf = async (token, spuId) =>
  (await call('GET', `/api/seller/products/${spuId}`, null, token)).body?.data;
const dbStatusOf = (spuId) =>
  one(`SELECT CONCAT(status,'|',audit_status,'|',IFNULL(sales_count,0),'|',IF(version IS NULL,'-',version))
        FROM t_spu WHERE id=${spuId}`);
const skuCountOf = (spuId) =>
  Number(one(`SELECT COUNT(*) FROM t_sku WHERE spu_id=${spuId}`) || 0);

async function createProduct(token, title, skus) {
  return call('POST', '/api/seller/products', {
    title,
    subtitle: 'A-17 冒烟',
    categoryId: CATEGORY,
    brandId: BRAND,
    mainImage: '/img/products/dog-food.svg',
    detail: 'A-17 冒烟商品',
    skus,
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

const csvCell = (v) => {
  const s = v == null ? '' : String(v);
  return s.includes(',') || s.includes('"') || s.includes('\n') ? `"${s.replace(/"/g, '""')}"` : s;
};
const csvLine = (cells) => cells.map(csvCell).join(',');
const HEADER = '分组键,商品标题,副标题,类目ID,品牌ID,主图,图集,商品介绍,商品详情,富文本详情,规格参数,包装规格,规格名,售价,库存,规格图';
function importLine(group, title, price, stock, specName) {
  return csvLine([group, title, '', CATEGORY, '', '/img/products/dog-food.svg', '', '', '', '',
    '', '', specName, price, stock, '']);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitSearchContains(keyword, spuId, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const res = await call('GET', `/api/products?keyword=${encodeURIComponent(keyword)}&page=1&pageSize=20`);
    const items = res.body?.data?.items ?? [];
    if (items.some((x) => String(x.id) === String(spuId))) return true;
    await sleep(1000);
  }
  return false;
}

async function main() {
  console.log('== 0. 登录、清理与基线 ==');
  const sellerToken = await tokenOf(SELLER, 'merchant');
  const otherToken = await tokenOf(OTHER_SELLER, 'merchant');
  const opToken = await tokenOf(OPERATOR, 'admin');
  const opMerchantToken = await tokenOf(OPERATOR, 'merchant');
  const buyerToken = await tokenOf(BUYER);
  check('卖家 / 另一店主 / 运营 / 买家登录成功',
    [sellerToken, otherToken, opToken, opMerchantToken, buyerToken].every(Boolean));
  if (![sellerToken, otherToken, opToken, opMerchantToken, buyerToken].every(Boolean)) return 1;

  cleanupStale();
  const baseline = await call('POST', '/api/admin/search/reindex', null, opToken);
  check('基线全量重建成功', baseline.status === 200 && baseline.body?.data?.reindexed === true, baseline.body);

  console.log('\n== 1. 复制商品 ==');
  const stamp = String(Date.now()).slice(-7);
  const titleSource = `A17 复制源 ${stamp}`;
  const created = await createProduct(sellerToken, titleSource, [
    { specName: '2kg', price: 2590, stock: 7, image: null },
    { specName: '5kg', price: 4990, stock: 3, image: null },
  ]);
  const sourceId = created.body?.data?.id;
  check('源商品创建成功', created.status === 200 && Boolean(sourceId), created.body);
  if (!sourceId) return 1;
  const sourceDetail = await ensureApprovedOnSale(sellerToken, opToken, sourceId);
  const sourceBefore = dbStatusOf(sourceId);

  const copied = await call('POST', `/api/seller/products/${sourceId}/copy`, null, sellerToken);
  const copyId = copied.body?.data?.id;
  check('复制成功（200）', copied.status === 200 && Boolean(copyId), copied.body);
  check('副本标题追加「（副本）」', copied.body?.data?.title === titleSource + '（副本）', copied.body?.data?.title);
  check('副本默认下架', copied.body?.data?.status === 'OFF_SHELF' && copied.body?.data?.statusLabel === '已下架',
    { status: copied.body?.data?.status, label: copied.body?.data?.statusLabel });
  check('副本按新建规则重新判定审核态（非 REJECTED）',
    ['APPROVED', 'PENDING'].includes(copied.body?.data?.auditStatus), copied.body?.data?.auditStatus);
  check('副本规格全量复制（2 个）', copied.body?.data?.skuCount === 2 && skuCountOf(copyId) === 2,
    { view: copied.body?.data?.skuCount, db: skuCountOf(copyId) });
  const copyDb = dbStatusOf(copyId);
  check('副本落库为 OFF_SHELF / sales_count=0',
    /^OFF_SHELF\|(APPROVED|PENDING)\|0\|/.test(copyDb ?? ''), copyDb);
  check('源商品零改动（状态/审核/销量/版本不变）', dbStatusOf(sourceId) === sourceBefore,
    { before: sourceBefore, after: dbStatusOf(sourceId) });
  check('源商品仍 ON_SALE', sourceDetail?.status === 'ON_SALE', sourceDetail?.status);

  const copyByOther = await call('POST', `/api/seller/products/${sourceId}/copy`, null, otherToken);
  // 商品域对 t_spu 启用了租户隔离：跨店 selectById 直接 404（比 403 更严格，也不泄露存在性）
  check('别人复制本店商品 → 404（租户隔离）', copyByOther.status === 404, copyByOther.body);
  const copyByOp = await call('POST', `/api/seller/products/${sourceId}/copy`, null, opMerchantToken);
  check('平台运营复制第三方商品 403（代运营门禁）', copyByOp.status === 403
    && String(copyByOp.body?.message ?? '').includes('平台不能直接复制'), copyByOp.body);

  console.log('\n== 2. CSV 导出与模板 ==');
  const exported = await call('GET', '/api/seller/products/export', null, sellerToken);
  const text = exported.body?.data?.content ?? '';
  check('导出成功且带 UTF-8 BOM', exported.status === 200 && text.charCodeAt(0) === 0xFEFF,
    { status: exported.status, first: text.charCodeAt(0) });
  check('导出表头为 16 列模板', text.replace(/^\uFEFF/, '').split('\r\n')[0] === HEADER);
  check('导出包含源商品（一个规格一行）', exported.body?.data?.rowCount >= 2
    && text.split('\r\n').some((line) => line.includes(titleSource)), exported.body?.data?.rowCount);
  check('导出文件名带日期', /^products-\d{8}\.csv$/.test(String(exported.body?.data?.filename)),
    exported.body?.data?.filename);

  const template = await call('GET', '/api/seller/products/import-template', null, sellerToken);
  const tpl = template.body?.data?.content ?? '';
  check('模板下载成功（表头 + 1 行示例）', template.status === 200
    && tpl.replace(/^\uFEFF/, '').startsWith(HEADER)
    && template.body?.data?.rowCount === 1, template.body?.data);
  check('模板示例行可被识别为 1 个分组', tpl.split('\r\n').filter(Boolean).length === 2, tpl.slice(0, 200));

  console.log('\n== 3. CSV 导入（部分成功） ==');
  const importTitle = `A17 CSV 导入 ${stamp}`;
  const formulaTitle = `=A17 公式标题 ${stamp}`;
  const validCsv = [
    HEADER,
    importLine('IMP1', importTitle, '10.00', '10', '2kg'),
    importLine('IMP1', importTitle, '20.00', '5', '5kg'),
    importLine('IMP2', formulaTitle, '9.90', '3', '默认'),
    importLine('IMP3', `A17 类目不存在 ${stamp}`, '1.00', '1', '默认').replace(`,${CATEGORY},`, ',999999,'),
  ].join('\r\n') + '\r\n';

  const imported = await call('POST', '/api/seller/products/import', { content: validCsv }, sellerToken);
  const result = imported.body?.data;
  check('导入请求整体 200', imported.status === 200, imported.body);
  check('3 个分组：成功 2、失败 1（部分成功）',
    result?.totalGroups === 3 && result?.successCount === 2 && result?.failedCount === 1, result);
  check('首个分组按行序返回 lineNo=2', result?.items?.[0]?.lineNo === 2, result?.items?.[0]);
  check('失败组带回可直接展示的原因', String(result?.items?.[2]?.message ?? '').includes('类目'),
    result?.items?.[2]);

  const importId = result?.items?.[0]?.spuId;
  const formulaId = result?.items?.[1]?.spuId;
  check('导入商品落库（2 件）',
    Boolean(importId) && Boolean(formulaId)
      && Number(one(`SELECT COUNT(*) FROM t_spu WHERE title IN ('${importTitle}','${formulaTitle}')`)) === 2,
    { importId, formulaId });
  check('导入商品的多规格聚合为 2 个 SKU', skuCountOf(importId) === 2, skuCountOf(importId));
  check('公式标题原样落库（未被转义污染）',
    one(`SELECT title FROM t_spu WHERE id=${formulaId}`) === formulaTitle);

  const exportAfter = await call('GET', '/api/seller/products/export', null, sellerToken);
  const textAfter = exportAfter.body?.data?.content ?? '';
  check('再次导出包含导入商品', textAfter.includes(importTitle), importTitle);
  check('公式注入标题以单引号前缀导出（Excel 不执行）', textAfter.includes(`'${formulaTitle}`),
    textAfter.split('\r\n').find((line) => line.includes('公式标题')));

  const badHeader = await call('POST', '/api/seller/products/import',
    { content: '商品标题,售价\r\n猫粮,10\r\n' }, sellerToken);
  check('表头不匹配 → 400', badHeader.status === 400, badHeader.body);
  const emptyCsv = await call('POST', '/api/seller/products/import', { content: '  ' }, sellerToken);
  check('空内容 → 400', emptyCsv.status === 400, emptyCsv.body);
  const badStock = await call('POST', '/api/seller/products/import', {
    content: [HEADER, importLine('BAD1', `A17 库存非法 ${stamp}`, '10.00', 'abc', '默认')].join('\r\n') + '\r\n',
  }, sellerToken);
  check('库存非整数 → 该组失败且原因含「库存」',
    badStock.status === 200 && badStock.body?.data?.failedCount === 1
      && String(badStock.body?.data?.items?.[0]?.message ?? '').includes('库存'), badStock.body?.data);
  const importByOther = await call('POST', '/api/seller/products/import', { content: validCsv }, otherToken);
  check('另一店主也可导入（归属各自店铺）', importByOther.status === 200, importByOther.body);
  const otherUserId = one(`SELECT id FROM duoduo_user.t_user WHERE phone='${OTHER_SELLER}'`);
  if (importByOther.body?.data?.successCount > 0 && otherUserId) {
    for (const item of importByOther.body.data.items.filter((x) => x.success)) {
      check(`另店主导入商品归属其店铺 ${item.groupKey}`,
        one(`SELECT seller_id FROM t_spu WHERE id=${item.spuId}`) === String(otherUserId), item);
    }
  }
  const importByBuyer = await call('POST', '/api/seller/products/import', { content: validCsv }, buyerToken);
  check('买家导入 → 403', importByBuyer.status === 403, importByBuyer.body);

  console.log('\n== 4. 曝光 / 点击 / 诊断 ==');
  // 导入走新建链路，第三方商品默认 PENDING；先由运营过审再验证公开曝光/点击
  await ensureApprovedOnSale(sellerToken, opToken, importId);
  const exposureHit = await waitSearchContains(importTitle, importId);
  check('公开搜索能搜到导入商品（过滤条件与索引就绪）', exposureHit, { importId, importTitle });
  const statAfterSearch = one(`SELECT CONCAT(IFNULL(SUM(exposure),0),'|',IFNULL(SUM(clicks),0))
    FROM t_product_daily_stat WHERE spu_id=${importId}`);
  check('搜索已记 1 次以上曝光', Number(statAfterSearch.split('|')[0]) >= 1, statAfterSearch);

  const publicDetail = await call('GET', `/api/products/${importId}`);
  check('公开详情可打开（已自动放行并在售）', publicDetail.status === 200, publicDetail.body);
  const statAfterClick = one(`SELECT CONCAT(IFNULL(SUM(exposure),0),'|',IFNULL(SUM(clicks),0))
    FROM t_product_daily_stat WHERE spu_id=${importId}`);
  check('详情打开已记 1 次点击', Number(statAfterClick.split('|')[1]) >= 1, statAfterClick);

  const diag = await call('GET', '/api/seller/products/diagnostics?days=30', null, sellerToken);
  const diagRow = (diag.body?.data?.items ?? []).find((x) => String(x.spuId) === String(importId));
  check('诊断接口返回本店商品', diag.status === 200 && Boolean(diagRow), diag.body?.data && {
    productCount: diag.body.data.productCount,
  });
  check('诊断曝光/点击与台账一致',
    diagRow && diagRow.exposure === Number(statAfterClick.split('|')[0])
      && diagRow.clicks === Number(statAfterClick.split('|')[1]),
    { diagRow, statAfterClick });
  check('诊断点击率 = 点击/曝光（百分比两位小数）',
    diagRow && diagRow.exposure > 0
      && Math.abs(diagRow.ctr - Math.round(diagRow.clicks * 10000 / diagRow.exposure) / 100) < 0.011,
    diagRow && { ctr: diagRow.ctr, exposure: diagRow.exposure, clicks: diagRow.clicks });
  check('诊断建议非空且可读', Array.isArray(diagRow?.suggestions) && diagRow.suggestions.length > 0,
    diagRow?.suggestions);
  check('days 缺省为 30', diag.body?.data?.days === 30, diag.body?.data?.days);
  const diagClamped = await call('GET', '/api/seller/products/diagnostics?days=999', null, sellerToken);
  check('days 上限被夹紧为 90', diagClamped.body?.data?.days === 90, diagClamped.body?.data?.days);
  const diagOther = await call('GET', '/api/seller/products/diagnostics?days=30', null, otherToken);
  check('别的店主诊断里没有本店商品',
    !(diagOther.body?.data?.items ?? []).some((x) => String(x.spuId) === String(importId)),
    (diagOther.body?.data?.items ?? []).length);
  const diagBuyer = await call('GET', '/api/seller/products/diagnostics', null, buyerToken);
  check('买家看诊断 → 403', diagBuyer.status === 403, diagBuyer.body);
  const diagAnon = await call('GET', '/api/seller/products/diagnostics');
  check('未登录看诊断 → 401', diagAnon.status === 401, diagAnon.body);

  console.log('\n== 5. 回收站商品不可复制 ==');
  const removed = await call('POST', `/api/seller/products/${formulaId}/delete`, null, sellerToken);
  check('软删导入商品成功', removed.status === 200, removed.body);
  const copyDeleted = await call('POST', `/api/seller/products/${formulaId}/copy`, null, sellerToken);
  check('复制回收站商品 → 404', copyDeleted.status === 404, copyDeleted.body);
  const copyUnknown = await call('POST', '/api/seller/products/999999999999999/copy', null, sellerToken);
  check('复制不存在的商品 → 404', copyUnknown.status === 404, copyUnknown.body);

  console.log('\n== 6. 收尾零残留 ==');
  const fixtureIds = sql(`SELECT id FROM t_spu WHERE title LIKE '%A17%'`);
  const otherFixtureIds = (importByOther.body?.data?.items ?? []).filter((x) => x.success).map((x) => x.spuId);
  const allIds = [...new Set([...fixtureIds, ...otherFixtureIds])];
  if (allIds.length) {
    const list = allIds.join(',');
    sql(`DELETE FROM t_product_daily_stat WHERE spu_id IN (${list})`);
    sql(`DELETE FROM t_spu_image WHERE spu_id IN (${list})`);
    sql(`DELETE FROM t_sku WHERE spu_id IN (${list})`);
    sql(`DELETE FROM t_spu_audit_log WHERE spu_id IN (${list})`);
    sql(`DELETE FROM t_spu WHERE id IN (${list})`);
  }
  await call('POST', '/api/admin/search/reindex', null, opToken);
  check('夹具 SPU 零残留', Number(one(`SELECT COUNT(*) FROM t_spu WHERE title LIKE '%A17%'`)) === 0);
  check('夹具 SKU / 图片 / 统计行零残留',
    Number(one(`SELECT COUNT(*) FROM t_sku WHERE spu_id IN (${allIds.join(',') || '0'})`)) === 0
      && Number(one(`SELECT COUNT(*) FROM t_product_daily_stat WHERE spu_id IN (${allIds.join(',') || '0'})`)) === 0);

  console.log(`\n== 结果：${pass} 通过 / ${failures.length} 失败 ==`);
  if (failures.length) {
    console.log('失败项：');
    for (const name of failures) console.log('  - ' + name);
    return 1;
  }
  return 0;
}

process.exitCode = await main();

/**
 * A-11 · 商品图片表（t_spu_image）冒烟（2026-10-25）。
 *
 * 守五组事实：
 *  A. 历史回填：每个有主图的 SPU 有且仅有一条 MAIN(sort 0)，GALLERY 序号从 1 连续；
 *     图片审核态与所属 SPU 一致，无孤儿行、无重复 URL；种子 1001 的主图重复项被剔除。
 *  B. 双写：发布商品时 CSV（main_image/images）照旧落库，图片表按同一形状拆分；
 *     主图重复 / 副图重复 / 空白项被去重，MAIN 恒为 sort 0。
 *  C. 编辑：改副图集合后图片表重建，顺序与 CSV 一致；未改主图时 MAIN 行不变。
 *  D. 审核联动：驳回 / 重提 / 通过三种结论都同步到图片行；这些动作只改 audit_status，
 *     不重建行（图片 ID 稳定）；通过后买家详情仍按「主图在前 + CSV 去重」展示。
 *  E. 生命周期：软删保留图片行（可恢复）；物理清理连同图片行一起删除，审核历史保留。
 *
 * 前置：容器在跑；incremental/52-spu-image.sql 已执行；卖家 13600003333、
 *       另一店主 13959064118、运营 13684295254 可用。
 * 用法：NO_PROXY="*" node scripts/spu-image-a11-smoke.mjs
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
const count = (query) => Number(sql(query)[0]);

const imageRows = (spuId) => sql(
  `SELECT image_type, sort_no, url, audit_status FROM t_spu_image
   WHERE spu_id = ${spuId}
   ORDER BY FIELD(image_type, 'MAIN', 'GALLERY'), sort_no`);
const imageIds = (spuId) => sql(
  `SELECT GROUP_CONCAT(id ORDER BY FIELD(image_type, 'MAIN', 'GALLERY'), sort_no)
   FROM t_spu_image WHERE spu_id = ${spuId}`)[0];
const distinctAudits = (spuId) => sql(
  `SELECT DISTINCT audit_status FROM t_spu_image WHERE spu_id = ${spuId} ORDER BY audit_status`);
const csvOf = (spuId) => sql(
  `SELECT main_image, IFNULL(images, ''), audit_status, version FROM t_spu WHERE id = ${spuId}`)[0];

async function sellerDetail(token, spuId) {
  return (await call('GET', `/api/seller/products/${spuId}`, null, token)).body?.data;
}

/** 启动自清理：直接删 A11 夹具（图片 / SKU / 审核历史 / SPU），保证可重复执行 */
function cleanupStale() {
  const rows = sql(`SELECT id FROM t_spu WHERE title LIKE 'A11 %'`);
  if (!rows.length) {
    console.log('  [CLEAN] 无历史 A11 夹具');
    return;
  }
  const ids = rows.join(',');
  sql(`DELETE FROM t_spu_image WHERE spu_id IN (${ids})`);
  sql(`DELETE FROM t_sku WHERE spu_id IN (${ids})`);
  sql(`DELETE FROM t_spu_audit_log WHERE spu_id IN (${ids})`);
  sql(`DELETE FROM t_spu WHERE id IN (${ids})`);
  console.log(`  [CLEAN] 历史 A11 夹具 ${rows.length} 件已删除`);
}

async function main() {
  console.log('== 0. 登录与历史夹具清理 ==');
  const sellerToken = await tokenOf(SELLER, 'merchant');
  const otherToken = await tokenOf(OTHER_SELLER, 'merchant');
  const opToken = await tokenOf(OPERATOR, 'admin');
  check('卖家登录成功', Boolean(sellerToken));
  check('另一店主登录成功（越权对照）', Boolean(otherToken));
  check('运营登录成功（admin 入口）', Boolean(opToken));
  if (!sellerToken || !otherToken || !opToken) return 1;

  check('52-spu-image.sql 已执行（表存在）',
    count(`SELECT COUNT(*) FROM information_schema.tables
           WHERE table_schema = '${DB}' AND table_name = 't_spu_image'`) === 1);
  cleanupStale();

  console.log('\n== 1. 历史回填完整性（只读） ==');
  const seed = imageRows('1001');
  check('种子 1001：MAIN(0) + GALLERY(1) 两行',
    seed.length === 2
      && seed[0] === 'MAIN\t0\t/img/products/1001.svg\tAPPROVED'
      && seed[1] === 'GALLERY\t1\t/img/products/1001-2.svg\tAPPROVED', seed);
  check('回填：有主图的 SPU 必有 MAIN 行（无遗漏）',
    count(`SELECT COUNT(*) FROM t_spu s
           WHERE s.main_image IS NOT NULL AND TRIM(s.main_image) <> ''
             AND NOT EXISTS (SELECT 1 FROM t_spu_image i
                             WHERE i.spu_id = s.id AND i.image_type = 'MAIN')`) === 0);
  check('回填：主图行 sort_no 恒为 0',
    count(`SELECT COUNT(*) FROM t_spu_image WHERE image_type = 'MAIN' AND sort_no <> 0`) === 0);
  check('回填：每个 SPU 最多一条 MAIN',
    count(`SELECT COUNT(*) FROM (SELECT spu_id FROM t_spu_image
           WHERE image_type = 'MAIN' GROUP BY spu_id HAVING COUNT(*) > 1) x`) === 0);
  check('回填：GALLERY 序号从 1 连续',
    count(`SELECT COUNT(*) FROM (SELECT spu_id, COUNT(*) c, MIN(sort_no) mn, MAX(sort_no) mx
           FROM t_spu_image WHERE image_type = 'GALLERY'
           GROUP BY spu_id HAVING mn <> 1 OR mx <> c) x`) === 0);
  check('回填：无孤儿行（spu_id 都能对上 t_spu）',
    count(`SELECT COUNT(*) FROM t_spu_image i
           LEFT JOIN t_spu s ON s.id = i.spu_id WHERE s.id IS NULL`) === 0);
  check('回填：同一 SPU 内 URL 不重复',
    count(`SELECT COUNT(*) FROM (SELECT spu_id, url FROM t_spu_image
           GROUP BY spu_id, url HAVING COUNT(*) > 1) x`) === 0);
  check('回填：图片审核态与 SPU 一致',
    count(`SELECT COUNT(*) FROM t_spu_image i JOIN t_spu s ON s.id = i.spu_id
           WHERE BINARY i.audit_status <> BINARY s.audit_status`) === 0);

  console.log('\n== 2. 发布：CSV 与图片表双写、去重、排序 ==');
  const stamp = String(Date.now()).slice(-7);
  const img = (name) => `/img/a11/${stamp}-${name}.svg`;
  const main = img('main');
  const g1 = img('g1');
  const g2 = img('g2');
  const g3 = img('g3');
  // 主图重复进副图列表、副图自身重复、空白项：三种都应被剔除
  const title = `A11 图片表冒烟 ${stamp} 微信abc12345`;
  const created = await call('POST', '/api/seller/products', {
    title,
    subtitle: 'A-11 图片表冒烟',
    categoryId: CATEGORY,
    brandId: BRAND,
    mainImage: main,
    images: [g1, main, g2, g1, '   '],
    detail: 'A-11 图片表冒烟详情',
    skus: [{ specName: '1kg', price: 2390, stock: 6, image: null }],
  }, sellerToken);
  const spuId = created.body?.data?.id;
  check('发布成功', created.status === 200 && Boolean(spuId), created.body);
  if (!spuId) return 1;
  const owner = sql(`SELECT seller_id FROM t_spu WHERE id=${spuId}`)[0];
  check('归属 = 当前登录卖家', Boolean(owner) && owner !== '0', owner);

  let detail = await sellerDetail(sellerToken, spuId);
  check('标题含联系方式 → 转人工（PENDING）', detail?.auditStatus === 'PENDING', detail?.auditStatus);
  let rows = imageRows(spuId);
  check('图片表：MAIN(0) + GALLERY(1,2)，重复项被剔除',
    rows.length === 3
      && rows[0] === `MAIN\t0\t${main}\tPENDING`
      && rows[1] === `GALLERY\t1\t${g1}\tPENDING`
      && rows[2] === `GALLERY\t2\t${g2}\tPENDING`, rows);
  const csv1 = csvOf(spuId).split('\t');
  check('CSV 照旧落库（主图 + 去重后的副图）',
    csv1[0] === main && csv1[1] === `${g1},${main},${g2}` && csv1[2] === 'PENDING', csv1);
  check('卖家详情 images 与 CSV 一致',
    JSON.stringify(detail?.images) === JSON.stringify([g1, main, g2]), detail?.images);

  console.log('\n== 3. 编辑副图：图片表重建且顺序跟随 CSV ==');
  const edited = await call('PUT', `/api/seller/products/${spuId}`, {
    images: [g2, g3, g1],
    version: detail.version,
  }, sellerToken);
  check('编辑成功', edited.status === 200, edited.body);
  detail = await sellerDetail(sellerToken, spuId);
  rows = imageRows(spuId);
  check('图片表按新顺序重建：MAIN 不变 + GALLERY g2,g3,g1',
    rows.length === 4
      && rows[0] === `MAIN\t0\t${main}\tPENDING`
      && rows[1] === `GALLERY\t1\t${g2}\tPENDING`
      && rows[2] === `GALLERY\t2\t${g3}\tPENDING`
      && rows[3] === `GALLERY\t3\t${g1}\tPENDING`, rows);
  const csv2 = csvOf(spuId).split('\t');
  check('CSV 同步为 g2,g3,g1', csv2[1] === `${g2},${g3},${g1}`, csv2);
  check('编辑后内容重审 → 图片仍 PENDING',
    distinctAudits(spuId).join(',') === 'PENDING', distinctAudits(spuId));

  console.log('\n== 4. 审核联动：驳回 / 重提 / 通过，只改审核态 ==');
  const idsBeforeAudit = imageIds(spuId);
  const rejected = await call('POST', `/api/admin/products/${spuId}/audit`,
    { action: 'REJECT', reason: 'A-11 冒烟：驳回看图片审核态', version: detail.version }, opToken);
  check('运营驳回 200', rejected.status === 200, rejected.body);
  check('图片行全部 REJECTED',
    distinctAudits(spuId).join(',') === 'REJECTED', distinctAudits(spuId));
  check('驳回只改审核态，图片 ID 稳定（未重建）', imageIds(spuId) === idsBeforeAudit);

  const resubmit = await call('POST', `/api/seller/products/${spuId}/resubmit`, null, sellerToken);
  check('卖家重新提审 200', resubmit.status === 200, resubmit.body);
  check('图片行全部回到 PENDING',
    distinctAudits(spuId).join(',') === 'PENDING', distinctAudits(spuId));
  check('重提只改审核态，图片 ID 稳定', imageIds(spuId) === idsBeforeAudit);

  detail = await sellerDetail(sellerToken, spuId);
  const approved = await call('POST', `/api/admin/products/${spuId}/audit`,
    { action: 'APPROVE', version: detail.version }, opToken);
  check('运营通过 200', approved.status === 200, approved.body);
  check('图片行全部 APPROVED',
    distinctAudits(spuId).join(',') === 'APPROVED', distinctAudits(spuId));
  check('通过只改审核态，图片 ID 稳定', imageIds(spuId) === idsBeforeAudit);
  check('SPU 审核态 APPROVED', csvOf(spuId).split('\t')[2] === 'APPROVED', csvOf(spuId));

  const publicDetail = await call('GET', `/api/products/${spuId}`);
  check('通过后买家详情可见', publicDetail.status === 200, publicDetail.status);
  check('买家详情图片：主图在前 + 副图按 CSV 顺序',
    JSON.stringify(publicDetail.body?.data?.images) === JSON.stringify([main, g2, g3, g1]),
    publicDetail.body?.data?.images);

  console.log('\n== 5. 越权：别人改不动，图片行保持原样 ==');
  const rowsBeforeOther = imageRows(spuId).join('\n');
  const otherEdit = await call('PUT', `/api/seller/products/${spuId}`,
    { images: [g1], version: detail.version }, otherToken);
  check('别的店主编辑被拒（404 租户隔离 / 403 归属校验）',
    otherEdit.status === 404 || otherEdit.status === 403,
    { status: otherEdit.status, msg: otherEdit.body?.message });
  check('越权尝试后图片表原样',
    imageRows(spuId).join('\n') === rowsBeforeOther, imageRows(spuId));

  console.log('\n== 6. 边界：无副图商品只有一条 MAIN ==');
  const created2 = await call('POST', '/api/seller/products', {
    title: `A11 无副图冒烟 ${stamp}`,
    categoryId: CATEGORY,
    brandId: BRAND,
    mainImage: img('solo'),
    detail: 'A-11 无副图边界',
    skus: [{ specName: '默认', price: 1990, stock: 3, image: null }],
  }, sellerToken);
  const spuId2 = created2.body?.data?.id;
  check('无副图商品发布成功', created2.status === 200 && Boolean(spuId2), created2.body);
  if (spuId2) {
    const soloRows = imageRows(spuId2);
    check('图片表只有 MAIN(0)', soloRows.length === 1 && soloRows[0].startsWith(`MAIN\t0\t${img('solo')}\t`),
      soloRows);
    check('CSV images 为空', csvOf(spuId2).split('\t')[1] === '', csvOf(spuId2));
  }

  console.log('\n== 7. 生命周期：软删保留图片，物理清理一并删除 ==');
  const del = await call('POST', `/api/seller/products/${spuId}/delete`, null, sellerToken);
  check('软删成功', del.status === 200, del.body);
  check('软删后图片行仍保留（可恢复）',
    count(`SELECT COUNT(*) FROM t_spu_image WHERE spu_id = ${spuId}`) === 4);
  check('软删不改变图片审核态',
    distinctAudits(spuId).join(',') === 'APPROVED', distinctAudits(spuId));

  if (spuId2) {
    check('第二件软删成功',
      (await call('POST', `/api/seller/products/${spuId2}/delete`, null, sellerToken)).status === 200);
  }
  sql(`UPDATE t_spu SET deleted_at = UTC_TIMESTAMP() - INTERVAL 31 DAY WHERE id IN (${spuId}${spuId2 ? ',' + spuId2 : ''})`);
  const purge = await call('POST', '/api/admin/products/recycle/purge', null, opToken);
  check('运营清理回收站 200', purge.status === 200, purge.body);
  check('SPU 物理删除', count(`SELECT COUNT(*) FROM t_spu WHERE id = ${spuId}`) === 0);
  check('图片行随 SPU 一并物理删除',
    count(`SELECT COUNT(*) FROM t_spu_image WHERE spu_id = ${spuId}`) === 0);
  check('SKU 一并物理删除',
    count(`SELECT COUNT(*) FROM t_sku WHERE spu_id = ${spuId}`) === 0);
  check('审核历史保留（append-only）',
    count(`SELECT COUNT(*) FROM t_spu_audit_log WHERE spu_id = ${spuId}`) > 0);
  if (spuId2) {
    check('第二件图片行也被清理',
      count(`SELECT COUNT(*) FROM t_spu_image WHERE spu_id = ${spuId2}`) === 0);
  }

  console.log('\n== 8. 收尾：清理审核历史，确认零残留 ==');
  sql(`DELETE FROM t_spu_audit_log WHERE spu_id IN (${spuId}${spuId2 ? ',' + spuId2 : ''})`);
  check('库中无 A11 商品残留',
    count(`SELECT COUNT(*) FROM t_spu WHERE title LIKE 'A11 %'`) === 0);
  check('库中无 A11 图片残留',
    count(`SELECT COUNT(*) FROM t_spu_image i JOIN t_spu s ON s.id = i.spu_id
           WHERE s.title LIKE 'A11 %'`) === 0);
  check('A11 审核历史已清空',
    count(`SELECT COUNT(*) FROM t_spu_audit_log WHERE spu_id IN (${spuId}${spuId2 ? ',' + spuId2 : ''})`) === 0);

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) {
    console.log('失败项：\n - ' + failures.join('\n - '));
    return 1;
  }
  return 0;
}

process.exitCode = await main();

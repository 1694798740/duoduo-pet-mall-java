#!/usr/bin/env node
/**
 * H-03 · 商品变更历史独立页冒烟（2026-10-26）。
 *
 * 守五组事实：
 *  A. 夹具：卖家提交商品（SUBMIT）→ 编辑标题（EDIT，带字段级 diff）；
 *  B. 跨商品接口按 spuId / 标题关键词 / 事件 / 店铺过滤，最新在前，标题回填；
 *  C. EDIT 行含「标题」改前 / 改后；分页 pageSize 生效而 total 不受影响；
 *  D. 非法 spuId / 未知事件 → 400；卖家 403、未登录 401；单商品时间线仍可用（回归 A-08）；
 *  E. 收尾：商品 / SKU / 图片 / 审核历史零残留。
 *
 * 前置：容器在跑；product 已部署 H-03；卖家 13600003333、运营 13684295254 可用。
 * 用法：NO_PROXY="*" node scripts/product-change-history-h03-smoke.mjs
 */
import { execFileSync } from 'node:child_process';

const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OPERATOR = '13684295254';
const USER_DB = 'duoduo_user';
const PRODUCT_DB = 'duoduo_product';

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
const originalTitle = `H03 变更历史 ${stamp}`;
const editedTitle = `H03 变更历史 ${stamp}（改标题）`;

const sellerToken = await tokenOf(SELLER, 'merchant');
const opToken = await tokenOf(OPERATOR, 'admin');
check('卖家 / 运营登录成功', Boolean(sellerToken) && Boolean(opToken));
if (!sellerToken || !opToken) process.exit(1);
const sellerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${SELLER}'`);

console.log('\n== 0. 夹具：提交 + 编辑（SUBMIT / EDIT） ==');
const created = await call('POST', '/api/seller/products', {
  title: originalTitle,
  subtitle: 'H-03 变更历史冒烟',
  categoryId: '101',
  brandId: '1',
  mainImage: '/img/products/dog-food.svg',
  detail: 'H-03 变更历史冒烟商品',
  skus: [{ specName: '默认规格', price: 1280, stock: 10, image: null }],
}, sellerToken);
const spuId = created.body?.data?.id;
check('商品提交成功（产生 SUBMIT）', created.status === 200 && Boolean(spuId), created.body);
let detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
const edited = await call('PUT', `/api/seller/products/${spuId}`, {
  title: editedTitle,
  version: detail?.version ?? 0,
}, sellerToken);
check('商品编辑成功（产生 EDIT）', edited.status === 200, edited.body);
const logsInDb = count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu_audit_log WHERE spu_id=${spuId}`);
check('库内 SUBMIT + EDIT 两条历史', logsInDb >= 2, logsInDb);

console.log('\n== 1. 按商品查询：最新在前 + 字段级改前改后 ==');
const bySpu = await call('GET',
  `/api/admin/products/change-history?spuId=${spuId}&page=1&pageSize=20`, null, opToken);
const rows = bySpu.body?.data?.items ?? [];
check('接口 200 且返回该商品记录', bySpu.status === 200 && rows.length >= 2, bySpu.body?.data?.total);
check('最新一条是 EDIT（按 id 倒序）', rows[0]?.event === 'EDIT', rows[0]);
check('回填商品标题（当前标题）与店铺', rows[0]?.spuTitle === editedTitle
  && rows[0]?.sellerId === String(sellerId), { title: rows[0]?.spuTitle, seller: rows[0]?.sellerId });
const titleChange = (rows[0]?.changes ?? []).find((c) => c.field === 'title');
check('EDIT 行含「标题」改前 / 改后',
  titleChange?.before === originalTitle && titleChange?.after === editedTitle, titleChange);
check('SUBMIT 行没有字段级 diff', rows.some((r) => r.event === 'SUBMIT' && (r.changes ?? []).length === 0), rows);

console.log('\n== 2. 标题关键词 / 事件 / 店铺过滤 ==');
const byKeyword = await call('GET',
  `/api/admin/products/change-history?keyword=${encodeURIComponent(`H03 变更历史 ${stamp}`)}&page=1&pageSize=20`, null, opToken);
const kwRows = byKeyword.body?.data?.items ?? [];
check('标题关键词命中该商品，且不含别的商品',
  kwRows.length > 0 && kwRows.every((r) => r.spuId === String(spuId)), kwRows.slice(0, 3));
const byEvent = await call('GET',
  `/api/admin/products/change-history?spuId=${spuId}&event=EDIT&page=1&pageSize=20`, null, opToken);
check('事件过滤只回 EDIT', (byEvent.body?.data?.items ?? []).every((r) => r.event === 'EDIT'),
  byEvent.body?.data?.items);
const bySeller = await call('GET',
  `/api/admin/products/change-history?sellerId=${sellerId}&page=1&pageSize=20`, null, opToken);
const sellerRows = bySeller.body?.data?.items ?? [];
check('店铺过滤只回本店', sellerRows.length > 0 && sellerRows.every((r) => r.sellerId === String(sellerId)),
  sellerRows.slice(0, 3));

console.log('\n== 3. 分页与回归：单商品时间线仍可用 ==');
const paged = await call('GET',
  `/api/admin/products/change-history?spuId=${spuId}&page=1&pageSize=1`, null, opToken);
check('pageSize=1 只回 1 条，total 仍为全量',
  (paged.body?.data?.items ?? []).length === 1 && Number(paged.body?.data?.total) >= 2,
  { total: paged.body?.data?.total, len: paged.body?.data?.items?.length });
const perProduct = await call('GET', `/api/admin/products/${spuId}/history?limit=50`, null, opToken);
check('回归 A-08：单商品时间线仍返回 ≥2 条', perProduct.status === 200
  && (perProduct.body?.data ?? []).length >= 2, perProduct.body?.data?.length);

console.log('\n== 4. 权限与非法参数 ==');
const sellerView = await call('GET', `/api/admin/products/change-history?spuId=${spuId}`, null, sellerToken);
check('卖家访问 → 403', sellerView.status === 403, sellerView.body);
const anonView = await call('GET', `/api/admin/products/change-history?spuId=${spuId}`);
check('未登录 → 401', anonView.status === 401, anonView.status);
const badSpu = await call('GET', '/api/admin/products/change-history?spuId=abc', null, opToken);
check('非法商品 id → 400', badSpu.status === 400, badSpu.body);
const badEvent = await call('GET', '/api/admin/products/change-history?event=WHATEVER', null, opToken);
check('未知事件 → 400', badEvent.status === 400, badEvent.body);

console.log('\n== 5. 收尾零残留 ==');
sql(PRODUCT_DB, `DELETE FROM t_spu_audit_log WHERE spu_id=${spuId}`);
sql(PRODUCT_DB, `DELETE FROM t_spu_image WHERE spu_id=${spuId}`);
sql(PRODUCT_DB, `DELETE FROM t_sku WHERE spu_id=${spuId}`);
sql(PRODUCT_DB, `DELETE FROM t_spu WHERE id=${spuId}`);
check('商品 / SKU / 图片 / 历史零残留',
  count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu WHERE id=${spuId}`) === 0
    && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_spu_audit_log WHERE spu_id=${spuId}`) === 0
    && count(PRODUCT_DB, `SELECT COUNT(*) FROM t_sku WHERE spu_id=${spuId}`) === 0);

console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exitCode = 1;
}

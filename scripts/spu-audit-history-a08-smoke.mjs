/**
 * A-08 · 商品审核历史与变更留痕 冒烟（2026-10-24）。
 *
 * 守四组：
 *  A. 时间线完整：提交 → 编辑 → 驳回 → 重新提审 → 通过，五个事件按序落库，
 *     actor / 版本 / 审核来源 / 驳回原因齐全；编辑事件带字段级 diff（改前值必须是改前值）。
 *  B. 读接口边界：卖家访问 403、未知商品 404、limit 生效；自营商品不进审核时间线（空数组）。
 *  C. 回库断言：t_spu_audit_log 的事件顺序、actor 快照、seller_id、changes JSON 与 API 一致。
 *  D. 抽检版本快照：给商品挂一条「抽中时版本 = 当前版本-1」的抽查任务，
 *     列表必须回带 sampledVersion / currentVersion / drift=true（只提示不阻断）。
 *
 * 前置：容器在跑；incremental/49 已执行；卖家 13600003333、运营 13684295254 存在。
 * 用法：NO_PROXY="*" node scripts/spu-audit-history-a08-smoke.mjs
 */
import { execFileSync } from 'node:child_process';

const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OPERATOR = '13684295254';
const CATEGORY = '101';
const BRAND = '1';
const DB = 'duoduo_product';
const SAMPLE_FIXTURE_ID = '777000000000000001';

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

function cleanupSample() {
  try {
    sql(`DELETE FROM t_audit_sample WHERE id = ${SAMPLE_FIXTURE_ID}`);
  } catch (err) {
    console.log('  [CLEAN] 抽查夹具清理失败：' + err.message);
  }
}

// 历史轮次的在售冒烟商品会抬高类目均价，触发本轮新品的 PRICE_ANOMALY。
// 启动时先下架标题以 A08 开头的在售商品（幂等，不计入断言）。
async function offShelfStale(token) {
  const stale = [];
  for (let page = 1; page <= 50; page++) {
    const data = (await call('GET', `/api/seller/products?page=${page}&pageSize=50`, null, token)).body?.data;
    const items = data?.items ?? [];
    stale.push(...items.filter((x) => String(x.title ?? '').startsWith('A08 冒烟商品') && x.status === 'ON_SALE'));
    if (items.length < 50 || page * 50 >= (data?.total ?? 0)) break;
  }
  let offShelfed = 0;
  for (const p of stale) {
    const r = await call('POST', `/api/seller/products/${p.id}/status`, { action: 'OFF_SHELF' }, token);
    if (r.status === 200) offShelfed++;
  }
  console.log(`  [CLEAN] 启动自清理：遗留 A08 在售商品 ${stale.length} 件，已下架 ${offShelfed} 件`);
}

async function historyOf(spuId, token, query = '?limit=50') {
  return call('GET', `/api/admin/products/${spuId}/history${query}`, null, token);
}

async function main() {
  cleanupSample();
  console.log('== 0. 登录卖家与运营 ==');
  const sellerToken = await tokenOf(SELLER, 'merchant');
  const opToken = await tokenOf(OPERATOR, 'admin');
  // 自营商品走商家端提交入口；运营账号在商家入口登录后由服务层平台视角放行
  const opMerchantToken = await tokenOf(OPERATOR, 'merchant');
  check('卖家（店主）登录成功', Boolean(sellerToken));
  check('运营登录成功（admin 入口）', Boolean(opToken));
  check('运营登录成功（商家入口，用于提交自营商品）', Boolean(opMerchantToken));
  if (!sellerToken || !opToken || !opMerchantToken) return 1;
  await offShelfStale(sellerToken);
  await offShelfStale(opToken);

  console.log('\n== 1. 提交第三方商品 → SUBMIT ==');
  const stamp = String(Date.now()).slice(-6);
  const originalTitle = `A08 冒烟商品 ${stamp}`;
  const editedTitle = `A08 冒烟商品 ${stamp}（改标题）`;
  const created = await call('POST', '/api/seller/products', {
    title: originalTitle,
    subtitle: '初始副标题',
    categoryId: CATEGORY,
    brandId: BRAND,
    mainImage: '/img/products/dog-food.svg',
    detail: 'A08 冒烟商品的详情',
    skus: [{ specName: '1kg', price: 1990, stock: 10, image: null }],
  }, sellerToken);
  const spuId = created.body?.data?.id;
  check('发布成功', created.status === 200 && Boolean(spuId), created.body);
  if (!spuId) return 1;

  const detail0 = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  check('新商品版本从 0 起步', detail0?.version === 0, detail0?.version);

  let hist = (await historyOf(spuId, opToken)).body?.data;
  check('历史接口返回 200 且至少一条', Array.isArray(hist) && hist.length === 1, hist);
  let sub = hist?.[0];
  check('首条事件 = SUBMIT（提交审核）', sub?.event === 'SUBMIT' && sub?.eventLabel === '提交审核', sub);
  check('SUBMIT 无「改前审核态」', sub?.fromAuditStatus == null, sub?.fromAuditStatus);
  check('SUBMIT 落到实际审核态', sub?.toAuditStatus === detail0?.auditStatus, { log: sub?.toAuditStatus, spu: detail0?.auditStatus });
  check('SUBMIT 版本 0 → 0', sub?.versionBefore == null && sub?.versionAfter === 0, sub);
  check('SUBMIT 操作人 = 卖家本人', sub?.actorType === 'SELLER' && Boolean(sub?.actorId), sub);
  check('SUBMIT 无字段变更', Array.isArray(sub?.changes) && sub.changes.length === 0, sub?.changes);

  console.log('\n== 2. 编辑内容 → EDIT（字段级 diff） ==');
  const edit = await call('PUT', `/api/seller/products/${spuId}`,
    { title: editedTitle, version: detail0?.version }, sellerToken);
  check('编辑成功', edit.status === 200, edit.body);
  const detail1 = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  check('编辑后版本 +1', detail1?.version === 1, detail1?.version);

  hist = (await historyOf(spuId, opToken)).body?.data;
  check('编辑后历史 2 条（最新在前）', hist?.length === 2, hist?.map((x) => x.event));
  const editLog = hist?.[0];
  check('最新事件 = EDIT', editLog?.event === 'EDIT' && editLog?.eventLabel === '编辑内容', editLog);
  check('EDIT 版本 0 → 1', editLog?.versionBefore === 0 && editLog?.versionAfter === 1, editLog);
  const titleChange = (editLog?.changes ?? []).find((c) => c.field === 'title');
  check('EDIT 带标题变更且改前值是改前值',
    titleChange?.before === originalTitle && titleChange?.after === editedTitle, titleChange);
  check('EDIT 记录操作人类型 SELLER', editLog?.actorType === 'SELLER', editLog?.actorType);

  console.log('\n== 3. 驳回 → REJECT，重新提审 → RESUBMIT ==');
  const rej = await call('POST', `/api/admin/products/${spuId}/audit`,
    { action: 'REJECT', reason: 'A08 冒烟：主图与实物不符', version: detail1?.version }, opToken);
  check('运营驳回成功', rej.status === 200, rej.body);
  hist = (await historyOf(spuId, opToken)).body?.data;
  const rejLog = hist?.[0];
  check('最新事件 = REJECT', rejLog?.event === 'REJECT' && rejLog?.eventLabel === '审核驳回', rejLog);
  check('REJECT 携带驳回原因', rejLog?.reason === 'A08 冒烟：主图与实物不符', rejLog?.reason);
  check('REJECT 操作人类型 OPERATOR、来源 MANUAL',
    rejLog?.actorType === 'OPERATOR' && rejLog?.auditSource === 'MANUAL', rejLog);
  check('REJECT 版本 1 → 2', rejLog?.versionBefore === 1 && rejLog?.versionAfter === 2, rejLog);

  const afterRej = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  const resub = await call('POST', `/api/seller/products/${spuId}/resubmit`, null, sellerToken);
  check('卖家重新提审成功', resub.status === 200, resub.body);
  hist = (await historyOf(spuId, opToken)).body?.data;
  const resubLog = hist?.[0];
  check('最新事件 = RESUBMIT', resubLog?.event === 'RESUBMIT' && resubLog?.eventLabel === '重新提审', resubLog);
  check('RESUBMIT 审核态 REJECTED → PENDING',
    resubLog?.fromAuditStatus === 'REJECTED' && resubLog?.toAuditStatus === 'PENDING', resubLog);
  check('RESUBMIT 已清空审核来源', resubLog?.auditSource == null, resubLog?.auditSource);
  check('RESUBMIT 版本 2 → 3', resubLog?.versionBefore === 2 && resubLog?.versionAfter === 3, resubLog);
  check('重提后商品回到待审', (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data?.auditStatus === 'PENDING');

  console.log('\n== 4. 通过 → APPROVE，读接口边界 ==');
  const detail2 = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  const appr = await call('POST', `/api/admin/products/${spuId}/audit`,
    { action: 'APPROVE', version: detail2?.version }, opToken);
  check('运营通过成功', appr.status === 200, appr.body);
  hist = (await historyOf(spuId, opToken)).body?.data;
  const apprLog = hist?.[0];
  check('最新事件 = APPROVE', apprLog?.event === 'APPROVE' && apprLog?.eventLabel === '审核通过', apprLog);
  check('APPROVE 审核态 PENDING → APPROVED',
    apprLog?.fromAuditStatus === 'PENDING' && apprLog?.toAuditStatus === 'APPROVED', apprLog);
  check('五条事件齐全（SUBMIT/EDIT/REJECT/RESUBMIT/APPROVE）',
    JSON.stringify((hist ?? []).map((x) => x.event).reverse())
      === JSON.stringify(['SUBMIT', 'EDIT', 'REJECT', 'RESUBMIT', 'APPROVE']),
    (hist ?? []).map((x) => x.event));

  const one = (await historyOf(spuId, opToken, '?limit=1')).body?.data;
  check('limit=1 只返回最新一条', Array.isArray(one) && one.length === 1 && one[0]?.event === 'APPROVE', one);
  const capped = (await historyOf(spuId, opToken, '?limit=1000')).body?.data;
  check('limit 超上限不报错（服务端封顶 100）', Array.isArray(capped) && capped.length === 5, capped?.length);

  const forbidden = await historyOf(spuId, sellerToken);
  check('卖家访问审核历史 → 403', forbidden.status === 403, forbidden.status);
  const missing = await historyOf('999999999999999999', opToken);
  check('未知商品 → 404', missing.status === 404, missing.status);

  console.log('\n== 5. 自营商品不进审核时间线 ==');
  const selfCreated = await call('POST', '/api/seller/products', {
    title: `A08 自营商品 ${stamp}`,
    categoryId: CATEGORY,
    brandId: BRAND,
    mainImage: '/img/products/cat-food.svg',
    skus: [{ specName: '1kg', price: 2990, stock: 5, image: null }],
  }, opMerchantToken);
  const selfId = selfCreated.body?.data?.id;
  check('运营创建自营商品成功', selfCreated.status === 200 && Boolean(selfId), selfCreated.body);
  if (selfId) {
    const selfHist = (await historyOf(selfId, opToken)).body?.data;
    check('自营商品审核历史为空数组', Array.isArray(selfHist) && selfHist.length === 0, selfHist);
    check('自营商品无历史行（回库）', sql(`SELECT COUNT(*) FROM t_spu_audit_log WHERE spu_id = ${selfId}`)[0] === '0');
    await call('POST', `/api/seller/products/${selfId}/status`, { action: 'OFF_SHELF' }, opMerchantToken);
  }

  console.log('\n== 6. 回库断言（事件顺序 / actor / seller / diff） ==');
  const dbCount = sql(`SELECT COUNT(*) FROM t_spu_audit_log WHERE spu_id = ${spuId}`)[0];
  check('库内事件数与 API 一致（5 条）', dbCount === '5', dbCount);
  const dbOrder = sql(`SELECT GROUP_CONCAT(event ORDER BY id SEPARATOR ',')
                       FROM t_spu_audit_log WHERE spu_id = ${spuId}`)[0];
  check('库内事件顺序正确', dbOrder === 'SUBMIT,EDIT,REJECT,RESUBMIT,APPROVE', dbOrder);

  const sellerRows = sql(`SELECT actor_type, actor_id, seller_id FROM t_spu_audit_log
                          WHERE spu_id = ${spuId} AND event IN ('SUBMIT','EDIT') ORDER BY id`);
  check('SUBMIT/EDIT 的 actor_id = seller_id（归属只认登录态）',
    sellerRows.length === 2 && sellerRows.every((r) => {
      const [type, actorId, sellerId] = r.split('\t');
      return type === 'SELLER' && actorId === sellerId && Boolean(actorId);
    }), sellerRows);

  const opRows = sql(`SELECT actor_type, actor_id IS NOT NULL, seller_id FROM t_spu_audit_log
                      WHERE spu_id = ${spuId} AND event IN ('REJECT','APPROVE') ORDER BY id`);
  check('REJECT/APPROVE 由运营落章（actor_id 非空）',
    opRows.length === 2 && opRows.every((r) => {
      const [type, hasActor, sellerId] = r.split('\t');
      return type === 'OPERATOR' && hasActor === '1' && sellerId !== '0';
    }), opRows);

  const changesJson = sql(`SELECT changes FROM t_spu_audit_log
                           WHERE spu_id = ${spuId} AND event = 'EDIT'`)[0] ?? '';
  check('库内 EDIT changes 含改前/改后标题',
    changesJson.includes(originalTitle) && changesJson.includes(editedTitle), changesJson.slice(0, 300));

  const versionRow = sql(`SELECT version_before, version_after FROM t_spu_audit_log
                          WHERE spu_id = ${spuId} AND event = 'APPROVE'`)[0];
  check('库内 APPROVE 版本 3 → 4', versionRow === '3\t4', versionRow);

  console.log('\n== 7. 抽检版本快照与漂移提示 ==');
  cleanupSample();
  const currentVersion = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data?.version;
  const sampledVersion = currentVersion - 1;
  sql(`INSERT INTO t_audit_sample
         (id, biz_type, biz_id, seller_id, sample_date, reason, status, spu_version, created_at, updated_at)
       VALUES (${SAMPLE_FIXTURE_ID}, 'SPU', ${spuId}, 0, UTC_DATE(), 'BASE', 'PENDING', ${sampledVersion}, UTC_TIMESTAMP(), UTC_TIMESTAMP())`);
  const sampleList = await call('GET', `/api/admin/audit-sla/samples?status=PENDING&keyword=${encodeURIComponent(editedTitle)}&page=1&pageSize=20`, null, opToken);
  const sample = (sampleList.body?.data?.items ?? []).find((x) => String(x.bizId) === String(spuId));
  check('抽查队列可读到夹具任务', Boolean(sample), sampleList.body);
  check('回带抽中版本与当前版本',
    sample?.sampledVersion === sampledVersion && sample?.currentVersion === currentVersion, sample);
  check('内容已变更 → drift=true', sample?.drift === true, { sampledVersion: sample?.sampledVersion, currentVersion: sample?.currentVersion, drift: sample?.drift });
  check('库内 spu_version 已落快照', sql(`SELECT spu_version FROM t_audit_sample WHERE id = ${SAMPLE_FIXTURE_ID}`)[0] === String(sampledVersion));
  cleanupSample();
  const sampleAfter = await call('GET', `/api/admin/audit-sla/samples?status=PENDING&keyword=${encodeURIComponent(editedTitle)}&page=1&pageSize=20`, null, opToken);
  check('夹具任务已清理', !(sampleAfter.body?.data?.items ?? []).some((x) => String(x.bizId) === String(spuId)));

  console.log('\n== 8. 收尾：下架本轮商品 ==');
  await call('POST', `/api/seller/products/${spuId}/status`, { action: 'OFF_SHELF' }, sellerToken);

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) {
    console.log('失败项：\n - ' + failures.join('\n - '));
    return 1;
  }
  return 0;
}

process.exitCode = await main();

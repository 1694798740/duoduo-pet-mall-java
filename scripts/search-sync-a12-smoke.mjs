/**
 * A-12 · 搜索索引自动同步 冒烟（2026-10-25）。
 *
 * 守五组：
 *  A. 5 秒可见：创建 / 改标题 / 下架 / 上架 / 驳回 / 重提 / 通过，全部 ≤ 5000ms。
 *  B. 删除与恢复：软删后文档摘除、恢复后文档重建（下架态不可搜）、重新上架可搜。
 *  C. 物理清理：库行消失后没有任何 updated_at 可扫，文档必须在清理返回时已摘除。
 *  D. 失败重试：停掉 ES 后改商品，恢复 ES 后变更必须自己补进索引（不手工重建）。
 *  E. 索引自愈：直接删掉 ES 索引后改商品，应用要重建索引并把整库补回。
 *
 * 追加断言：对账报告不再把「软删商品」误报为索引缺失。
 *
 * 前置：容器在跑；incremental/52 已执行；ES（duoduo-es）healthy；
 *       商品容器使用 A-12 构建（DUODUO_SEARCH_SWEEP_MS=3000）。
 * 用法：NO_PROXY="*" node scripts/search-sync-a12-smoke.mjs
 */
import { execFileSync } from 'node:child_process';

const API = 'http://localhost:8080';
const ES = 'http://localhost:9200';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OPERATOR = '13684295254';
const CATEGORY = '101';
const BRAND = '1';
const DB = 'duoduo_product';
const BUDGET_MS = 5000;

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

function sql(query) {
  const out = execFileSync('docker', [
    'exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B',
    '--default-character-set=utf8mb4', '-D', DB, '-e', query,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return out.split(/\r?\n/).filter((line) => line && !line.includes('Warning'));
}

async function es(method, path, body) {
  try {
    const res = await fetch(ES + path, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(5000),
    });
    return { status: res.status, body: await res.json().catch(() => null) };
  } catch (ex) {
    return { status: 0, body: null, error: String(ex) };
  }
}
async function docOf(spuId) {
  const r = await es('GET', `/duoduo-search/_doc/PRODUCT:${spuId}`);
  const src = r.body?._source;
  return {
    http: r.status,
    present: r.status === 200 && Boolean(src),
    title: src?.title,
    status: src?.status,
    audit: src?.auditStatus,
  };
}
async function esProductCount() {
  const r = await es('POST', '/duoduo-search/_count', { query: { term: { docType: 'PRODUCT' } } });
  return r.status === 200 ? r.body?.count : -1;
}
/** 索引里是否还存在某个标题的精确短语（绕开应用侧「严格召回为零后逐字兜底」的干扰） */
async function esTitleHits(title) {
  const r = await es('POST', '/duoduo-search/_search', {
    size: 0,
    query: {
      bool: {
        filter: [{ term: { docType: 'PRODUCT' } }],
        must: [{ match_phrase: { title } }],
      },
    },
    track_total_hits: true,
  });
  return r.status === 200 ? (r.body?.hits?.total?.value ?? 0) : -1;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 轮询直到 ok；返回耗时与最后一次观察值 */
async function waitFor(fn, timeoutMs, intervalMs = 200) {
  const started = Date.now();
  let last;
  while (Date.now() - started <= timeoutMs) {
    last = await fn();
    if (last?.ok) {
      return { ok: true, elapsed: Date.now() - started, value: last.value };
    }
    await sleep(intervalMs);
  }
  return { ok: false, elapsed: Date.now() - started, value: last?.value };
}

async function publicSearch(keyword) {
  return call('GET', `/api/products?keyword=${encodeURIComponent(keyword)}&page=1&pageSize=50`);
}
async function searchHas(keyword, spuId) {
  const r = await publicSearch(keyword);
  return (r.body?.data?.items ?? []).some((x) => String(x.id) === String(spuId));
}
async function sellerDetail(token, spuId) {
  return call('GET', `/api/seller/products/${spuId}`, null, token);
}

/** 创建 → 若命中风险规则落了待审，则人工通过；返回最终详情 */
async function createApproved(sellerToken, opToken, title, subtitle) {
  const created = await call('POST', '/api/seller/products', {
    title,
    subtitle,
    categoryId: CATEGORY,
    brandId: BRAND,
    mainImage: '/img/products/dog-food.svg',
    detail: 'A-12 搜索自动同步冒烟商品',
    skus: [{ specName: '1kg', price: 2590, stock: 7, image: null }],
  }, sellerToken);
  const spuId = created.body?.data?.id;
  if (created.status !== 200 || !spuId) {
    return { spuId: null, created, detail: null };
  }
  let detail = (await sellerDetail(sellerToken, spuId)).body?.data;
  if (detail?.auditStatus === 'PENDING') {
    await call('POST', `/api/admin/products/${spuId}/audit`,
      { action: 'APPROVE', version: detail.version }, opToken);
    detail = (await sellerDetail(sellerToken, spuId)).body?.data;
  }
  return { spuId, created, detail };
}

/** 编辑后若又落回待审（理论上干净内容会自动放行），补一次人工通过 */
async function approveIfPending(sellerToken, opToken, spuId) {
  let detail = (await sellerDetail(sellerToken, spuId)).body?.data;
  if (detail?.auditStatus === 'PENDING') {
    await call('POST', `/api/admin/products/${spuId}/audit`,
      { action: 'APPROVE', version: detail.version }, opToken);
    detail = (await sellerDetail(sellerToken, spuId)).body?.data;
  }
  return detail;
}

async function cleanupStale(opToken) {
  const ids = sql(`SELECT id FROM t_spu WHERE title LIKE 'A12 %'`);
  if (ids.length) {
    sql(`UPDATE t_spu SET deleted_at = UTC_TIMESTAMP() - INTERVAL 31 DAY, status = 'OFF_SHELF'
         WHERE title LIKE 'A12 %'`);
    const purge = await call('POST', '/api/admin/products/recycle/purge', null, opToken);
    console.log(`  [CLEAN] 历史 A12 商品 ${ids.length} 件已清理（purged=${purge.body?.data?.purged}）`);
  } else {
    console.log('  [CLEAN] 无历史 A12 商品');
  }
  return ids;
}

let esStopped = false;
/** 本脚本创建过的全部 SPU id：主夹具会在第 6 组被物理清理，收尾时查不到，必须自己记账 */
const fixtureIds = [];

async function main() {
  console.log('== 0. 前置与登录 ==');
  const health = await es('GET', '/_cluster/health');
  check('ES 可达且健康', health.status === 200 && ['green', 'yellow'].includes(health.body?.status),
    { status: health.status, body: health.body });
  const sellerToken = await tokenOf(SELLER, 'merchant');
  const opToken = await tokenOf(OPERATOR, 'admin');
  check('卖家登录成功', Boolean(sellerToken));
  check('运营登录成功', Boolean(opToken));
  if (!sellerToken || !opToken) return 1;
  await cleanupStale(opToken);
  const baseline = await call('POST', '/api/admin/search/reindex', null, opToken);
  check('基线全量重建成功（消除历史脏文档）',
    baseline.status === 200 && baseline.body?.data?.reindexed === true, baseline.body);

  console.log('\n== 0b. 等待增量扫描接管（滚动发布后选主锁交接）==');
  // 容器刚重建时，上一实例的选主锁可能还没过期。A-12 把日常租约降到 15s 并加了
  // 优雅停机释放，但本脚本不能假设部署方式 —— 先确认「扫描基准真的在前进」再计时，
  // 否则会把「锁等待」误判成「同步慢」。
  const firstStatus = (await call('GET', '/api/admin/search/reconcile?type=product', null, opToken))
    .body?.data?.syncStatus;
  const takeover = await waitFor(async () => {
    const s = (await call('GET', '/api/admin/search/reconcile?type=product', null, opToken))
      .body?.data?.syncStatus;
    return {
      ok: Boolean(firstStatus?.lastSweepAt && s?.bootstrapped && s?.indexReady && s?.lastSweepAt
        && s.lastSweepAt !== firstStatus.lastSweepAt),
      value: s,
    };
  }, 30_000, 3200);
  check('增量扫描已接管（lastSweepAt 前进）', takeover.ok,
    { first: firstStatus?.lastSweepAt, last: takeover.value?.lastSweepAt });

  const stamp = String(Date.now()).slice(-7);
  const title = `A12 自动同步 ${stamp}`;

  console.log('\n== 1. 创建 → 5 秒内可搜 ==');
  const { spuId, created, detail } = await createApproved(sellerToken, opToken, title, 'A-12 创建同步');
  check('发布成功', created.status === 200 && Boolean(spuId), created.body);
  if (!spuId) return 1;
  fixtureIds.push(spuId);
  check('商品在售且已过审', detail?.status === 'ON_SALE' && detail?.auditStatus === 'APPROVED',
    { status: detail?.status, audit: detail?.auditStatus });
  const createdWait = await waitFor(async () => ({ ok: await searchHas(title, spuId) }), BUDGET_MS);
  check(`创建后 ≤${BUDGET_MS}ms 买家可搜（实测 ${createdWait.elapsed}ms）`,
    createdWait.ok && createdWait.elapsed <= BUDGET_MS, createdWait);
  const createdDoc = await docOf(spuId);
  check('ES 文档与库同口径（ON_SALE/APPROVED）',
    createdDoc.present && createdDoc.status === 'ON_SALE' && createdDoc.audit === 'APPROVED', createdDoc);

  console.log('\n== 2. 改标题 → 5 秒内新旧标题切换 ==');
  const renamed = `A12 改名 ${stamp}`;
  let current = (await sellerDetail(sellerToken, spuId)).body?.data;
  const update = await call('PUT', `/api/seller/products/${spuId}`,
    { title: renamed, version: current.version }, sellerToken);
  check('编辑标题成功', update.status === 200, update.body);
  current = await approveIfPending(sellerToken, opToken, spuId);
  check('编辑后仍是已过审在售（干净内容自动放行）',
    current?.auditStatus === 'APPROVED' && current?.status === 'ON_SALE',
    { audit: current?.auditStatus, status: current?.status });
  const renamedWait = await waitFor(async () => ({ ok: await searchHas(renamed, spuId) }), BUDGET_MS);
  check(`改标题后 ≤${BUDGET_MS}ms 新标题可搜（实测 ${renamedWait.elapsed}ms）`,
    renamedWait.ok && renamedWait.elapsed <= BUDGET_MS, renamedWait);
  // 不用公开搜索断言「旧标题搜不到」：应用侧在严格召回为零时会做逐字 OR 兜底，
  // 新副标题里的同字会把商品带回来。A-12 要守的是索引内容，直接在 ES 层验精确短语。
  check('旧标题已从索引移除（ES 精确短语零命中）', (await esTitleHits(title)) === 0);
  const renamedDoc = await docOf(spuId);
  check('ES 文档标题已更新', renamedDoc.title === renamed, renamedDoc);

  console.log('\n== 3. 上下架 → 5 秒内可搜性翻转 ==');
  const off = await call('POST', `/api/seller/products/${spuId}/status`, { action: 'OFF_SHELF' }, sellerToken);
  check('下架成功', off.status === 200 && off.body?.data?.status === 'OFF_SHELF', off.body);
  const offWait = await waitFor(async () => ({ ok: !(await searchHas(renamed, spuId)) }), BUDGET_MS);
  check(`下架后 ≤${BUDGET_MS}ms 从搜索消失（实测 ${offWait.elapsed}ms）`,
    offWait.ok && offWait.elapsed <= BUDGET_MS, offWait);
  const offDoc = await docOf(spuId);
  check('下架后文档仍在且状态为 OFF_SHELF（不是删文档）',
    offDoc.present && offDoc.status === 'OFF_SHELF', offDoc);
  const on = await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, sellerToken);
  check('上架成功', on.status === 200 && on.body?.data?.status === 'ON_SALE', on.body);
  const onWait = await waitFor(async () => ({ ok: await searchHas(renamed, spuId) }), BUDGET_MS);
  check(`上架后 ≤${BUDGET_MS}ms 恢复可搜（实测 ${onWait.elapsed}ms）`,
    onWait.ok && onWait.elapsed <= BUDGET_MS, onWait);

  console.log('\n== 4. 审核态 → 驳回消失 / 重提隐藏 / 通过恢复 ==');
  current = (await sellerDetail(sellerToken, spuId)).body?.data;
  const reject = await call('POST', `/api/admin/products/${spuId}/audit`,
    { action: 'REJECT', reason: 'A-12 冒烟：验证审核态同步', version: current.version }, opToken);
  check('驳回成功', reject.status === 200, reject.body);
  const rejectWait = await waitFor(async () => ({ ok: !(await searchHas(renamed, spuId)) }), BUDGET_MS);
  check(`驳回后 ≤${BUDGET_MS}ms 从搜索消失（实测 ${rejectWait.elapsed}ms）`,
    rejectWait.ok && rejectWait.elapsed <= BUDGET_MS, rejectWait);
  const rejectDoc = await docOf(spuId);
  check('驳回后文档审核态 REJECTED', rejectDoc.present && rejectDoc.audit === 'REJECTED', rejectDoc);

  const resubmit = await call('POST', `/api/seller/products/${spuId}/resubmit`, null, sellerToken);
  check('重新提审成功', resubmit.status === 200 && resubmit.body?.data?.auditStatus === 'PENDING', resubmit.body);
  const pendingDocWait = await waitFor(async () => {
    const d = await docOf(spuId);
    return { ok: d.present && d.audit === 'PENDING', value: d };
  }, BUDGET_MS);
  check(`重提后 ≤${BUDGET_MS}ms 文档回到 PENDING（实测 ${pendingDocWait.elapsed}ms）`,
    pendingDocWait.ok && pendingDocWait.elapsed <= BUDGET_MS, pendingDocWait);
  check('待审商品不可被买家搜到', !(await searchHas(renamed, spuId)));

  current = (await sellerDetail(sellerToken, spuId)).body?.data;
  const approve = await call('POST', `/api/admin/products/${spuId}/audit`,
    { action: 'APPROVE', version: current.version }, opToken);
  check('审核通过成功', approve.status === 200, approve.body);
  const approveWait = await waitFor(async () => ({ ok: await searchHas(renamed, spuId) }), BUDGET_MS);
  check(`通过后 ≤${BUDGET_MS}ms 恢复可搜（实测 ${approveWait.elapsed}ms）`,
    approveWait.ok && approveWait.elapsed <= BUDGET_MS, approveWait);

  console.log('\n== 5. 删除 / 恢复 ==');
  const del = await call('POST', `/api/seller/products/${spuId}/delete`, null, sellerToken);
  check('删除成功', del.status === 200 && Boolean(del.body?.data?.deletedAt), del.body);
  const delDocWait = await waitFor(async () => ({ ok: !(await docOf(spuId)).present }), BUDGET_MS);
  check(`软删后 ≤${BUDGET_MS}ms 文档被摘除（实测 ${delDocWait.elapsed}ms）`,
    delDocWait.ok && delDocWait.elapsed <= BUDGET_MS, delDocWait);
  check('买家搜索不再命中', !(await searchHas(renamed, spuId)));

  const reconcileAfterDelete = await call('GET', '/api/admin/search/reconcile?type=product', null, opToken);
  const report = reconcileAfterDelete.body?.data;
  check('对账报告不把软删商品误报为缺失',
    reconcileAfterDelete.status === 200
      && !(report?.missingInIndex ?? []).map(String).includes(String(spuId))
      && !(report?.extraInIndex ?? []).map(String).includes(String(spuId)),
    { missing: report?.missingInIndex, extra: report?.extraInIndex });

  const restore = await call('POST', `/api/seller/products/${spuId}/restore`, null, sellerToken);
  check('恢复成功且保持下架', restore.status === 200 && restore.body?.data?.status === 'OFF_SHELF', restore.body);
  const restoreDocWait = await waitFor(async () => {
    const d = await docOf(spuId);
    return { ok: d.present && d.status === 'OFF_SHELF', value: d };
  }, BUDGET_MS);
  check(`恢复后 ≤${BUDGET_MS}ms 文档重建为 OFF_SHELF（实测 ${restoreDocWait.elapsed}ms）`,
    restoreDocWait.ok && restoreDocWait.elapsed <= BUDGET_MS, restoreDocWait);
  check('恢复后买家仍搜不到（下架态，不是删除态）', !(await searchHas(renamed, spuId)));

  const relist = await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, sellerToken);
  check('重新上架成功', relist.status === 200, relist.body);
  const relistWait = await waitFor(async () => ({ ok: await searchHas(renamed, spuId) }), BUDGET_MS);
  check(`重新上架后 ≤${BUDGET_MS}ms 恢复可搜（实测 ${relistWait.elapsed}ms）`,
    relistWait.ok && relistWait.elapsed <= BUDGET_MS, relistWait);

  console.log('\n== 6. 物理清理 → 文档立即摘除 ==');
  const delAgain = await call('POST', `/api/seller/products/${spuId}/delete`, null, sellerToken);
  check('再次删除成功', delAgain.status === 200, delAgain.status);
  sql(`UPDATE t_spu SET deleted_at = UTC_TIMESTAMP() - INTERVAL 31 DAY WHERE id = ${spuId}`);
  const purge = await call('POST', '/api/admin/products/recycle/purge', null, opToken);
  check('清理接口成功且带走了该商品',
    purge.status === 200 && (purge.body?.data?.purged ?? 0) >= 1, purge.body);
  check('清理返回时 ES 文档已不存在（无需等下一轮扫描）',
    !(await docOf(spuId)).present, await docOf(spuId));
  check('回库确认商品已物理删除', sql(`SELECT COUNT(*) FROM t_spu WHERE id=${spuId}`)[0] === '0');

  console.log('\n== 7. 失败重试：停 ES → 变更 → 启 ES 自愈 ==');
  const retryTitle = `A12 重试 ${stamp}`;
  const retryFixture = await createApproved(sellerToken, opToken, retryTitle, 'A-12 重试同步');
  const retryId = retryFixture.spuId;
  if (retryId) fixtureIds.push(retryId);
  check('重试夹具创建并已索引', Boolean(retryId)
    && (await waitFor(async () => ({ ok: await searchHas(retryTitle, retryId) }), BUDGET_MS)).ok, retryFixture.created?.body);

  execFileSync('docker', ['stop', 'duoduo-es'], { stdio: 'ignore' });
  esStopped = true;
  const retryRenamed = `A12 重试已改 ${stamp}`;
  const retryDetail = (await sellerDetail(sellerToken, retryId)).body?.data;
  const retryEdit = await call('PUT', `/api/seller/products/${retryId}`,
    { title: retryRenamed, version: retryDetail.version }, sellerToken);
  check('ES 停机期间商品仍可编辑（索引是旁路，不阻塞主链路）', retryEdit.status === 200, retryEdit.body);
  // 留足至少一个失败扫描周期：写入失败必须进重试队列而不是被游标抛下
  await sleep(4500);

  execFileSync('docker', ['start', 'duoduo-es'], { stdio: 'ignore' });
  esStopped = false;
  const esHealthy = await waitFor(async () => {
    const h = await es('GET', '/_cluster/health');
    return { ok: h.status === 200 && ['green', 'yellow'].includes(h.body?.status), value: h.body };
  }, 90_000, 1000);
  check('ES 已恢复健康', esHealthy.ok, esHealthy);

  const retryHealed = await waitFor(async () => {
    const d = await docOf(retryId);
    return { ok: d.present && d.title === retryRenamed, value: d };
  }, 30_000);
  check('ES 恢复后变更被重试队列补进索引（未手工重建）',
    retryHealed.ok, { elapsed: retryHealed.elapsed, doc: retryHealed.value });
  const pendingCleared = await waitFor(async () => {
    const r = await call('GET', '/api/admin/search/reconcile?type=product', null, opToken);
    const s = r.body?.data?.syncStatus;
    return { ok: s?.pendingProducts === 0 && s?.pendingListings === 0, value: s };
  }, 15_000);
  check('待重试队列清零', pendingCleared.ok, pendingCleared.value);

  console.log('\n== 8. 索引被删 → 自动重建并补回整库 ==');
  const selfTitle = `A12 自愈 ${stamp}`;
  const selfFixture = await createApproved(sellerToken, opToken, selfTitle, 'A-12 索引自愈');
  const selfId = selfFixture.spuId;
  if (selfId) fixtureIds.push(selfId);
  const indexed = await waitFor(async () => ({ ok: await searchHas(selfTitle, selfId) }), BUDGET_MS);
  check('自愈夹具已可搜', indexed.ok, indexed);
  const countBefore = await esProductCount();
  check('删除索引前 ES 文档数正常', countBefore > 0, countBefore);

  const drop = await es('DELETE', '/duoduo-search');
  check('ES 索引已删除（模拟数据丢失）', drop.status === 200 || drop.status === 404, drop);
  const selfRenamed = `A12 自愈已改 ${stamp}`;
  const selfDetail = (await sellerDetail(sellerToken, selfId)).body?.data;
  const selfEdit = await call('PUT', `/api/seller/products/${selfId}`,
    { title: selfRenamed, version: selfDetail.version }, sellerToken);
  check('索引缺失时商品仍可编辑', selfEdit.status === 200, selfEdit.body);

  const selfHealed = await waitFor(async () => {
    const d = await docOf(selfId);
    return { ok: d.present && d.title === selfRenamed, value: d };
  }, 20_000);
  check('索引被删后自动重建并把变更补回', selfHealed.ok,
    { elapsed: selfHealed.elapsed, doc: selfHealed.value });
  const countRestored = await waitFor(async () => {
    const n = await esProductCount();
    return { ok: n >= countBefore, value: n };
  }, 20_000);
  check('重建后整库文档数恢复到删除前（全量对齐生效）',
    countRestored.ok, { before: countBefore, after: countRestored.value });

  console.log('\n== 9. 收尾：零残留 ==');
  const staleIds = await cleanupStale(opToken);
  check('A12 商品零残留（含失败重试/自愈夹具）',
    sql(`SELECT COUNT(*) FROM t_spu WHERE title LIKE 'A12 %'`)[0] === '0');
  // 主夹具已在第 6 组物理清理，staleIds 里不会有它 —— 用本脚本的记账 ids 兜住审核历史
  const auditIds = [...new Set([...staleIds, ...fixtureIds].map(String))];
  if (auditIds.length) {
    sql(`DELETE FROM t_spu_audit_log WHERE spu_id IN (${auditIds.join(',')})`);
  }
  check('A12 审核历史零残留',
    sql(`SELECT COUNT(*) FROM t_spu_audit_log WHERE spu_id IN (${auditIds.join(',') || '0'})`)[0] === '0');
  // 自愈夹具与重试夹具不在首个 cleanup 的 id 列表里：统一按 id 再确认一次文档已摘除
  for (const id of [retryId, selfId].filter(Boolean)) {
    const gone = await waitFor(async () => ({ ok: !(await docOf(id)).present }), BUDGET_MS);
    check(`夹具 ${id} 的 ES 文档已摘除`, gone.ok, gone.value);
  }
  check('收尾后 ES 中无 A12 标题文档',
    (await es('POST', '/duoduo-search/_search', {
      size: 0,
      query: { prefix: { title: 'A12 ' } },
      track_total_hits: true,
    })).body?.hits?.total?.value === 0);

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) {
    console.log('失败项：\n - ' + failures.join('\n - '));
    return 1;
  }
  return 0;
}

let exitCode = 1;
try {
  exitCode = await main();
} catch (ex) {
  console.error('冒烟异常终止：', ex);
  exitCode = 1;
} finally {
  if (esStopped) {
    try {
      execFileSync('docker', ['start', 'duoduo-es'], { stdio: 'ignore' });
      console.log('  [CLEAN] 异常退出兜底：已重新启动 duoduo-es');
    } catch (ex) {
      console.error('  [CLEAN] 重启 duoduo-es 失败：', ex.message);
    }
  }
}
process.exitCode = exitCode;

/**
 * A-13 · 商家端批量上下架 UI 验收（2026-10-25）。
 *
 * 打容器产物（merchant.localhost:8091），不是 dev server。覆盖：
 *  ① 搜索后勾选两件商品，出现「已选 N 件（仅当前页）」批量栏；
 *  ② 批量下架走站内 useConfirm（不是原生 confirm），弹窗说明「部分失败不回滚」；
 *  ③ 结果弹窗逐条列出商品与结果，关闭后列表状态已刷新；
 *  ④ 混合「已下架 + 待审核」批量上架：1 成功 1 失败，失败原因直接可读；
 *  ⑤ 全选本页 / 清空选择 / 切换审核筛选会清空选择；
 *  ⑥ 全程无原生弹窗、无页面级 JS 异常。
 *
 * 前置：容器在跑（8091 / 8080）；product 已部署 A-13；卖家 13600003333、
 *       运营 13684295254 可用。
 * 用法：NO_PROXY="*" node scripts/e2e/product-batch-status-a13-ui-check.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const MERCHANT = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const API = 'http://localhost:8080';
const DB = 'duoduo_product';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OPERATOR = '13684295254';

let pass = 0;
const failures = [];
function check(name, ok, got) {
  if (ok) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    failures.push(name);
    console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`.slice(0, 500));
  }
}
const step = (t) => console.log(`\n> ${t}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function call(method, p, body, token, entry) {
  const res = await fetch(API + p, {
    method,
    headers: {
      ...(body ? { 'content-type': 'application/json' } : {}),
      ...(token ? { authorization: 'Bearer ' + token } : {}),
      ...(entry ? { 'x-duoduo-entry': entry } : {}),
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
const detailOf = async (token, spuId) =>
  (await call('GET', `/api/seller/products/${spuId}`, null, token)).body?.data;

const sellerToken = await tokenOf(SELLER, 'merchant');
const opToken = await tokenOf(OPERATOR, 'admin');
if (!sellerToken || !opToken) {
  console.log('登录失败，终止');
  process.exit(1);
}

const stamp = String(Date.now()).slice(-7);
const titleA = `A13 UI 冒烟商品 ${stamp} A`;
const titleB = `A13 UI 冒烟商品 ${stamp} B`;
const titleC = `A13 UI 冒烟商品 ${stamp} C`;
const fixtureIds = [];

async function createProduct(title, suffix) {
  const created = await call('POST', '/api/seller/products', {
    title,
    subtitle: 'A-13 UI 验收' + suffix,
    categoryId: '101',
    brandId: '1',
    mainImage: '/img/products/dog-food.svg',
    detail: 'A-13 UI 验收商品',
    skus: [{ specName: '1kg', price: 2790, stock: 4, image: null }],
  }, sellerToken);
  const id = created.body?.data?.id;
  if (!id) throw new Error('创建夹具失败：' + JSON.stringify(created.body).slice(0, 300));
  fixtureIds.push(String(id));
  return id;
}

async function ensureApprovedOnSale(spuId) {
  let detail = await detailOf(sellerToken, spuId);
  if (detail?.auditStatus === 'PENDING') {
    await call('POST', `/api/admin/products/${spuId}/audit`,
      { action: 'APPROVE', version: detail.version }, opToken);
    detail = await detailOf(sellerToken, spuId);
  }
  if (detail?.status !== 'ON_SALE') {
    await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, sellerToken);
    detail = await detailOf(sellerToken, spuId);
  }
  return detail;
}

const idA = await createProduct(titleA, ' A');
const idB = await createProduct(titleB, ' B');
const idC = await createProduct(titleC, ' C');
const detailA0 = await ensureApprovedOnSale(idA);
const detailB0 = await ensureApprovedOnSale(idB);
const detailC0 = await ensureApprovedOnSale(idC);
// C：驳回 → 重提 = PENDING（前台不可见，但 status 仍是 ON_SALE）
await call('POST', `/api/admin/products/${idC}/audit`,
  { action: 'REJECT', reason: 'A13 UI：待人工复核', version: detailC0.version }, opToken);
await call('POST', `/api/seller/products/${idC}/resubmit`, null, sellerToken);
const detailC = await detailOf(sellerToken, idC);
if (!detailC || detailC.auditStatus !== 'PENDING') {
  console.log('夹具 C 未进入 PENDING，终止：' + JSON.stringify(detailC));
  process.exit(1);
}
console.log(`夹具：A=${idA}(${detailA0.status}) B=${idB}(${detailB0.status}) C=${idC}(${detailC.status}/${detailC.auditStatus})`);

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH ?? undefined, args: ['--no-sandbox'] });

/** 轮询等待某个 locator 的文本匹配（容器接口刷新有延迟，固定 sleep 不可靠） */
async function waitText(locator, pattern, timeout = 15000) {
  const deadline = Date.now() + timeout;
  let last = '';
  while (Date.now() < deadline) {
    last = await locator.innerText().catch(() => '');
    if (pattern.test(last)) return last;
    await sleep(250);
  }
  return last;
}

try {
  const page = await browser.newPage({ viewport: { width: 1500, height: 940 } });
  const errors = [];
  const nativeDialogs = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('dialog', async (d) => {
    nativeDialogs.push(`${d.type()}:${d.message()}`);
    await d.dismiss().catch(() => {});
  });

  step('商家端登录');
  await page.goto(`${MERCHANT}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[placeholder*="手机号"]').fill(SELLER);
  await page.locator('input[type="password"]').fill(PWD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
  check('商家端登录成功', !page.url().includes('/login'), page.url());

  step('进入商品管理并按批次号过滤');
  await page.goto(`${MERCHANT}/seller/products`, { waitUntil: 'networkidle' });
  await page.locator('input[placeholder="搜商品标题"]').fill(stamp);
  await page.waitForURL((u) => u.searchParams.get('keyword') === stamp, { timeout: 10000 });
  const rowA = page.locator('tr', { hasText: titleA }).first();
  const rowB = page.locator('tr', { hasText: titleB }).first();
  const rowC = page.locator('tr', { hasText: titleC }).first();
  await rowA.waitFor({ timeout: 20000 });
  check('三件夹具均出现在筛选结果', (await rowA.count()) === 1 && (await rowB.count()) === 1 && (await rowC.count()) === 1);

  step('勾选 A/B → 批量栏');
  await rowA.getByRole('checkbox').check();
  await rowB.getByRole('checkbox').check();
  check('批量栏显示已选 2 件（仅当前页）', /已选\s*2\s*件（仅当前页）/.test(await page.locator('body').innerText()));
  check('批量栏提供上架 / 下架 / 清空', await page.getByRole('button', { name: '批量上架' }).count() === 1
    && await page.getByRole('button', { name: '批量下架' }).count() === 1
    && await page.getByRole('button', { name: '清空选择' }).count() === 1);

  step('切换审核筛选：选择必须清空');
  await page.getByRole('button', { name: '待审核', exact: true }).click();
  await page.waitForURL(/auditStatus=PENDING/, { timeout: 10000 });
  await page.waitForTimeout(600);
  check('切换筛选后批量栏消失（不会带着上一页的勾选操作）',
    !/已选\s*\d+\s*件/.test(await page.locator('body').innerText()));
  await page.getByRole('button', { name: '全部', exact: true }).click();
  await page.waitForURL((u) => !u.searchParams.get('auditStatus'), { timeout: 10000 });
  await rowA.waitFor({ timeout: 20000 });

  step('全选本页 / 清空选择');
  await page.getByLabel('全选本页').check();
  check('全选后批量栏显示已选 3 件', /已选\s*3\s*件（仅当前页）/.test(await page.locator('body').innerText()));
  await page.getByRole('button', { name: '清空选择' }).click();
  check('清空后批量栏消失', !/已选\s*\d+\s*件/.test(await page.locator('body').innerText()));
  check('清空后行复选框全部取消', (await rowA.getByRole('checkbox').isChecked()) === false
    && (await rowB.getByRole('checkbox').isChecked()) === false
    && (await rowC.getByRole('checkbox').isChecked()) === false);

  step('批量下架：站内确认 → 逐条结果');
  await rowA.getByRole('checkbox').check();
  await rowB.getByRole('checkbox').check();
  await page.getByRole('button', { name: '批量下架' }).click();
  const confirmDialog = page.locator('[role="dialog"]', { hasText: '批量下架' });
  await confirmDialog.waitFor({ timeout: 10000 });
  const confirmText = await confirmDialog.innerText();
  check('确认弹窗说明仅作用于当前页', /仅当前页/.test(confirmText), confirmText.slice(0, 160));
  check('确认弹窗说明部分失败不回滚', /部分失败不会回滚/.test(confirmText), confirmText.slice(0, 200));
  await confirmDialog.getByRole('button', { name: '批量下架' }).click();

  const resultDialog = page.locator('[role="dialog"]', { hasText: '批量操作结果' });
  await resultDialog.waitFor({ timeout: 15000 });
  const resultText = await waitText(resultDialog, /成功\s*2\s*件/);
  check('结果弹窗汇总：2 件全部成功', /共\s*2\s*件：成功\s*2\s*件，失败\s*0\s*件/.test(resultText), resultText.slice(0, 200));
  check('结果逐条列出 A/B 且均为已下架',
    resultText.includes(titleA) && resultText.includes(titleB)
      && (resultText.match(/已下架/g) ?? []).length >= 2, resultText.slice(0, 400));
  await page.screenshot({ path: path.join(here, 'verify-a13-batch-result.png'), fullPage: true });
  await resultDialog.getByRole('button', { name: '知道了' }).click();
  await page.waitForTimeout(400);

  await waitText(rowA, /已下架/);
  check('关闭结果后列表 A 已刷新为已下架', /已下架/.test(await rowA.innerText()), (await rowA.innerText()).slice(0, 120));
  check('关闭结果后列表 B 已刷新为已下架', /已下架/.test(await rowB.innerText()), (await rowB.innerText()).slice(0, 120));
  const afterOffA = await detailOf(sellerToken, idA);
  const afterOffB = await detailOf(sellerToken, idB);
  check('接口回读：A/B 均为 OFF_SHELF 且 version 未变',
    afterOffA?.status === 'OFF_SHELF' && afterOffB?.status === 'OFF_SHELF'
      && String(afterOffA?.version) === String(detailA0.version)
      && String(afterOffB?.version) === String(detailB0.version),
    { A: afterOffA?.status, B: afterOffB?.status });

  step('混合批量上架：1 成功 1 失败，失败原因可读');
  await rowA.getByRole('checkbox').check();
  await rowC.getByRole('checkbox').check();
  await page.getByRole('button', { name: '批量上架' }).click();
  const onConfirm = page.locator('[role="dialog"]', { hasText: '批量上架' });
  await onConfirm.waitFor({ timeout: 10000 });
  check('批量上架确认说明只有过审商品会成功', /审核通过/.test(await onConfirm.innerText()),
    (await onConfirm.innerText()).slice(0, 200));
  await onConfirm.getByRole('button', { name: '批量上架' }).click();

  const onResult = page.locator('[role="dialog"]', { hasText: '批量操作结果' });
  await onResult.waitFor({ timeout: 15000 });
  const onText = await waitText(onResult, /成功\s*1\s*件/);
  check('结果弹窗汇总：成功 1 件 / 失败 1 件',
    /共\s*2\s*件：成功\s*1\s*件，失败\s*1\s*件/.test(onText), onText.slice(0, 220));
  check('结果弹窗说明失败条目不回滚', /不会回滚/.test(onText), onText.slice(0, 260));
  check('失败条目展示待审核原因', onText.includes(titleC)
    && /待审核|审核/.test(onText.slice(onText.indexOf(titleC))), onText.slice(0, 500));
  await onResult.getByRole('button', { name: '知道了' }).click();
  await page.waitForTimeout(400);

  await waitText(rowA, /在售/);
  check('列表 A 已刷新为在售', /在售/.test(await rowA.innerText()), (await rowA.innerText()).slice(0, 120));
  check('列表 C 仍显示待审核', /待审核/.test(await rowC.innerText()), (await rowC.innerText()).slice(0, 120));
  const afterOnA = await detailOf(sellerToken, idA);
  const afterOnC = await detailOf(sellerToken, idC);
  check('接口回读：A 在售、C 仍 PENDING',
    afterOnA?.status === 'ON_SALE' && afterOnC?.auditStatus === 'PENDING',
    { A: afterOnA?.status, C: `${afterOnC?.status}/${afterOnC?.auditStatus}` });
  check('全程无原生 window.confirm / alert', nativeDialogs.length === 0, nativeDialogs);
  check('无页面级 JS 异常', errors.length === 0, errors.slice(0, 2));

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) console.log('失败项：\n - ' + failures.join('\n - '));
} finally {
  await browser.close();
  if (fixtureIds.length) {
    const ids = fixtureIds.join(',');
    sql(`DELETE FROM t_spu_image WHERE spu_id IN (${ids})`);
    sql(`DELETE FROM t_sku WHERE spu_id IN (${ids})`);
    sql(`DELETE FROM t_spu_audit_log WHERE spu_id IN (${ids})`);
    sql(`DELETE FROM t_spu WHERE id IN (${ids})`);
    await call('POST', '/api/admin/search/reindex', null, opToken);
  }
}
process.exitCode = failures.length ? 1 : 0;

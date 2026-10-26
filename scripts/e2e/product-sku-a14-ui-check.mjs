/**
 * A-14 · 商家端规格管理 UI 验收（2026-10-26）。
 *
 * 打容器产物（merchant.localhost:8091），不是 dev server。覆盖：
 *  ① 列表「规格」入口打开弹窗，说明「结构变更重审、只改价/库存不重审」；
 *  ② 真实下单占用一个规格 → 该行标「已有 1 笔订单，不可删除」且删除按钮禁用；
 *  ③ 仅改价：无确认弹窗、toast 明说「不会触发重新审核」、版本与审核态不变；
 *  ④ 新增规格：站内确认（非原生 confirm）说明「重新进入审核」，保存后版本 +1；
 *  ⑤ 改规格名：确认文案点名「改规格名 1 个」，审核历史同时留旧名与新名；
 *  ⑥ 删除无订单规格：确认文案点名「删除 1 个」，回库该行物理删除；
 *  ⑦ 全程无原生弹窗、无页面级 JS 异常。
 *
 * 前置：容器在跑（8091 / 8080）；product / trade 已部署 A-14；卖家 13600003333、
 *       运营 13684295254 可用。
 * 用法：NO_PROXY="*" node scripts/e2e/product-sku-a14-ui-check.mjs
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
const PRODUCT_DB = 'duoduo_product';
const TRADE_DB = 'duoduo_trade';
const USER_DB = 'duoduo_user';
const PWD = 'duoduo12345';
const BUYER_PWD = 'Passw0rd!23';
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
    console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`.slice(0, 600));
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

function sql(db, query) {
  const out = execFileSync('docker', [
    'exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B',
    '--default-character-set=utf8mb4', '-D', db, '-e', query,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return out.split(/\r?\n/).filter((line) => line && !line.includes('Warning'));
}
const count = (db, query) => Number(sql(db, query)[0] ?? 0);
const one = (db, query) => sql(db, query)[0];

const sellerToken = await tokenOf(SELLER, 'merchant');
const opToken = await tokenOf(OPERATOR, 'admin');
if (!sellerToken || !opToken) {
  console.log('登录失败，终止');
  process.exit(1);
}

const stamp = String(Date.now()).slice(-7);
const title = `A14 UI 规格商品 ${stamp}`;
const buyerPhone = '198' + String(Date.now()).slice(-8);
const fixtureIds = [];

async function detailOf(spuId) {
  return (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
}
async function ensureApprovedOnSale(spuId) {
  let detail = await detailOf(spuId);
  if (detail?.auditStatus === 'PENDING') {
    await call('POST', `/api/admin/products/${spuId}/audit`,
      { action: 'APPROVE', version: detail.version }, opToken);
    detail = await detailOf(spuId);
  }
  if (detail?.status !== 'ON_SALE') {
    await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, sellerToken);
    detail = await detailOf(spuId);
  }
  return detail;
}

// ---------- 夹具：2 个规格 + 真实订单占用 5kg ----------
const created = await call('POST', '/api/seller/products', {
  title,
  subtitle: 'A-14 UI 验收',
  categoryId: '101',
  brandId: '1',
  mainImage: '/img/products/dog-food.svg',
  detail: 'A-14 UI 验收商品',
  skus: [
    { specName: '1kg', price: 2990, stock: 5, image: null },
    { specName: '5kg', price: 5990, stock: 3, image: null },
  ],
}, sellerToken);
const spuId = created.body?.data?.id;
if (!spuId) {
  console.log('创建夹具失败：' + JSON.stringify(created.body).slice(0, 300));
  process.exit(1);
}
fixtureIds.push(String(spuId));
let detail = await ensureApprovedOnSale(spuId);
const sku1 = (detail.skus ?? []).find((x) => x.specName === '1kg');
const sku5 = (detail.skus ?? []).find((x) => x.specName === '5kg');
if (!sku1 || !sku5) {
  console.log('夹具规格缺失：' + JSON.stringify(detail.skus));
  process.exit(1);
}

await call('POST', '/api/auth/register',
  { phone: buyerPhone, password: BUYER_PWD, nickname: 'A14 UI 买家' });
const buyerToken = (await call('POST', '/api/auth/login/token',
  { phone: buyerPhone, password: BUYER_PWD })).body?.data?.accessToken;
const address = await call('POST', '/api/addresses', {
  receiverName: 'A14 UI 买家', receiverPhone: buyerPhone,
  province: '广东省', city: '深圳市', district: '南山区',
  detail: '科技园路 14 号', isDefault: true,
}, buyerToken);
const addressId = address.body?.data?.id;
const placed = await call('POST', '/api/orders', {
  addressId, buySkuId: sku5.id, buyQuantity: 1, idempotentKey: 'a14-ui-' + Date.now(),
}, buyerToken);
const orderNo = (placed.body?.data?.orders ?? [])[0]?.orderNo;
if (!buyerToken || !addressId || !orderNo) {
  console.log('下单夹具失败：' + JSON.stringify({ buyerToken: Boolean(buyerToken), addressId, placed: placed.body }).slice(0, 400));
  process.exit(1);
}
console.log(`夹具：spu=${spuId} 1kg=${sku1.id} 5kg=${sku5.id} order=${orderNo}`);

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH ?? undefined, args: ['--no-sandbox'] });

async function specRowByName(dialog, name) {
  const rows = dialog.locator('tbody tr');
  const n = await rows.count();
  for (let i = 0; i < n; i++) {
    const value = await rows.nth(i).locator('input').first().inputValue().catch(() => null);
    if (value === name) return rows.nth(i);
  }
  return null;
}

/** 弹窗内容异步加载：轮询到目标规格行出现（固定 sleep 会偶发拿到空表） */
async function waitSpecRow(dialog, name, timeout = 15000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const found = await specRowByName(dialog, name);
    if (found) return found;
    await sleep(200);
  }
  return null;
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

  step('商家端登录并进入商品管理');
  await page.goto(`${MERCHANT}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[placeholder*="手机号"]').fill(SELLER);
  await page.locator('input[type="password"]').fill(PWD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
  check('商家端登录成功', !page.url().includes('/login'), page.url());
  await page.goto(`${MERCHANT}/seller/products`, { waitUntil: 'networkidle' });
  await page.locator('input[placeholder="搜商品标题"]').fill(stamp);
  await page.waitForURL((u) => u.searchParams.get('keyword') === stamp, { timeout: 10000 });
  const row = page.locator('tr', { hasText: title }).first();
  await row.waitFor({ timeout: 20000 });
  check('夹具出现在商品列表', (await row.count()) === 1);

  step('打开规格弹窗：说明与订单占用标记');
  await row.getByRole('button', { name: '规格', exact: true }).click();
  const specDialog = page.locator('[role="dialog"]', { hasText: '管理规格' });
  await specDialog.waitFor({ timeout: 15000 });
  await specDialog.locator('tbody tr').first().waitFor({ timeout: 15000 });
  const dialogText = await specDialog.innerText();
  check('弹窗说明结构变更会重审', /新增、删除、改规格名都会让/.test(dialogText)
    && /重新进入审核/.test(dialogText), dialogText.slice(0, 200));
  check('弹窗说明只改价 / 库存不重审', /只改价格 \/ 库存不会触发重新审核/.test(dialogText),
    dialogText.slice(0, 260));
  const row1 = await specRowByName(specDialog, '1kg');
  const row5 = await specRowByName(specDialog, '5kg');
  check('两个规格行均显示', Boolean(row1) && Boolean(row5));
  check('有订单的 5kg 行标注「已有 1 笔订单，不可删除」',
    row5 && /已有 1 笔订单，不可删除/.test(await row5.innerText()), row5 ? await row5.innerText() : null);
  check('有订单的 5kg 行删除按钮禁用',
    row5 && await row5.getByRole('button', { name: '删除' }).isDisabled());
  check('无订单的 1kg 行标「可删除」且删除按钮可用',
    row1 && /可删除/.test(await row1.innerText())
      && !(await row1.getByRole('button', { name: '删除' }).isDisabled()),
    row1 ? await row1.innerText() : null);
  await page.screenshot({ path: path.join(here, 'verify-a14-spec-modal.png'), fullPage: true });

  step('仅改价：无确认弹窗、版本与审核态不变');
  const versionBeforePrice = (await detailOf(spuId)).version;
  const auditBeforePrice = (await detailOf(spuId)).auditStatus;
  await row1.locator('input').nth(1).fill('31.90');
  await specDialog.getByRole('button', { name: '保存规格' }).click();
  await sleep(900);
  check('仅改价不弹「保存规格结构」确认',
    (await page.locator('[role="dialog"]', { hasText: '保存规格结构' }).count()) === 0);
  await specDialog.waitFor({ state: 'hidden', timeout: 15000 });
  const priceToast = await page.locator('.toast').filter({ hasText: '不会触发重新审核' })
    .first().waitFor({ timeout: 8000 }).then(() => true).catch(() => false);
  check('toast 明说价格库存变更不重审', priceToast);
  const afterPrice = await detailOf(spuId);
  check('接口回读：1kg 价格已改、版本与审核态不变',
    String(afterPrice.version) === String(versionBeforePrice)
      && afterPrice.auditStatus === auditBeforePrice
      && String((afterPrice.skus ?? []).find((x) => String(x.id) === String(sku1.id))?.price) === '3190',
    { version: afterPrice.version, audit: afterPrice.auditStatus });

  step('新增规格：站内确认 → 版本 +1');
  await row.getByRole('button', { name: '规格', exact: true }).click();
  await specDialog.waitFor({ timeout: 15000 });
  await specDialog.locator('tbody tr').first().waitFor({ timeout: 15000 });
  const versionBeforeAdd = (await detailOf(spuId)).version;
  await specDialog.getByRole('button', { name: '+ 新增规格' }).click();
  const newRow = specDialog.locator('tbody tr').last();
  await newRow.locator('input').nth(0).fill('10kg');
  await newRow.locator('input').nth(1).fill('89.90');
  await newRow.locator('input').nth(2).fill('2');
  await specDialog.getByRole('button', { name: '保存规格' }).click();
  const addConfirm = page.locator('[role="dialog"]', { hasText: '保存规格结构' });
  await addConfirm.waitFor({ timeout: 10000 });
  const addText = await addConfirm.innerText();
  check('确认弹窗点名「新增 1 个」并说明重新审核',
    /新增 1 个/.test(addText) && /重新进入审核/.test(addText), addText.slice(0, 220));
  await page.screenshot({ path: path.join(here, 'verify-a14-spec-confirm.png'), fullPage: true });
  await addConfirm.getByRole('button', { name: '保存并重新提审' }).click();
  await specDialog.waitFor({ state: 'hidden', timeout: 15000 });
  const addToast = await page.locator('.toast').filter({ hasText: '已重新进入审核' })
    .first().waitFor({ timeout: 8000 }).then(() => true).catch(() => false);
  check('新增后 toast 说明已重新进入审核', addToast);
  const afterAdd = await detailOf(spuId);
  check('接口回读：3 个规格、版本 +1',
    (afterAdd.skus ?? []).length === 3 && String(afterAdd.version) === String(versionBeforeAdd + 1),
    { skus: (afterAdd.skus ?? []).length, version: afterAdd.version, was: versionBeforeAdd });

  step('改规格名：确认点名改名、历史留旧名与新名');
  await row.getByRole('button', { name: '规格', exact: true }).click();
  await specDialog.waitFor({ timeout: 15000 });
  const row10 = await waitSpecRow(specDialog, '10kg');
  check('新增的 10kg 规格已出现在弹窗', Boolean(row10));
  if (!row10) throw new Error('10kg 规格行未出现');
  const versionBeforeRename = (await detailOf(spuId)).version;
  await row10.locator('input').first().fill('10kg 装');
  await specDialog.getByRole('button', { name: '保存规格' }).click();
  const renameConfirm = page.locator('[role="dialog"]', { hasText: '保存规格结构' });
  await renameConfirm.waitFor({ timeout: 10000 });
  const renameText = await renameConfirm.innerText();
  check('确认弹窗点名「改规格名 1 个」', /改规格名 1 个/.test(renameText), renameText.slice(0, 220));
  await renameConfirm.getByRole('button', { name: '保存并重新提审' }).click();
  await specDialog.waitFor({ state: 'hidden', timeout: 15000 });
  const afterRename = await detailOf(spuId);
  check('接口回读：规格名已改、版本 +1',
    (afterRename.skus ?? []).some((x) => x.specName === '10kg 装')
      && String(afterRename.version) === String(versionBeforeRename + 1),
    { skus: (afterRename.skus ?? []).map((x) => x.specName), version: afterRename.version });
  const history = one(PRODUCT_DB,
    `SELECT changes FROM t_spu_audit_log WHERE spu_id=${spuId} AND event='EDIT' ORDER BY id DESC LIMIT 1`);
  check('审核历史同时带旧名与新名',
    history?.includes('10kg') && history?.includes('10kg 装'), history);

  step('删除无订单规格：确认点名删除、回库物理删除');
  await row.getByRole('button', { name: '规格', exact: true }).click();
  await specDialog.waitFor({ timeout: 15000 });
  const row10b = await waitSpecRow(specDialog, '10kg 装');
  check('改名后的 10kg 装 规格已出现在弹窗', Boolean(row10b));
  if (!row10b) throw new Error('10kg 装 规格行未出现');
  const versionBeforeDelete = (await detailOf(spuId)).version;
  const sku10Id = String((await detailOf(spuId)).skus.find((x) => x.specName === '10kg 装').id);
  await row10b.getByRole('button', { name: '删除' }).click();
  check('删除后草稿行立即消失', (await specRowByName(specDialog, '10kg 装')) === null);
  await specDialog.getByRole('button', { name: '保存规格' }).click();
  const delConfirm = page.locator('[role="dialog"]', { hasText: '保存规格结构' });
  await delConfirm.waitFor({ timeout: 10000 });
  const delText = await delConfirm.innerText();
  check('确认弹窗点名「删除 1 个」', /删除 1 个/.test(delText), delText.slice(0, 220));
  await delConfirm.getByRole('button', { name: '保存并重新提审' }).click();
  await specDialog.waitFor({ state: 'hidden', timeout: 15000 });
  const afterDelete = await detailOf(spuId);
  check('接口回读：2 个规格、版本 +1',
    (afterDelete.skus ?? []).length === 2 && String(afterDelete.version) === String(versionBeforeDelete + 1),
    { skus: (afterDelete.skus ?? []).length, version: afterDelete.version });
  check('回库：被删规格行已物理删除',
    count(PRODUCT_DB, `SELECT COUNT(*) FROM t_sku WHERE id=${sku10Id}`) === 0);
  check('回库：有订单的 5kg 规格仍在',
    count(PRODUCT_DB, `SELECT COUNT(*) FROM t_sku WHERE id=${sku5.id}`) === 1);

  check('全程无原生 window.confirm / prompt / alert', nativeDialogs.length === 0, nativeDialogs);
  check('无页面级 JS 异常', errors.length === 0, errors.slice(0, 2));

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) console.log('失败项：\n - ' + failures.join('\n - '));
} finally {
  await browser.close();
  const buyerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}'`);
  const orderIds = buyerId ? sql(TRADE_DB, `SELECT id FROM t_order WHERE user_id=${buyerId}`) : [];
  if (orderIds.length) {
    const list = orderIds.join(',');
    sql(TRADE_DB, `DELETE FROM t_stock_freeze WHERE order_id IN (${list})`);
    sql(TRADE_DB, `DELETE FROM t_payment WHERE order_id IN (${list})`);
    sql(TRADE_DB, `DELETE FROM t_order_status_log WHERE order_id IN (${list})`);
    sql(TRADE_DB, `DELETE FROM t_order_item WHERE order_id IN (${list})`);
    sql(TRADE_DB, `DELETE FROM t_order WHERE id IN (${list})`);
  }
  if (fixtureIds.length) {
    const ids = fixtureIds.join(',');
    sql(PRODUCT_DB, `DELETE FROM t_spu_image WHERE spu_id IN (${ids})`);
    sql(PRODUCT_DB, `DELETE FROM t_sku WHERE spu_id IN (${ids})`);
    sql(PRODUCT_DB, `DELETE FROM t_spu_audit_log WHERE spu_id IN (${ids})`);
    sql(PRODUCT_DB, `DELETE FROM t_spu WHERE id IN (${ids})`);
  }
  if (buyerId) {
    sql(TRADE_DB, `DELETE FROM t_cart_item WHERE user_id=${buyerId}`);
    sql(USER_DB, `DELETE FROM t_user_address WHERE user_id=${buyerId}`);
    sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${buyerId}`);
    sql(USER_DB, `DELETE FROM t_user WHERE id=${buyerId}`);
  }
  await call('POST', '/api/admin/search/reindex', null, opToken);
}
process.exitCode = failures.length ? 1 : 0;

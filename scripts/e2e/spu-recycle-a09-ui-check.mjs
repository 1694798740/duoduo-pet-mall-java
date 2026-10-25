/**
 * A-09 · 商家端删除 / 回收站 / 恢复 UI 验收（2026-10-25）。
 *
 * 打容器产物（merchant.localhost:8091），不是 dev server。覆盖：
 *  ① 删除入口用的是站内弹窗（useConfirm），不是原生 window.confirm；
 *  ② 取消 = 不删；确认后商品从「我的商品」消失、进入「回收站」；
 *  ③ 回收站列显示删除时间 / 预计清理 / 删除前审核态，且不出现「发布商品」与审核筛选；
 *  ④ 恢复后商品回到「我的商品」且为下架态，回收站不再显示；
 *  ⑤ 全程无页面级 JS 异常、无原生弹窗。
 *
 * 前置：容器在跑（8091 / 8080）；卖家 13600003333、运营 13684295254 存在。
 * 用法：NO_PROXY="*" node scripts/e2e/spu-recycle-a09-ui-check.mjs
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
    console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`.slice(0, 400));
  }
}
const step = (t) => console.log(`\n> ${t}`);

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

const sellerToken = await tokenOf(SELLER, 'merchant');
const opToken = await tokenOf(OPERATOR, 'admin');
if (!sellerToken || !opToken) {
  console.log('登录失败，终止');
  process.exit(1);
}

const stamp = String(Date.now()).slice(-7);
const title = `A09 UI 冒烟商品 ${stamp}`;
const created = await call('POST', '/api/seller/products', {
  title,
  subtitle: 'A-09 UI 验收',
  categoryId: '101',
  brandId: '1',
  mainImage: '/img/products/dog-food.svg',
  detail: 'A-09 UI 验收商品',
  skus: [{ specName: '1kg', price: 2790, stock: 4, image: null }],
}, sellerToken);
const spuId = created.body?.data?.id;
if (!spuId) {
  console.log('创建 UI 冒烟商品失败：' + JSON.stringify(created.body).slice(0, 300));
  process.exit(1);
}
if (created.body?.data?.auditStatus === 'PENDING') {
  const detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  await call('POST', `/api/admin/products/${spuId}/audit`,
    { action: 'APPROVE', version: detail?.version }, opToken);
}
// 确保在售（E28 自动放行可能直接 ON_SALE；若被规则置下架则补一次上架）
const before = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
if (before?.status !== 'ON_SALE') {
  await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, sellerToken);
}

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH ?? undefined, args: ['--no-sandbox'] });

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

  step('我的商品：删除弹窗（取消不删）');
  await page.goto(`${MERCHANT}/seller/products`, { waitUntil: 'networkidle' });
  const row = page.locator('tr', { hasText: title }).first();
  await row.waitFor({ timeout: 20000 });
  check('商品出现在「我的商品」', (await row.count()) === 1);
  await row.getByRole('button', { name: '删除' }).click();
  const dialog = page.locator('[role="dialog"]');
  await dialog.waitFor({ timeout: 10000 });
  const dialogText = await dialog.innerText();
  check('删除是站内弹窗（标题「删除商品」）', /删除商品/.test(dialogText), dialogText.slice(0, 80));
  check('弹窗说明 30 天内可恢复', /30 天内.*恢复/.test(dialogText), dialogText.slice(0, 160));
  check('弹窗说明恢复后需重新上架', /重新上架/.test(dialogText), dialogText.slice(0, 160));
  await dialog.getByRole('button', { name: '取消' }).click();
  await page.waitForTimeout(600);
  check('取消后弹窗关闭', (await page.locator('[role="dialog"]').count()) === 0);
  check('取消后商品仍在列表', (await page.locator('tr', { hasText: title }).count()) === 1);

  step('确认删除 → 回收站');
  const rowAgain = page.locator('tr', { hasText: title }).first();
  await rowAgain.getByRole('button', { name: '删除' }).click();
  await page.locator('[role="dialog"]').waitFor({ timeout: 10000 });
  await page.locator('[role="dialog"]').getByRole('button', { name: '移入回收站' }).click();
  // 等列表重新拉取：先等行消失，再确认接口事实
  await page.locator('tr', { hasText: title }).first().waitFor({ state: 'detached', timeout: 20000 });
  const afterDelete = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken));
  check('删除后商家详情 404（已进回收站）', afterDelete.status === 404, afterDelete.status);

  step('回收站视图');
  await page.getByRole('button', { name: '回收站' }).click();
  await page.waitForURL(/deleted=1/, { timeout: 10000 });
  const recycleRow = page.locator('tr', { hasText: title }).first();
  await recycleRow.waitFor({ timeout: 20000 });
  check('回收站显示该商品', (await recycleRow.count()) === 1);
  const headText = await page.locator('table thead').innerText();
  check('回收站表头含 删除时间 / 预计清理 / 删除前审核态',
    ['删除时间', '预计清理', '删除前审核态'].every((k) => headText.includes(k)), headText.replace(/\s+/g, ' '));
  check('回收站不显示「发布商品」', (await page.getByRole('button', { name: '+ 发布商品' }).count()) === 0);
  check('回收站不显示审核筛选标签', (await page.getByRole('button', { name: '待审核', exact: true }).count()) === 0);
  check('回收站行有「恢复」按钮', (await recycleRow.getByRole('button', { name: '恢复' }).count()) === 1);
  await page.screenshot({ path: path.join(here, 'verify-a09-recycle.png'), fullPage: true });

  step('恢复');
  await recycleRow.getByRole('button', { name: '恢复' }).click();
  await page.locator('tr', { hasText: title }).first().waitFor({ state: 'detached', timeout: 20000 });
  const afterRestore = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  check('恢复后为下架态', afterRestore?.status === 'OFF_SHELF', afterRestore?.status);
  check('恢复后审核态保持 APPROVED', afterRestore?.auditStatus === 'APPROVED', afterRestore?.auditStatus);

  step('回到我的商品');
  await page.getByRole('button', { name: '我的商品' }).click();
  await page.waitForURL((u) => !u.searchParams.get('deleted'), { timeout: 10000 });
  const restoredRow = page.locator('tr', { hasText: title }).first();
  await restoredRow.waitFor({ timeout: 20000 });
  check('恢复后商品回到「我的商品」', (await restoredRow.count()) === 1);
  check('恢复后展示为下架', (await restoredRow.innerText()).includes('已下架'), (await restoredRow.innerText()).slice(0, 120));
  check('全程无原生 window.confirm / alert', nativeDialogs.length === 0, nativeDialogs);
  check('无页面级 JS 异常', errors.length === 0, errors.slice(0, 2));

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) console.log('失败项：\n - ' + failures.join('\n - '));
} finally {
  await browser.close();
  // 收尾：删除 → 置为超期 → 走系统清理，避免每次运行都留一件下架商品
  await call('POST', `/api/seller/products/${spuId}/delete`, null, sellerToken);
  sql(`UPDATE t_spu SET deleted_at = UTC_TIMESTAMP() - INTERVAL 31 DAY WHERE id = ${spuId}`);
  await call('POST', '/api/admin/products/recycle/purge', null, opToken);
}
process.exitCode = failures.length ? 1 : 0;

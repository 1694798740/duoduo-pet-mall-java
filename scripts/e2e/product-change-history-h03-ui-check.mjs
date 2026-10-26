/**
 * H-03 · 商品变更历史独立页 · UI 自测（**自包含**）
 *
 * 覆盖：
 *   S1 夹具：卖家提交商品 + 编辑标题（SUBMIT / EDIT）；
 *   S2 运营登录后台 →「变更历史」页渲染，按商品 ID 查到记录；
 *   S3 行内显示商品标题 / 事件 / 操作人 / 版本，点「查看改动」弹出改前改后；
 *   S4 事件筛选 EDIT 仍有记录、切到 APPROVE 显示空态；
 *   S5 0 pageerror / 0 5xx；收尾删除商品与审核历史。
 *
 * 前置：容器在跑；product / admin-web 已部署 H-03；卖家与运营账号可用。
 */

import path from 'node:path';
import { shotsDir } from '../lib/e2e-shots.mjs';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const ADMIN_UI = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? undefined;
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OPERATOR = '13684295254';
const SHOTS = shotsDir();
fs.mkdirSync(SHOTS, { recursive: true });

let pass = 0;
const failures = [];
function check(name, ok, got) {
  if (ok) { pass++; console.log(`  [PASS] ${name}`); }
  else { failures.push(name); console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`); }
}
const step = (t) => console.log(`\n> ${t}`);

async function call(method, p, body, token, entry) {
  const res = await fetch(GW + p, {
    method,
    headers: {
      ...(body ? { 'content-type': 'application/json' } : {}),
      ...(token ? { authorization: 'Bearer ' + token } : {}),
      ...(entry ? { 'X-Duoduo-Entry': entry } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const raw = await res.text();
  let json = null; try { json = JSON.parse(raw); } catch { /* raw */ }
  return { status: res.status, data: json?.data, json };
}
const tokenOf = async (phone, entry) =>
  (await call('POST', '/api/auth/login/token', { phone, password: PWD }, null, entry)).data?.accessToken;

const stamp = String(Date.now()).slice(-7);
const originalTitle = `H03 UI 变更历史 ${stamp}`;
const editedTitle = `H03 UI 变更历史 ${stamp}（改标题）`;

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
const page = await ctx.newPage();
const consoleErrors = [];
const httpErrors = [];
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
page.on('response', (r) => { if (r.status() >= 500) httpErrors.push(`${r.status()} ${r.url()}`); });

let spuId = null;
try {
  step('S1 夹具：提交 + 编辑');
  const sellerToken = await tokenOf(SELLER, 'merchant');
  check('卖家登录成功', Boolean(sellerToken));
  const created = await call('POST', '/api/seller/products', {
    title: originalTitle,
    subtitle: 'H-03 UI 自测',
    categoryId: '101',
    brandId: '1',
    mainImage: '/img/products/dog-food.svg',
    detail: 'H-03 UI 自测商品',
    skus: [{ specName: '默认规格', price: 1280, stock: 5, image: null }],
  }, sellerToken);
  spuId = created.data?.id;
  const detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).data;
  const edited = await call('PUT', `/api/seller/products/${spuId}`,
    { title: editedTitle, version: detail?.version ?? 0 }, sellerToken);
  check('商品提交 + 编辑成功', Boolean(spuId) && edited.status === 200, edited.json);

  step('S2 后台变更历史页 + 按商品 ID 查询');
  await page.goto(`${ADMIN_UI}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[placeholder*="手机号"]').fill(OPERATOR);
  await page.locator('input[type="password"]').fill(PWD);
  await page.getByRole('button', { name: /登录/ }).click();
  await page.waitForURL((u) => !String(u).includes('/login'), { timeout: 15000 });
  await page.goto(`${ADMIN_UI}/admin/product-history`, { waitUntil: 'networkidle' });
  await page.getByText('商品变更历史', { exact: true }).first().waitFor({ timeout: 10000 });
  check('导航含「变更历史」入口',
    await page.getByRole('link', { name: '变更历史' }).isVisible());
  await page.locator('input[placeholder="雪花 ID（可空）"]').fill(spuId);
  await page.locator('input[placeholder="如：冻干 / 猫粮"]').fill(`H03 UI 变更历史 ${stamp}`);
  await page.getByRole('button', { name: '查询' }).click();
  const row = page.getByRole('row', { name: new RegExp(editedTitle) }).first();
  await row.waitFor({ timeout: 10000 });
  check('查到该商品记录（商品标题回填）', await row.getByText(editedTitle).first().isVisible());
  check('行内展示「编辑内容」事件与操作人', await row.getByText('编辑内容').isVisible()
    && await row.getByText(/卖家/).first().isVisible());
  await page.screenshot({ path: `${SHOTS}/h03_change_history.png` });

  step('S3 查看改动：改前 / 改后');
  await row.getByRole('button', { name: '查看改动' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.waitFor({ timeout: 5000 });
  const dialogText = await dialog.innerText();
  check('弹窗含改前 / 改后区块', dialogText.includes('改前') && dialogText.includes('改后'));
  check('★ 标题改前值是旧标题、改后是新标题',
    dialogText.includes(originalTitle) && dialogText.includes(editedTitle), dialogText.slice(0, 300));
  await page.screenshot({ path: `${SHOTS}/h03_change_detail.png` });
  await dialog.getByRole('button', { name: '关闭' }).click();
  await dialog.waitFor({ state: 'detached', timeout: 5000 });

  step('S4 事件筛选');
  const eventSelect = page.locator('.field', { hasText: '事件' }).locator('select');
  await eventSelect.selectOption('EDIT');
  await row.waitFor({ timeout: 10000 });
  check('筛选 EDIT 仍有记录', await row.isVisible());
  await eventSelect.selectOption('APPROVE');
  await page.getByText('没有匹配的变更记录').waitFor({ timeout: 10000 });
  check('筛选 APPROVE 显示空态', true);

  step('S5 无 pageerror / 5xx');
  check('无 pageerror / 5xx',
    httpErrors.length === 0 && !consoleErrors.some((e) => e.startsWith('pageerror:')),
    { http: httpErrors, console: consoleErrors });
} finally {
  await browser.close();
  step('收尾：删除商品 / SKU / 图片 / 审核历史');
  if (spuId) {
    await mysql('duoduo_product', `DELETE FROM t_spu_audit_log WHERE spu_id=${spuId};`);
    await mysql('duoduo_product', `DELETE FROM t_spu_image WHERE spu_id=${spuId};`);
    await mysql('duoduo_product', `DELETE FROM t_sku WHERE spu_id=${spuId};`);
    await mysql('duoduo_product', `DELETE FROM t_spu WHERE id=${spuId};`);
    const remain = (await mysql('duoduo_product',
      `SELECT COUNT(*) FROM t_spu WHERE id=${spuId};`)).trim();
    check('夹具商品已清理', remain === '0', remain);
  }
}

console.log(`\n=== H-03 变更历史 UI 自测：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log('  - ' + f);
}
process.exit(failures.length ? 1 : 0);

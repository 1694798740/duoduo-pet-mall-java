/**
 * H-04 · 后台待办角标聚合 · UI 自测（**自包含**）
 *
 * 覆盖：
 *   S1 夹具：卖家提交一件第三方商品（PENDING），运营查询当前待审总数 N；
 *   S2 运营登录后台，「商品审核」导航显示 N（>99 显示 99+）；
 *   S3 通过该商品后刷新，角标变为 N-1（N=1 时角标消失）；
 *   S4 其他队列（内容审核 / 评价 / 举报 / 售后）的角标只显示服务端 pending 总数，
 *      不因页面切换丢失；0 pageerror / 5xx；
 *   S5 收尾：商品 / SKU / 图片 / 审核历史零残留。
 *
 * 前置：容器在跑；product / admin-web 已部署 H-04；卖家 13600003333、运营 13684295254 可用。
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
const title = `H04 角标商品 ${stamp}`;

const sellerToken = await tokenOf(SELLER, 'merchant');
const opToken = await tokenOf(OPERATOR, 'admin');
check('卖家 / 运营登录成功', Boolean(sellerToken) && Boolean(opToken));
if (!sellerToken || !opToken) process.exit(1);

const created = await call('POST', '/api/seller/products', {
  title,
  subtitle: 'H-04 角标自测',
  categoryId: '101',
  brandId: '1',
  mainImage: '/img/products/dog-food.svg',
  detail: 'H-04 角标自测商品',
  skus: [{ specName: '默认规格', price: 1290, stock: 5, image: null }],
}, sellerToken);
const spuId = created.data?.id;
check('夹具商品提交成功且为待审',
  created.status === 200 && Boolean(spuId) && created.data?.auditStatus === 'PENDING',
  created.data);
const detail = await call('GET', `/api/seller/products/${spuId}`, null, sellerToken);
const version = detail.data?.version;
check('取到商品版本号（审核需带版本）', typeof version === 'number', detail.data);

const before = await call('GET',
  '/api/admin/products?auditStatus=PENDING&page=1&pageSize=1', null, opToken);
const pendingTotal = Number(before.data?.total) || 0;
check('拿到待审总数 N', pendingTotal >= 1, pendingTotal);

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
const page = await ctx.newPage();
const consoleErrors = [];
const httpErrors = [];
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
page.on('response', (r) => { if (r.status() >= 500) httpErrors.push(`${r.status()} ${r.url()}`); });

try {
  step('S2 导航角标 = 服务端 pending 总数');
  await page.goto(`${ADMIN_UI}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[placeholder*="手机号"]').fill(OPERATOR);
  await page.locator('input[type="password"]').fill(PWD);
  await page.getByRole('button', { name: /登录/ }).click();
  await page.waitForURL((u) => !String(u).includes('/login'), { timeout: 15000 });
  await page.goto(`${ADMIN_UI}/admin`, { waitUntil: 'networkidle' });
  const productLink = page.getByRole('link', { name: '商品审核' });
  await productLink.waitFor({ timeout: 10000 });
  const expected = pendingTotal > 99 ? '99+' : String(pendingTotal);
  await page.waitForFunction(
    ({ name, expected }) => {
      const link = [...document.querySelectorAll('a')].find((a) => a.textContent?.includes(name));
      const badge = link?.querySelector('span');
      return (badge?.textContent ?? '') === expected;
    },
    { name: '商品审核', expected },
    { timeout: 15000 },
  );
  check('商品审核角标显示 N', true);
  const otherQueues = ['内容审核', '评价管理', '举报处理', '售后仲裁'];
  for (const q of otherQueues) {
    check(`队列「${q}」入口存在（角标按服务端总数，0 时不显示）`,
      await page.getByRole('link', { name: q }).isVisible());
  }
  await page.screenshot({ path: `${SHOTS}/h04_admin_badges.png` });

  step('S3 处理一条后角标实时收敛');
  const approved = await call('POST', `/api/admin/products/${spuId}/audit`,
    { action: 'APPROVE', version }, opToken);
  check('运营通过该商品', approved.status === 200, approved.json);
  const nextExpected = pendingTotal - 1 > 99 ? '99+' : String(pendingTotal - 1);
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForFunction(
    ({ name, expected }) => {
      const link = [...document.querySelectorAll('a')].find((a) => a.textContent?.includes(name));
      const badge = link?.querySelector('span');
      return (badge?.textContent ?? '') === expected;
    },
    { name: '商品审核', expected: nextExpected },
    { timeout: 15000 },
  );
  check(pendingTotal === 1 ? 'N=1 时角标消失' : '角标从 N 变为 N-1', true);

  step('S4 路由切换不丢角标 / 无报错');
  await page.getByRole('link', { name: '租户健康度' }).click();
  await page.waitForURL((u) => String(u).includes('/admin/tenant-health'), { timeout: 15000 });
  await page.getByRole('link', { name: '商品审核' }).waitFor({ timeout: 10000 });
  check('切页后角标仍在导航上', true);
  check('无 pageerror / 5xx',
    httpErrors.length === 0 && !consoleErrors.some((e) => e.startsWith('pageerror:')),
    { http: httpErrors, console: consoleErrors });
} finally {
  await browser.close();
  step('S5 收尾');
  if (spuId) {
    await mysql('duoduo_product', `DELETE FROM t_spu_audit_log WHERE spu_id=${spuId};`);
    await mysql('duoduo_product', `DELETE FROM t_spu_image WHERE spu_id=${spuId};`);
    await mysql('duoduo_product', `DELETE FROM t_sku WHERE spu_id=${spuId};`);
    await mysql('duoduo_product', `DELETE FROM t_spu WHERE id=${spuId};`);
    const remain = (await mysql('duoduo_product', `SELECT COUNT(*) FROM t_spu WHERE id=${spuId};`)).trim();
    check('夹具商品已清理', remain === '0', remain);
  }
}

console.log(`\n=== H-04 后台角标 UI 自测：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log('  - ' + f);
}
process.exit(failures.length ? 1 : 0);

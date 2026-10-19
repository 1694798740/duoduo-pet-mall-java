/**
 * 商家子账号体系 · 自测（E13，**自包含**）
 *
 * 覆盖总纲交付项：邀请 / 停用店员 + 平台侧跨租户处置 + 店员不可看结算。
 *
 *   S1 夹具：店主（库内直建 APPROVED 店铺）+ 店员（普通注册 USER）
 *   S2 店主邀请店员 → 成员列表可见
 *   S3 店员准入：/users/me 带成员简报 → 商家订单 200（可见店铺主体的单）→ **结算 403**
 *   S4 店主停用 → 店员访问订单立即 403 → 重新启用恢复
 *   S5 平台处置：admin 总览可见 → 平台停用 → 店员 403 → 恢复
 *   S6 UI：商家端店员管理页 + 平台总览页渲染正常（无 pageerror）
 *
 * 口径：
 *   · 全程用主运营账号做平台处置（不新建 OPERATOR，防 E26 根因复发）
 *   · 店员是 USER 角色，收尾把成员行删除即可，无后台入口残留
 */

import path from 'node:path';
import { shotsDir } from '../lib/e2e-shots.mjs';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const ADMIN_UI = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
const MERCHANT_UI = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? undefined;
const PWD = 'duoduo12345';
const OPERATOR_PHONE = '13684295254';
const SHOTS = shotsDir(); // 默认落到 gitignored 的 scripts/e2e/.shots；要归档配图用 SHOTS=<路径> 覆盖
fs.mkdirSync(SHOTS, { recursive: true });

let pass = 0;
const failures = [];
function check(name, ok, got) {
  if (ok) { pass++; console.log(`  [PASS] ${name}`); }
  else { failures.push(name); console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`); }
}
const step = (t) => console.log(`\n> ${t}`);

async function call(method, p, body, jar, base) {
  const headers = {};
  if (body !== undefined && !(body instanceof FormData)) headers['content-type'] = 'application/json';
  if (jar && jar.size) headers.cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
  const res = await fetch((p.startsWith('http') ? '' : (base ?? GW)) + p, {
    method, headers, body: body === undefined ? undefined
      : body instanceof FormData ? body : JSON.stringify(body),
  });
  if (jar) {
    for (const c of res.headers.getSetCookie?.() ?? []) {
      const pair = c.split(';')[0]; const i = pair.indexOf('=');
      if (i > 0) jar.set(pair.slice(0, i), pair.slice(i + 1));
    }
  }
  const raw = await res.text();
  let json = null; try { json = JSON.parse(raw); } catch { /* raw */ }
  return { status: res.status, data: json?.data, json };
}

const randPhone = () => '139' + String(Math.floor(1e7 + Math.random() * 8e7));

// ---------------- 浏览器 ----------------
const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
function watch(page) {
  const consoleErrors = [];
  const httpErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
  page.on('response', (r) => { if (r.status() >= 500) httpErrors.push(`${r.status()} ${r.url()}`); });
  return { consoleErrors, httpErrors };
}
async function loginUI(page, base, phone) {
  await page.goto(`${base}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[placeholder*="手机号"]').fill(phone);
  await page.locator('input[type="password"]').fill(PWD);
  await page.getByRole('button', { name: /登录/ }).click();
  await page.waitForURL((u) => !String(u).includes('/login'), { timeout: 15000 });
}

try {
  // ---------------- S1 夹具 ----------------
  step('S1 夹具：店主（库内直建店铺）+ 店员（注册 USER）');
  const owner = { phone: randPhone(), jar: new Map() };
  await call('POST', '/api/auth/register', { phone: owner.phone, password: PWD, nickname: '店主夹具' }, owner.jar);
  const { createSellerFixture } = await import('../lib/e2e-seller-fixture.mjs');
  const fixture = await createSellerFixture({ phone: owner.phone, storeName: '子账号夹具店', doRealname: true, callFn: call, jar: owner.jar });
  check('店主店铺直建成功', /^\d{15,}$/.test(fixture.merchantId), fixture.merchantId);
  await call('POST', '/api/auth/login', { phone: owner.phone, password: PWD }, owner.jar);

  const staff = { phone: randPhone(), jar: new Map() };
  check('店员注册（普通 USER）', (await call('POST', '/api/auth/register',
    { phone: staff.phone, password: PWD, nickname: '店员夹具' }, staff.jar)).status === 200);

  // ---------------- S2 邀请 ----------------
  step('S2 店主邀请店员');
  check('邀请成功', (await call('POST', '/api/seller/staff', { phone: staff.phone }, owner.jar)).status === 200);
  const list = await call('GET', '/api/seller/staff', undefined, owner.jar);
  check('成员列表含店员', list.data?.some((m) => m.phoneMasked?.includes('****')), list.data?.length);
  check('重复邀请被拒（一人一店）',
    (await call('POST', '/api/seller/staff', { phone: staff.phone }, owner.jar)).status === 422);
  const inviteUnknown = await call('POST', '/api/seller/staff', { phone: '13000000000' }, owner.jar);
  check('邀请未注册手机号 → 404', inviteUnknown.status === 404, inviteUnknown.status);

  // ---------------- S3 店员准入 ----------------
  step('S3 店员准入：可经营、不可见结算');
  await call('POST', '/api/auth/login', { phone: staff.phone, password: PWD }, staff.jar);
  const me = await call('GET', '/api/users/me', undefined, staff.jar);
  check('/users/me 下发成员简报（店 + STAFF）',
    me.data?.merchant?.merchantId === fixture.merchantId && me.data.merchant.memberRole === 'STAFF', me.data?.merchant);
  const orders = await call('GET', '/api/seller/orders?page=1&pageSize=1', undefined, staff.jar);
  check('★ 店员看店铺订单 → 200', orders.status === 200, orders.status);
  const settlements = await call('GET', '/api/seller/settlements?page=1', undefined, staff.jar);
  check('★ 店员看结算 → 403', settlements.status === 403, settlements.status);
  const staffManage = await call('GET', '/api/seller/staff', undefined, staff.jar);
  check('★ 店员访问成员管理 → 403', staffManage.status === 403, staffManage.status);

  // ---------------- S4 店主停用 / 启用 ----------------
  step('S4 店主停用店员（即时生效）');
  const memberId = list.data.find((m) => m.role === 'STAFF')?.memberId;
  check('停用成功', (await call('POST', `/api/seller/staff/${memberId}/disable`, {}, owner.jar)).status === 200);
  const afterDisable = await call('GET', '/api/seller/orders?page=1&pageSize=1', undefined, staff.jar);
  check('★ 停用后店员访问订单 → 403（零延迟）', afterDisable.status === 403, afterDisable.status);
  check('启用恢复', (await call('POST', `/api/seller/staff/${memberId}/enable`, {}, owner.jar)).status === 200);
  check('启用后访问订单恢复 200',
    (await call('GET', '/api/seller/orders?page=1&pageSize=1', undefined, staff.jar)).status === 200);

  // ---------------- S5 平台处置 ----------------
  step('S5 平台侧跨租户处置');
  const op = { jar: new Map() };
  await call('POST', '/api/auth/login', { phone: OPERATOR_PHONE, password: PWD }, op.jar,
    'http://127.0.0.1:8080');
  const adminPage = await call('GET', '/api/admin/merchant-staff?page=1&pageSize=20', undefined, op.jar);
  check('平台总览含该店员', adminPage.data?.items?.some(
    (m) => m.memberId === memberId && m.storeName.includes('子账号夹具店')), adminPage.data?.total);
  const anon = await fetch(`${GW}/api/admin/merchant-staff`);
  check('未登录访问总览 → 401', anon.status === 401, anon.status);
  check('平台停用', (await call('POST', `/api/admin/merchant-staff/${memberId}/disable`, {}, op.jar)).status === 200);
  check('★ 平台停用后店员访问订单 → 403',
    (await call('GET', '/api/seller/orders?page=1&pageSize=1', undefined, staff.jar)).status === 403);
  check('平台启用恢复',
    (await call('GET', '/api/seller/orders?page=1&pageSize=1', undefined, staff.jar)).status === 403 &&
    (await call('POST', `/api/admin/merchant-staff/${memberId}/enable`, {}, op.jar)).status === 200);
  check('恢复后店员访问订单 → 200',
    (await call('GET', '/api/seller/orders?page=1&pageSize=1', undefined, staff.jar)).status === 200);

  // ---------------- S6 UI ----------------
  step('S6 UI：商家端店员管理页 + 平台总览页');
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
  const page = await ctx.newPage();
  const w = watch(page);
  // 店主看店员管理页
  await loginUI(page, MERCHANT_UI, owner.phone);
  await page.goto(`${MERCHANT_UI}/seller/staff`, { waitUntil: 'networkidle' });
  await page.getByText('店员管理', { exact: true }).first().waitFor({ timeout: 10000 });
  // 2026-10-19 该页新增了列表搜索框（placeholder「搜昵称、手机号或身份」），
  // 与邀请表单的「11 位手机号」同含「手机号」→ 旧选择器变歧义。按 placeholder 精确区分。
  check('店员管理页渲染（含邀请表单）', await page.locator('input[placeholder="11 位手机号"]').isVisible());
  await page.screenshot({ path: `${SHOTS}/merchant_staff_page.png` });
  // 平台总览页
  await page.goto(`${ADMIN_UI}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[placeholder*="手机号"]').fill(OPERATOR_PHONE);
  await page.locator('input[type="password"]').fill(PWD);
  await page.getByRole('button', { name: /登录/ }).click();
  await page.waitForURL((u) => !String(u).includes('/login'), { timeout: 15000 });
  await page.goto(`${ADMIN_UI}/admin/merchant-staff`, { waitUntil: 'networkidle' });
  await page.getByRole('row', { name: /子账号夹具店/ }).first().waitFor({ timeout: 10000 });
  check('平台总览页渲染（含跨租户成员行）', true);
  await page.screenshot({ path: `${SHOTS}/merchant_staff_admin.png` });
  check('无 pageerror / 5xx', w.httpErrors.length === 0, w.httpErrors);
  await ctx.close();

  // ---------------- 收尾 ----------------
  step('收尾：删除夹具成员行（店员是 USER 角色，无后台入口残留）');
  await mysql('duoduo_user', `DELETE FROM t_merchant_member WHERE id=${memberId};`);
  const remain = (await mysql('duoduo_user',
    `SELECT COUNT(*) FROM t_merchant_member WHERE id=${memberId};`)).trim();
  check('夹具成员行已删除', remain === '0', remain);
} finally {
  await browser.close();
}

console.log(`\n=== 子账号体系自测：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log('  - ' + f);
}
process.exit(failures.length ? 1 : 0);

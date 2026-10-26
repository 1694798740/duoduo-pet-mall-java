/**
 * F-04 · 店员权限细分 · UI 自测（**自包含**）
 *
 * 覆盖：
 *   S1 夹具：店主（库内直建 APPROVED 店铺）+ 店员（普通注册 USER）+ 邀请；
 *   S2 店员管理页：新店员默认全量权限，店主行显示「全部权限」；
 *   S3 权限弹窗：清空后只勾「订单发货」→ 保存 → 表格与库内都只剩 ORDER_SHIP；
 *   S4 再次打开弹窗「全选」→ 保存 → 恢复 6 项；
 *   S5 无 pageerror / 5xx；收尾删除夹具成员 / 店铺 / 临时账号。
 *
 * 前置：容器在跑；user/merchant-web 已部署 F-04；incremental/59 已执行。
 */

import path from 'node:path';
import { shotsDir } from '../lib/e2e-shots.mjs';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const MERCHANT_UI = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? undefined;
const PWD = 'duoduo12345';
const SHOTS = shotsDir();
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
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (jar && jar.size) headers.cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
  const res = await fetch((p.startsWith('http') ? '' : (base ?? GW)) + p, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
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

const randPhone = () => '138' + String(Math.floor(1e7 + Math.random() * 8e7));

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

let owner = null;
let staff = null;
let fixture = null;
let memberId = null;

try {
  step('S1 夹具：店主店铺 + 店员邀请');
  owner = { phone: randPhone(), jar: new Map() };
  await call('POST', '/api/auth/register', { phone: owner.phone, password: PWD, nickname: 'F04店主' }, owner.jar);
  const { createSellerFixture } = await import('../lib/e2e-seller-fixture.mjs');
  fixture = await createSellerFixture({
    phone: owner.phone, storeName: 'F04权限夹具店', doRealname: true, callFn: call, jar: owner.jar,
  });
  check('店主店铺直建成功', /^\d{15,}$/.test(fixture.merchantId), fixture.merchantId);
  await call('POST', '/api/auth/login', { phone: owner.phone, password: PWD }, owner.jar);

  staff = { phone: randPhone(), jar: new Map() };
  check('店员注册（普通 USER）', (await call('POST', '/api/auth/register',
    { phone: staff.phone, password: PWD, nickname: 'F04店员' }, staff.jar)).status === 200);
  const invite = await call('POST', '/api/seller/staff', { phone: staff.phone }, owner.jar);
  memberId = invite.data?.memberId;
  check('邀请店员成功（默认全量权限）',
    invite.status === 200 && (invite.data?.permissions ?? []).length === 6, invite.data);

  step('S2 店员管理页：默认全量 + 店主行全部权限');
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
  const page = await ctx.newPage();
  const w = watch(page);
  await loginUI(page, MERCHANT_UI, owner.phone);
  await page.goto(`${MERCHANT_UI}/seller/staff`, { waitUntil: 'networkidle' });
  await page.getByText('店员管理', { exact: true }).first().waitFor({ timeout: 10000 });
  const staffRow = page.getByRole('row', { name: /F04店员/ }).first();
  await staffRow.waitFor({ timeout: 10000 });
  check('新店员行显示全部 6 项权限',
    (await staffRow.getByText('商品内容编辑').isVisible())
      && (await staffRow.getByText('客服回复').isVisible()), '权限列');
  const ownerRow = page.getByRole('row', { name: /F04店主/ }).first();
  check('店主行显示「全部权限」', await ownerRow.getByText('全部权限').isVisible());
  await page.screenshot({ path: `${SHOTS}/f04_staff_permissions.png` });

  step('S3 权限弹窗：清空后只勾「订单发货」');
  await staffRow.getByRole('button', { name: '权限' }).click();
  await page.getByRole('dialog').waitFor({ timeout: 5000 });
  check('权限弹窗渲染 6 个复选项',
    (await page.getByRole('dialog').locator('input[type="checkbox"]').count()) === 6);
  await page.getByRole('button', { name: '清空（仅查看）' }).click();
  await page.getByRole('dialog').locator('label', { hasText: '订单发货' }).locator('input').check();
  await page.getByRole('button', { name: '保存权限' }).click();
  await page.getByRole('dialog').waitFor({ state: 'detached', timeout: 10000 });
  await page.getByText('权限已更新，立即生效').waitFor({ timeout: 5000 }).catch(() => {});
  await staffRow.getByText('订单发货').waitFor({ timeout: 5000 });
  const csv = (await mysql('duoduo_user',
    `SELECT IFNULL(permissions,'NULL') FROM t_merchant_member WHERE id=${memberId};`)).trim();
  check('★ 保存后表格显示「订单发货」，库内 CSV = ORDER_SHIP', csv === 'ORDER_SHIP', csv);
  check('表格不再显示被取消的「商品内容编辑」',
    !(await staffRow.getByText('商品内容编辑').isVisible().catch(() => false)));
  await page.screenshot({ path: `${SHOTS}/f04_staff_permissions_saved.png` });

  step('S4 再次打开弹窗：全选恢复');
  await staffRow.getByRole('button', { name: '权限' }).click();
  await page.getByRole('dialog').waitFor({ timeout: 5000 });
  await page.getByRole('button', { name: '全选' }).click();
  await page.getByRole('button', { name: '保存权限' }).click();
  await page.getByRole('dialog').waitFor({ state: 'detached', timeout: 10000 });
  const csvAll = (await mysql('duoduo_user',
    `SELECT IFNULL(permissions,'NULL') FROM t_merchant_member WHERE id=${memberId};`)).trim();
  check('★ 全选后恢复 6 项权限',
    csvAll === 'PRODUCT_EDIT,PRODUCT_PRICE,PRODUCT_STATUS,ORDER_SHIP,ORDER_REFUND,SUPPORT_REPLY', csvAll);
  // 登录页在未登录时会先探测 /users/me 得到 401，属于正常流程；这里只看 5xx 与 pageerror。
  check('无 pageerror / 5xx',
    w.httpErrors.length === 0 && !w.consoleErrors.some((e) => e.startsWith('pageerror:')),
    { http: w.httpErrors, console: w.consoleErrors });
  await ctx.close();
} finally {
  await browser.close();
  step('收尾：删除夹具成员 / 店铺 / 临时账号');
  if (memberId) await mysql('duoduo_user', `DELETE FROM t_merchant_member WHERE id=${memberId};`);
  if (fixture) {
    await mysql('duoduo_user', `DELETE FROM t_merchant_member WHERE merchant_id=${fixture.merchantId};`);
    await mysql('duoduo_user', `DELETE FROM t_merchant WHERE id=${fixture.merchantId};`);
  }
  for (const u of [owner, staff]) {
    if (!u?.phone) continue;
    await mysql('duoduo_user', `DELETE FROM t_refresh_token WHERE user_id=(SELECT id FROM t_user WHERE phone='${u.phone}');`);
    await mysql('duoduo_user', `DELETE FROM t_user WHERE phone='${u.phone}';`);
  }
  const remain = (await mysql('duoduo_user',
    `SELECT COUNT(*) FROM t_user WHERE phone IN ('${owner?.phone ?? ''}','${staff?.phone ?? ''}');`)).trim();
  check('夹具账号已清理', remain === '0', remain);
}

console.log(`\n=== F-04 店员权限 UI 自测：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log('  - ' + f);
}
process.exit(failures.length ? 1 : 0);

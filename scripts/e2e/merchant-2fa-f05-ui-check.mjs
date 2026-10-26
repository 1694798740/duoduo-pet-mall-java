/**
 * F-05 · 商家端两步验证（2FA）· UI 自测（**自包含**）
 *
 * 覆盖：
 *   S1 夹具：临时店主（库内直建 APPROVED 店铺），商家端正常登录；
 *   S2 账号中心出现「账号安全 · 两步验证」，开始绑定 → 从 otpauth 链接取密钥 → 确认启用；
 *   S3 恢复码一次性展示，点「我已保存」后状态变为「已启用」；
 *   S4 退出后重新登录：密码正确 → 出现「该账号已启用两步验证」与验证码输入框；
 *      错误验证码被拒，正确验证码登录成功；
 *   S5 后台账号安全页仍正常渲染（共享面板回归）；
 *   S6 0 pageerror / 0 5xx；收尾清空 2FA / 恢复码 / 店铺 / 临时账号。
 *
 * 前置：容器在跑；user 已部署 E21（/api/auth/2fa）；merchant-web / admin-web 为最新产物。
 */

import path from 'node:path';
import crypto from 'node:crypto';
import { shotsDir } from '../lib/e2e-shots.mjs';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const MERCHANT_UI = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const ADMIN_UI = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
const MALL_UI = process.env.MALL_UI_BASE ?? 'http://localhost:8088';
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? undefined;
const PWD = 'duoduo12345';
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

async function call(method, p, body, jar) {
  const headers = {};
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (jar && jar.size) headers.cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
  const res = await fetch(GW + p, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  if (jar) for (const c of res.headers.getSetCookie?.() ?? []) {
    const pair = c.split(';')[0]; const i = pair.indexOf('=');
    if (i > 0) jar.set(pair.slice(0, i), pair.slice(i + 1));
  }
  const raw = await res.text();
  let json = null; try { json = JSON.parse(raw); } catch { /* raw */ }
  return { status: res.status, data: json?.data, json };
}
const randPhone = () => '137' + String(Math.floor(1e7 + Math.random() * 8e7));

/** Base32 → Buffer（RFC 4648，忽略填充） */
function base32Decode(input) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0;
  let value = 0;
  const out = [];
  for (const ch of String(input).replace(/=+$/g, '').toUpperCase()) {
    const idx = alphabet.indexOf(ch);
    if (idx < 0) continue;
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

/** RFC 6238 TOTP（30 秒 / 6 位 / SHA1）；stepOffset=+1 用于避开启用时已消耗的步号 */
function totp(secret, stepOffset = 0) {
  const stepNo = Math.floor(Date.now() / 1000 / 30) + stepOffset;
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(stepNo));
  const mac = crypto.createHmac('sha1', base32Decode(secret)).update(msg).digest();
  const offset = mac[mac.length - 1] & 0x0f;
  const binary = ((mac[offset] & 0x7f) << 24)
    | ((mac[offset + 1] & 0xff) << 16)
    | ((mac[offset + 2] & 0xff) << 8)
    | (mac[offset + 3] & 0xff);
  return String(binary % 1_000_000).padStart(6, '0');
}

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

let ownerPhone = null;
let ownerId = null;
let merchantId = null;
let secret = null;
let filterRecoveryCodes = [];

try {
  step('S1 夹具：临时店主 + 店铺');
  ownerPhone = randPhone();
  const jar = new Map();
  await call('POST', '/api/auth/register', { phone: ownerPhone, password: PWD, nickname: 'F05店主' }, jar);
  const { createSellerFixture } = await import('../lib/e2e-seller-fixture.mjs');
  const fixture = await createSellerFixture({
    phone: ownerPhone, storeName: 'F05两步验证店', doRealname: true, callFn: call, jar,
  });
  merchantId = fixture.merchantId;
  await call('POST', '/api/auth/login', { phone: ownerPhone, password: PWD }, jar);
  const me = await call('GET', '/api/users/me', undefined, jar);
  ownerId = me.data?.id;
  check('临时店主与店铺就绪', Boolean(ownerId) && /^\d{15,}$/.test(merchantId), { ownerId, merchantId });

  step('S2 商家端账号中心：开始绑定两步验证');
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1200 }, locale: 'zh-CN' });
  const page = await ctx.newPage();
  const w = watch(page);
  await loginUI(page, MERCHANT_UI, ownerPhone);
  await page.goto(`${MERCHANT_UI}/seller/account`, { waitUntil: 'networkidle' });
  await page.getByText('账号安全 · 两步验证', { exact: true }).waitFor({ timeout: 10000 });
  check('账号中心出现两步验证面板',
    await page.getByText(/当前状态：/).first().isVisible());
  await page.getByRole('button', { name: '开始绑定' }).click();
  const uri = await page.locator('code', { hasText: 'otpauth://' }).innerText({ timeout: 10000 });
  secret = /secret=([A-Z2-7]+)/.exec(uri)?.[1] ?? '';
  check('拿到 otpauth 密钥', secret.length >= 16, uri);
  await page.screenshot({ path: `${SHOTS}/f05_merchant_2fa_setup.png` });

  step('S3 输入动态码启用 + 保存恢复码');
  await page.locator('input[placeholder="6 位数字"]').fill(totp(secret));
  await page.getByRole('button', { name: '确认绑定' }).click();
  await page.getByText('请立即保存这些恢复码').waitFor({ timeout: 10000 });
  filterRecoveryCodes = await page.getByText(/^[A-Z2-7]{4}-[A-Z2-7]{4}$/).allInnerTexts();
  check('绑定成功并一次性展示恢复码', filterRecoveryCodes.length >= 5, filterRecoveryCodes);
  await page.screenshot({ path: `${SHOTS}/f05_merchant_2fa_codes.png` });
  await page.getByRole('button', { name: '我已保存' }).click();
  await page.getByText('已启用', { exact: false }).first().waitFor({ timeout: 5000 });
  check('状态变为「已启用」', true);
  const enabledInDb = (await mysql('duoduo_user',
    `SELECT CONCAT(totp_enabled,'/',IF(totp_bound_at IS NULL,'NO','YES')) FROM t_user WHERE id=${ownerId};`)).trim();
  check('回库：totp_enabled=1 且已记录绑定时间', enabledInDb === '1/YES', enabledInDb);

  step('S4 退出后重新登录：必须输入验证码');
  await page.getByRole('button', { name: '退出登录' }).click();
  await page.waitForURL((u) => String(u).includes('/login'), { timeout: 15000 });
  await page.locator('input[placeholder*="手机号"]').fill(ownerPhone);
  await page.locator('input[type="password"]').fill(PWD);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByText('该账号已启用两步验证').waitFor({ timeout: 10000 });
  check('登录页切到第二步（出现验证码输入框）',
    await page.locator('input[placeholder="请输入 6 位数字"]').isVisible());
  await page.locator('input[placeholder="请输入 6 位数字"]').fill('000000');
  await page.getByRole('button', { name: '验证并登录' }).click();
  await page.getByRole('alert').waitFor({ timeout: 10000 });
  check('错误验证码被拒且停在登录页',
    String(page.url()).includes('/login') && await page.getByRole('alert').isVisible(),
    await page.getByRole('alert').innerText());
  // 启用时已消耗当前步；用下一步的码（服务端容忍 ±1 步）
  await page.locator('input[placeholder="请输入 6 位数字"]').fill(totp(secret, 1));
  await page.getByRole('button', { name: '验证并登录' }).click();
  await page.waitForURL((u) => !String(u).includes('/login'), { timeout: 15000 });
  check('★ 正确验证码登录成功进入工作台', String(page.url()).includes('/seller'), page.url());
  const w2 = watch(page);
  await page.goto(`${MERCHANT_UI}/seller/account`, { waitUntil: 'networkidle' });
  check('登录后账号中心仍显示「已启用」',
    await page.getByText('已启用', { exact: false }).first().isVisible());
  check('商家端全程无 pageerror / 5xx',
    w.httpErrors.length === 0 && w2.httpErrors.length === 0
      && ![...w.consoleErrors, ...w2.consoleErrors].some((e) => e.startsWith('pageerror:')),
    { http: [...w.httpErrors, ...w2.httpErrors], console: [...w.consoleErrors, ...w2.consoleErrors] });
  await ctx.close();

  step('S4b 商城端：同一账号的 2FA 同样生效（用一枚恢复码）');
  const mallCtx = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN' });
  const mall = await mallCtx.newPage();
  const wm = watch(mall);
  await mall.goto(`${MALL_UI}/login`, { waitUntil: 'networkidle' });
  await mall.locator('input[placeholder="11 位手机号"]').fill(ownerPhone);
  await mall.locator('input[type="password"]').fill(PWD);
  await mall.getByRole('button', { name: '登录', exact: true }).click();
  await mall.getByText('该账号已启用两步验证').waitFor({ timeout: 10000 });
  check('商城登录也切到第二步（账号级 2FA）', true);
  await mall.getByRole('button', { name: /改用恢复码/ }).click();
  await mall.locator('input[placeholder*="恢复码"]').fill(filterRecoveryCodes[0] ?? '');
  await mall.getByRole('button', { name: '验证并登录' }).click();
  await mall.waitForURL((u) => !String(u).includes('/login'), { timeout: 15000 });
  check('★ 恢复码可在商城端完成登录', !String(mall.url()).includes('/login'), mall.url());
  check('商城端无 pageerror / 5xx',
    wm.httpErrors.length === 0 && !wm.consoleErrors.some((e) => e.startsWith('pageerror:')),
    { http: wm.httpErrors, console: wm.consoleErrors });
  await mallCtx.close();

  step('S5 后台账号安全页回归（共享面板）');
  const adminCtx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
  const adminPage = await adminCtx.newPage();
  const wa = watch(adminPage);
  await loginUI(adminPage, ADMIN_UI, OPERATOR);
  await adminPage.goto(`${ADMIN_UI}/admin/account-security`, { waitUntil: 'networkidle' });
  await adminPage.getByText('账号安全 · 两步验证', { exact: true }).waitFor({ timeout: 10000 });
  await adminPage.getByText(/当前状态：/).first().waitFor({ timeout: 10000 });
  check('后台面板渲染并读到当前状态', true);
  check('后台无 pageerror / 5xx',
    wa.httpErrors.length === 0 && !wa.consoleErrors.some((e) => e.startsWith('pageerror:')),
    { http: wa.httpErrors, console: wa.consoleErrors });
  await adminCtx.close();
} finally {
  await browser.close();
  step('收尾：清空 2FA / 恢复码 / 店铺 / 临时账号');
  if (ownerId) {
    await mysql('duoduo_user', `DELETE FROM t_totp_recovery_code WHERE user_id=${ownerId};`);
    await mysql('duoduo_user',
      `UPDATE t_user SET totp_secret=NULL, totp_enabled=0, totp_bound_at=NULL, totp_last_step=NULL WHERE id=${ownerId};`);
    await mysql('duoduo_user', `DELETE FROM t_audit_log WHERE target_id=${ownerId};`);
    await mysql('duoduo_user', `DELETE FROM t_merchant_member WHERE user_id=${ownerId};`);
  }
  if (merchantId) await mysql('duoduo_user', `DELETE FROM t_merchant WHERE id=${merchantId};`);
  if (ownerId) {
    await mysql('duoduo_user', `DELETE FROM t_refresh_token WHERE user_id=${ownerId};`);
    await mysql('duoduo_user', `DELETE FROM t_user WHERE id=${ownerId};`);
  }
  if (ownerId) {
    const remain = (await mysql('duoduo_user', `SELECT COUNT(*) FROM t_user WHERE id=${ownerId};`)).trim();
    const codes = (await mysql('duoduo_user', `SELECT COUNT(*) FROM t_totp_recovery_code WHERE user_id=${ownerId};`)).trim();
    check('临时账号与恢复码已清理', remain === '0' && codes === '0', { remain, codes });
  }
}

console.log(`\n=== F-05 商家端两步验证 UI 自测：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log('  - ' + f);
}
process.exit(failures.length ? 1 : 0);

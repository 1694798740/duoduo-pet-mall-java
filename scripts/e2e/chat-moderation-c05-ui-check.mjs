/**
 * C-05 · 聊天内容质检 UI 验收（2026-10-26）。
 *
 * 商家工作台客服会话：
 *  ① 正常回复可发出并出现在气泡里；
 *  ② 含站外链接的回复被服务端 422 拦截，页面出现可读提示，消息不落库、不出现气泡；
 *  ③ 拦截后继续发正常内容不受影响；
 *  ④ 全程 0 原生弹窗、0 pageerror；夹具零残留。
 *
 * 前置：容器在跑（8091 / 8080）；social 与商家端已部署 C-05；卖家 13600003333 可用。
 * 用法：NO_PROXY="*" node scripts/e2e/chat-moderation-c05-ui-check.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { shotsDir } from '../lib/e2e-shots.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const MERCHANT = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const SOCIAL_DB = 'duoduo_social';
const USER_DB = 'duoduo_user';

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

async function call(method, url, body, token) {
  const res = await fetch(API + url, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}
const tokenOf = async (phone) =>
  (await call('POST', '/api/auth/login/token', { phone, password: PWD })).body?.data?.accessToken;

function sql(db, query) {
  const out = execFileSync('docker', [
    'exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B',
    '--default-character-set=utf8mb4', '-D', db, '-e', query,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return out.split(/\r?\n/).filter((line) => line && !line.includes('Warning'));
}
const one = (db, query) => sql(db, query)[0] ?? '';
const count = (db, query) => Number(sql(db, query)[0] ?? 0);

const stamp = String(Date.now()).slice(-7);
const suffix = String(Date.now()).slice(-8);
const buyerPhone = `187${suffix}`;
const buyerName = `C05UI聊天买家${stamp}`;

const ownerToken = await tokenOf(SELLER);
check('卖家登录成功', Boolean(ownerToken));
if (!ownerToken) process.exit(1);
const ownerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${SELLER}'`);

// ---------------- 1. 夹具 ----------------
step('夹具：买家 + 客服会话 + 一条初始消息');
await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: buyerName });
const buyerToken = await tokenOf(buyerPhone);
const buyerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}'`);
const session = await call('GET', `/api/support/session?sellerId=${ownerId}`, null, buyerToken);
const sessionId = session.body?.data?.id;
await call('POST', '/api/support/messages',
  { sellerId: ownerId, content: `C05 UI 冒烟 ${stamp} 请问什么时候发货` }, buyerToken);
check('客服会话就绪', Boolean(sessionId) && Boolean(buyerId), session.body);

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH ?? undefined, args: ['--no-sandbox'] });
const nativeDialogs = [];
const pageErrors = [];
const watch = (page) => {
  page.on('dialog', async (d) => {
    nativeDialogs.push(`${d.type()}:${d.message()}`);
    await d.dismiss().catch(() => {});
  });
  page.on('pageerror', (e) => pageErrors.push(e.message));
};

try {
  step('商家工作台：正常回复 → 链接被拦截 → 再发正常内容');
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
  const page = await ctx.newPage();
  watch(page);
  await page.goto(`${MERCHANT}/login`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('11 位手机号').fill(SELLER);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });

  await page.goto(`${MERCHANT}/seller/support`, { waitUntil: 'networkidle' });
  const item = page.locator('.sup-item', { hasText: buyerName });
  await item.waitFor({ timeout: 20000 });
  await item.click();
  await page.locator('.sup-thread-head', { hasText: buyerName }).waitFor({ timeout: 20000 });

  const input = page.getByPlaceholder('回复买家…');
  await input.fill('好的，今天下午安排发货');
  await page.getByRole('button', { name: '回复', exact: true }).click();
  await page.getByText('好的，今天下午安排发货', { exact: false }).waitFor({ timeout: 20000 });
  check('正常回复发出并出现在气泡中', true);

  const before = count(SOCIAL_DB, `SELECT COUNT(*) FROM t_support_message WHERE session_id=${sessionId}`);
  await input.fill('去 www.evil.com 下单更便宜');
  await page.getByRole('button', { name: '回复', exact: true }).click();
  const warning = page.getByText(/站外链接/, { exact: false });
  await warning.waitFor({ timeout: 20000 });
  check('链接回复出现可读拦截提示', /站外链接/.test(await warning.innerText()));
  const bodyText = await page.locator('body').innerText();
  check('被拦截内容没有出现在气泡里', !bodyText.includes('www.evil.com'));
  const after = count(SOCIAL_DB, `SELECT COUNT(*) FROM t_support_message WHERE session_id=${sessionId}`);
  check('被拦截回复未落库（消息数不变）', after === before, { before, after });

  await input.fill('明天上午发出，请留意物流');
  await page.getByRole('button', { name: '回复', exact: true }).click();
  await page.getByText('明天上午发出，请留意物流', { exact: false }).waitFor({ timeout: 20000 });
  check('拦截后仍可正常发送', true);
  check('回库：正常回复 2 条 + 买家 1 条 = 3',
    count(SOCIAL_DB, `SELECT COUNT(*) FROM t_support_message WHERE session_id=${sessionId}`) === 3);

  step('前端异常检查与截图');
  const shot = process.env.C05_UI_SHOT ?? path.join(shotsDir(), 'verify-c05-chat-moderation.png');
  await page.screenshot({ path: shot, fullPage: true });
  console.log(`  [截图] ${shot}`);
  check('全程无原生 window.confirm/prompt/alert', nativeDialogs.length === 0, nativeDialogs);
  check('无页面级 JS 异常', pageErrors.length === 0, pageErrors);
} finally {
  await browser.close();
  sql(SOCIAL_DB, `DELETE FROM t_support_ticket_log WHERE session_id=${sessionId}`);
  sql(SOCIAL_DB, `DELETE FROM t_support_message WHERE session_id=${sessionId}`);
  sql(SOCIAL_DB, `DELETE FROM t_support_session WHERE id=${sessionId}`);
  for (const uid of [buyerId]) {
    sql(USER_DB, `DELETE FROM t_merchant_member WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_user WHERE id=${uid}`);
  }
  check('会话 / 消息 / 临时买家零残留',
    count(SOCIAL_DB, `SELECT COUNT(*) FROM t_support_session WHERE id=${sessionId}`) === 0
      && count(SOCIAL_DB, `SELECT COUNT(*) FROM t_support_message WHERE session_id=${sessionId}`) === 0
      && count(USER_DB, `SELECT COUNT(*) FROM t_user WHERE id=${buyerId}`) === 0);
}

console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exitCode = 1;
}

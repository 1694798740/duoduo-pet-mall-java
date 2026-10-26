/**
 * C-04 · 客服工单 UI 验收（2026-10-26）。
 *
 * 商家工作台（店员登录）：
 *  ① 客服会话行显示「未认领 / 处理人」；聊天头部有「工单」入口；
 *  ② 工单弹窗可认领 → 当前处理人变为本人，处理记录出现「认领」；
 *  ③ 可释放 → 回到未认领，记录出现「释放」；
 *  ④ 再次认领后可转交给店主（备注必填可选）→ 处理人变化、记录出现「转交」；
 *  ⑤ 处理记录逐条展示操作者 / 前后处理人 / 备注；
 *  ⑥ 全程 0 原生弹窗、0 pageerror；夹具零残留。
 *
 * 前置：容器在跑（8091 / 8080）；social 与商家端已部署 C-04；
 *       卖家 13600003333 可用；`incremental/58-support-ticket.sql` 已执行。
 * 用法：NO_PROXY="*" node scripts/e2e/support-ticket-c04-ui-check.mjs
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
const staffPhone = `191${suffix}`;
const buyerPhone = `190${suffix}`;
const staffName = `C04UI店员${stamp}`;
const buyerName = `C04UI买家${stamp}`;

const ownerToken = await tokenOf(SELLER);
check('店主登录成功', Boolean(ownerToken));
if (!ownerToken) process.exit(1);
const ownerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${SELLER}'`);

const ownerRow = sql(USER_DB, `SELECT IFNULL(nickname,'店主') FROM t_user WHERE id=${ownerId}`)[0] ?? '';
const ownerName = ownerRow || '店主';

// ---------------- 1. 夹具 ----------------
step('夹具：店主 + 店员 + 买家 + 客服会话');
await call('POST', '/api/auth/register', { phone: staffPhone, password: PWD, nickname: staffName });
await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: buyerName });
const staffId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${staffPhone}'`);
const buyerId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}'`);
const invite = await call('POST', '/api/seller/staff', { phone: staffPhone }, ownerToken);
const staffToken = await tokenOf(staffPhone);
const buyerToken = await tokenOf(buyerPhone);
check('店员邀请 / 登录，买家登录成功',
  invite.status === 200 && Boolean(staffToken) && Boolean(buyerToken), invite.body);

const session = await call('GET', `/api/support/session?sellerId=${ownerId}`, null, buyerToken);
const sessionId = session.body?.data?.id;
await call('POST', '/api/support/messages',
  { sellerId: ownerId, content: `C04 UI 冒烟 ${stamp} 请问什么时候发货` }, buyerToken);
check('客服会话与消息就绪', Boolean(sessionId), session.body);

const ticket0 = (await call('GET', `/api/seller/support/sessions/${sessionId}/ticket`, null, staffToken)).body?.data;
const ownerMember = ticket0?.members?.find((m) => m.role === 'OWNER');
check('工单成员含店主与店员，初始未认领',
  Boolean(ownerMember) && ticket0?.members?.length >= 2 && !ticket0?.assigneeId, ticket0);

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
  step('店员工作台：会话列表 → 工单弹窗');
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
  const page = await ctx.newPage();
  watch(page);
  await page.goto(`${MERCHANT}/login`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('11 位手机号').fill(staffPhone);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });

  await page.goto(`${MERCHANT}/seller/support`, { waitUntil: 'networkidle' });
  const item = page.locator('.sup-item', { hasText: buyerName });
  await item.waitFor({ timeout: 20000 });
  check('会话行显示「未认领」', /未认领/.test(await item.innerText()), await item.innerText());
  await item.click();
  await page.locator('.sup-thread-head', { hasText: buyerName }).waitFor({ timeout: 20000 });

  await page.getByRole('button', { name: '工单', exact: true }).click();
  const dialog = page.locator('[role="dialog"]', { hasText: '客服工单' });
  await dialog.waitFor({ timeout: 10000 });
  const info = dialog.getByText(/不改变店主与在职店员都能查看全店会话的权限/);
  await info.waitFor({ timeout: 10000 });
  check('工单弹窗说明「处理人不改变全店可见权限」', await info.isVisible());

  step('认领 → 释放 → 再认领');
  await dialog.getByRole('button', { name: '认领', exact: true }).click();
  await dialog.getByText(`当前处理人：${staffName}`, { exact: false }).waitFor({ timeout: 20000 });
  check('认领后当前处理人为本店员', (await dialog.innerText()).includes(`当前处理人：${staffName}`));
  check('处理记录出现「认领」', /认领/.test(await dialog.innerText()));

  await dialog.getByRole('button', { name: '释放为未认领', exact: true }).click();
  await dialog.getByText('当前处理人：未认领', { exact: false }).waitFor({ timeout: 20000 });
  check('释放后回到未认领', /当前处理人：未认领/.test(await dialog.innerText()));

  await dialog.getByRole('button', { name: '认领', exact: true }).click();
  await dialog.getByText(`当前处理人：${staffName}`, { exact: false }).waitFor({ timeout: 20000 });

  step('转交给店主（带备注）');
  await dialog.getByLabel('转交对象').selectOption(ownerMember.userId);
  await dialog.getByPlaceholder('最多 200 字').fill('C04 UI：店长请接手这笔咨询');
  await dialog.getByRole('button', { name: '转交', exact: true }).click();
  await dialog.getByText(`当前处理人：${ownerName}`, { exact: false }).waitFor({ timeout: 20000 });
  const finalText = await dialog.innerText();
  check('转交后当前处理人为店主', finalText.includes(`当前处理人：${ownerName}`), finalText);
  check('处理记录含转交与备注',
    /转交/.test(finalText) && finalText.includes('C04 UI：店长请接手这笔咨询'), finalText);
  check('处理记录逐条展示操作者与前后处理人',
    finalText.includes(staffName) && finalText.includes(ownerName)
      && /未认领 →/.test(finalText), finalText);

  const dbLogs = sql(SOCIAL_DB,
    `SELECT CONCAT(action,'|',IFNULL(from_assignee,'-'),'|',IFNULL(to_assignee,'-'),'|',IFNULL(note,'-'))
     FROM t_support_ticket_log WHERE session_id=${sessionId} ORDER BY id`);
  check('回库：CLAIM → RELEASE → CLAIM → TRANSFER 共 4 条',
    dbLogs.length === 4
      && dbLogs[0].startsWith('CLAIM|') && dbLogs[1].startsWith('RELEASE|')
      && dbLogs[2].startsWith('CLAIM|') && dbLogs[3].startsWith(`TRANSFER|${staffId}|${ownerId}|`), dbLogs);
  check('回库：会话当前处理人为店主',
    one(SOCIAL_DB, `SELECT assignee_id FROM t_support_session WHERE id=${sessionId}`) === String(ownerId));

  step('前端异常检查与截图');
  const shot = process.env.C04_UI_SHOT ?? path.join(shotsDir(), 'verify-c04-support-ticket.png');
  await page.screenshot({ path: shot, fullPage: true });
  console.log(`  [截图] ${shot}`);
  check('全程无原生 window.confirm/prompt/alert', nativeDialogs.length === 0, nativeDialogs);
  check('无页面级 JS 异常', pageErrors.length === 0, pageErrors);
} finally {
  await browser.close();
  sql(SOCIAL_DB, `DELETE FROM t_support_ticket_log WHERE session_id=${sessionId}`);
  sql(SOCIAL_DB, `DELETE FROM t_support_message WHERE session_id=${sessionId}`);
  sql(SOCIAL_DB, `DELETE FROM t_support_session WHERE id=${sessionId}`);
  for (const uid of [staffId, buyerId]) {
    sql(USER_DB, `DELETE FROM t_merchant_member WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_user WHERE id=${uid}`);
  }
  check('会话 / 消息 / 处理记录 / 临时账号零残留',
    count(SOCIAL_DB, `SELECT COUNT(*) FROM t_support_session WHERE id=${sessionId}`) === 0
      && count(SOCIAL_DB, `SELECT COUNT(*) FROM t_support_message WHERE session_id=${sessionId}`) === 0
      && count(SOCIAL_DB, `SELECT COUNT(*) FROM t_support_ticket_log WHERE session_id=${sessionId}`) === 0
      && count(USER_DB, `SELECT COUNT(*) FROM t_user WHERE id IN (${staffId},${buyerId})`) === 0);
}

console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exitCode = 1;
}

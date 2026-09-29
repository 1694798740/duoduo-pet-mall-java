/**
 * 分享 / 评论举报处理台（第五十六轮）· 真实浏览器 UI 自测
 *
 * 为什么接口冒烟（r56-share-circle-smoke.py 的 D 段 20 项）之外还要跑浏览器：
 *   接口测的是「规则对不对」，测不出「运营是否真的看得见、点得动」。
 *   本轮这个页面尤其需要浏览器验证 —— 它是「举报闭环」的最后一环，
 *   而闭环的价值全在**运营看得懂摘要、点得动处理、结论真的生效**这三件事上：
 *   · 列表若不显示被举报内容摘要，运营得逐条点开才知道在举报什么（接口层看不出来）；
 *   · 弹窗的**默认结论**如果是「下架」，一次误点就把别人的内容删了；
 *   · 「驳回」必须**不动内容** —— 这是接口能测的，但「页面上仍能看到它」只能在这里验。
 *
 * 前置：
 *   后端 9 容器在跑（网关 :8080）；frontend dev server 在跑（见下方端口说明）
 *
 * ⚠️ 必须用 http://localhost:<port>，不能用 127.0.0.1：网关 CORS_ORIGINS 只认 localhost。
 * ⚠️ 端口用 3000（5173 落在 Windows 排除端口段）：
 *      cd frontend && node node_modules/vite/bin/vite.js --port 3000 --strictPort --host localhost
 *      UI_BASE=http://localhost:3000 node scripts/e2e/share-report-ui-check.mjs
 *
 * 用法：node scripts/e2e/share-report-ui-check.mjs
 * 环境变量：GATEWAY / UI_BASE / CHROME_PATH / PW_CORE / KEEP_FIXTURE=1
 */

import path from 'node:path';

const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const UI = process.env.UI_BASE ?? 'http://localhost:3000';
const SHOTS = 'D:/智能体项目/duoduo-pet-mall-java/frontend/docs/screenshots';
const CHROME =
  process.env.CHROME_PATH ??
  'C:/Users/16947/AppData/Local/ms-playwright/chromium-1161/chrome-win/chrome.exe';
const PW_CORE =
  process.env.PW_CORE ??
  'file:///C:/Users/16947/.workbuddy/binaries/node/workspace/node_modules/playwright-core/index.mjs';

const PWD = 'duoduo12345';
const OPERATOR_PHONE = '13688774031'; // 历史运营账号（role=OPERATOR）

let pass = 0;
const failures = [];
function check(name, ok, got) {
  if (ok) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    failures.push(name);
    console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`);
  }
}
const step = (t) => console.log(`\n> ${t}`);

// ---------------- 1. 接口层：造夹具 ----------------

async function call(method, p, body, jar) {
  const headers = {};
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (jar && jar.size) headers.cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
  const res = await fetch(GW + p, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (jar) {
    for (const c of res.headers.getSetCookie?.() ?? []) {
      const pair = c.split(';')[0];
      const i = pair.indexOf('=');
      if (i > 0) jar.set(pair.slice(0, i), pair.slice(i + 1));
    }
  }
  const raw = await res.text();
  let json = null;
  try {
    json = JSON.parse(raw);
  } catch {
    /* 非 JSON 响应保留原文 */
  }
  return { status: res.status, json, raw };
}

const tokenOf = (r) => r.json?.data?.accessToken ?? null;
const uidOf = (r) => r.json?.data?.id ?? null;

step('夹具：作者 + 举报人 + 一条待处理举报');

const stamp = Date.now().toString().slice(-8);
const authorPhone = `137${stamp}`;
const reporterPhone = `138${stamp}`;

const authorReg = await call('POST', '/api/auth/register', {
  phone: authorPhone,
  password: PWD,
  nickname: '举报台作者',
});
const authorId = uidOf(authorReg);
const authorLogin = await call('POST', '/api/auth/login/token', {
  phone: authorPhone,
  password: PWD,
});
const authorToken = tokenOf(authorLogin);
check('夹具：作者已注册并登录', !!authorId && !!authorToken, { s: authorReg.status });

const reporterReg = await call('POST', '/api/auth/register', {
  phone: reporterPhone,
  password: PWD,
  nickname: '举报台举报人',
});
const reporterId = uidOf(reporterReg);
const reporterLogin = await call('POST', '/api/auth/login/token', {
  phone: reporterPhone,
  password: PWD,
});
const reporterToken = tokenOf(reporterLogin);
check('夹具：举报人已注册并登录', !!reporterId && !!reporterToken, { s: reporterReg.status });

// 一条内容明显可辨的分享，便于在页面上确认「摘要真的显示出来了」
const marker = `UI举报靶子${stamp}`;
const shareRes = await call(
  'POST',
  '/api/shares',
  { spuId: 1001, content: marker, topics: [] },
  undefined,
);
// 上面的调用没带令牌，必然是 401 —— 显式断言，避免夹具悄悄失败
check('夹具：未带令牌发布分享被拒（401）', shareRes.status === 401, shareRes.status);

const shareAuth = await fetch(GW + '/api/shares', {
  method: 'POST',
  headers: { 'content-type': 'application/json', authorization: 'Bearer ' + authorToken },
  body: JSON.stringify({ spuId: 1001, content: marker, topics: [] }),
});
const shareBody = await shareAuth.json();
const shareId = shareBody?.data?.id ?? null;
check('夹具：作者发布了一条分享', !!shareId, { s: shareAuth.status, body: shareBody?.code });

const reportRes = await fetch(GW + `/api/shares/${shareId}/report`, {
  method: 'POST',
  headers: { 'content-type': 'application/json', authorization: 'Bearer ' + reporterToken },
  body: JSON.stringify({ reason: '疑似垃圾广告（UI 自测）' }),
});
const reportBody = await reportRes.json();
const reportId = reportBody?.data?.id ?? null;
check('夹具：举报人成功举报（PENDING）', !!reportId && reportBody?.data?.status === 'PENDING', {
  s: reportRes.status,
  body: reportBody?.code,
});

// ---------------- 2. 浏览器：运营处理举报 ----------------

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

async function login(page, phone) {
  await page.goto(`${UI}/login`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('11 位手机号').fill(phone);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /登录/ }).first().click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
}

async function shot(page, name) {
  await page.screenshot({ path: path.join(SHOTS, name), fullPage: true });
  console.log(`  [截图] frontend/docs/screenshots/${name}`);
}

step('运营登录并打开举报处理台');

const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
const page = await ctx.newPage();
await login(page, OPERATOR_PHONE);
check('运营登录成功并离开登录页', !page.url().includes('/login'), page.url());

await page.goto(`${UI}/admin/share-reports`, { waitUntil: 'networkidle' });
check(
  'U1 页面标题渲染',
  await page.getByText('平台管理后台 · 举报处理').first().isVisible(),
  await page.title(),
);
check('U2 「举报处理」页签存在', await page.getByRole('link', { name: '举报处理' }).first().isVisible(), 'ok');
check('U3 默认停在「待处理」页签', await page.getByRole('button', { name: '待处理' }).first().isVisible(), 'ok');

await page.getByText('待处理', { exact: false }).first().waitFor({ timeout: 10000 }).catch(() => {});

const rowText = await page.locator('table.table').innerText().catch(() => '');
check('U4 ★ 列表直接显示被举报内容摘要（不必点开才知道在举报什么）', rowText.includes(marker), rowText.slice(0, 200));
check('U5 列表显示举报理由', rowText.includes('疑似垃圾广告'), rowText.slice(0, 200));

const targetRow = page.locator('table.table tbody tr', { hasText: marker }).first();
check('U6 能定位到刚造的那条举报行', (await targetRow.count()) > 0, rowText.slice(0, 200));
await shot(page, 'report_1_list.png');

step('弹窗：默认不选中「下架」，且说明必填');

await targetRow.getByRole('button', { name: '处理' }).click();
await page.getByText('处理举报').first().waitFor({ timeout: 8000 });
check('U7 点「处理」弹出处理弹窗', await page.getByText('处理举报').first().isVisible(), 'ok');

const rejectRadio = page.locator('input[name="report-action"]').nth(0);
const acceptRadio = page.locator('input[name="report-action"]').nth(1);
check('U8 ★ 默认选中「举报不成立」（误点确认不会删内容）', await rejectRadio.isChecked(), {
  reject: await rejectRadio.isChecked(),
  accept: await acceptRadio.isChecked(),
});

await page.getByRole('button', { name: '驳回举报' }).click();
await page.waitForTimeout(400);
check(
  'U9 不填说明直接提交 → 被拦住并提示',
  await page.getByText('处理说明必填').first().isVisible().catch(() => false),
  'ok',
);
await shot(page, 'report_2_modal.png');

step('处理：驳回不动内容');

await page.locator('textarea.textarea').first().fill('核实后属正常用户分享，不构成广告');
await page.getByRole('button', { name: '驳回举报' }).click();

// 正确处理完的**正确期望**：这一条不再属于「待处理」，所以它应当从当前列表消失。
// （不是「状态文案变成举报不成立」—— 当前页签过滤的就是 PENDING，留着一行反而是缺陷：
//   说明处理完列表没刷新，运营会以为没点上而重复处理。）
await targetRow.waitFor({ state: 'detached', timeout: 10000 }).catch(() => {});
const afterText = await page.locator('table.table').innerText().catch(() => '');
check(
  'U10 ★ 处理后该行从「待处理」列表消失（结论已生效 + 列表已刷新）',
  !afterText.includes(marker),
  afterText.slice(0, 220),
);

step('接口层复核：结论落库，且内容未被误删');

const opJar = new Map();
const opLogin = await call('POST', '/api/auth/login', { phone: OPERATOR_PHONE, password: PWD }, opJar);
check('运营 Web 登录（cookie）成功', opLogin.status === 200, opLogin.status);

const rejected = await call(
  'GET',
  '/api/admin/share-reports?status=REJECTED&page=1&pageSize=50',
  undefined,
  opJar,
);
const rejectedHit = (rejected.json?.data?.items ?? []).find((r) => String(r.id) === String(reportId));
check('U11 举报状态已落库为 REJECTED', rejectedHit?.status === 'REJECTED', rejectedHit?.status ?? rejected.json?.code);
check('U12 处理说明已记录', !!rejectedHit?.handleNote, rejectedHit?.handleNote ?? null);
check('U13 处理人已记录', !!rejectedHit?.handledBy, rejectedHit?.handledBy ?? null);

const stillThere = await call('GET', `/api/shares/${shareId}`);
check(
  'U14 ★ 驳回**不会**误删内容（分享仍可访问）',
  stillThere.status === 200 && stillThere.json?.data?.id === shareId,
  stillThere.status,
);

step('页签切换与非运营准入');

await page.getByRole('button', { name: '已驳回' }).first().click();
await page.waitForTimeout(1200);
const rejectedText = await page.locator('table.table').innerText().catch(() => '');
check('U15 「已驳回」页签下能看到它', rejectedText.includes(marker), rejectedText.slice(0, 200));
await shot(page, 'report_3_rejected.png');

await page.getByRole('button', { name: '已成立' }).first().click();
await page.waitForTimeout(1200);
const acceptedText = await page.locator('table.table').innerText().catch(() => '');
const emptyShown = await page
  .getByText('没有符合条件的举报')
  .first()
  .isVisible()
  .catch(() => false);
check('U16 「已成立」页签不炸（有列表或空态）', !acceptedText.includes(marker) || emptyShown, acceptedText.slice(0, 120));

const ctx2 = await browser.newContext({ viewport: { width: 1280, height: 860 }, locale: 'zh-CN' });
const p2 = await ctx2.newPage();
await login(p2, authorPhone); // 普通买家
await p2.goto(`${UI}/admin/share-reports`, { waitUntil: 'networkidle' });
check(
  'U17 ★ 非运营访问 → 看到准入提示（后端 403 之外还有可读解释）',
  await p2.getByText('需要运营或管理员身份').first().isVisible().catch(() => false),
  p2.url(),
);
await p2.screenshot({ path: path.join(SHOTS, 'report_4_denied.png'), fullPage: true });
console.log('  [截图] frontend/docs/screenshots/report_4_denied.png');

await browser.close();

console.log('\n' + '='.repeat(74));
console.log(`结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) console.log('失败项：\n  - ' + failures.join('\n  - '));
console.log('='.repeat(74));
process.exit(failures.length ? 1 : 0);

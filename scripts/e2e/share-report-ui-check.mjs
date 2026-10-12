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
 *   后端 9 容器在跑（网关 :8080）；三站点容器（商城 8088 / 平台后台 8090 / 商家 8091）
 *
 * ⚠️ 不能用 127.0.0.1 访问站点：网关 CORS_ORIGINS 只认 localhost 系主机名。
 * 端口：商城站点默认与其余 e2e 一致（容器 8088 / dev server 5173，可用 UI_BASE 覆盖）；
 *      举报处理台在**后台独立站点**（admin.localhost:8090，可用 ADMIN_UI_BASE 覆盖）。
 *      真遇到 5173 被系统排除的机器，用 UI_BASE=http://localhost:3000 覆盖商城侧即可。
 *
 * **E6b 改动（两处，都是 E5 摘除后台 + T2b 清库之后的「预期变化」）**：
 *   ① 举报台页面前由**商城入口** `${UI}/admin/share-reports` 驱动；商城摘除后台后该路径
 *      在商城站点上是「页面不存在」，故改指后台**独立站点** `${ADMIN_UI}/admin/share-reports`，
 *      登录也改用后台**自有登录页**（`loginAdmin`）。
 *   ② 运营账号此前是**硬编码的历史账号 13688774031**，而该账号已被 T2b 清库删除 ——
 *      套件因此从"能过"变成"必红"。按本仓既定纪律（**每个用例自建前置**，不依赖库内既有数据）
 *      改为**随机手机号 + 库提权**动态建运营夹具，收尾再降权回 USER ——
 *      顺带消除「每跑一次就在库里多留一个永久 OPERATOR 账号」这个卫生问题。
 *
 * 用法：node scripts/e2e/share-report-ui-check.mjs
 * 环境变量：GATEWAY / UI_BASE / ADMIN_UI_BASE / CHROME_PATH / PW_CORE / KEEP_FIXTURE=1
 */

import path from 'node:path';
import { mysql } from '../lib/docker-exec.mjs';

const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
/** 商城站点（C 端 / 分享圈） */
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
/** 平台管理后台**独立站点**（举报处理台在这里） */
const ADMIN_UI = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
const SHOTS = 'D:/智能体项目/duoduo-pet-mall-java/frontend/docs/screenshots';
const CHROME =
  process.env.CHROME_PATH ?? null; // 不指定则交给 playwright 解析自己安装的那份浏览器
const PW_CORE =
  process.env.PW_CORE ??
  'file:///D:/智能体项目/duoduo-pet-mall-java/scripts/e2e/node_modules/playwright-core/index.mjs';

const PWD = 'duoduo12345';

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

// ---------------- 夹具：平台运营账号（自建，不依赖库内既有账号） ----------------

step('夹具：平台运营账号（随机手机号 + 库提权）');

const opJar = new Map();
const opPhone = '139' + String(Math.floor(1e7 + Math.random() * 8e7));
const opReg = await call('POST', '/api/auth/register', { phone: opPhone, password: PWD, nickname: '举报台运营-夹具' }, opJar);
const opId = uidOf(opReg);
check('夹具：运营账号已注册', !!opId, { s: opReg.status, body: opReg.json?.code });

// 提权走库（重试 5 次，与 seckill-ui-check 同一套路）：**绝不把库内既有账号改成 OPERATOR**
for (let i = 0; i < 5; i++) {
  await mysql('duoduo_user', `UPDATE t_user SET role='OPERATOR' WHERE id=${opId};`);
  const role = (await mysql('duoduo_user', `SELECT role FROM t_user WHERE id=${opId};`)).trim();
  if (role === 'OPERATOR') break;
}
const opLogin = await call('POST', '/api/auth/login', { phone: opPhone, password: PWD }, opJar);
const opRole = opLogin.json?.data?.role;
check('★ 夹具：运营账号提权成功（否则后面全是 403，排查方向会整个跑偏）', opRole === 'OPERATOR', opRole);

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
const browser = await chromium.launch({ executablePath: CHROME ?? undefined, args: ['--no-sandbox'] });

/**
 * 平台后台**独立站点**登录（运营视角）。
 * 入口取 `/admin.html`（真入口）而非直达 `/login`：未登录时后台外壳会落到**自有登录页**，
 * 顺带把「后台不再依赖商城登录」这条性质走一遍。
 */
async function loginAdmin(page, phone) {
  await page.goto(`${ADMIN_UI}/admin.html`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('请输入平台账号手机号').fill(phone);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => u.pathname === '/admin', { timeout: 20000 });
}

async function shot(page, name) {
  await page.screenshot({ path: path.join(SHOTS, name), fullPage: true });
  console.log(`  [截图] frontend/docs/screenshots/${name}`);
}

step('运营登录并打开举报处理台（后台独立站点）');

const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
const page = await ctx.newPage();
await loginAdmin(page, opPhone);
check('运营登录成功并进入后台工作台（不经过商城登录页）', !page.url().includes('/login'), page.url());
check('★ 后台站点已独立：URL 落在 admin.localhost（不再是商城主机名）',
  new URL(page.url()).hostname === new URL(ADMIN_UI).hostname, page.url());

await page.goto(`${ADMIN_UI}/admin/share-reports`, { waitUntil: 'networkidle' });
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

// 运营会话沿用夹具段建的 opJar（共用同一份 cookie，避免为同一账号重复登录一次）
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

step('非运营准入：后台站点的两道防线（E6b 后口径变更）');

/*
 * ⚠️ 口径变更说明（不是"把红改成绿"）：旧套件让普通买家**登录商城后**访问
 * `${UI}/admin/share-reports`，断言页面上出现「需要运营或管理员身份」——
 * 那是"商城承载后台"时代的形态。后台独立后：
 *   · 商城站点已无 /admin/* 路由（再走那条路径只会得到「页面不存在」）；
 *   · 后台站点有**自有登录页**，非运营账号在**登录这一步**就被挡下（并主动退出会话）。
 * 所以 U17/U18 断言的是新形态，并补一条**接口层纵深**断言（U19）：
 * 入口层拿不到 ≠ 接口层能拿到 —— 两道防线都要钉住，缺一条就只剩"看起来安全"。
 */

// ① 入口层：非运营在后台自有登录页被明确拒绝，且**不留半登录状态**
const ctx2 = await browser.newContext({ viewport: { width: 1280, height: 860 }, locale: 'zh-CN' });
const p2 = await ctx2.newPage();
await p2.goto(`${ADMIN_UI}/admin.html`, { waitUntil: 'networkidle' });
check('非运营访问后台站点 → 落在后台自有登录页（不跳商城）',
  (await p2.getByPlaceholder('请输入平台账号手机号').count()) === 1, p2.url());

await p2.getByPlaceholder('请输入平台账号手机号').fill(authorPhone);
await p2.getByPlaceholder('请输入密码').fill(PWD);
await p2.getByRole('button', { name: /^登录$/ }).click();
await p2.waitForTimeout(2200);
const deniedText = await p2.locator('body').innerText();
check('U17 ★ 非运营账号登录后台 → 被明确拒绝并说明原因（「无平台后台权限」）',
  /无平台后台权限/.test(deniedText), deniedText.slice(0, 200));
check('U18 ★ 被拒后仍停在登录页，**不进入**后台工作台（不留半登录状态）',
  p2.url().includes('/login') && !deniedText.includes('举报处理'), p2.url());
await p2.screenshot({ path: path.join(SHOTS, 'report_4_denied.png'), fullPage: true });
console.log('  [截图] frontend/docs/screenshots/report_4_denied.png');

// ② 接口层纵深：拿到**有效会话**、绕过入口直接打后台接口 → 必须 403
const buyerJar = new Map();
await call('POST', '/api/auth/login', { phone: authorPhone, password: PWD }, buyerJar);
const forbidden = await call('GET', '/api/admin/share-reports?status=PENDING&page=1&pageSize=10', undefined, buyerJar);
check('U19 ★ 纵深：非运营会话直接请求后台接口 → 403（入口层拿不到 ≠ 接口层能拿到）',
  forbidden.status === 403, { status: forbidden.status, code: forbidden.json?.code });

// ---------------- 收尾：不留运营权限与自测内容痕迹 ----------------

step('收尾：夹具账号降权 + 自测分享软删');

if (process.env.KEEP_FIXTURE === '1') {
  console.log('  [跳过] KEEP_FIXTURE=1：保留夹具与运营权限，便于人工复查');
} else {
  /*
   * ① 运营夹具**降权回 USER**。这是本次顺带处置的一个真实卫生问题：
   *    此前各 e2e 用「随机手机号 + 库提权」建运营账号后从不收尾，库里已积压数十个
   *    **永久 OPERATOR**（"后台入口-运营""闲置自测-运营"…），它们全都能登录进平台后台 ——
   *    等于每个测试账号都是一个多余的后台入口。
   */
  await mysql('duoduo_user', `UPDATE t_user SET role='USER' WHERE id=${opId};`);
  const opRoleAfter = (await mysql('duoduo_user', `SELECT role FROM t_user WHERE id=${opId};`)).trim();
  check('★ 收尾：运营夹具已降权回 USER（不留多余后台入口）', opRoleAfter === 'USER', opRoleAfter);

  /*
   * ② 自测靶子内容**软删**（不物理删 —— t_product_share 的既定口径：
   *    转发单的原帖引用与举报记录都依赖"这条内容曾经存在"这个事实本身）。
   *    不软删的话，每跑一次就在分享圈留下一条「UI举报靶子…」的测试内容。
   */
  await mysql('duoduo_fulfilment', `UPDATE t_product_share SET deleted_at=NOW() WHERE id=${shareId};`);
  const delFlag = (await mysql('duoduo_fulfilment',
    `SELECT deleted_at IS NOT NULL FROM t_product_share WHERE id=${shareId};`)).trim();
  check('★ 收尾：自测分享已软删（不再出现在分享圈）', delFlag === '1', delFlag);
}

await browser.close();

console.log('\n' + '='.repeat(74));
console.log(`结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) console.log('失败项：\n  - ' + failures.join('\n  - '));
console.log('='.repeat(74));
process.exit(failures.length ? 1 : 0);

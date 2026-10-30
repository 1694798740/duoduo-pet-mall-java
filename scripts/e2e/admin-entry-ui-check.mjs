/**
 * 平台管理后台「入口」· 真实浏览器 UI 自测（**自包含**，不需要任何外部夹具）
 *
 * 为什么必须补这一套：后台入口此前只有**顶栏一条**，而用户报障的原话是
 * 「只有登录后自动跳转才能进入后台、关闭页面后无法再次进入」——
 * 这类「入口找不到了」的问题**在后端接口层完全测不出来**（接口一直是 200），
 * 只有真的开浏览器、真的刷新、真的重开上下文才看得见。
 *
 * 本脚本覆盖四组场景：
 *   S1 未登录：顶栏不显示入口；直接访问 /admin 被送到 /login?next=/admin（next 必须精确等于 /admin）
 *   S2 普通买家登录：顶栏与头像下拉都**不**显示入口；/admin 渲染「需要运营或管理员身份」拦截页
 *   S3 运营登录（核心）：
 *      · 顶栏「管理后台」可见 → 点击落到 /admin 且渲染「运营概览」
 *      · 头像下拉「进入管理后台」可见 → 点击同样落到 /admin
 *      · **刷新后入口仍在**（reload）
 *      · **同浏览器新开标签页，首页入口仍在**（模拟关掉标签页再打开）
 *      · **只带 dd_rt、不带 dd_at 的全新上下文**（模拟关掉浏览器再打开）入口仍在且能进后台
 *   S4 「登录后自动回跳后台」这条既有行为未被新入口破坏：未登录直奔 /admin → 登录 → 回到 /admin
 *
 * 两条容易踩的口径：
 *   1. 角色判定一律用 pages/admin/shared.tsx 的 isOperator()（忽略大小写）。
 *      store/auth.tsx 里的 isAdmin 写的是全小写比较，永远 false —— 断言里若用它当预期值，
 *      整套测试会「与实现一起错」，所以这里断言的是**页面实际渲染结果**。
 *   2. 必须用 http://localhost:5173，不能用 127.0.0.1 —— 网关 CORS 白名单只认 localhost。
 *
 * 前置：10 容器在跑（网关 :8080）；frontend dev server 在 :5173（run-ui-checks.ps1 会托管）。
 *
 * 用法：
 *   node scripts/e2e/admin-entry-ui-check.mjs
 *   UI_BASE=http://localhost:5173 GATEWAY=http://127.0.0.1:8080 node scripts/e2e/admin-entry-ui-check.mjs
 */

import path from 'node:path';
import { shotsDir } from '../lib/e2e-shots.mjs';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
/** 平台管理后台**独立站点**（T-ARCH：不同主机名 → 独立会话）。可用 ADMIN_UI_BASE 覆盖。 */
const ADMIN_UI = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
/**
 * 同一站点的**命令行可达地址**：Node 的解析器**不认识** `*.localhost`（浏览器按 RFC 6761 才认），
 * 所以服务端侧（fetch）一律走 127.0.0.1 + 端口；浏览器侧仍用 ADMIN_UI（保证 Cookie 作用域正确）。
 * 管理后台站点的 nginx `server_name` 同时含 localhost，故两条路都能到。
 */
const ADMIN_HTTP = process.env.ADMIN_HTTP_BASE ?? 'http://127.0.0.1:8090';
const SHOTS = shotsDir(); // 默认落到 gitignored 的 scripts/e2e/.shots；要归档配图用 SHOTS=<路径> 覆盖
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? undefined;

const PWD = 'duoduo12345';

fs.mkdirSync(SHOTS, { recursive: true });

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

// ---------------- HTTP 辅助（建夹具用） ----------------
async function call(method, p, body, jar) {
  const headers = {};
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (jar && jar.size) headers.cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
  const res = await fetch((p.startsWith('http') ? '' : GW) + p, {
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
  try { json = JSON.parse(raw); } catch { /* 保留 raw */ }
  return { status: res.status, data: json?.data, json, raw };
}

const randPhone = () => '138' + String(Math.floor(1e7 + Math.random() * 8e7));

/**
 * 本轮跑批的唯一标记。
 *
 * ★ 为什么必须：昵称原来是**固定字符串**，而每跑一次就新注册一批账号，
 *   于是「账号管理-目标用户」在库里会越攒越多 —— 搜索该昵称时列表里同时出现多行，
 *   `row.count() === 1` 恒不成立，改角色按钮的点击还会撞 strict mode violation。
 *   表现是「第一次跑通过、之后每次都红」，极易被误判成功能坏了。
 *   夹具昵称带跑批标记后，每次只看得到本轮自己建的账号。
 */
const RUN_TAG = String(Date.now()).slice(-6);

/** 建一个**指定角色**的账号：注册 → 提权重试走库 → 重新登录（角色是令牌里的快照）。
 *  返回 id，便于后续回表核对与收尾自毁。 */
async function newAccount(role, nickname) {
  const jar = new Map();
  const phone = randPhone();
  const reg = await call('POST', '/api/auth/register', { phone, password: PWD, nickname }, jar);
  let actual = '';
  for (let i = 0; i < 5; i++) {
    await mysql('duoduo_user', `UPDATE t_user SET role='${role}' WHERE phone='${phone}';`);
    actual = (await mysql('duoduo_user', `SELECT role FROM t_user WHERE phone='${phone}';`)).trim();
    if (actual === role) break;
  }
  const login = await call('POST', '/api/auth/login', { phone, password: PWD }, jar);
  const id = (await mysql('duoduo_user', `SELECT id FROM t_user WHERE phone='${phone}';`)).trim();
  return { jar, phone, nickname, role: actual, id, regStatus: reg.status, loginRole: login.data?.role };
}

/** 建一个 OPERATOR 账号：随机手机号 + 提权走库（既有账号一个都不动） */
async function newOperator(nickname) {
  const jar = new Map();
  const phone = randPhone();
  const reg = await call('POST', '/api/auth/register', { phone, password: PWD, nickname }, jar);
  // 提权重试：SQL 偶发静默失败会让后面全变 403，排查方向会整个跑偏
  let role = '';
  for (let i = 0; i < 5; i++) {
    await mysql('duoduo_user', `UPDATE t_user SET role='OPERATOR' WHERE phone='${phone}';`);
    role = (await mysql('duoduo_user', `SELECT role FROM t_user WHERE phone='${phone}';`)).trim();
    if (role === 'OPERATOR') break;
  }
  // 角色是访问令牌里的快照 —— 提权后必须重新登录，否则前端拿到的还是 USER
  const login = await call('POST', '/api/auth/login', { phone, password: PWD }, jar);
  return { jar, phone, nickname, role, regStatus: reg.status, loginRole: login.data?.role };
}

/**
 * 上一轮跑批遗留夹具的自愈清理。
 *
 * ★ 为什么必须有这一段：本脚本的夹具昵称是**固定前缀**（「账号管理-管理员甲/乙」），
 *   而每次跑批都会新注册一批 —— 早先**跑崩**的批次（比如崩在 S5a）会把账号留在库里，
 *   于是 ADMIN 越攒越多：S5d 里「并发前库里恰有 2 名 ADMIN」恒不成立，
 *   「最后一个 ADMIN 不可降级」这条分支**根本走不到**，两条并发请求会一起成功。
 *   表现是「第一次跑通过、之后每次都红」，极易被误判成后端功能坏了。
 *
 * 只删**本脚本自己命名的夹具**（昵称前缀限定 + 排除本轮 id），绝不碰真实账号。
 */
async function purgeLeftoverAdminFixtures(keepIds) {
  const ids = (
    await mysql(
      'duoduo_user',
      `SELECT id FROM t_user WHERE nickname LIKE '账号管理-管理员%' AND id NOT IN (${keepIds.join(',')});`,
    )
  ).trim();
  const list = ids ? ids.split('\n').map((s) => s.trim()).filter(Boolean) : [];
  if (!list.length) return 0;
  const inIds = list.join(',');
  await mysql('duoduo_user', `DELETE FROM t_refresh_token WHERE user_id IN (${inIds});`);
  await mysql('duoduo_user', `DELETE FROM t_audit_log WHERE target_id IN (${inIds}) OR actor_id IN (${inIds});`);
  await mysql('duoduo_user', `DELETE FROM t_user WHERE id IN (${inIds});`);
  return list.length;
}

// ---------------- 浏览器辅助 ----------------
const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

/** 新开一个上下文；cookies 用于「只带 dd_rt」这类模拟 */
async function newContext(cookies) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
  if (cookies?.length) await ctx.addCookies(cookies);
  return ctx;
}

function watch(page) {
  const consoleErrors = [];
  const httpErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
  page.on('response', (r) => { if (r.status() >= 500) httpErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`); });
  return { consoleErrors, httpErrors };
}

async function loginUI(page, phone, nickname) {
  await page.goto(`${UI}/login`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('11 位手机号').fill(phone);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
  // 等 /api/users/me 回来把 role 填进 store —— 否则入口还没渲染就断言，会假红
  await page.getByRole('button', { name: new RegExp(nickname) }).waitFor({ timeout: 10000 });
}

/** 顶栏里那条「管理后台」链接（exact 避免命中下拉里的「进入管理后台」） */
const navAdminLink = (page) =>
  page.locator('.app-header .nav-links').getByRole('link', { name: '管理后台', exact: true });
/** 头像下拉里那条「进入管理后台」——限定在 `.user-menu` 作用域内查，
 *  否则顶栏里的「商家端」等同名项会被一并数进来（同一标签两处出现时就假红）。 */
const menuAdminLink = (page) =>
  page.locator('.app-header .user-menu').getByRole('link', { name: '进入管理后台', exact: true });

/**
 * 打开头像菜单。
 *
 * 必须点 `.avatar-btn`：顶栏里 `.icon-btn` 不止一个（购物车、消息铃铛都在前面），
 * 取 `.first()` 会点到购物车图标 —— 菜单永远不展开，后面「下拉入口」三条断言全红，
 * 而产品本身是好的（DEF-004 的根因）。
 */
async function openUserMenu(page) {
  await page.locator('.app-header button.icon-btn.avatar-btn').first().click();
  await page.locator('.app-header .user-menu').waitFor({ state: 'visible', timeout: 3000 });
}

/**
 * console 噪声过滤 —— **401 是设计内状态，不是缺陷**，别把它当失败。
 *
 * 未登录时 `/api/users/me`、`/api/cart/count` 必然返回 401（页面已各自兜底，
 * 见 Layout.tsx 顶部注释）；S3b「只带 dd_rt」场景更是**靠一次 401 触发单飞刷新
 * 再重放原请求**，401 正是那条链路正常运转的证据。
 * 断言若不过滤它，就会变成「功能全对但测试全红」的假阳性 —— 而假阳性比漏报更伤：
 * 噪声一起来，真正的 pageerror 也会被忽略。
 * 口径与 scripts/e2e/seckill-ui-check.mjs 保持一致。
 */
const consoleNoise = (list) => list.filter((e) => !/favicon|status of 401/i.test(e));

const ADMIN_TITLE = '平台管理后台 · 运营概览';

// ================= 夹具 =================
step('夹具：运营账号 + 普通买家账号');
const op = await newOperator(`后台入口-运营-${RUN_TAG}`);
check('运营账号提权成功（否则后面全是 403，排查方向整个跑偏）', op.role === 'OPERATOR', op.role);
check('提权后重新登录，令牌里的角色已是 OPERATOR（角色是令牌快照）', op.loginRole === 'OPERATOR', op.loginRole);

const buyerJar = new Map();
const buyerPhone = randPhone();
const buyerReg = await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: `后台入口-买家-${RUN_TAG}` }, buyerJar);
check('普通买家账号注册成功', buyerReg.status === 200, buyerReg.status);

// ================= S1 未登录 =================
step('S1 · 未登录：不显示入口；商城站内 /admin 已摘除（显示页面不存在）');
{
  const ctx = await newContext();
  const page = await ctx.newPage();
  const { consoleErrors } = watch(page);

  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  check('未登录时顶栏不出现「管理后台」', (await navAdminLink(page).count()) === 0);
  check('未登录时也没有头像下拉（入口无处可点，符合「仅登录用户可见」）',
    (await page.locator('.app-header button.icon-btn').count()) === 0);

  // T-ARCH 第 2 步：后台已独立部署 → 商城内**不再有** /admin 路由
  await page.goto(`${UI}/admin`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);
  const bodyS1 = await page.locator('body').innerText();
  check('★ 商城内 /admin 已摘除（显示「页面不存在」，不再是站内后台）',
    bodyS1.includes('页面不存在'), bodyS1.slice(0, 80));
  check('★ 也不会被送去登录页（站内已无后台路由，不存在 next 回跳）',
    new URL(page.url()).pathname === '/admin', page.url());
  check('S1 无 console 报错', consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await ctx.close();
}

// ================= S2 普通买家登录 =================
step('S2 · 普通买家登录：顶栏与下拉都不显示入口');
{
  const ctx = await newContext();
  const page = await ctx.newPage();
  const { consoleErrors } = watch(page);

  await loginUI(page, buyerPhone, '后台入口-买家');
  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(400);
  check('买家登录后顶栏仍不出现「管理后台」', (await navAdminLink(page).count()) === 0);

  await openUserMenu(page);
  check('买家头像下拉里不出现「进入管理后台」', (await menuAdminLink(page).count()) === 0);
  check('下拉菜单本体确实展开了（否则上一条是空断言）',
    (await page.locator('.app-header .user-menu').getByRole('link', { name: '个人中心' }).count()) === 1);
  await page.keyboard.press('Escape');

  // 权限边界：登录用户进 /admin 不该被踢回登录页，而应看到可读的拦截说明
  await page.goto(`${UI}/admin`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);
  const body = await page.locator('body').innerText();
  check('★ 买家访问商城 /admin 也是「页面不存在」（后台已不在商城内，故不再是拦截页）',
    body.includes('页面不存在'), body.slice(0, 120));
  check('S2 无 console 报错', consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await page.screenshot({ path: path.join(SHOTS, 'admin-entry-buyer-denied.png') });
  await ctx.close();
}

// ================= S3 运营（核心） =================
step('S3 · 运营登录：两条入口都可用，且关掉再打开仍在');
const ctxOp = await newContext();
{
  const page = await ctxOp.newPage();
  const { consoleErrors, httpErrors } = watch(page);

  await loginUI(page, op.phone, op.nickname);

  // —— 顶栏那条（既有行为，必须仍然在）
  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(400);
  check('★ 运营登录后顶栏出现「管理后台」', (await navAdminLink(page).count()) === 1);
  check('任意页面都能看到：从 /coupons 看仍在',
    await (async () => {
      await page.goto(`${UI}/coupons`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(400);
      return (await navAdminLink(page).count()) === 1;
    })());

  await navAdminLink(page).click();
  await page.waitForURL((u) => u.host.includes('admin.localhost'), { timeout: 15000 });
  await page.waitForTimeout(700);
  const navBody = await page.locator('body').innerText();
  check('★ 点顶栏入口 → **跨站点**打开平台管理后台（该上下文未在后台登录，故显示后台自有登录页）',
    navBody.includes('平台管理后台 · 登录'), navBody.slice(0, 120));
  check('★ 打开的是后台**独立站点**（主机名不是商城）',
    new URL(page.url()).host.includes('admin.localhost'), page.url());

  // —— 下拉那条（本轮新增）
  // 先数再点：入口一旦消失，直接 click 会等 30 秒超时抛异常，
  // 拿不到「哪一条断言失败」这种可归因的信息（反向验证时尤其重要）。
  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(400);
  await openUserMenu(page);
  const menuCount = await menuAdminLink(page).count();
  check('★ 运营头像下拉里出现「进入管理后台」', menuCount === 1, menuCount);
  await page.screenshot({ path: path.join(SHOTS, 'admin-entry-menu.png') });

  if (menuCount === 1) {
    await menuAdminLink(page).click();
    await page.waitForURL((u) => u.host.includes('admin.localhost'), { timeout: 15000 });
    await page.waitForTimeout(700);
    check('★ 点下拉入口 → 同样跨站点打开平台管理后台',
      (await page.locator('body').innerText()).includes('平台管理后台 · 登录'));
  } else {
    check('★ 点下拉入口 → 同样跨站点打开平台管理后台', false, '入口不存在，无从点击');
  }

  // —— 刷新
  // 刻意**先显式回到 /admin 再刷新**，不与上一步的点击结果耦合：
  // 否则「下拉入口坏了」会连带把这一条也打红，看起来像两个缺陷，实为一个
  // —— 级联失败会把归因引偏（本项目吃过「不重不漏」的亏）。
  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(700);
  check('★ 商城首页刷新后，顶栏「管理后台」入口仍在（跨站点链接）',
    (await navAdminLink(page).count()) === 1);

  // —— 关掉标签页再打开（同一浏览器）
  const page2 = await ctxOp.newPage();
  watch(page2);
  await page2.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await page2.waitForTimeout(700);
  check('★ 同浏览器新开标签页（模拟关掉页面再打开）→ 首页入口仍在',
    (await navAdminLink(page2).count()) === 1);
  await page2.close();

  check('S3 无 console 报错', consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  check('S3 无 5xx（4xx 不算：备份接口按设计会 401/403）', httpErrors.length === 0, httpErrors.slice(0, 3));
  await page.screenshot({ path: path.join(SHOTS, 'admin-entry-nav.png') });
}

// —— 关掉浏览器再打开：只保留持久化的 dd_rt（dd_at 是 15 分钟短时效），验证自动续期后入口仍可达
step('S3b · 只带 dd_rt 的全新上下文（模拟关闭浏览器后重开）');
{
  const rt = op.jar.get('dd_rt');
  check('夹具拿到了 dd_rt（刷新令牌，7 天有效）', Boolean(rt), rt ? 'ok' : 'missing');
  const ctx = await newContext([{ name: 'dd_rt', value: rt, domain: 'localhost', path: '/' }]);
  const page = await ctx.newPage();
  const { consoleErrors } = watch(page);

  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1800); // 等 401 → 单飞刷新 dd_at → 重放 /api/users/me
  // 2026-10-20 起右上角只放**圆形头像**，昵称收进悬浮面板（平时不渲染在 DOM 里）——
  // 「已登录」的页面证据从「body 出现昵称」改为「头像按钮的 aria-label 带昵称」：
  // 两者同源（都来自 GET /api/users/me 回填的 user），断言性质不变。
  const loginEvidence = await page.evaluate(() => {
    const btn = document.querySelector('.app-header button.icon-btn.avatar-btn');
    return btn ? (btn.getAttribute('aria-label') ?? '') : '';
  });
  check('只带 dd_rt 时仍被识别为已登录（头像按钮读屏名出现昵称）',
    loginEvidence.includes(op.nickname), loginEvidence);
  check('★ 「关闭浏览器再打开」后，顶栏入口依然可见', (await navAdminLink(page).count()) === 1);

  await navAdminLink(page).click();
  await page.waitForURL((u) => u.host.includes('admin.localhost'), { timeout: 15000 });
  await page.waitForTimeout(700);
  check('★ 从这里点进去能真正打开**后台独立站点**（商城会话不带到后台 → 落在后台自有登录页）',
    (await page.locator('body').innerText()).includes('平台管理后台 · 登录'), page.url());
  check('S3b 无 console 报错', consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await ctx.close();
}
await ctxOp.close();

// ================= S4 既有「登录后回跳后台」未被破坏 =================
step('S4 · 后台已独立：站内路由摘除 + 后台站点自有登录链路（不经过商城）');
{
  const ctx = await newContext();
  const page = await ctx.newPage();
  const { consoleErrors } = watch(page);

  // ① 商城侧：/admin 已不存在（证明商城不再承载后台）
  await page.goto(`${UI}/admin`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
  check('★ 商城站点内已无 /admin（显示「页面不存在」）',
    (await page.locator('body').innerText()).includes('页面不存在'), page.url());

  // ② 后台站点：未登录 → **自有登录页** → 登录 → 运营概览（全程不经过商城）
  await page.goto(`${ADMIN_UI}/admin.html`, { waitUntil: 'networkidle' });
  check('后台站点未登录 → 落在自有登录页', (await page.locator('text=平台管理后台 · 登录').count()) > 0);
  await page.getByPlaceholder('请输入平台账号手机号').fill(op.phone);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => u.pathname === '/admin', { timeout: 15000 });
  await page.waitForTimeout(900);
  check('★ 后台站点登录后直接进入「运营概览」（不经过商城登录页）',
    (await page.locator('body').innerText()).includes(ADMIN_TITLE), page.url());
  check('S4 无 console 报错', consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await ctx.close();
}

// ---------- 后台独立入口（T3/T-ARCH）：/admin.html 与短地址 /console ----------
console.log('\n> 平台管理后台独立入口');
{
  const r = await fetch(`${ADMIN_HTTP}/admin.html`);
  check('后台独立站点入口可访问（200）', r.status === 200, `status=${r.status}`);
  const html = await r.text();
  check('后台入口返回的是后台页面（标题正确）', html.includes('平台管理后台'), 'title 不匹配');
  check('后台入口未被商城 SPA 兜底', !html.includes('宠物用品自营商城'), '命中商城兜底');

  const c = await fetch(`${ADMIN_HTTP}/console`, { redirect: 'manual' });
  const loc = c.headers.get('location') || '';
  check('短地址 /console 跳转到后台入口', c.status === 302 && loc.endsWith('/admin.html'), `status=${c.status} loc=${loc}`);
  // ★ 端口必须保留：nginx 用 $host 生成绝对地址会丢端口（与 T-66 同坑）
  check('短地址跳转保留端口（不得丢端口）', loc.includes(':8090'), `loc=${loc}`);

  // 同一浏览器里两个站点各自独立：后台站点未登录 → 自有登录页
  const ctx = await newContext();
  const page = await ctx.newPage();
  await page.goto(`${ADMIN_UI}/admin.html`, { waitUntil: 'networkidle' });
  check('未登录访问后台站点 → 落在**本平台**登录页（不跳商城）',
    (await page.locator('text=平台管理后台 · 登录').count()) > 0);
  check('登录页不出现"去商城登录"', (await page.locator('text=去商城登录').count()) === 0);
  await ctx.close();
}

// ================= S5 运营账号页：改角色只有 ADMIN 能做 =================
/*
 * 这一段的由来（原来这里是个**假绿**）：`/admin/operators` 的「改角色」控件此前
 * 「库内没有 ADMIN 账号，浏览器断言无从构造」——于是它只被 admin-smoke 在**接口层**覆盖，
 * 而那条接口断言是这么写的：
 *
 *     $adminCount = SELECT COUNT(*) WHERE role='ADMIN'
 *     $r = POST /api/admin/users/<管理员自己>/role {role:'OPERATOR'}   ← actor == target！
 *     if ($adminCount -eq 1) { 期望 422「最后一个 ADMIN 不可降级」 } else { 期望 200 }
 *
 * 问题是 actor == target 时**先命中「不能给自己降权」**（同样是 422，但完全是另一条规则），
 * 而两条断言都只比状态码 ⇒ **无论哪条规则生效都是绿的**。
 * 更糟的是 else 分支（期望 200）永远不可能成立 —— 自己降自己必是 422。
 * 也就是说「最后一个 ADMIN 不可降级」这条规则**从来没有被真正测过**。
 *
 * 本段补上：① 现场建 ADMIN 夹具（浏览器真的点一遍）；② 断言**服务端下发的文案**
 * 而不是只看状态码；③ 用**并发**把「最后一个 ADMIN」那条分支真正走到（见 S5e）。
 */
step('S5 · 账号管理页「改角色」：仅 ADMIN 可改（UI 正/反向 + 接口层 + 审计 + 并发不变式）');

const adminA = await newAccount('ADMIN', `账号管理-管理员甲-${RUN_TAG}`);
const adminB = await newAccount('ADMIN', `账号管理-管理员乙-${RUN_TAG}`);
const targetU = await newAccount('USER', `账号管理-目标用户-${RUN_TAG}`);
check('夹具：管理员甲为 ADMIN（建不起则后面全 403，排查方向整个跑偏）', adminA.role === 'ADMIN', adminA.role);
check('夹具：管理员乙为 ADMIN', adminB.role === 'ADMIN', adminB.role);
check('夹具：目标用户为 USER', targetU.role === 'USER', targetU.role);
check('夹具：三个账号都拿到了 id（回表核对的前提）',
  Boolean(adminA.id && adminB.id && targetU.id), `${adminA.id}/${adminB.id}/${targetU.id}`);

// 清掉历次跑崩留下的 ADMIN 夹具：S5d 的并发不变式要求「库里恰有 2 名 ADMIN」，
// 历史残留会让它恒不成立（详见 purgeLeftoverAdminFixtures 的注释）。
const purgedAdmins = await purgeLeftoverAdminFixtures([adminA.id, adminB.id]);
console.log(`  [信息] 清理历史遗留的「账号管理-管理员」夹具 ${purgedAdmins} 个`);

/** 在**后台独立站点**登录（与商城会话完全隔离，走自有登录页） */
async function loginAdminSite(page, phone) {
  await page.goto(`${ADMIN_UI}/admin.html`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('请输入平台账号手机号').fill(phone);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => u.pathname === '/admin', { timeout: 15000 });
  await page.waitForTimeout(600);
}

/** 从导航标签进「运营账号」页，按昵称检索出目标行 */
async function openOperatorsRow(page, nickname) {
  await page.getByRole('link', { name: '运营账号', exact: true }).click();
  await page.waitForURL((u) => u.pathname === '/admin/operators', { timeout: 15000 });
  await page.waitForTimeout(400);
  // 「全站分页/搜索统一」之后：搜索框换成统一 SearchBar，**输入即搜**，
  // 没有「查询」按钮了，占位符也由「手机号 / 昵称」改成「搜手机号或昵称」。
  // 页面内部有 350ms 防抖，填完必须等到防抖 + 请求都走完再看结果。
  await page.getByPlaceholder('搜手机号或昵称').fill(nickname);
  await page.waitForTimeout(1200);
  return page.locator('table.table tbody tr').filter({ hasText: nickname });
}

// —— S5a UI 反向：OPERATOR 能看列表，但改不动（前端隐藏 + 后端 403 双层）
step('S5a · OPERATOR 看得到账号列表，但没有「改角色」入口');
{
  const ctx = await newContext();
  const page = await ctx.newPage();
  const { consoleErrors } = watch(page);
  await loginAdminSite(page, op.phone);
  const row = await openOperatorsRow(page, targetU.nickname);

  check('运营能看到目标用户那一行（列表权限 OPERATOR 有）', (await row.count()) === 1, await row.count());
  check('★ 该行**没有**「改角色」按钮（前端按 isAdminRole 隐藏）',
    (await row.getByRole('button', { name: '改角色' }).count()) === 0);
  check('★ 该行取而代之显示「仅 ADMIN」（不是空白，用户知道为什么点不了）',
    (await row.getByText('仅 ADMIN').count()) === 1);
  const body = await page.locator('body').innerText();
  check('★ 页面顶部也有说明「变更角色仅限超级管理员（ADMIN）」',
    body.includes('变更角色仅限超级管理员'), body.slice(0, 120));
  check('S5a 无 console 报错', consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await ctx.close();
}

// —— S5b UI 正向：ADMIN 真的点一遍，改角色成功
step('S5b · ADMIN 在页面上把 USER 改成 SELLER：成功 + 文案原文 + 回表核对');
let uiChangeNotice = '';
{
  const ctx = await newContext();
  const page = await ctx.newPage();
  const { consoleErrors } = watch(page);
  await loginAdminSite(page, adminA.phone);
  const row = await openOperatorsRow(page, targetU.nickname);

  check('★ ADMIN 看到的该行**有**「改角色」按钮', (await row.getByRole('button', { name: '改角色' }).count()) === 1);
  check('ADMIN 看到的该行不再有「仅 ADMIN」字样', (await row.getByText('仅 ADMIN').count()) === 0);

  await row.getByRole('button', { name: '改角色' }).click();
  await page.waitForTimeout(300);
  check('点开弹窗（标题「变更用户角色」）', (await page.locator('text=变更用户角色').count()) > 0);

  await page.locator('.modal select.input').first().selectOption('SELLER');
  await page.getByRole('button', { name: '确认变更' }).click();
  await page.waitForTimeout(1200);

  check('★ 页面出现「角色已更新」成功提示', (await page.locator('.toast-host .toast').first().textContent() ?? '').includes('角色已更新'),
    await page.locator('.toast-host').innerText().catch(() => '(无 toast)'));

  // ★ 服务端下发的 notice 必须**原样展示**（它是运营判断「这次变更生效到哪一步」的唯一线索）：
  //   E17 起成功文案含「下一个请求即生效（无需重新登录）」，绝不能本地拼成「重新登录后生效」。
  const alerts = await page.locator('.alert-success').allInnerTexts();
  uiChangeNotice = alerts.join(' | ');
  check('★ 展示的是服务端 notice 原文：含「下一个请求即生效」', uiChangeNotice.includes('下一个请求即生效'), uiChangeNotice);
  check('★ 且明确「无需重新登录」（不能反过来只说「重新登录」）',
    uiChangeNotice.includes('无需重新登录'), uiChangeNotice);

  await page.screenshot({ path: path.join(SHOTS, 'admin-operators-role-changed.png') });
  check('S5b 无 console 报错', consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await ctx.close();
}
// 回表读（铁律 22：计数/终态必须回表，不看响应体自证）
check('★ 回表核对：目标用户 role 已落库为 SELLER',
  (await mysql('duoduo_user', `SELECT role FROM t_user WHERE id=${targetU.id};`)).trim() === 'SELLER',
  await mysql('duoduo_user', `SELECT role FROM t_user WHERE id=${targetU.id};`));
{
  const audit = (await mysql('duoduo_user',
    `SELECT CONCAT(actor_id,'|',action,'|',detail,'|',target_id) FROM t_audit_log WHERE target_id=${targetU.id} ORDER BY id DESC LIMIT 1;`)).trim();
  check('★ 审计留痕：actor=管理员甲 / 动作 USER_ROLE_CHANGE / 变更 USER -> SELLER',
    audit === `${adminA.id}|USER_ROLE_CHANGE|USER -> SELLER|${targetU.id}`, audit);
}

// —— S5c 接口层：状态码 + 服务端文案（只看状态码会把两条规则混为一谈）
step('S5c · 接口层：两层权限门 + 参数校验 + 幂等 + 「不能给自己降权」');
{
  const r = await call('POST', `/api/admin/users/${targetU.id}/role`, { role: 'OPERATOR' }, op.jar);
  check('★ OPERATOR 直接调改角色接口 → 403（前端隐藏只是少撞一次墙，硬边界在后端）', r.status === 403, r.status);
  check('★ 403 文案点明「仅限超级管理员」（不是笼统的「无权限」）',
    String(r.json?.message ?? '').includes('仅限超级管理员'), r.json?.message);

  // ★ 授权可用性优先于参数吻合性：没权限的人传非法角色，应得 403 而不是 422 ——
  //   否则接口变成"参数校验器"，会向无权限者泄露「哪些角色名是合法的」。
  const r2 = await call('POST', `/api/admin/users/${targetU.id}/role`, { role: 'VIP' }, op.jar);
  check('★ OPERATOR + 非法角色 → 仍是 403（先判权、后校验参数）', r2.status === 403, r2.status);

  const r3 = await call('POST', `/api/admin/users/${targetU.id}/role`, { role: 'VIP' }, adminA.jar);
  check('ADMIN + 非法角色 → 422（角色白名单来自 RolePermissionMatrix）', r3.status === 422, r3.status);

  const r4 = await call('POST', '/api/admin/users/1000000000000000001/role', { role: 'OPERATOR' }, adminA.jar);
  check('ADMIN + 不存在的用户 → 404', r4.status === 404, r4.status);

  const before = (await mysql('duoduo_user', `SELECT COUNT(*) FROM t_audit_log WHERE target_id=${targetU.id};`)).trim();
  const r5 = await call('POST', `/api/admin/users/${targetU.id}/role`, { role: 'SELLER' }, adminA.jar);
  const after = (await mysql('duoduo_user', `SELECT COUNT(*) FROM t_audit_log WHERE target_id=${targetU.id};`)).trim();
  check('幂等：目标已是该角色 → 200', r5.status === 200, r5.status);
  check('★ 幂等文案是「未做变更」而不是「角色已更新」',
    String(r5.json?.data?.notice ?? '').includes('未做变更'), r5.json?.data?.notice);
  check('★ 幂等调用**不产生**新审计事件（重复确认同一状态不构成事件）', before === after, `${before} -> ${after}`);

  const self = await call('POST', `/api/admin/users/${adminA.id}/role`, { role: 'USER' }, adminA.jar);
  check('ADMIN 给自己降权 → 422', self.status === 422, self.status);
  check('★ 且文案是「不能给自己降权」——**不是**「最后一个 ADMIN 不可降级」（两条规则必须分得开）',
    String(self.json?.message ?? '').includes('不能给自己降权'), self.json?.message);
}

// —— S5d 并发：把「最后一个 ADMIN 不可降级」那条分支真正走到
step('S5d · 并发降级最后两名 ADMIN：平台必须还剩至少一名管理员');
{
  const aCountBefore = (await mysql('duoduo_user', "SELECT COUNT(*) FROM t_user WHERE role='ADMIN';")).trim();
  check('并发前库里恰有 2 名 ADMIN（甲、乙）', aCountBefore === '2', aCountBefore);

  // 甲降乙、乙降甲**同时**发出（两条都是合法请求：actor≠target、目标是 ADMIN、新角色是 USER）
  const [r1, r2] = await Promise.all([
    call('POST', `/api/admin/users/${adminB.id}/role`, { role: 'USER' }, adminA.jar),
    call('POST', `/api/admin/users/${adminA.id}/role`, { role: 'USER' }, adminB.jar),
  ]);
  const ok = [r1, r2].filter((r) => r.status === 200);
  const loser = [r1, r2].find((r) => r.status !== 200);
  const aCountAfter = (await mysql('duoduo_user', "SELECT COUNT(*) FROM t_user WHERE role='ADMIN';")).trim();

  check('★ 并发降级**不能两条都成功**（都成功 ⇒ 平台一个管理员都不剩）', ok.length <= 1,
    `status=${r1.status}/${r2.status}`);
  check('★ 恰好一条成功、一条被拒（不是两条都被拒）', ok.length === 1, `status=${r1.status}/${r2.status}`);
  check('★ 回表读：库里仍至少有 1 名 ADMIN（不看响应体，直接数库）',
    Number(aCountAfter) >= 1, `ADMIN=${aCountAfter}`);
  check('★ 被拒那条走的是「最后一个 ADMIN 不可降级」分支（并把服务端文案带回来了）',
    loser?.status === 422 && String(loser?.json?.message ?? '').includes('至少保留一名超级管理员'),
    `status=${loser?.status} msg=${loser?.json?.message}`);
}

// —— S5e 夹具自毁（建得起就必须删得掉；删不掉显式报红）
step('S5e · 夹具收尾：三个账号与它们产生的审计行一并删除');
{
  const phones = [adminA.phone, adminB.phone, targetU.phone];
  const ids = [adminA.id, adminB.id, targetU.id];
  await mysql('duoduo_user', `DELETE FROM t_refresh_token WHERE user_id IN (${ids.join(',')});`);
  await mysql('duoduo_user', `DELETE FROM t_audit_log WHERE target_id IN (${ids.join(',')}) OR actor_id IN (${ids.join(',')});`);
  await mysql('duoduo_user', `DELETE FROM t_user WHERE id IN (${ids.join(',')});`);
  const left = (await mysql('duoduo_user', `SELECT COUNT(*) FROM t_user WHERE phone IN ('${phones.join("','")}');`)).trim();
  check('★ 夹具账号已自毁（删不掉会让每次跑 e2e 都往库里堆账号）', left === '0', `剩余 ${left} 个`);
  const auditLeft = (await mysql('duoduo_user', `SELECT COUNT(*) FROM t_audit_log WHERE actor_id IN (${ids.join(',')});`)).trim();
  check('★ 夹具产生的审计行也已清掉', auditLeft === '0', `剩余 ${auditLeft} 行`);
}

await browser.close();

// ================= 汇总 =================
console.log(`\n=== 后台入口 UI 断言：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);

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
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
const SHOTS = process.env.SHOTS ?? 'D:/智能体项目/duoduo-pet-mall-java/frontend/docs/screenshots';
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

async function openUserMenu(page) {
  await page.locator('.app-header button.icon-btn').first().click();
  await page.waitForTimeout(200);
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
const op = await newOperator('后台入口-运营');
check('运营账号提权成功（否则后面全是 403，排查方向整个跑偏）', op.role === 'OPERATOR', op.role);
check('提权后重新登录，令牌里的角色已是 OPERATOR（角色是令牌快照）', op.loginRole === 'OPERATOR', op.loginRole);

const buyerJar = new Map();
const buyerPhone = randPhone();
const buyerReg = await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: '后台入口-买家' }, buyerJar);
check('普通买家账号注册成功', buyerReg.status === 200, buyerReg.status);

// ================= S1 未登录 =================
step('S1 · 未登录：不显示入口，直奔 /admin 被送到带 next 的登录页');
{
  const ctx = await newContext();
  const page = await ctx.newPage();
  const { consoleErrors } = watch(page);

  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  check('未登录时顶栏不出现「管理后台」', (await navAdminLink(page).count()) === 0);
  check('未登录时也没有头像下拉（入口无处可点，符合「仅登录用户可见」）',
    (await page.locator('.app-header button.icon-btn').count()) === 0);

  await page.goto(`${UI}/admin`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);
  const url = new URL(page.url());
  check('未登录访问 /admin → 落到登录页', url.pathname === '/login', page.url());
  check('★ 回跳参数 next 精确等于 /admin（登录后才会回运营概览）',
    decodeURIComponent(url.searchParams.get('next') ?? '') === '/admin',
    url.searchParams.get('next'));
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
  await page.waitForTimeout(800);
  const body = await page.locator('body').innerText();
  check('买家访问 /admin 渲染「需要运营或管理员身份」拦截页（不是白屏也不是跳登录）',
    body.includes('需要运营或管理员身份'), body.slice(0, 120));
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
  await page.waitForURL((u) => u.pathname === '/admin', { timeout: 10000 });
  await page.waitForTimeout(900);
  const navBody = await page.locator('body').innerText();
  check('★ 点顶栏入口 → 直接落到「运营概览」页（不是登录页、不是拦截页）',
    navBody.includes(ADMIN_TITLE), navBody.slice(0, 120));

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
    await page.waitForURL((u) => u.pathname === '/admin', { timeout: 10000 });
    await page.waitForTimeout(900);
    check('★ 点下拉入口 → 同样落到「运营概览」页',
      (await page.locator('body').innerText()).includes(ADMIN_TITLE));
  } else {
    check('★ 点下拉入口 → 同样落到「运营概览」页', false, '入口不存在，无从点击');
  }

  // —— 刷新
  // 刻意**先显式回到 /admin 再刷新**，不与上一步的点击结果耦合：
  // 否则「下拉入口坏了」会连带把这一条也打红，看起来像两个缺陷，实为一个
  // —— 级联失败会把归因引偏（本项目吃过「不重不漏」的亏）。
  await page.goto(`${UI}/admin`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(800);
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(900);
  check('★ 在后台页刷新后，顶栏入口与页面都还在',
    (await navAdminLink(page).count()) === 1
      && (await page.locator('body').innerText()).includes(ADMIN_TITLE));

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
  const body = await page.locator('body').innerText();
  check('只带 dd_rt 时仍被识别为已登录（页面出现昵称）', body.includes(op.nickname), body.slice(0, 120));
  check('★ 「关闭浏览器再打开」后，顶栏入口依然可见', (await navAdminLink(page).count()) === 1);

  await navAdminLink(page).click();
  await page.waitForURL((u) => u.pathname === '/admin', { timeout: 10000 });
  await page.waitForTimeout(900);
  check('★ 从这里点进去能真正打开后台「运营概览」（不只是链接显示出来了）',
    (await page.locator('body').innerText()).includes(ADMIN_TITLE));
  check('S3b 无 console 报错', consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await ctx.close();
}
await ctxOp.close();

// ================= S4 既有「登录后回跳后台」未被破坏 =================
step('S4 · 未登录直奔 /admin → 登录 → 自动回到 /admin（既有行为保持）');
{
  const ctx = await newContext();
  const page = await ctx.newPage();
  const { consoleErrors } = watch(page);

  await page.goto(`${UI}/admin`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);
  check('被送到登录页', new URL(page.url()).pathname === '/login', page.url());

  await page.getByPlaceholder('11 位手机号').fill(op.phone);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => u.pathname === '/admin', { timeout: 15000 });
  await page.waitForTimeout(900);
  check('★ 登录成功后自动回到 /admin（next 回跳链路完好）',
    new URL(page.url()).pathname === '/admin', page.url());
  check('★ 回跳后直接是「运营概览」页', (await page.locator('body').innerText()).includes(ADMIN_TITLE));
  check('★ 两条路径不冲突：回跳后顶栏入口同时也在', (await navAdminLink(page).count()) === 1);
  check('S4 无 console 报错', consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await ctx.close();
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

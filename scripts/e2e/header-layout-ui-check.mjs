/**
 * 顶栏布局完整性 · 真实浏览器 UI 自测（**自包含**，不需要任何外部夹具）
 *
 * 为什么必须补这一套：顶栏的溢出**在任何接口层都测不出来** ——
 * 被挤出去的标签依然在 DOM 里、链接照样跳转正常、接口全是 200，
 * 只有真的量一次「元素位置 vs 可视框」才看得见。用户报障原话是
 * 「顶栏上有很多标签未能完整显示，部分标签被截断或缺失」。
 *
 * 缺陷成因（实测数据，1920px 视口下）：
 *   · 顶栏所在 `.container` 最大宽度 1200px（`--max-w`），一行可用约 1160px；
 *   · 而「9 项基础导航 + 商家端 + 管理后台」需要 812px，加上品牌 146、搜索 260、
 *     购物车 38、用户按钮 92，这一行共需约 1408px —— **放大窗口永远不会好转**；
 *   · `.nav-links` 有 `overflow-x:auto`，flex 自动最小尺寸因此为 0，
 *     于是它独自让出 218px 承担全部收缩，把最后的标签挤出可视区；
 *   · 滚动条被 `scrollbar-width:none` 隐藏且无溢出提示 → 用户只看到标签「凭空消失」。
 *
 * 本套断言的就是**不变式**，不是某一版的项数：
 *   ① 顶栏里的每个导航项都必须完整落在可视框内（逐项比对 boundingRect）；
 *   ② 用户按钮里的昵称不得折行（折行会撑出固定 38px 的药丸外）；
 *   ③ 个人事务入口必须仍然**可达**（收进头像下拉，不是删掉）。
 *
 * 用法：node scripts/e2e/header-layout-ui-check.mjs
 *   UI_BASE=http://localhost:5173 GATEWAY=http://127.0.0.1:8080 node scripts/e2e/header-layout-ui-check.mjs
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

fs.mkdirSync(SHOTS, { recursive: true });

const PWD = 'duoduo12345';
/** 视口矩阵：从超宽到常见的笔记本宽度（1152 ≈ 13 寸默认，1024 ≈ 老式小屏） */
const WIDTHS = [1920, 1440, 1280, 1264, 1152, 1024];
/** 顶栏应出现的「逛 / 买」一级动线（与 Layout.tsx 的 NAV 一致） */
const NAV_CORE = ['首页', '逛商品', 'AI 导购', '二手闲置', '优惠券', '秒杀'];
/** 已收进头像下拉的个人事务入口 —— 必须仍可达 */
const MENU_PERSONAL = ['个人中心', '我的订单', '我的券包', '我的宠物', '收货地址'];

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

/**
 * console 噪声过滤：401 是**设计内状态**（未登录时 /api/users/me、/api/cart/count 必然 401），
 * 口径与 seckill-ui-check.mjs 一致；pageerror 一律不放过。
 */
const consoleNoise = (list) => list.filter((e) => !/favicon|status of 401/i.test(e));

// ---------------- HTTP 夹具 ----------------
async function call(method, p, body, jar) {
  const headers = {};
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (jar && jar.size) headers.cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
  const res = await fetch((p.startsWith('http') ? '' : GW) + p, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (jar) for (const c of res.headers.getSetCookie?.() ?? []) {
    const pair = c.split(';')[0]; const i = pair.indexOf('=');
    if (i > 0) jar.set(pair.slice(0, i), pair.slice(i + 1));
  }
  const raw = await res.text();
  let json = null; try { json = JSON.parse(raw); } catch { /* 保留 raw */ }
  return { status: res.status, data: json?.data };
}
const randPhone = (prefix) => prefix + String(Math.floor(1e7 + Math.random() * 8e7));

step('夹具：运营账号（昵称刻意用 4 字，复现「平台运营」折行的情形）');
const opPhone = randPhone('138');
const opJar = new Map();
await call('POST', '/api/auth/register', { phone: opPhone, password: PWD, nickname: '平台运营' }, opJar);
let opRole = '';
for (let i = 0; i < 5; i++) {
  await mysql('duoduo_user', `UPDATE t_user SET role='OPERATOR' WHERE phone='${opPhone}';`);
  opRole = (await mysql('duoduo_user', `SELECT role FROM t_user WHERE phone='${opPhone}';`)).trim();
  if (opRole === 'OPERATOR') break;
}
await call('POST', '/api/auth/login', { phone: opPhone, password: PWD }, opJar);
check('运营账号提权成功（否则顶栏少两项，断言会假绿）', opRole === 'OPERATOR', opRole);

step('夹具：普通买家账号');
const buyerPhone = randPhone('137');
const buyerJar = new Map();
const bReg = await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: '顶栏买家' }, buyerJar);
check('买家账号注册成功', bReg.status === 200, bReg.status);

step('夹具：超长昵称的运营账号（昵称最长允许 20 字，不封顶就会反过来挤掉导航）');
const longPhone = randPhone('136');
const longJar = new Map();
const lReg = await call('POST', '/api/auth/register', {
  phone: longPhone, password: PWD, nickname: '我是一个名字特别特别长的平台运营',
}, longJar);
let longRole = '';
for (let i = 0; i < 5; i++) {
  await mysql('duoduo_user', `UPDATE t_user SET role='OPERATOR' WHERE phone='${longPhone}';`);
  longRole = (await mysql('duoduo_user', `SELECT role FROM t_user WHERE phone='${longPhone}';`)).trim();
  if (longRole === 'OPERATOR') break;
}
await call('POST', '/api/auth/login', { phone: longPhone, password: PWD }, longJar);
check('长昵称账号注册并提权成功', lReg.status === 200 && longRole === 'OPERATOR', { reg: lReg.status, longRole });

// ---------------- 浏览器 ----------------
const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

/** 在页面里量一次顶栏：返回被截断的导航项与昵称是否折行 */
const MEASURE = () => {
  const nav = document.querySelector('.app-header .nav-links');
  if (!nav) return { missingHeader: true };
  const box = nav.getBoundingClientRect();
  const items = [...nav.querySelectorAll('.nav-link')];
  const clipped = items
    .filter((a) => {
      const r = a.getBoundingClientRect();
      // 完整可见 = 左右边缘都落在可视框内（1px 容差）
      return !(r.left >= box.left - 1 && r.right <= box.right + 1);
    })
    .map((a) => a.textContent.trim());
  const userBtn = document.querySelector('.app-header button.icon-btn');
  const nick = userBtn ? userBtn.querySelector('.small') : null;
  const nickRect = nick ? nick.getBoundingClientRect() : null;
  return {
    navOverflow: nav.scrollWidth > nav.clientWidth + 1,
    navNeed: nav.scrollWidth,
    navHave: nav.clientWidth,
    labels: items.map((a) => a.textContent.trim()),
    clipped,
    nickWrapped: nickRect ? nickRect.height > 22 : null,
    nickH: nickRect ? Math.round(nickRect.height) : null,
    btnH: userBtn ? Math.round(userBtn.getBoundingClientRect().height) : null,
  };
};

const contexts = {};
for (const [key, jar] of [['运营', opJar], ['买家', buyerJar], ['长昵称运营', longJar]]) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addCookies([...jar].map(([name, value]) => ({ name, value, domain: 'localhost', path: '/' })));
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1600); // 等 /api/users/me 回来把 role 填进 store
  contexts[key] = { ctx, page, consoleErrors };
}
const anonCtx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
const anonPage = await anonCtx.newPage();
const anonErrors = [];
anonPage.on('console', (m) => { if (m.type() === 'error') anonErrors.push(m.text()); });
anonPage.on('pageerror', (e) => anonErrors.push('pageerror: ' + e.message));
await anonPage.goto(`${UI}/`, { waitUntil: 'networkidle' });
await anonPage.waitForTimeout(800);
contexts['未登录'] = { ctx: anonCtx, page: anonPage, consoleErrors: anonErrors };

// ---------------- ① 宽度矩阵：任何角色、任何宽度都不得截断 ----------------
for (const [role, { page }] of Object.entries(contexts)) {
  step(`① ${role}：${WIDTHS.length} 档宽度逐项检查（导航不得被截断 / 昵称不得折行）`);
  const expectExtra = role === '运营' || role === '长昵称运营' ? ['商家端', '管理后台'] : [];
  for (const w of WIDTHS) {
    await page.setViewportSize({ width: w, height: 900 });
    await page.waitForTimeout(320);
    const m = await page.evaluate(MEASURE);
    check(`${role} @${w}px：顶栏无任何标签被截断（需要 ${m.navNeed}px / 可用 ${m.navHave}px）`,
      m.clipped.length === 0, m.clipped);
    if (role !== '未登录') {
      check(`${role} @${w}px：昵称不折行（高度 ${m.nickH}px，不撑出 ${m.btnH}px 的按钮）`,
        m.nickWrapped === false, { nickH: m.nickH, btnH: m.btnH });
    }
    const missing = [...NAV_CORE, ...expectExtra].filter((l) => !m.labels.includes(l));
    check(`${role} @${w}px：应有的导航项一个不少（缺 ${missing.join('·') || '无'}）`,
      missing.length === 0, m.labels);
    // 留一张证据图：1264px 正是用户截图的实际视口，也是最容易出问题的一档
    if (role === '运营' && w === 1264) {
      await page.screenshot({ path: path.join(SHOTS, 'header-layout-1264.png') });
    }
  }
}

// ---------------- ② 信息架构：顶栏不含个人事务项，但它们必须仍可达 ----------------
step('② 顶栏只放一级动线，个人事务收进头像下拉且仍可达');
{
  const { page } = contexts['买家'];
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.waitForTimeout(300);
  const navLabels = (await page.evaluate(MEASURE)).labels;
  const leaked = MENU_PERSONAL.filter((l) => navLabels.includes(l));
  check('顶栏不再重复放个人事务入口（我的订单 / 我的宠物 / 收货地址）',
    leaked.length === 0, { leaked, navLabels });

  await page.locator('.app-header button.icon-btn').first().click();
  await page.waitForTimeout(250);
  // 必须限定在**下拉菜单**里查：运营的顶栏本身也有「商家端」，
  // 不限定作用域会把两个都数进来（首版就是这么红的）。
  const menu = page.locator('.app-header .user-menu');
  const missing = [];
  for (const label of MENU_PERSONAL) {
    if ((await menu.getByRole('link', { name: label, exact: true }).count()) !== 1) missing.push(label);
  }
  check('★ 头像下拉里个人事务入口一个不少（收起来不等于删掉）',
    missing.length === 0, missing);

  // 真正点一次，证明可达（不是只渲染了个链接）
  await menu.getByRole('link', { name: '收货地址', exact: true }).click();
  await page.waitForURL((u) => u.pathname === '/addresses', { timeout: 10000 });
  await page.waitForTimeout(600);
  check('★ 从下拉点「收货地址」能真正进入地址页（入口可达，不只是长得像）',
    new URL(page.url()).pathname === '/addresses', page.url());

  // 顶栏一级动线也要真能点（拿「优惠券」当代表）
  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
  await page.locator('.app-header .nav-links').getByRole('link', { name: '优惠券', exact: true }).click();
  await page.waitForURL((u) => u.pathname === '/coupons', { timeout: 10000 });
  check('顶栏一级动线可点（优惠券 → /coupons）', new URL(page.url()).pathname === '/coupons', page.url());
}

// ---------------- ③ 不回归：两个工作台入口仍在 ----------------
step('③ 工作台入口不回归（商家端 / 管理后台）');
{
  const { page } = contexts['运营'];
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(700);
  const nav = page.locator('.app-header .nav-links');
  check('运营顶栏含「管理后台」（R63 的入口未被本轮改动破坏）',
    (await nav.getByRole('link', { name: '管理后台', exact: true }).count()) === 1);
  await page.locator('.app-header button.icon-btn').first().click();
  await page.waitForTimeout(250);
  const menu = page.locator('.app-header .user-menu');
  check('头像下拉含「进入管理后台」与「商家端」',
    (await menu.getByRole('link', { name: '进入管理后台', exact: true }).count()) === 1
    && (await menu.getByRole('link', { name: '商家端', exact: true }).count()) === 1,
    { admin: await menu.getByRole('link', { name: '进入管理后台', exact: true }).count(),
      seller: await menu.getByRole('link', { name: '商家端', exact: true }).count() });
  // 菜单没有 Esc 关闭行为，再点一次头像收起它（避免浮层盖住后续要点的元素）
  await page.locator('.app-header button.icon-btn').first().click();
  await page.waitForTimeout(200);

  const { page: buyerPage } = contexts['买家'];
  await buyerPage.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await buyerPage.waitForTimeout(500);
  check('普通买家顶栏不含「管理后台」',
    (await buyerPage.locator('.app-header .nav-links').getByRole('link', { name: '管理后台', exact: true }).count()) === 0);
}

// ---------------- ⑤ 头像下拉的关闭行为（R74-2） ----------------
step('⑤ 头像下拉：Esc 可关、点外部可关（此前只能靠「切换到别的页面」）');
{
  const { page } = contexts['买家'];
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);

  const menu = page.locator('.app-header .user-menu');
  const toggle = page.locator('.app-header button.icon-btn[aria-haspopup="menu"]');

  /**
   * 每个用例都**自建前置**（回首页 → 打开菜单 → 断言确已打开）再验证关闭行为。
   *
   * 为什么要这么啰嗦：首版是一条龙顺序写下来的，反向验证（临时停用关闭逻辑）时发现
   * 「点外部关闭」那条**依然通过** —— 因为前一条 Esc 用例失败后菜单还开着，
   * 下一个 click 把 toggle 又切回了关闭态，于是「点外部前菜单已关」→ 断言空转。
   * 失败用例的**残留状态**会让后续断言变得不可信，这正是「断言必须自证不级联」的由来。
   */
  const openMenu = async () => {
    await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(400);
    await toggle.click();
    await page.waitForTimeout(250);
    check('  前置：头像下拉已打开', (await menu.count()) === 1 && (await toggle.getAttribute('aria-expanded')) === 'true',
      `menu=${await menu.count()} aria-expanded=${await toggle.getAttribute('aria-expanded')}`);
  };

  // ---- ① Esc ----
  await openMenu();
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);
  check('★ Esc 能关掉头像下拉（此前关不掉，菜单一直压着页面右上角）', (await menu.count()) === 0,
    await menu.count());
  check('Esc 关闭后 aria-expanded 复位为 false', (await toggle.getAttribute('aria-expanded')) === 'false');
  check('★ Esc 关闭后焦点回到触发按钮（键盘用户不丢位置）',
    await page.evaluate(() => document.activeElement?.getAttribute('aria-haspopup') === 'menu'));

  // ---- ② 点菜单外部 ----
  // ★ 必须点「不跳转」的元素：首版点的是 brand（一个 Link），而路由变化本来就会收起菜单 ——
  // 那条断言在停用新逻辑后依然通过（反向验证抓出来的假通过）。这里点顶栏搜索框的放大镜（纯 span）。
  await openMenu();
  await page.locator('.app-header .search-inline span').first().click();
  await page.waitForTimeout(300);
  check('★ 点击菜单外部能关掉下拉（点的是不跳转元素，排除「路由切换顺带收起」）',
    (await menu.count()) === 0, await menu.count());

  // ---- ③ 反向：点菜单内部不能被误判成外部点击（否则用户根本点不到里面的入口） ----
  await openMenu();
  await menu.getByRole('link', { name: '个人中心', exact: true }).click();
  await page.waitForURL((u) => u.pathname === '/me', { timeout: 10000 });
  await page.waitForTimeout(300);
  check('★ 点菜单内部条目照常跳转（内部点击不被当成外部点击）',
    new URL(page.url()).pathname === '/me' && (await menu.count()) === 0, page.url());
}

// ---------------- ④ console ----------------
step('④ 控制台无真错误（401 系设计内，页面各自兜底）');
for (const [role, { consoleErrors }] of Object.entries(contexts)) {
  check(`${role}：无 console 报错 / pageerror`, consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
}

for (const { ctx } of Object.values(contexts)) await ctx.close();
await browser.close();

console.log(`\n=== 顶栏布局 UI 断言：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);

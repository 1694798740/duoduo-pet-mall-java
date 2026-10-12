/**
 * 平台管理后台「经营图表」· 真实浏览器 UI 自测（**自包含**）
 *
 * <h2>本轮用户要求「至少加入分布图、折线图、柱状图、饼图」</h2>
 * 六套断言分别钉住：四类图各自存在、canvas 真的画出来了、数据与接口一致、
 * **悬停即时出数据**（旧实现用原生 title，延迟 1–2 秒且不可配置）、
 * 图表**不阻塞**报表卡（图慢的时候数字要能先看到）、权限边界。
 *
 * 用法：node scripts/e2e/admin-charts-ui-check.mjs
 *
 * **E6b 改动**：后台页此前由**商城入口**的 `${UI}/admin` 驱动；E5 把后台从商城摘除后，
 * 商城站点已无 `/admin` 路由，故改指后台**独立站点**（`ADMIN_UI`）。
 * Cookie 注入的 domain 也必须随之改成后台主机名 —— Cookie 是**主机级**作用域，
 * 注入到 `localhost` 后浏览器在 `admin.localhost` 上根本不会带上它（表现是"会话丢了、停在登录页"）。
 */

import path from 'node:path';
import { shotsDir } from '../lib/e2e-shots.mjs';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
/** 商城站点：本套件只在改造说明里提及，实际断言全部在后台站点 */
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
/** 平台管理后台**独立站点**（图表页在这里） */
const ADMIN_UI = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
const SHOTS = shotsDir(); // 默认落到 gitignored 的 scripts/e2e/.shots；要归档配图用 SHOTS=<路径> 覆盖
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? undefined;

const PWD = 'duoduo12345';
const TOOLTIP_BUDGET_MS = 500;

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
const consoleNoise = (list) => list.filter((e) => !/favicon|status of 401/i.test(e));

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
const randPhone = (p) => p + String(Math.floor(1e7 + Math.random() * 8e7));

async function newOperator(nickname) {
  const jar = new Map();
  const phone = randPhone('138');
  await call('POST', '/api/auth/register', { phone, password: PWD, nickname }, jar);
  let role = '';
  for (let i = 0; i < 5; i++) {
    await mysql('duoduo_user', `UPDATE t_user SET role='OPERATOR' WHERE phone='${phone}';`);
    role = (await mysql('duoduo_user', `SELECT role FROM t_user WHERE phone='${phone}';`)).trim();
    if (role === 'OPERATOR') break;
  }
  await call('POST', '/api/auth/login', { phone, password: PWD }, jar);
  return { jar, role, phone };
}

// ---------------- 夹具 ----------------
step('夹具');
const op = await newOperator('后台图表-运营');
check('运营账号提权成功', op.role === 'OPERATOR', op.role);

const buyerJar = new Map();
const buyerPhone = randPhone('137');
const bReg = await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: '后台图表-买家' }, buyerJar);
check('普通买家账号注册成功', bReg.status === 200, bReg.status);

// ---------------- 接口 ----------------
step('接口：/api/admin/reports/charts 四个维度完整且是数字');
const chartsApi = await call('GET', '/api/admin/reports/charts', undefined, op.jar);
const c = chartsApi.data ?? {};
check('运营取到图表数据', chartsApi.status === 200 && Boolean(chartsApi.data), chartsApi.status);
check('分布数据：全平台订单状态 6 项', Array.isArray(c.statusDistribution) && c.statusDistribution.length === 6, c.statusDistribution?.length);
check('折线数据：趋势 14 点（**已补零**）', Array.isArray(c.trend) && c.trend.length === 14, c.trend?.length);
check('柱状数据：商品销量 Top 有数据', Array.isArray(c.topProducts) && c.topProducts.length > 0, c.topProducts?.length);
check('饼图数据：资金构成 3 项', Array.isArray(c.fundComposition) && c.fundComposition.length === 3, c.fundComposition?.length);
const slices = [...(c.statusDistribution ?? []), ...(c.topProducts ?? []), ...(c.fundComposition ?? [])];
check(`★ 切片数值是 JSON 数字而不是字符串（实测 ${typeof slices[0]?.value}）`,
  slices.every((s) => typeof s.value === 'number'), slices.filter((s) => typeof s.value !== 'number').slice(0, 2));
check('趋势点数值也是数字', (c.trend ?? []).every((t) => typeof t.orderCount === 'number' && typeof t.gmv === 'number'));
const statusSum = (c.statusDistribution ?? []).reduce((x, s) => x + s.value, 0);
const overview = await call('GET', '/api/admin/reports/overview', undefined, op.jar);
check(`状态分布六项之和 == 报表的总订单量（${statusSum} == ${overview.data?.totalOrders}）`,
  statusSum === overview.data?.totalOrders, { statusSum, total: overview.data?.totalOrders });

const denied = await call('GET', '/api/admin/reports/charts', undefined, buyerJar);
check(`★ 后端边界：普通用户调图表接口 403（实测 ${denied.status}）`, denied.status === 403, denied.status);

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

const READ_CHARTS = () => [...document.querySelectorAll('[role="img"]')].map((el) => ({
  label: el.getAttribute('aria-label') ?? '',
  canvas: (() => { const cv = el.querySelector('canvas'); return cv ? { w: cv.getBoundingClientRect().width, h: cv.getBoundingClientRect().height } : null; })(),
  hasNativeTitle: Boolean(el.getAttribute('title')),
}));
const TOOLTIP_TEXT = (i) => {
  const box = document.querySelectorAll('[role="img"]')[i];
  if (!box) return null;
  const found = [...box.querySelectorAll('div')]
    .filter((d) => (d.getAttribute('style') || '').includes('position: absolute') && /[笔件¥]/.test(d.textContent || ''));
  return found.length ? found[found.length - 1].textContent.trim() : null;
};
async function hoverAndMeasure(page, i, points) {
  const box = await page.locator('[role="img"]').nth(i).boundingBox();
  if (!box) return { ms: -1, text: null };
  const started = Date.now();
  for (const [relX, relY] of points) {
    await page.mouse.move(box.x + box.width * relX, box.y + box.height * relY);
    const deadline = Date.now() + 250;
    while (Date.now() < deadline) {
      const text = await page.evaluate(TOOLTIP_TEXT, i);
      if (text) return { ms: Date.now() - started, text };
      await page.waitForTimeout(15);
    }
  }
  return { ms: Date.now() - started, text: null };
}
/** 环形饼图沿环取点（圆心是空的，只试一个点会落进洞里） */
const RING_POINTS = (() => {
  const pts = [];
  for (let k = 0; k < 8; k++) {
    const ang = (Math.PI * 2 * k) / 8 - Math.PI / 2;
    pts.push([0.5 + 0.3 * Math.cos(ang) * 0.72, 0.44 + 0.3 * Math.sin(ang)]);
  }
  return pts;
})();

// ---------------- 页面 ----------------
step('① 运营视角 · 四类图与即时 tooltip');
{
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1200 }, locale: 'zh-CN' });
  await ctx.addCookies([...op.jar].map(([name, value]) => ({ name, value, domain: 'admin.localhost', path: '/' })));
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));

  await page.goto(`${ADMIN_UI}/admin`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(2800);

  const body = await page.locator('body').innerText();
  check('后台运营概览渲染出来', body.includes('平台管理后台 · 运营概览'), body.slice(0, 120));
  check('报表卡与资金对账仍在（图表是「补充」，不是替换）',
    body.includes('经营报表') && body.includes('资金对账') && body.includes('经营图表'));
  check('★ 用户点名的四类图都在',
    ['近 14 天成交趋势', '资金构成', '商品销量 Top 8', '订单状态分布'].every((t) => body.includes(t)));

  const charts = await page.evaluate(READ_CHARTS);
  check('页面上有 4 个图表容器', charts.length === 4, charts.length);
  check('每个图表都真的画出了 canvas（非零尺寸）',
    charts.every((x) => x.canvas && x.canvas.w > 100 && x.canvas.h > 100),
    charts.map((x) => (x.canvas ? `${Math.round(x.canvas.w)}x${Math.round(x.canvas.h)}` : 'no-canvas')));
  check('★ 图表不再使用原生 title 做悬停提示（那是 1–2 秒延迟的根源）',
    charts.every((x) => !x.hasNativeTitle), charts.map((x) => x.hasNativeTitle));

  const trendLabel = charts[0]?.label ?? '';
  check('折线图的摘要含日期点（可读断言锚点，canvas 本身读不出内容）',
    /\d{4}-\d{2}-\d{2}/.test(trendLabel), trendLabel.slice(0, 80));
  const fundLabel = charts[1]?.label ?? '';
  check('资金构成图摘要含三态金额', fundLabel.includes('托管中') && fundLabel.includes('¥'), fundLabel.slice(0, 100));

  const h1 = await hoverAndMeasure(page, 0, [[0.6, 0.5], [0.5, 0.4], [0.7, 0.6], [0.4, 0.6]]);
  check(`★★ 悬停折线图即时出数据：实测 ${h1.ms}ms（预算 ${TOOLTIP_BUDGET_MS}ms）`,
    h1.ms >= 0 && h1.ms < TOOLTIP_BUDGET_MS, h1);
  check('折线提示框同时给出订单数与成交额',
    Boolean(h1.text) && h1.text.includes('订单数') && h1.text.includes('成交额'), h1.text);

  const h2 = await hoverAndMeasure(page, 1, RING_POINTS);
  check(`★★ 悬停饼图即时出数据：实测 ${h2.ms}ms`, h2.ms >= 0 && h2.ms < TOOLTIP_BUDGET_MS, h2);
  check('饼图提示框给出金额与占比', Boolean(h2.text) && h2.text.includes('¥') && h2.text.includes('%'), h2.text);

  const h3 = await hoverAndMeasure(page, 3, [[0.4, 0.3], [0.4, 0.6], [0.3, 0.45]]);
  check(`★★ 悬停分布图即时出数据：实测 ${h3.ms}ms`, h3.ms >= 0 && h3.ms < TOOLTIP_BUDGET_MS, h3);
  check('分布提示框给出「状态 + 笔数」', Boolean(h3.text) && /笔/.test(h3.text), h3.text);

  check('后台概览页无 console 报错 / pageerror',
    consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await page.screenshot({ path: path.join(SHOTS, 'admin-dashboard-charts.png'), fullPage: true });
  await ctx.close();
}

// ---------------- ② 权限 ----------------
step('② 权限边界 · 普通用户看不到后台');
{
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN' });
  await ctx.addCookies([...buyerJar].map(([name, value]) => ({ name, value, domain: 'admin.localhost', path: '/' })));
  const page = await ctx.newPage();
  await page.goto(`${ADMIN_UI}/admin`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  const body = await page.locator('body').innerText();
  check('普通用户访问 /admin 看到拦截页（不是白屏）', body.includes('需要运营或管理员身份'), body.slice(0, 150));
  check('普通用户看不到任何图表', (await page.locator('[role="img"]').count()) === 0);
  await ctx.close();
}

// ---------------- ③ 主题约定（R74-6） ----------------
step('③ 图表主题：不跟随系统深色（避免与浅色卡片打架）');
{
  // 图表配色已集中到 components/chartTheme.ts，且**刻意不读 prefers-color-scheme**：
  // 应用卡片背景是固定浅色，图表若按系统偏好自行变深，会出现浅底 + 浅字/深轴线的不可读组合。
  // 本段用**深色系统偏好**的浏览器上下文打开同一个后台页：图表仍应是浅色一套。
  // 这条断言是防「半修复」的闸门 —— 将来谁只在图表里加个 prefers-color-scheme 分支，这里会红。
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 1200 },
    locale: 'zh-CN',
    colorScheme: 'dark',
  });
  // 复用运营会话（与①同姿势）
  await ctx.addCookies([...op.jar].map(([name, value]) => ({ name, value, domain: 'admin.localhost', path: '/' })));
  const page = await ctx.newPage();
  await page.goto(`${ADMIN_UI}/admin`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);

  const themeProbe = await page.evaluate(() => {
    const root = document.documentElement;
    const canvas = document.querySelector('canvas');
    const card = canvas?.closest('.card');
    const bg = card ? getComputedStyle(card).backgroundColor : '';
    const m = bg.match(/\d+/g)?.map(Number) ?? [];
    // 相对亮度（0=黑 1=白）；浅色卡片应显著偏亮
    const lum = m.length >= 3 ? (0.299 * m[0] + 0.587 * m[1] + 0.114 * m[2]) / 255 : -1;
    return { dataTheme: root.getAttribute('data-theme'), cardBg: bg, lum, hasCanvas: Boolean(canvas) };
  });

  check('深色系统偏好下图表仍正常渲染（canvas 存在）', themeProbe.hasCanvas, themeProbe);
  check('★ 应用未切换深色主题（无 data-theme）——图表主题跟随应用而非系统',
    !themeProbe.dataTheme, themeProbe.dataTheme);
  check('★ 图表所在卡片仍是浅色底（亮度 > 0.8），与浅色坐标轴/文字可读组合一致',
    themeProbe.lum > 0.8, themeProbe);
  await page.screenshot({ path: path.join(SHOTS, 'admin-charts-darkpref.png'), fullPage: true });
  await ctx.close();
}

// ---------------- ④ 主题接入点（S10）：<html data-theme> 真的会改图表配色 ----------------
step('④ 图表主题接入点：切到 data-theme="dark" 时图表配色真的改变');
{
  // S10 前半。chartTheme.ts 的 currentChartThemeMode() 读 <html data-theme>，
  // charts.tsx 在**组件内**求值（不再是模块级常量 —— 那样主题切了不会生效）。
  // 本段是「能走到 dark 分支」的端到端证据（铁律 19：凡降级/兜底必写能走到那个分支的测试）：
  // 不注入 data-theme 的话，那一套 dark 配色永远是死代码，坏了也没人知道。
  //
  // ★ 判据用 **canvas 像素抽样指纹**，不用截图文件的哈希 ——
  //   同一像素重新编码成 PNG 的字节也可能不同（压缩非确定性），拿文件哈希会假红。
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1200 }, locale: 'zh-CN' });
  await ctx.addCookies([...op.jar].map(([name, value]) => ({ name, value, domain: 'admin.localhost', path: '/' })));
  const page = await ctx.newPage();

  /** 抽样读 canvas 像素做指纹（步长 97：够快，且对配色变化敏感） */
  const canvasFingerprint = async () => {
    await page.locator('canvas').first().waitFor({ state: 'visible', timeout: 15000 });
    return page.evaluate(() => {
      const c = document.querySelector('canvas');
      const g = c.getContext('2d');
      const d = g.getImageData(0, 0, c.width, c.height).data;
      let h = 0;
      for (let i = 0; i < d.length; i += 97) h = (Math.imul(h, 31) + d[i]) >>> 0;
      return { hash: h, w: c.width, hh: c.height };
    });
  };

  // ① 默认加载：应用没有任何地方写 data-theme → 图表应走 light
  await page.goto(`${ADMIN_UI}/admin`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  const defaultTheme = await page.evaluate(() => document.documentElement.getAttribute('data-theme'));
  const light = await canvasFingerprint();

  // ② 强制 dark：必须在页面脚本执行**之前**设上 —— React 只在渲染时求值，
  //    页面加载完再改属性不会触发重渲染。用 addInitScript + reload 保证顺序。
  await page.addInitScript(() => {
    const apply = () => {
      if (document.documentElement) { document.documentElement.setAttribute('data-theme', 'dark'); return true; }
      return false;
    };
    if (!apply()) {
      new MutationObserver((_, ob) => { if (apply()) ob.disconnect(); })
        .observe(document, { childList: true, subtree: true });
    }
  });
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  const forcedTheme = await page.evaluate(() => document.documentElement.getAttribute('data-theme'));
  const dark = await canvasFingerprint();

  check('④ 默认加载时应用未设置 data-theme（图表走 light）', defaultTheme === null, defaultTheme);
  check('④ 注入的 data-theme="dark" 确实生效（证明注入方式可靠、断言有效）', forcedTheme === 'dark', forcedTheme);
  check('★ ④ dark 与 light 的 canvas 像素指纹不同（图表主题真的被消费，不是死代码）',
    light.hash !== dark.hash, `light=${light.hash} dark=${dark.hash}`);
  check('④ 两次渲染的 canvas 尺寸一致（排除「因为尺寸变了才不同」）',
    light.w === dark.w && light.hh === dark.hh, `light=${light.w}x${light.hh} dark=${dark.w}x${dark.hh}`);

  await ctx.close();
}

// ---------------- 收尾：运营夹具降权（不留多余后台入口） ----------------
// 与 seckill / share-report 同一处置：e2e 用「随机手机号 + 库提权」建的 OPERATOR
// 必须收尾降权，否则每跑一次就在库里多留一个**能登录平台后台**的账号。
await mysql('duoduo_user', `UPDATE t_user SET role='USER' WHERE phone='${op.phone}';`);
const opRoleAfter = (await mysql('duoduo_user', `SELECT role FROM t_user WHERE phone='${op.phone}';`)).trim();
check('★ 收尾：运营夹具已降权回 USER（不留多余后台入口）', opRoleAfter === 'USER', opRoleAfter);

await browser.close();

console.log(`\n=== 平台后台图表 UI 断言：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);

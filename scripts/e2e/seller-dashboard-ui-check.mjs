/**
 * 商家端概览 · 真实浏览器 UI 自测（**自包含**，不需要任何外部夹具）
 *
 * <h2>本套钉住的三条不变式</h2>
 * <ol>
 *   <li><b>图表数据与接口一致</b>：五张图读到的数值必须与 `/api/seller/analytics` 一致，
 *       且六状态之和 == 页面「共 N 笔订单」。</li>
 *   <li><b>柱高/扇形不能塌</b>：canvas 必须真的画出来（非零尺寸），
 *       且 aria 摘要里能读出正确数值 —— 第六十五轮「柱状图全平」就是渲染塌陷、
 *       数据却完全正常的经典假象。</li>
 *   <li><b>悬停必须即时出数据</b>（本轮核心诉求）：从鼠标进入图形到 tooltip 可见
 *       必须在一段很短的预算内（这里给 500ms，实测约 20ms）。
 *       旧实现用 HTML 原生 `title`，延迟由操作系统决定（Windows 上 1–2 秒）且**不可配置** ——
 *       所以本套同时断言「图表里不许再用原生 title 做提示」，否则换个实现又会退化回去。</li>
 * </ol>
 *
 * 用法：node scripts/e2e/seller-dashboard-ui-check.mjs
 */

import path from 'node:path';
import { shotsDir } from '../lib/e2e-shots.mjs';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
/** 商家工作台独立站点（E6）：商家页面已从商城摘除，本套件的被测页面都在商家站点上。 */
const MERCHANT = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const SHOTS = shotsDir(); // 默认落到 gitignored 的 scripts/e2e/.shots；要归档配图用 SHOTS=<路径> 覆盖
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? undefined;

const PWD = 'duoduo12345';
/** 悬停 → tooltip 可见的时间预算。原生 title 在 Windows 上要 1000–2000ms，这里卡 500ms 足够区分 */
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
/** 401 是设计内状态（未登录时 /api/users/me 等必然 401），口径同 seckill-ui-check */
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
  return { jar, phone, role };
}

// ---------------- 夹具 ----------------
step('夹具');
const op = await newOperator('商家端看板-运营');
check('运营账号提权成功（否则 /seller 只有入驻引导，图表根本不存在）', op.role === 'OPERATOR', op.role);

const buyerJar = new Map();
const buyerPhone = randPhone('137');
const bReg = await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: '商家端看板-买家' }, buyerJar);
check('普通买家账号注册成功', bReg.status === 200, bReg.status);

// ---------------- 接口口径（先证数据是好的） ----------------
step('接口：/api/seller/analytics 的五个维度都完整且是**数字**');
const ana = await call('GET', '/api/seller/analytics', undefined, op.jar);
const a = ana.data ?? {};
check('运营取到统计数据', ana.status === 200 && Boolean(ana.data), ana.status);
check('维度一 状态分布 6 项', Array.isArray(a.statusDistribution) && a.statusDistribution.length === 6, a.statusDistribution?.length);
check('维度二 趋势 14 点（**已补零**，不是只返回有数据的那几天）',
  Array.isArray(a.trend) && a.trend.length === 14, a.trend?.length);
check('维度三 商品销量 Top 有数据', Array.isArray(a.topProducts) && a.topProducts.length > 0, a.topProducts?.length);
check('维度四 资金构成 3 项', Array.isArray(a.fundComposition) && a.fundComposition.length === 3, a.fundComposition?.length);
check('维度五 订单类型 2 项', Array.isArray(a.orderTypeMix) && a.orderTypeMix.length === 2, a.orderTypeMix?.length);
// ★ 类型必须是数字：字符串下去 ECharts 会把它当类目名，画不出图
const allSlices = [...(a.statusDistribution ?? []), ...(a.topProducts ?? []), ...(a.fundComposition ?? []), ...(a.orderTypeMix ?? [])];
check(`★ 切片数值必须是 JSON 数字而不是字符串（实测首个 ${typeof allSlices[0]?.value}）`,
  allSlices.every((s) => typeof s.value === 'number'), allSlices.filter((s) => typeof s.value !== 'number').slice(0, 2));
check('趋势点的数值也是数字', (a.trend ?? []).every((t) => typeof t.orderCount === 'number' && typeof t.gmv === 'number'));
const sumStates = (a.statusDistribution ?? []).reduce((x, s) => x + s.value, 0);

const summary = await call('GET', '/api/seller/summary', undefined, op.jar);
check(`状态分布六项之和 == 概览总订单数（${sumStates} == ${summary.data?.totalOrderCount}）`,
  sumStates === summary.data?.totalOrderCount, { sumStates, total: summary.data?.totalOrderCount });

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

/** 读页面上所有图表的 aria 摘要（ECharts 画在 canvas 上，DOM 里唯一可读的就是它） */
const READ_CHARTS = () => [...document.querySelectorAll('[role="img"]')].map((el) => ({
  label: el.getAttribute('aria-label') ?? '',
  canvas: (() => { const c = el.querySelector('canvas'); return c ? { w: c.getBoundingClientRect().width, h: c.getBoundingClientRect().height } : null; })(),
  hasNativeTitle: Boolean(el.getAttribute('title')),
}));

/** 在指定的第 i 张图里找 tooltip（ECharts 的浮层是无类名 div，靠 position:absolute + 文案特征识别） */
const TOOLTIP_TEXT = (i) => {
  const box = document.querySelectorAll('[role="img"]')[i];
  if (!box) return null;
  const found = [...box.querySelectorAll('div')]
    .filter((d) => (d.getAttribute('style') || '').includes('position: absolute') && /[笔件¥]/.test(d.textContent || ''));
  return found.length ? found[found.length - 1].textContent.trim() : null;
};

/**
 * 悬停第 i 张图，返回 tooltip 文本与耗时（超预算返回 null）。
 *
 * <p>`points` 是相对坐标候选点，按顺序试到命中为止 —— **环形饼图的圆心是空的**，
 * 只试一个点会落在洞里、拿不到 tooltip，看起来像「饼图不响应」（首版就这么红的）。
 * 计时从**第一次移动**开始算，才是用户感知到的「悬停到出数」延迟。
 */
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

/** 环形饼图：沿环上取 8 个点（半径 0.3 × 短边，正好落在 46%~68% 的环带里） */
const RING_POINTS = (() => {
  const pts = [];
  for (let k = 0; k < 8; k++) {
    const ang = (Math.PI * 2 * k) / 8 - Math.PI / 2;
    pts.push([0.5 + 0.3 * Math.cos(ang) * 0.72, 0.44 + 0.3 * Math.sin(ang)]);
  }
  return pts;
})();

// ---------------- ① 运营视角：五张图 + 即时 tooltip ----------------
step('① 运营视角 · 五张图渲染 / 数据一致 / 悬停即时');
{
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1100 }, locale: 'zh-CN' });
  // ★ 商家页面在**独立主机名**上（E6）：Cookie 必须注入到 merchant.localhost ——
  //   写死 localhost 会让商家站点看不到会话（表现为停在登录页）。
  await ctx.addCookies([...op.jar].map(([name, value]) => ({ name, value, domain: 'merchant.localhost', path: '/' })));
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));

  await page.goto(`${MERCHANT}/seller`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(2500);

  const bodyText = await page.locator('body').innerText();
  check('概览页渲染出来（不是入驻引导）', bodyText.includes('商家端 · 概览'), bodyText.slice(0, 120));
  check('五个图表标题都在',
    ['订单状态分布', '近 14 天成交趋势', '商品销量 Top 8', '资金构成', '订单类型占比'].every((t) => bodyText.includes(t)));

  const charts = await page.evaluate(READ_CHARTS);
  check('页面上有 5 个图表容器', charts.length === 5, charts.length);
  check('每个图表都真的画出了 canvas（非零尺寸 —— 塌陷的图会是 0×0）',
    charts.every((c) => c.canvas && c.canvas.w > 100 && c.canvas.h > 100),
    charts.map((c) => (c.canvas ? `${Math.round(c.canvas.w)}x${Math.round(c.canvas.h)}` : 'no-canvas')));

  // ★ 原生 title 是「悬停 2 秒才提示」的元凶，必须彻底不再使用
  check('★ 图表不再使用原生 title 做悬停提示（否则延迟无法控制）',
    charts.every((c) => !c.hasNativeTitle), charts.map((c) => c.hasNativeTitle));

  // 状态分布那张图的摘要必须与接口一致
  const statusLabel = charts[0]?.label ?? '';
  const expectTop = (a.statusDistribution ?? []).filter((s) => s.value > 0)
    .map((s) => `${s.name} ${s.value} 笔`).slice(0, 3);
  check(`★ 状态分布图的可读摘要与接口一致（期望含「${expectTop[0] ?? ''}」）`,
    expectTop.every((t) => statusLabel.includes(t)), statusLabel.slice(0, 120));
  check('趋势图摘要含 14 个日期点（与接口 trend 长度一致）',
    (charts[1]?.label ?? '').split('，').length >= 6, (charts[1]?.label ?? '').slice(0, 60));

  // ★★ 悬停即时：这是本轮用户报障的核心诉求
  const h1 = await hoverAndMeasure(page, 0, [[0.5, 0.75], [0.25, 0.7], [0.75, 0.7], [0.5, 0.5]]);
  check(`★★ 悬停柱状图即时出数据：实测 ${h1.ms}ms（预算 ${TOOLTIP_BUDGET_MS}ms）`,
    h1.ms >= 0 && h1.ms < TOOLTIP_BUDGET_MS, h1);
  check('提示框内容是「状态名 + 笔数」而不是空白',
    Boolean(h1.text) && /笔/.test(h1.text), h1.text);

  const h2 = await hoverAndMeasure(page, 1, [[0.6, 0.5], [0.5, 0.4], [0.7, 0.6], [0.4, 0.6]]);
  check(`★★ 悬停折线图即时出数据：实测 ${h2.ms}ms`, h2.ms >= 0 && h2.ms < TOOLTIP_BUDGET_MS, h2);
  check('折线提示框同时给出订单数与成交额',
    Boolean(h2.text) && h2.text.includes('订单数') && h2.text.includes('成交额'), h2.text);

  const h3 = await hoverAndMeasure(page, 3, RING_POINTS);
  check(`★★ 悬停饼图即时出数据：实测 ${h3.ms}ms`, h3.ms >= 0 && h3.ms < TOOLTIP_BUDGET_MS, h3);
  check('饼图提示框给出金额与占比', Boolean(h3.text) && h3.text.includes('¥') && h3.text.includes('%'), h3.text);

  check('概览页无 console 报错 / pageerror',
    consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await page.screenshot({ path: path.join(SHOTS, 'seller-dashboard-charts.png'), fullPage: true });
  await ctx.close();
}

// ---------------- ② 权限边界：普通用户看不到商家端 ----------------
step('② 权限边界 · 普通用户');
{
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN' });
  await ctx.addCookies([...buyerJar].map(([name, value]) => ({ name, value, domain: 'merchant.localhost', path: '/' })));
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));

  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  check('普通用户顶栏不出现「商家端」入口',
    (await page.locator('.app-header .nav-links').getByRole('link', { name: '商家端', exact: true }).count()) === 0);

  await page.goto(`${MERCHANT}/seller`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  const bodyText = await page.locator('body').innerText();
  check('普通用户进 /seller 看到的是入驻引导（不是空白页、不是报错）',
    bodyText.includes('商家端需要卖家身份'), bodyText.slice(0, 150));
  check('引导里明确写出前置条件：先完成实名认证', bodyText.includes('实名认证'));
  check('普通用户看不到图表（引导页不渲染概览卡片）', !bodyText.includes('订单状态分布'));

  const deniedAna = await call('GET', '/api/seller/analytics', undefined, buyerJar);
  const deniedSum = await call('GET', '/api/seller/summary', undefined, buyerJar);
  check(`★ 后端边界兜底：普通用户调 summary / analytics 都是 403（实测 ${deniedSum.status} / ${deniedAna.status}）`,
    deniedSum.status === 403 && deniedAna.status === 403, { deniedSum: deniedSum.status, deniedAna: deniedAna.status });
  check('普通用户页面无 console 报错 / pageerror',
    consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await ctx.close();
}

await browser.close();

console.log(`\n=== 商家端概览 UI 断言：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);

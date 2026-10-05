/**
 * 商家端概览 · 真实浏览器 UI 自测（**自包含**，不需要任何外部夹具）
 *
 * 为什么必须补这一套：用户报「订单状态分布柱状图全部是平的、数值全为零」。
 * 实测后确认**数据完全正常**（待发货 144 / 在途 20 / 已完成 260 / 已退款 104 / 已关闭 273），
 * 塌掉的是**渲染**：柱高用百分比，而父元素 `.bar-col` 的高度是 `auto` ——
 * 「父高由内容决定、内容高又依赖父高」形成循环依赖，浏览器按规范把百分比退化成 `auto`，
 * 于是**无论数值多大，所有柱子都塌成 `min-height:3px`**。表象是「全平、像是零」。
 *
 * 这类缺陷**接口层与静态检查都测不出来**（接口 200、字段齐全、style.height 的百分比也写对了），
 * 只有真开浏览器量一次「柱子实际渲染高度 vs 数值」才看得见。因此本套钉的是**不变式**：
 *   ① 柱高比例 == 数值比例（成正比）；
 *   ② 值最大的那根柱子不能塌（高度 ≥ 轨道的 80%）；
 *   ③ 值为 0 的柱子保持最小高度（不能把 0 画成有高度）；
 *   ④ 各状态之和 == 概览页显示的「共 N 笔订单」（图与总数同源）；
 *   ⑤ 权限边界：普通用户进 /seller 看到入驻引导、接口 403。
 *
 * 用法：node scripts/e2e/seller-dashboard-ui-check.mjs
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
  return { status: res.status, data: json?.data, json };
}
const randPhone = (p) => p + String(Math.floor(1e7 + Math.random() * 8e7));

/** 建一个 OPERATOR：商家端对运营开放「平台自营」范围，因此一定有数据可画 */
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
step('接口：/api/seller/summary 的数据是完整的');
const summary = await call('GET', '/api/seller/summary', undefined, op.jar);
const FIELDS = ['pendingPaymentCount', 'pendingShipCount', 'shippedCount',
  'completedCount', 'refundedCount', 'closedCount', 'totalOrderCount'];
check('运营取到概览数据', summary.status === 200 && summary.data, summary.status);
const missing = FIELDS.filter((f) => typeof summary.data?.[f] !== 'number');
check('六个状态计数 + 总订单数都是数字（不是缺字段/字符串）', missing.length === 0, missing);
const sumOfStates = FIELDS.slice(0, 6).reduce((a, f) => a + (summary.data?.[f] ?? 0), 0);
check(`接口口径自洽：六状态之和 == totalOrderCount（${sumOfStates} == ${summary.data?.totalOrderCount}）`,
  sumOfStates === summary.data?.totalOrderCount, { sumOfStates, total: summary.data?.totalOrderCount });
check('接口层确实有非零状态（否则下面「柱子不能塌」的断言会失去意义）',
  sumOfStates > 0, sumOfStates);

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

/** 在页面里量柱状图：解析 title 里的数值 + 读实际渲染高度 */
const MEASURE_CHART = () => {
  const bars = document.querySelector('.bars');
  if (!bars) return { missing: true };
  const cols = [...bars.querySelectorAll('.bar-col')];
  return {
    trackHeight: bars.clientHeight - parseFloat(getComputedStyle(bars).paddingTop || '0'),
    colHeight: cols.length ? +cols[0].getBoundingClientRect().height.toFixed(1) : null,
    series: cols.map((c) => {
      const bar = c.querySelector('.bar');
      const title = bar ? bar.getAttribute('title') ?? '' : '';
      const m = /:\s*(\d+)/.exec(title);
      return {
        label: c.querySelector('.bar-label')?.textContent.trim() ?? '?',
        value: m ? Number(m[1]) : null,
        height: bar ? +bar.getBoundingClientRect().height.toFixed(1) : null,
        inlineHeight: bar ? bar.style.height : null,
      };
    }),
  };
};

// ---------------- ① 运营视角：图表必须按数值成比例 ----------------
step('① 运营视角 · 订单状态分布（柱高必须与数值成正比）');
{
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN' });
  await ctx.addCookies([...op.jar].map(([name, value]) => ({ name, value, domain: 'localhost', path: '/' })));
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));

  await page.goto(`${UI}/seller`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);

  const bodyText = await page.locator('body').innerText();
  check('概览页渲染出来（不是入驻引导）', bodyText.includes('商家端 · 概览'), bodyText.slice(0, 120));

  const m = await page.evaluate(MEASURE_CHART);
  check('图表存在且六根柱子都在', !m.missing && m.series.length === 6, m.series.map((s) => s.label));
  const parsed = m.series.filter((s) => typeof s.value === 'number');
  check('每根柱子的数值都可从 title 中读出（便于断言而非靠肉眼）', parsed.length === 6, m.series.length);

  const nonzero = m.series.filter((s) => s.value > 0);
  const zero = m.series.filter((s) => s.value === 0);
  check('接口里确实存在非零状态（前置自证）', nonzero.length > 0, m.series);

  // ② 最高柱不能塌
  const tallest = m.series.reduce((a, b) => (b.value > a.value ? b : a), m.series[0]);
  check(`★ 最高柱（${tallest.label} ${tallest.value} 笔）高度 ≥ 轨道的 80%：实测 ${tallest.height}px / 轨道 ${m.trackHeight}px`,
    tallest.height >= m.trackHeight * 0.8, { height: tallest.height, track: m.trackHeight });

  // ① 成正比：任取两根，高度比 ≈ 数值比
  let worst = { ratio: 0, pair: null };
  for (let i = 0; i < m.series.length; i++) {
    for (let j = i + 1; j < m.series.length; j++) {
      const a = m.series[i], b = m.series[j];
      if (!(a.value > 0 && b.value > 0)) continue;
      const wantH = (a.value / b.value) * b.height;
      const ratio = Math.abs(wantH - a.height) / Math.max(wantH, 1);
      if (ratio > worst.ratio) worst = { ratio, pair: `${a.label}/${b.label} 期望${wantH.toFixed(1)}px 实际${a.height}px` };
    }
  }
  check(`★ 柱高与数值成正比（最差一格相对偏差 ${(worst.ratio * 100).toFixed(1)}%：${worst.pair ?? '—'}）`,
    worst.ratio < 0.1, worst);

  // ③ 0 值不能画成有高度
  check(`值为 0 的柱子保持最小高度、不参与等比（${zero.map((z) => z.label).join('·') || '本页无 0 值'}）`,
    zero.every((z) => z.height <= 5),
    zero.map((z) => ({ label: z.label, h: z.height })));

  // ④ 图与总数同源
  const totalMatch = /共\s*(\d+)\s*笔订单/.exec(bodyText);
  const pageTotal = totalMatch ? Number(totalMatch[1]) : null;
  const chartSum = m.series.reduce((a, s) => a + (s.value ?? 0), 0);
  check(`★ 六根柱子的数值之和 == 页面上「共 N 笔订单」（${chartSum} == ${pageTotal}）`,
    pageTotal !== null && chartSum === pageTotal, { chartSum, pageTotal });

  check('概览页无 console 报错 / pageerror',
    consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await page.screenshot({ path: path.join(SHOTS, 'seller-dashboard-chart.png') });
  await ctx.close();
}

// ---------------- ② 权限边界：普通用户看不到商家端 ----------------
step('② 权限边界 · 普通用户');
{
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN' });
  await ctx.addCookies([...buyerJar].map(([name, value]) => ({ name, value, domain: 'localhost', path: '/' })));
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));

  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  check('普通用户顶栏不出现「商家端」入口',
    (await page.locator('.app-header .nav-links').getByRole('link', { name: '商家端', exact: true }).count()) === 0);

  await page.goto(`${UI}/seller`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  const bodyText = await page.locator('body').innerText();
  check('普通用户进 /seller 看到的是入驻引导（不是空白页、不是报错）',
    bodyText.includes('商家端需要卖家身份'), bodyText.slice(0, 150));
  check('引导里明确写出前置条件：先完成实名认证',
    bodyText.includes('实名认证'), bodyText.slice(0, 300));
  check('普通用户看不到图表（引导页不渲染概览卡片）', !bodyText.includes('订单状态分布'));

  const denied = await call('GET', '/api/seller/summary', undefined, buyerJar);
  check(`★ 后端边界兜底：普通用户调 /api/seller/summary 得到 403（实测 ${denied.status}）`,
    denied.status === 403, denied.status);
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

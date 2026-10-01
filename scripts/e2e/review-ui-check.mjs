/**
 * 评价与晒单（M11/P2-1）· 真实浏览器 UI 自测
 *
 * 为什么接口冒烟（review-smoke.ps1）全绿还不够：
 *   接口对了不代表页面走通 —— 路由挂错、按钮点不动、接口参数名写错
 *   这些只有真实浏览器里点一遍才现形（M9 的物流单号、M6 的字典码值都是这么发现的）。
 *
 * 本脚本做三件事：
 *   1. 用真实接口备一笔**已完成**订单（下单 → 支付 → 内部发货 → 确认收货）；
 *   2. 真实 Chromium 登录后走：/orders 看「去评价」→ /reviews/new 打分提交 →
 *      /reviews/mine 看到评价 → /product/{spuId} 看到公开评价区；
 *   3. 每步截图 + 断言真实文案，并收集 console 错误与非预期 HTTP 错误。
 *
 * 前置：8 容器在跑（网关 :8080）、frontend dev server 在跑（:5173；若本机报 EACCES 见 08 篇 T-27，
 *       改用 --port 3000 并设 UI_BASE=http://localhost:3000，3000 已在 CORS 白名单内）
 *
 * ⚠️ 必须用 http://localhost:5173，不能用 127.0.0.1 —— 网关 CORS 白名单只认 localhost。
 *
 * 用法：
 *   cd frontend && node node_modules/vite/bin/vite.js --port 5173   # 另开一个终端
 *   node scripts/e2e/review-ui-check.mjs
 */

import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { mysql as mysqlExec } from '../lib/docker-exec.mjs';
import { internalHeaders } from '../lib/internal-token.mjs';

const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
// 注：R60 前这里靠「直连交易服务 :8083 + 内部凭据」发货，那条路子已随旧端点一起下线，
// 现在统一走网关的卖家正规入口，所以不再需要 TRADE_BASE。

const UI = process.env.UI_BASE ?? 'http://localhost:5173';
const SHOTS = 'D:/智能体项目/duoduo-pet-mall-java/frontend/docs/screenshots';
const CHROME =
  process.env.CHROME_PATH ?? null; // 不指定则交给 playwright 解析自己安装的那份浏览器
const PW_CORE =
  process.env.PW_CORE ??
  'file:///D:/智能体项目/duoduo-pet-mall-java/scripts/e2e/node_modules/playwright-core/index.mjs';
// 内部凭据：R58 起是 HMAC 签名（dd1.caller.ts.nonce.sig），发明文旧令牌只会 403。
const PWD = 'duoduo12345';
const SKU = 2001;

const { chromium } = await import(PW_CORE);

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

async function call(method, p, body, jar, extraHeaders) {
  const headers = { ...(extraHeaders ?? {}) };
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
  try {
    json = JSON.parse(raw);
  } catch {
    /* 保留 raw：雪花 ID 需要按原始文本提取 */
  }
  return { status: res.status, data: json?.data, json, raw };
}

/**
 * 库查询（异步）。
 * 不能用 execFileSync：本机 Node 的**同步**派生态一律 EBUSY（连 cmd/where 都起不来），
 * 而异步 spawn 正常 —— 详见 scripts/lib/docker-exec.mjs 文件头。之前卡在这里时，
 * 表象是 `Error: spawnSync docker EBUSY`，看起来像 docker 不可用，方向会整个跑偏。
 */
const sql = (db, q) => mysqlExec(db, q);

/**
 * 平台运营会话（R60）
 *
 * 为什么需要它：旧的直连发货 `/api/orders/{no}/ship` 只验内部凭据、**不验归属** ——
 * 有内部凭据就能发任何人的货。它已在 R60 下线，正规入口是 `/api/seller/orders/{no}/ship`，
 * 走 `requireSellerScope` + `requireOwnOrder`。而本脚本这笔是**自营订单**（seller_id=0，
 * 归平台），按归属规则只有 OPERATOR 发得动，普通 SELLER 会被 403。
 * 所以这里必须现场造一个 OPERATOR 会话，不能沿用买家 jar。
 *
 * 提权走库（与 scripts/lib/platform-operator.ps1 同姿势）：运营角色没有自助申请入口，
 * 也不该有 —— 那是后台人工审批的事。角色没提成就让调用方看见：带 USER 角色去调
 * 卖家接口只会得到 403，报错却是「该账号不是卖家」，排查方向会完全跑偏。
 */
async function newPlatformOperator() {
  const phone = `136${String(Math.floor(1e7 + Math.random() * 8e7))}`;
  await call('POST', '/api/auth/register', { phone, password: PWD, nickname: '平台运营' }, null);
  let role = '';
  for (let i = 0; i < 5; i++) {
    await sql('duoduo_user', `UPDATE t_user SET role='OPERATOR' WHERE phone='${phone}';`);
    role = (await sql('duoduo_user', `SELECT role FROM t_user WHERE phone='${phone}';`)).trim();
    if (role === 'OPERATOR') break;
  }
  const jar = new Map();
  const login = await call('POST', '/api/auth/login', { phone, password: PWD }, jar);
  return { phone, role, jar, status: login.status };
}

// ---------------- 1. 夹具 ----------------
step('夹具：SKU 快照');
const snapRes = await call('GET', `/api/products/internal/skus/batch?ids=${SKU}`, undefined, null, internalHeaders('GET'));
const snap = Array.isArray(snapRes.data) ? snapRes.data[0] : snapRes.data;
if (!snap?.spuId) throw new Error(`取 SKU 快照失败：${snapRes.status} ${snapRes.raw?.slice(0, 200)}`);
const SPU = snap.spuId;
check(`取到 SKU ${SKU} 快照（SPU ${SPU}）`, Boolean(snap.spuId), snap);

step('夹具：买家 + 一笔已完成订单');
const phone = `135${String(Math.floor(1e7 + Math.random() * 8e7))}`;
const jar = new Map();
const reg = await call('POST', '/api/auth/register', { phone, password: PWD, nickname: '评价UI测试' }, jar);
check('买家注册成功', reg.status === 200, reg.status);

await call('POST', '/api/cart/items', { skuId: SKU, quantity: 1 }, jar);
const placed = await call('POST', '/api/orders', {
  receiverName: '小林',
  receiverPhone: '13900000000',
  receiverAddress: '上海市浦东新区张江路 1 号',
  idempotentKey: randomUUID(),
}, jar);
const orderNo = placed.data?.orders?.[0]?.orderNo;
check('下单成功（取到订单号）', Boolean(orderNo), placed.status);

const paid = await call('POST', `/api/orders/${orderNo}/pay`, { channel: 'MOCK', idempotentKey: randomUUID() }, jar);
check('支付成功（MOCK 即时）', paid.data?.orderStatus === 'PAID', paid.data?.orderStatus);

step('夹具：平台运营会话（自营订单只归平台，普通卖家发不动）');
const op = await newPlatformOperator();
check(`平台运营会话就绪（role=${op.role}）`, op.role === 'OPERATOR' && op.status === 200, {
  role: op.role,
  login: op.status,
});

const shipped = await call('POST', `/api/seller/orders/${orderNo}/ship`, {
  logisticsCompany: '顺丰速运', logisticsNo: `SF${Date.now()}`,
}, op.jar);
check('卖家正规入口发货成功', shipped.data?.status === 'SHIPPED', { status: shipped.data?.status, http: shipped.status });

const confirmed = await call('POST', `/api/orders/${orderNo}/confirm`, undefined, jar);
check('确认收货 → COMPLETED', confirmed.data?.status === 'COMPLETED', confirmed.data?.status);

// ---------------- 2. 浏览器 ----------------
const browser = await chromium.launch({ executablePath: CHROME ?? undefined, args: ['--no-sandbox'] });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
const page = await ctx.newPage();
const consoleErrors = [];
const httpErrors = [];
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text());
});
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
page.on('response', (r) => {
  if (r.status() >= 400) httpErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`);
});

const shot = async (name) => {
  await page.screenshot({ path: path.join(SHOTS, name), fullPage: true });
  console.log(`  [截图] frontend/docs/screenshots/${name}`);
};
const textNow = () => page.locator('body').innerText();

step('UI：登录');
await page.goto(`${UI}/login`, { waitUntil: 'networkidle' });
await page.getByPlaceholder('11 位手机号').fill(phone);
await page.getByPlaceholder('请输入密码').fill(PWD);
await page.getByRole('button', { name: /^登录$/ }).click();
await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
check('登录取到登录态', !page.url().includes('/login'), page.url());

step('UI：订单列表出现「去评价」入口');
await page.goto(`${UI}/orders?status=COMPLETED`, { waitUntil: 'networkidle' });
await page.waitForTimeout(800);
let t = await textNow();
check('已完成订单列表出现「去评价」按钮', t.includes('去评价'), t.slice(0, 300));
await shot('rev_orders_entry.png');

step('UI：进入评价页并提交');
await page.getByRole('link', { name: '去评价' }).first().click();
await page.waitForURL((u) => u.pathname.includes('/reviews/new'), { timeout: 15000 });
await page.waitForTimeout(600);
t = await textNow();
check('评价页展示订单号', t.includes(orderNo), orderNo);
check('评价页出现评分与内容输入区', t.includes('评分') && t.includes('评价内容'), t.slice(0, 200));
await page.getByRole('button', { name: '5 星' }).first().click();
await page.getByPlaceholder(/说说商品怎么样/).fill('毛孩子吃得很香，包装也扎实');
await page.getByRole('checkbox').check();
await shot('rev_new_form.png');
await page.getByRole('button', { name: /提交 \d+ 条评价/ }).click();
await page.waitForURL((u) => u.pathname.includes('/reviews/mine'), { timeout: 20000 });
check('提交后跳转「我的评价」', page.url().includes('/reviews/mine'), page.url());

step('UI：我的评价');
await page.waitForTimeout(800);
t = await textNow();
check('我的评价里出现刚写的内容', t.includes('毛孩子吃得很香'), t.slice(0, 300));
check('我的评价标注为匿名', t.includes('匿名'), t.slice(0, 300));
check('我的评价显示订单号', t.includes(orderNo), orderNo);
await shot('rev_mine.png');

step('UI：商品详情页公开评价区');
await page.goto(`${UI}/product/${SPU}`, { waitUntil: 'networkidle' });
await page.waitForTimeout(900);
// 详情页很长，评价区在下方：滚动到标题再断言，避免「内容其实渲染了但被误判没渲染」
await page.getByText('商品评价', { exact: false }).first().scrollIntoViewIfNeeded();
await page.waitForTimeout(400);
t = await textNow();
check('详情页出现「商品评价」区', t.includes('商品评价'), t.slice(0, 200));
check('评价区显示平均分与好评率', t.includes('好评率'), t.slice(0, 400));
check('评价区出现刚写的评价内容', t.includes('毛孩子吃得很香'), t.slice(0, 600));
check('匿名评价在公开区显示为「匿名用户」', t.includes('匿名用户'), t.slice(0, 600));
await shot('rev_product_public.png');

// ---------------- 3. 收尾 ----------------
if (consoleErrors.length) {
  console.log('\n  console 错误：');
  consoleErrors.slice(0, 10).forEach((e) => console.log('      ' + e));
}
if (httpErrors.length) {
  console.log('\n  HTTP 4xx/5xx：');
  httpErrors.slice(0, 10).forEach((e) => console.log('      ' + e));
}
const unexpectedHttp = httpErrors.filter((e) => !/favicon/i.test(e) && !/(^|\s)401\s/.test(e));
check('无非预期 HTTP 错误（401 探测与 favicon 除外）', unexpectedHttp.length === 0, unexpectedHttp.slice(0, 5));
check(
  '无 console / pageerror（favicon 与 401 资源除外）',
  consoleErrors.filter((e) => !/favicon/i.test(e) && !/status of 401/.test(e)).length === 0,
  consoleErrors.slice(0, 4),
);

await browser.close();

if (!process.env.KEEP_FIXTURE) {
  await sql('duoduo_fulfilment', `DELETE FROM t_review WHERE order_no = '${orderNo}';`);
  console.log(`  （夹具已复原）评价已删除，订单号 ${orderNo} 留档`);
}

console.log(`\n=== UI 自测结果：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  failures.forEach((f) => console.log(`  - ${f}`));
  process.exitCode = 1;
}

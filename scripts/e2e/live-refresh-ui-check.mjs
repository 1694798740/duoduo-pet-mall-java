/**
 * 全站实时刷新（补 2）· 真实浏览器 UI 自测
 *
 * 为什么必须用真浏览器测：这套东西**整个是时间维度的行为** ——
 *   「30 秒后会自动更新」「切回标签页会更新」「更新时不闪骨架屏」，
 *   在 tsc / build / 接口冒烟里全都看不出来。唯一能证明它的方式，
 *   是让页面真的停在那儿，然后在**另一个入口**（这里直接用 SQL 扮演
 *   「服务端单方面改状态」的角色）把数据改掉，观察页面**自己**变过来。
 *
 * 被验证的不变式：
 *   ① 写操作广播：同端写完后其他页面立即跟上（dataBus，已在秒杀/详情页覆盖，本套不重复）；
 *   ② 切回标签页：`visibilitychange` 触发一次静默拉取 —— 且必须在 30s 轮询之前生效，
 *      否则「到底是被哪一层救的」分不清，测了等于没测（见下面的时序断言）；
 *   ③ 兜底轮询：没有任何交互，页面停在原地 30 秒也会跟上；
 *   ④ **静默**：上述所有刷新都不得把页面打回骨架屏，也不得整页 reload。
 *
 * ⚠️ 时序设计（本套的核心）：页面加载记为 t0，轮询点固定在 t0+30s。
 *   ② 的断言窗口是 t0+3s 改库 → 切走切回 → 要求 t0+20s 前更新完，**早于首个轮询点**；
 *   ③ 的断言则必须**晚于** t0+30s。两个窗口不重叠，才能各自定罪。
 *
 * 用法：
 *   node scripts/e2e/live-refresh-ui-check.mjs
 * 环境变量：GATEWAY / UI_BASE / CHROME_PATH / PW_CORE / KEEP_FIXTURE=1
 *
 * 夹具纪律：临时买家与订单**现场建**，跑完按 order_id 逐表删除并回补库存冻结量，
 *   最后一次查询自证「0 残留」；建不起 / 删不掉都直接判红。
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { shotsDir } from '../lib/e2e-shots.mjs';
import { mysql as mysqlExec } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
/** 必须是 localhost：网关 CORS 白名单只认它（见 aftersale-ui-check.mjs 文件头） */
const UI = process.env.UI_BASE ?? 'http://localhost:8088';
const SHOTS = shotsDir();
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? undefined;
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

const sql = (db, q) => mysqlExec(db, q);

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
    /* 保留 raw */
  }
  return { status: res.status, data: json?.data, raw };
}

// ---------------- 1. 夹具 ----------------
step('夹具：临时买家 + 一笔待付款订单');

const phone = `139${String(Math.floor(1e7 + Math.random() * 8e7))}`;
const jar = new Map();
const reg = await call('POST', '/api/auth/register', { phone, password: PWD, nickname: '实时刷新自测' }, jar);
check('临时买家注册成功', reg.status === 200 || reg.status === 201, reg.status);

const list = (await call('GET', '/api/products?page=1&pageSize=1', undefined, jar)).data;
const spuId = list?.items?.[0]?.id;
const detail = spuId ? (await call('GET', `/api/products/${spuId}`, undefined, jar)).data : null;
const skuId = detail?.skus?.[0]?.id;
check('取到可售 SKU', Boolean(skuId), { spuId, skuId });

const frozenBefore = await sql('duoduo_product', `SELECT frozen_stock FROM t_sku WHERE id = ${skuId};`);
await call('POST', '/api/cart/items', { skuId, quantity: 1 }, jar);
const created = (
  await call(
    'POST',
    '/api/orders',
    {
      receiverName: '小林',
      receiverPhone: '13900000000',
      receiverAddress: '上海市浦东新区张江路 1 号',
      idempotentKey: randomUUID(),
    },
    jar,
  )
).data;
const orderNo = created?.orders?.[0]?.orderNo;
check('下单成功（PENDING_PAYMENT）', Boolean(orderNo), created?.orders?.[0]?.status);
const orderId = await sql('duoduo_trade', `SELECT id FROM t_order WHERE order_no = '${orderNo}';`);

// ---------------- 2. 浏览器 ----------------
const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME ?? undefined, args: ['--no-sandbox'] });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
const page = await ctx.newPage();

const consoleErrors = [];
const httpErrors = [];
const reloads = [];
/** 资金托管接口的调用次数：未支付订单不该去打（打必 404） */
let settlementCalls = 0;
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text());
});
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
page.on('request', (r) => {
  if (/\/api\/orders\/[^/]+\/settlement/.test(r.url())) settlementCalls++;
});
page.on('response', (r) => {
  if (r.status() >= 400) httpErrors.push(`${r.status()} ${r.url()}`);
});
page.on('load', () => reloads.push(Date.now()));

const bodyText = () => page.evaluate(() => document.body.innerText);

/**
 * 订单卡片上的状态标签（列表页）。
 *
 * <p>为什么不能拿整页 innerText 判：状态筛选栏里就有「已关闭」这个**标签页文案**，
 * 于是 `bodyText.includes('已关闭')` 恒为真 —— 反向验证时它让两条断言**假绿**，
 * 看起来「摘掉实时刷新也照样通过」。断言必须精确到**这张单的卡片**。</p>
 */
const listCardStatus = () =>
  page.evaluate((no) => {
    const card = [...document.querySelectorAll('.card')].find((c) => c.textContent?.includes(no));
    return card ? (card.querySelector('.tag')?.textContent ?? '').trim() : null;
  }, orderNo);

/** 详情页：页头第一个状态标签即订单状态（后续的托管状态、支付渠道也是 .tag） */
const detailStatus = () =>
  page.evaluate(() => (document.querySelectorAll('.tag')[0]?.textContent ?? '').trim());

/** 轮询式等待某个取值出现：命中返回耗时（ms），超时返回 -1 */
async function waitFor(read, expected, timeoutMs) {
  const t = Date.now();
  while (Date.now() - t < timeoutMs) {
    if ((await read()) === expected) return Date.now() - t;
    await page.waitForTimeout(250);
  }
  return -1;
}

/** 旧的整页文本等待，仅用于「订单号是否存在」这种卡片无关的判断 */
async function waitForText(needle, timeoutMs) {
  const t = Date.now();
  while (Date.now() - t < timeoutMs) {
    if ((await bodyText()).includes(needle)) return Date.now() - t;
    await page.waitForTimeout(250);
  }
  return -1;
}

/** 静默性采样：列表**已渲染出数据之后**才开始，避免把首屏骨架屏算成「刷新抖动」 */
let skeletonHits = 0;
let sampling = false;
let samplerDone = false;
const sampler = (async () => {
  while (!samplerDone) {
    if (sampling) {
      try {
        const n = await page.evaluate(() => document.querySelectorAll('.loading-block').length);
        if (n > 0) skeletonHits++;
      } catch {
        /* 页面切换瞬间上下文失效，忽略 */
      }
    }
    await new Promise((r) => setTimeout(r, 150));
  }
})();

step('UI：买家登录并打开「我的订单」');
await page.goto(`${UI}/login`, { waitUntil: 'networkidle' });
await page.getByPlaceholder('11 位手机号').fill(phone);
await page.getByPlaceholder('请输入密码').fill(PWD);
await page.getByRole('button', { name: /^登录$/ }).click();
await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });

// t0：以订单列表**首次渲染出这张单**为准（此刻 useLiveRefresh 已订阅、轮询计时开始）
reloads.length = 0; // 从「订单列表」这一页开始计数：登录页那次加载不该算进来
await page.goto(`${UI}/orders`, { waitUntil: 'networkidle' });
const t0 = Date.now();
const seenAtStart = await waitForText(orderNo, 12000);
check('订单列表能看到这笔单', seenAtStart >= 0, seenAtStart);
check('列表初始状态为「待付款」', (await listCardStatus()) === '待付款', await listCardStatus());
check('列表只发生过一次整页加载', reloads.length === 1, reloads.length);
// 首屏已经出数据了，从这里开始采「刷新时会不会闪骨架屏」
sampling = true;

// ---- ② 切回标签页 ----
step('② 切回标签页：hidden 时不刷、visible 时立刻刷（窗口压在首个 30s 轮询之前）');
await sql('duoduo_trade', `UPDATE t_order SET status = 'CLOSED', closed_at = NOW(), close_reason = '自测：模拟超时关单' WHERE id = ${orderId};`);
console.log(`  （改库时刻 t0+${((Date.now() - t0) / 1000).toFixed(1)}s）`);

/**
 * headless Chromium 里 `bringToFront` **不会**真的把页面变成 hidden
 * （实测 visibilityState 始终是 visible），所以「切走切回」这套动作在无头模式下测不到。
 * 这里改为覆写 visibilityState 后派发**真实的 visibilitychange 事件** ——
 * 事件分发、监听器调用都是真的，只是触发源由浏览器换成了我们。
 * 断言因此是**双向**的：hidden 时不许刷、visible 时才刷；只测后者会放过「隐藏时也在白跑轮询」的退化。
 */
const fireVisibility = (state) =>
  page.evaluate((s) => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => s });
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => s === 'hidden' });
    document.dispatchEvent(new Event('visibilitychange'));
  }, state);

await fireVisibility('hidden');
await page.waitForTimeout(3000);
const stillStale = (await listCardStatus()) === '待付款';
check('页面隐藏时收到 visibilitychange 不刷新（不白打接口）', stillStale, { 仍显示待付款: stillStale });

await fireVisibility('visible');
const visDelay = await waitFor(listCardStatus, '已关闭', 15000);
const visElapsed = Date.now() - t0;
check(
  '切回（visible）后立即刷新为「已关闭」，且早于首个 30s 轮询点',
  visDelay >= 0 && visElapsed < 25000,
  { 切回后耗时ms: visDelay, 距加载ms: visElapsed, 首轮询点ms: 30000 },
);

// ---- ③ 兜底轮询：完全不动，等过 30s ----
step('③ 兜底轮询（不做任何交互，等过 30s 轮询点）');
await sql('duoduo_trade', `UPDATE t_order SET status = 'PENDING_PAYMENT', closed_at = NULL, close_reason = NULL WHERE id = ${orderId};`);
const sincePoll = Date.now() - t0;
console.log(`  （改库时刻 t0+${(sincePoll / 1000).toFixed(1)}s）`);
const pollDelay = await waitFor(listCardStatus, '待付款', 45000);
const total = Date.now() - t0;
/**
 * 判据要卡**下界**：上一轮刷新发生在 t0+10s 左右，若这里 1 秒内就变，
 * 那只可能是某种残余的即时刷新，而不是「等到 30 秒才拉」。故要求滞后 ≥15s。
 */
check('无任何交互，等到 30s 轮询才自动变为「待付款」', pollDelay >= 0 && pollDelay >= 15000 && total >= 25000, {
  改库到变化ms: pollDelay,
  距加载ms: total,
});
check('列表仍然只加载过一次（说明是局部刷新，不是整页 reload）', reloads.length === 1, reloads.length);

// ---- ④ 详情页同样跟随 ----
step('④ 订单详情页（同样的静默跟随）');
sampling = false; // 页面切换后的首屏骨架屏不属于「刷新抖动」
await page.goto(`${UI}/orders/${orderNo}`, { waitUntil: 'networkidle' });
const d0 = Date.now();
await page.waitForTimeout(1200);
const detailBefore = await detailStatus();
check('详情页打开时展示「待付款」', detailBefore === '待付款', detailBefore);
const detailLoads = reloads.length;
const settlementCallsBefore = settlementCalls;
sampling = true;

await sql('duoduo_trade', `UPDATE t_order SET status = 'CLOSED', closed_at = NOW(), close_reason = '自测：模拟超时关单' WHERE id = ${orderId};`);
const detailDelay = await waitFor(detailStatus, '已关闭', 40000);
check('详情页无交互也会跟上（≤40s）', detailDelay >= 0, { 耗时ms: detailDelay, 距加载ms: Date.now() - d0 });
check('详情页没有整页 reload', reloads.length === detailLoads, reloads.length - detailLoads);
/**
 * 未支付订单**没有**资金托管记录，服务端只能回 404。
 * 详情页必须不去发这个请求：否则 30s 轮询会把它变成每半分钟一次 404。
 */
check(
  '未支付/已关闭订单不再请求资金托管接口（不再产生必然 404）',
  settlementCalls - settlementCallsBefore === 0,
  { 期间请求次数: settlementCalls - settlementCallsBefore },
);

await page.screenshot({ path: path.join(SHOTS, 'live_refresh_orders.png'), fullPage: false });

sampling = false;
samplerDone = true;
await sampler;

step('静默性');
// 采样只在「列表已显示出订单之后」开启：首屏骨架屏属于首次加载，不该算成刷新抖动。
check('自动刷新过程中未出现骨架屏（不闪屏）', skeletonHits === 0, { 命中次数: skeletonHits });

step('收尾与自证');
await browser.close();

if (!process.env.KEEP_FIXTURE) {
  // 逐表删除（都挂在 order_id 上，不靠 LIKE 猜）；再回补冻结量
  await sql('duoduo_trade', `DELETE FROM t_order_item WHERE order_id = ${orderId};`);
  await sql('duoduo_trade', `DELETE FROM t_order_status_log WHERE order_id = ${orderId};`);
  await sql('duoduo_trade', `DELETE FROM t_stock_freeze WHERE order_id = ${orderId};`);
  await sql('duoduo_trade', `DELETE FROM t_settlement WHERE order_id = ${orderId};`);
  await sql('duoduo_trade', `DELETE FROM t_payment WHERE order_id = ${orderId};`);
  await sql('duoduo_trade', `DELETE FROM t_order WHERE id = ${orderId};`);
  await sql('duoduo_product', `UPDATE t_sku SET frozen_stock = frozen_stock - 1 WHERE id = ${skuId} AND frozen_stock > 0;`);
  await sql('duoduo_trade', `DELETE FROM t_cart_item WHERE user_id = (SELECT id FROM duoduo_user.t_user WHERE phone = '${phone}');`);
  await sql('duoduo_trade', `DELETE FROM t_order_place_task WHERE user_id = (SELECT id FROM duoduo_user.t_user WHERE phone = '${phone}');`);
  const uid = await sql('duoduo_user', `SELECT id FROM t_user WHERE phone = '${phone}';`);
  if (uid && uid !== 'NULL') {
    await sql('duoduo_user', `DELETE FROM t_refresh_token WHERE user_id = ${uid};`);
    await sql('duoduo_user', `DELETE FROM t_user WHERE id = ${uid};`);
  }

  // 自证：全库口径，而不是「我删过的那些 id」
  const leftOrder = await sql('duoduo_trade', `SELECT COUNT(*) FROM t_order WHERE order_no = '${orderNo}';`);
  const leftItems = await sql('duoduo_trade', `SELECT COUNT(*) FROM t_order_item WHERE order_id = ${orderId};`);
  const leftUser = await sql('duoduo_user', `SELECT COUNT(*) FROM t_user WHERE phone = '${phone}';`);
  const frozenAfter = await sql('duoduo_product', `SELECT frozen_stock FROM t_sku WHERE id = ${skuId};`);
  check('订单已删除', leftOrder === '0', leftOrder);
  check('订单明细已删除', leftItems === '0', leftItems);
  check('临时账号已删除', leftUser === '0', leftUser);
  check('SKU 冻结库存已回补', String(frozenAfter) === String(frozenBefore), { before: frozenBefore, after: frozenAfter });
}

const unexpectedHttp = httpErrors.filter((e) => !/favicon/i.test(e) && !/(^|\s)401\s/.test(e));
check('无非预期 HTTP 错误（401 探测与 favicon 除外）', unexpectedHttp.length === 0, unexpectedHttp.slice(0, 5));
check(
  '无 console / pageerror',
  consoleErrors.filter((e) => !/favicon/i.test(e) && !/status of 401/.test(e)).length === 0,
  consoleErrors.slice(0, 4),
);

console.log(`\n=== UI 自测结果：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  failures.forEach((f) => console.log(`  - ${f}`));
  process.exitCode = 1;
}

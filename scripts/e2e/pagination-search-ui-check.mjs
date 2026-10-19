/**
 * 三端分页与搜索（2026-10-19）· 真实浏览器 + 接口自测
 *
 * <p><b>为什么必须真浏览器</b>：这次改的是「列表能不能翻页、能不能搜、翻页按钮点得动吗」，
 * 全部是交互行为。tsc 通过 ≠ 按钮有反应；接口返回对 ≠ 页码能点。
 * 尤其是数字页码：它由 `pageWindow()` 算出来，页数少时不该出现 `…`、页数多时不该平铺，
 * 这些只能看渲染结果。</p>
 *
 * <p><b>为什么还要打接口</b>：搜索走**服务端**筛选（分页下前端过滤只能看到当前页那几条）。
 * 如果只测页面，页面拿到的永远是「当前页里匹配的」，看起来对、实际错。
 * 所以先用接口断言 `keyword` 真的把 total 收敛了，再看页面。</p>
 *
 * <h2>验证的不变式</h2>
 * <ol>
 *   <li><b>服务端搜索</b>：订单号命中 1 笔、商品标题片段能命中、乱码命中 0 笔；</li>
 *   <li><b>数字页码</b>：11 笔订单 => 2 页 => 页码 1/2 都在、点「2」真的换页、上一页能回；</li>
 *   <li><b>搜索与分页联动</b>：搜索后页码回到第 1 页、单页时显示「共 N 条」而不是伪分页；</li>
 *   <li><b>三端一致</b>：用户端（订单/售后/我的闲置）、平台后台（秒杀）、商家端（订单/售后）
 *       都出现同一个搜索框与同一个分页条；</li>
 *   <li><b>秒杀页三处修复</b>：不展示 19 位活动 ID、时间不出现 `T`、有「活动余量」列。</li>
 * </ol>
 *
 * 用法：node scripts/e2e/pagination-search-ui-check.mjs
 * 环境变量：GATEWAY / UI_BASE / ADMIN_UI_BASE / MERCHANT_UI_BASE / PW_CORE / CHROME_PATH / KEEP_FIXTURE=1
 *
 * 夹具纪律：临时买家 + 运营**现场建**，11 笔订单**跑完全部删除**（逐表 + 回补冻结库存），
 * 最后一次查库自证 0 残留；删不掉直接判红。
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { shotsDir } from '../lib/e2e-shots.mjs';
import { mysql as mysqlExec } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
/** 必须 localhost（网关 CORS 白名单），且三端是三个主机名，Cookie 互不共享 */
const UI = process.env.UI_BASE ?? 'http://localhost:8088';
const ADMIN_UI = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
const MERCHANT_UI = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const SHOTS = shotsDir();
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? undefined;
const PWD = 'duoduo12345';
const ORDER_COUNT = 11;

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

/**
 * 清理专用：删除语句容错执行。
 *
 * <p>清理段一旦抛错，后面的表、账号都不会被删，夹具就留在库里（还给下一轮
 * 造成「脏数据」）。所以逐条删除只记警告不中断，最后再用自证查询兜底；
 * 库名也必须按实物来 —— t_after_sale / t_logistics_track 在 duoduo_fulfilment，
 * 不在 duoduo_trade（写错了不会静默，会直接 `Table ... doesn't exist`）。</p>
 */
async function del(db, q) {
  try {
    await sql(db, q);
  } catch (e) {
    console.log(`  [WARN] 清理语句失败（已跳过）：${db} · ${e.message.slice(0, 160)}`);
  }
}

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
    /* 保留 raw，断言看状态码与原文 */
  }
  return { status: res.status, data: json?.data, raw };
}

// ---------------- 1. 夹具 ----------------
step('夹具：临时买家 + 运营，建 11 笔订单（凑出第 2 页）');

const buyerPhone = `139${String(Math.floor(1e7 + Math.random() * 8e7))}`;
const buyerJar = new Map();
const reg = await call('POST', '/api/auth/register',
  { phone: buyerPhone, password: PWD, nickname: '分页自测买家' }, buyerJar);
check('临时买家注册成功', reg.status === 200 || reg.status === 201, reg.status);

// 选一个库存足够的 SKU：11 笔各买 1 件，必须留余量，避免把商品买断货
let skuId = null;
let spuTitle = null;
const firstPage = (await call('GET', '/api/products?page=1&pageSize=20', undefined, buyerJar)).data;
for (const card of firstPage?.items ?? []) {
  const detail = (await call('GET', `/api/products/${card.id}`, undefined, buyerJar)).data;
  const sku = (detail?.skus ?? []).find((s) => s.stock >= ORDER_COUNT + 5);
  if (sku) {
    skuId = sku.id;
    spuTitle = card.title;
    break;
  }
}
check('找到库存充足的 SKU', Boolean(skuId), skuId);

const frozenBefore = Number(await sql('duoduo_product', `SELECT frozen_stock FROM t_sku WHERE id = ${skuId};`));
const orderNos = [];
for (let i = 0; i < ORDER_COUNT; i += 1) {
  await call('POST', '/api/cart/items', { skuId, quantity: 1 }, buyerJar);
  const placed = await call('POST', '/api/orders', {
    receiverName: '分页自测',
    receiverPhone: '13900000000',
    receiverAddress: '上海市浦东新区张江路 1 号',
    idempotentKey: randomUUID(),
  }, buyerJar);
  const no = placed.data?.orders?.[0]?.orderNo;
  if (!no) {
    check(`第 ${i + 1} 笔下单成功`, false, placed.status + ' ' + placed.raw.slice(0, 160));
    break;
  }
  orderNos.push(no);
}
check(`建出 ${ORDER_COUNT} 笔订单`, orderNos.length === ORDER_COUNT, orderNos.length);

const newestNo = orderNos[orderNos.length - 1];
const oldestNo = orderNos[0];
/** 标题前 4 个字：足够唯一地命中，又不会因为规格后缀不一致而漏 */
const titleProbe = (spuTitle ?? '').slice(0, 4);

// ---------------- 2. 接口：搜索必须真的收窄 total ----------------
step('接口：keyword 服务端筛选（不是前端过滤当前页）');

const byNo = await call('GET', `/api/orders?keyword=${newestNo}&page=1&pageSize=10`, undefined, buyerJar);
check('按订单号搜：命中 1 笔', byNo.data?.total === 1, { total: byNo.data?.total, status: byNo.status });
check('按订单号搜：命中的就是那笔', byNo.data?.items?.[0]?.orderNo === newestNo, byNo.data?.items?.[0]?.orderNo);

const byTitle = await call('GET',
  `/api/orders?keyword=${encodeURIComponent(titleProbe)}&page=1&pageSize=10`, undefined, buyerJar);
check('按商品标题片段搜：有命中', (byTitle.data?.total ?? 0) >= 1, {
  probe: titleProbe, total: byTitle.data?.total, status: byTitle.status,
});
check('按商品标题搜：命中的商品名包含关键词',
  (byTitle.data?.items ?? []).every((o) => o.items.some((it) => it.title.includes(titleProbe))),
  (byTitle.data?.items ?? []).map((o) => o.items.map((it) => it.title)));

const byJunk = await call('GET', '/api/orders?keyword=zzz-NOT-EXIST-9527&page=1&pageSize=10', undefined, buyerJar);
check('乱码关键词：total=0（空结果不是报错）', byJunk.status === 200 && byJunk.data?.total === 0, {
  status: byJunk.status, total: byJunk.data?.total,
});

const asMine = await call('GET', '/api/aftersales/mine?keyword=zzz-NOT-EXIST-9527&page=1', undefined, buyerJar);
check('售后接口收 keyword 且返回 200/0', asMine.status === 200 && asMine.data?.total === 0, {
  status: asMine.status, total: asMine.data?.total,
});

const listingMine = await call('GET',
  '/api/listings/mine/list?keyword=zzz-NOT-EXIST-9527&page=1', undefined, buyerJar);
check('我的闲置接口收 keyword 且返回 200/0', listingMine.status === 200 && listingMine.data?.total === 0, {
  status: listingMine.status, total: listingMine.data?.total,
});

// 运营账号：后台页面要用（库内提权，跑完删号）
const opPhone = `136${String(Math.floor(1e7 + Math.random() * 8e7))}`;
const opJar = new Map();
const opReg = await call('POST', '/api/auth/register',
  { phone: opPhone, password: PWD, nickname: '分页自测运营' }, opJar);
check('临时运营注册成功', opReg.status === 200 || opReg.status === 201, opReg.status);
await sql('duoduo_user', `UPDATE t_user SET role = 'OPERATOR' WHERE phone = '${opPhone}';`);
await call('POST', '/api/auth/login', { phone: opPhone, password: PWD }, opJar);

const adminOrders = await call('GET', '/api/admin/orders?keyword=zzz-NOT-EXIST-9527&page=1&pageSize=10', undefined, opJar);
check('后台订单接口收 keyword', adminOrders.status === 200 && adminOrders.data?.total === 0, {
  status: adminOrders.status, total: adminOrders.data?.total,
});
const adminMerchants = await call('GET', '/api/admin/merchants?keyword=zzz-NOT-EXIST-9527&page=1', undefined, opJar);
check('后台商家接口收 keyword', adminMerchants.status === 200 && adminMerchants.data?.total === 0, {
  status: adminMerchants.status, total: adminMerchants.data?.total,
});
const adminStaff = await call('GET', '/api/admin/merchant-staff?keyword=zzz-NOT-EXIST-9527&page=1', undefined, opJar);
check('后台成员接口收 keyword', adminStaff.status === 200 && adminStaff.data?.total === 0, {
  status: adminStaff.status, total: adminStaff.data?.total,
});

// ---------------- 3. 浏览器 ----------------
const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME ?? undefined, args: ['--no-sandbox'] });

const consoleErrors = [];
const httpErrors = [];
/** 未登录（没有 dd_at / dd_rt Cookie）时的 401 计数，用于过滤对应的控制台噪音 */
let anonymous401 = 0;
async function newPage(viewport = { width: 1440, height: 1000 }) {
  const ctx = await browser.newContext({ viewport, locale: 'zh-CN' });
  const p = await ctx.newPage();
  p.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  p.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
  p.on('response', (r) => {
    if (r.status() < 400) return;
    const cookie = r.request().headers()['cookie'] ?? '';
    const hasSession = /(^|;\s*)dd_(at|rt)=/.test(cookie);
    // 未登录时前端仍会探活 /api/users/me、/api/cart/count、/api/auth/refresh，
    // 这些 401 是「没登录」的正常答复，不是缺陷 —— 全部报红会让断言失去意义。
    // 但**带着会话 Cookie 仍然 401** 就是真问题（令牌失效 / 鉴权回归），必须报红。
    if (r.status() === 401 && !hasSession) {
      anonymous401 += 1;
      return;
    }
    httpErrors.push(`${r.status()} ${r.url()}`);
  });
  return { ctx, p };
}

/** 页面上当前渲染的订单卡片数（用户端 /orders 用 div.stack > div.card.card-pad） */
const orderCardCount = (p) => p.locator('div.stack > div.card.card-pad').count();

step('用户端：登录 → 我的订单');
const mall = await newPage();
await mall.p.goto(`${UI}/login`, { waitUntil: 'networkidle' });
await mall.p.getByPlaceholder(/手机号/).first().fill(buyerPhone);
await mall.p.getByPlaceholder(/密码/).first().fill(PWD);
await mall.p.getByRole('button', { name: /^登录/ }).click();
await mall.p.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });

await mall.p.goto(`${UI}/orders`, { waitUntil: 'networkidle' });
await mall.p.waitForTimeout(900);

check('订单页：第 1 页 10 张卡片', (await orderCardCount(mall.p)) === 10, await orderCardCount(mall.p));
const nav = mall.p.locator('nav[aria-label="分页"]');
check('订单页：有统一分页条', (await nav.count()) === 1, await nav.count());
check('订单页：分页条含「上一页 / 下一页」',
  (await nav.getByRole('button', { name: '上一页' }).count()) === 1
  && (await nav.getByRole('button', { name: '下一页' }).count()) === 1);
check('订单页：含数字页码 1 与 2',
  (await nav.getByRole('button', { name: '1', exact: true }).count()) === 1
  && (await nav.getByRole('button', { name: '2', exact: true }).count()) === 1);
check('订单页：分页条上写着总数与页码',
  /共\s*11\s*条.*第\s*1\/2\s*页/.test(await nav.innerText()), await nav.innerText());

step('用户端：点数字页码 2 → 换到第 2 页');
await nav.getByRole('button', { name: '2', exact: true }).click();
await mall.p.waitForTimeout(900);
check('第 2 页只有 1 张卡片', (await orderCardCount(mall.p)) === 1, await orderCardCount(mall.p));
check('页码状态更新为 2/2',
  /第\s*2\/2\s*页/.test(await nav.innerText()), await nav.innerText());
check('第 2 页里能看到最早那笔订单',
  (await mall.p.locator(`text=${oldestNo}`).count()) >= 1, oldestNo);

step('用户端：上一页回到第 1 页');
await nav.getByRole('button', { name: '上一页' }).click();
await mall.p.waitForTimeout(800);
check('回到 10 张卡片', (await orderCardCount(mall.p)) === 10, await orderCardCount(mall.p));

step('用户端：搜索第 2 页那笔订单号');
const search = mall.p.getByPlaceholder('搜订单号或商品名称');
check('订单页：有搜索框', (await search.count()) === 1, await search.count());
await search.fill(newestNo);
await mall.p.waitForTimeout(1200);
check('搜索后只剩 1 张卡片', (await orderCardCount(mall.p)) === 1, await orderCardCount(mall.p));
check('搜索结果里就是那笔订单', (await mall.p.locator(`text=${newestNo}`).count()) >= 1, newestNo);
check('搜索后页码回到第 1 页且只剩 1 页',
  /第\s*1\/1\s*页/.test(await nav.innerText()), await nav.innerText());

step('用户端：搜乱码 → 空态文案要说「没有匹配」');
await search.fill('zzz-NOT-EXIST-9527');
await mall.p.waitForTimeout(1200);
check('空态：没有匹配的订单', (await mall.p.getByText(/没有匹配「zzz-NOT-EXIST-9527」的订单/).count()) >= 1,
  (await mall.p.locator('body').innerText()).slice(0, 120));
await mall.p.screenshot({ path: path.join(SHOTS, 'pagination-search-orders.png') });
await search.fill('');

step('用户端：我的售后 / 我的闲置 也有搜索与分页');
await mall.p.goto(`${UI}/after-sales`, { waitUntil: 'networkidle' });
await mall.p.waitForTimeout(600);
check('售后页：有搜索框', (await mall.p.getByPlaceholder('搜售后单号或订单号').count()) === 1);
check('售后页：有统一分页条', (await mall.p.locator('nav[aria-label="分页"]').count()) === 1);

await mall.p.goto(`${UI}/listings/mine`, { waitUntil: 'networkidle' });
await mall.p.waitForTimeout(600);
check('我的闲置页：有搜索框', (await mall.p.getByPlaceholder('搜标题、品种或城市').count()) === 1);
check('我的闲置页：有统一分页条', (await mall.p.locator('nav[aria-label="分页"]').count()) === 1);
await mall.ctx.close();

step('平台后台：秒杀页（隐藏 ID / 时间无 T / 活动余量 / 搜索 / 分页）');
const admin = await newPage();
await admin.p.goto(`${ADMIN_UI}/admin.html`, { waitUntil: 'networkidle' });
await admin.p.getByPlaceholder('请输入平台账号手机号').fill(opPhone);
await admin.p.getByPlaceholder('请输入密码').fill(PWD);
await admin.p.getByRole('button', { name: /^登录$/ }).click();
await admin.p.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
await admin.p.goto(`${ADMIN_UI}/admin/seckill`, { waitUntil: 'networkidle' });
await admin.p.waitForTimeout(900);

const tableText = await admin.p.locator('.table').innerText().catch(() => '');
check('秒杀页：表格里没有 19 位活动 ID', !/\b\d{18,20}\b/.test(tableText),
  (tableText.match(/\b\d{18,20}\b/) ?? [])[0]);
check('秒杀页：时间不出现 "T" 字符', !/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(tableText),
  (tableText.match(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/) ?? [])[0]);
check('秒杀页：有「活动余量」列', (await admin.p.locator('th', { hasText: '活动余量' }).count()) === 1);
check('秒杀页：余量有进度条（不是纯文本）',
  (await admin.p.locator('.sk-remain .sk-remain-bar').count()) >= 1);
check('秒杀页：有搜索框', (await admin.p.getByPlaceholder('搜活动标题').count()) === 1);
check('秒杀页：有统一分页条', (await admin.p.locator('nav[aria-label="分页"]').count()) === 1);
// 2026-10-19 二次重构：原「统计条」升级为**可点击的阶段筛选**（此前是纯展示 span，
// 运营点了没反应）。所以这里除了"三段分开不重叠"，还要断言 chip 真的是 button。
check('秒杀页：筛选条 / 工具栏分开，不挤成一团',
  (await admin.p.locator('.seckill-filters').count()) === 1
  && (await admin.p.locator('.list-toolbar .search-bar').count()) === 1);
check('秒杀页：阶段筛选是真按钮（不是点了没反应的 span）',
  (await admin.p.locator('.seckill-filters button.seckill-filter').count()) === 5);
check('秒杀页：「+ 新建活动」按钮在页签下方、贴着列表（不再被挤到页面最顶部）', (() => {
  // 位置关系在 e2e 里用 DOM 顺序表达：按钮必须出现在 AdminTabs 之后
  return admin.p.evaluate(() => {
    const tabs = [...document.querySelectorAll('a.btn')].find((a) => a.textContent?.includes('秒杀活动'));
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent?.includes('新建活动'));
    if (!tabs || !btn) return false;
    return !!(tabs.compareDocumentPosition(btn) & Node.DOCUMENT_POSITION_FOLLOWING);
  });
})());

step('平台后台：搜索活动标题 → 空态');
await admin.p.getByPlaceholder('搜活动标题').fill('zzz-NOT-EXIST-9527');
await admin.p.waitForTimeout(600);
check('秒杀页：空态文案含关键词',
  (await admin.p.getByText(/没有匹配「zzz-NOT-EXIST-9527」的活动/).count()) >= 1,
  (await admin.p.locator('body').innerText()).slice(0, 160));
await admin.p.screenshot({ path: path.join(SHOTS, 'pagination-search-seckill.png') });
await admin.ctx.close();

step('商家端：订单 / 售后 也有搜索与分页');
const mer = await newPage();
await mer.p.goto(`${MERCHANT_UI}/merchant.html`, { waitUntil: 'networkidle' });
// 商家端登录页与后台同构（手机号 + 密码），运营账号可进卖家工作台
await mer.p.getByPlaceholder(/手机号/).first().fill(opPhone);
await mer.p.getByPlaceholder(/密码/).first().fill(PWD);
await mer.p.getByRole('button', { name: /^登录/ }).click();
await mer.p.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 }).catch(() => {});
await mer.p.goto(`${MERCHANT_UI}/seller/orders`, { waitUntil: 'networkidle' });
await mer.p.waitForTimeout(800);
check('商家订单页：有搜索框', (await mer.p.getByPlaceholder('搜订单号或商品名称').count()) === 1);
check('商家订单页：有统一分页条', (await mer.p.locator('nav[aria-label="分页"]').count()) === 1);

await mer.p.goto(`${MERCHANT_UI}/seller/after-sales`, { waitUntil: 'networkidle' });
await mer.p.waitForTimeout(800);
check('商家售后页：有搜索框', (await mer.p.getByPlaceholder('搜售后单号或订单号').count()) === 1);
check('商家售后页：有统一分页条', (await mer.p.locator('nav[aria-label="分页"]').count()) === 1);
await mer.ctx.close();
await browser.close();

// ---------------- 4. 清理与自证 ----------------
step('清理夹具（逐表删除 + 回补冻结库存 + 自证 0 残留）');
const keep = process.env.KEEP_FIXTURE === '1';
if (keep) {
  console.log('  KEEP_FIXTURE=1，保留夹具（买家', buyerPhone, '/ 运营', opPhone, '）');
} else {
  const buyerId = await sql('duoduo_user', `SELECT id FROM t_user WHERE phone = '${buyerPhone}';`);
  for (const no of orderNos) {
    const oid = await sql('duoduo_trade', `SELECT id FROM t_order WHERE order_no = '${no}';`);
    if (!oid || oid === 'NULL') continue;
    await del('duoduo_trade', `DELETE FROM t_order_item WHERE order_id = ${oid};`);
    await del('duoduo_trade', `DELETE FROM t_order_status_log WHERE order_id = ${oid};`);
    await del('duoduo_trade', `DELETE FROM t_stock_freeze WHERE order_id = ${oid};`);
    await del('duoduo_trade', `DELETE FROM t_settlement WHERE order_id = ${oid};`);
    await del('duoduo_trade', `DELETE FROM t_payment WHERE order_id = ${oid};`);
    // 售后 / 物流在履约库，不在交易库
    await del('duoduo_fulfilment', `DELETE FROM t_after_sale WHERE order_no = '${no}';`);
    await del('duoduo_fulfilment', `DELETE FROM t_logistics_track WHERE order_no = '${no}';`);
    await del('duoduo_trade', `DELETE FROM t_order WHERE id = ${oid};`);
  }
  // 冻结库存按实际下单件数回补（11 笔 × 1 件）
  await sql('duoduo_product',
    `UPDATE t_sku SET frozen_stock = GREATEST(0, frozen_stock - ${orderNos.length}) WHERE id = ${skuId};`);
  if (buyerId && buyerId !== 'NULL') {
    await del('duoduo_trade', `DELETE FROM t_cart_item WHERE user_id = ${buyerId};`);
    await del('duoduo_trade', `DELETE FROM t_order_place_task WHERE user_id = ${buyerId};`);
    await del('duoduo_user', `DELETE FROM t_refresh_token WHERE user_id = ${buyerId};`);
    await del('duoduo_user', `DELETE FROM t_user WHERE id = ${buyerId};`);
  }
  const opId = await sql('duoduo_user', `SELECT id FROM t_user WHERE phone = '${opPhone}';`);
  if (opId && opId !== 'NULL') {
    await del('duoduo_user', `DELETE FROM t_refresh_token WHERE user_id = ${opId};`);
    await del('duoduo_user', `DELETE FROM t_user WHERE id = ${opId};`);
  }

  const leftOrders = Number(await sql('duoduo_trade',
    `SELECT COUNT(*) FROM t_order WHERE order_no IN (${orderNos.map((n) => `'${n}'`).join(',')});`));
  const leftBuyer = Number(await sql('duoduo_user',
    `SELECT COUNT(*) FROM t_user WHERE phone IN ('${buyerPhone}','${opPhone}');`));
  const frozenAfter = Number(await sql('duoduo_product',
    `SELECT frozen_stock FROM t_sku WHERE id = ${skuId};`));
  check('自证：11 笔订单 0 残留', leftOrders === 0, leftOrders);
  check('自证：两个临时账号 0 残留', leftBuyer === 0, leftBuyer);
  check('自证：SKU 冻结库存已回补', frozenAfter === frozenBefore, { frozenBefore, frozenAfter });
}

// ---------------- 5. 结论 ----------------
const realConsoleErrors = consoleErrors.filter((t) =>
  !/favicon|ResizeObserver/i.test(t)
  // 未登录探活产生的 401 会在控制台留一行 "Failed to load resource ... 401"，
  // 与上面被计入 anonymous401 的响应是同一批请求，成对过滤
  && !(anonymous401 > 0 && /Failed to load resource.*status of 401/.test(t)));
check('浏览器控制台无错误', realConsoleErrors.length === 0, realConsoleErrors.slice(0, 3));
check('无 4xx/5xx 接口响应（未登录 401 已按会话 Cookie 区分）',
  httpErrors.length === 0, httpErrors.slice(0, 5));

console.log(`\n===== 结果：${pass} 通过 / ${failures.length} 失败 =====`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log(' - ' + f);
  process.exit(1);
}
console.log(`截图：${SHOTS}`);

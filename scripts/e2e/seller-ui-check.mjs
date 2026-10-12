/**
 * 商家端 Web（M9）· 真实浏览器 UI 自测
 *
 * 为什么单靠 `tsc` 与 `vite build` 不够：
 *   类型检查与打包只能证明「代码能过」，证明不了「页面真的渲染出来了、接口真的通了」。
 *   M6（买家端）就是这样发现的：页面编译全绿，但真实浏览器里字典码值错配、表单 422。
 *
 * 这段脚本做三件事：
 *   1. 用真实接口准备好夹具（卖家账号 + 两条属于他的订单：一条待发货、一条已发货）
 *   2. 用真实 Chromium 登录，逐页走 /seller、/seller/orders、详情页、/seller/settlements
 *   3. 每一步都截图 + 断言页面真的出了预期内容，并把 console error 全部收集出来
 *
 * 前置：后端 7 容器在跑（网关 :8080）、frontend dev server 在跑（:5173；若本机报 EACCES 见 08 篇 T-27，
 *       改用 --port 3000 并设 UI_BASE=http://localhost:3000，3000 已在 CORS 白名单内）
 *
 * ⚠️ 必须用 http://localhost:5173 访问，不能用 http://127.0.0.1:5173：
 *    网关的 CORS_ORIGINS 白名单只认 `localhost:5173`，带 127.0.0.1 的 Origin 会被
 *    Spring 按「非法跨域请求」直接 403（前端表现为「没有权限执行该操作」，极易误判为业务报错）。
 *    curl 不带 Origin 头所以复现不出来 —— 这是个只在浏览器里现形的坑。
 *
 * 用法：
 *   cd frontend && node node_modules/vite/bin/vite.js --port 5173   # 另开一个终端
 *   node scripts/e2e/seller-ui-check.mjs
 * 环境变量覆盖：GATEWAY / UI_BASE / CHROME_PATH / PW_CORE / KEEP_FIXTURE=1
 */

import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { shotsDir } from '../lib/e2e-shots.mjs';
import { createSellerFixture } from '../lib/e2e-seller-fixture.mjs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { mysql as mysqlExec } from '../lib/docker-exec.mjs';

const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
// 必须是 localhost，不能是 127.0.0.1 —— 见文件头注释（网关 CORS 白名单只认 localhost）
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
/**
 * 商家工作台**独立站点**（E6）：商家页面已从商城摘除，本套件的被测页面全部在商家站点上。
 * `*.localhost` 只有浏览器能解析（Node 不行），而本套件的访问全部经 Playwright → 可安全使用主机名。
 */
const MERCHANT = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const here = path.dirname(fileURLToPath(import.meta.url)); // 脚本自身目录（下面 SHOTS / PW_CORE 都按它解析，不写死绝对路径）
const SHOTS = shotsDir(); // 默认落到 gitignored 的 scripts/e2e/.shots；要归档配图用 SHOTS=<路径> 覆盖
const CHROME =
  process.env.CHROME_PATH ?? null; // 不指定则交给 playwright 解析自己安装的那份浏览器
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;

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

// ---------------- 1. 接口层：准备夹具 ----------------

/** 极简 cookie jar： clerk */
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
    /* 留 raw 供大整数提取 */
  }
  return { status: res.status, data: json?.data, json, raw };
}

/**
 * 从原始 JSON 文本里取大整数 ID（雪花 ID 有 18 位，超过 Number.MAX_SAFE_INTEGER）。
 * 为什么不能直接读 json.data.id：JSON.parse 会把它四舍五入成尾数不对的另一个数，
 * 拿去写 t_order.seller_id 就会「改到一个根本不存在的卖家头上」，
 * 表现为「夹具明明改成功了，卖家列表却一笔都看不到」——极难排查。
 */
function bigId(raw, key = 'id') {
  // 兼容两种下发形态：雪花 id 早已按**字符串**下发（"id":"710..."），
  // 只认数字型（"id":710...）会在「后端改了序列化」之后静默取不到值 —— 那比报错更难查。
  const m = raw.match(new RegExp(`"${key}"\\s*:\\s*"?(\\d{15,})"?`));
  if (!m) throw new Error(`未从响应中提取到 ${key}：${raw.slice(0, 200)}`);
  return m[1];
}

/**
 * 库查询（异步）。
 * 不能用 execFileSync：本机 Node 的**同步**派生态一律 EBUSY（连 cmd/where 都起不来），
 * 而异步 spawn 正常 —— 详见 scripts/lib/docker-exec.mjs 文件头。之前卡在这里时，
 * 表象是 `Error: spawnSync docker EBUSY`，看起来像 docker 不可用，方向会整个跑偏。
 */
const sql = (db, q) => mysqlExec(db, q);

async function newUser(prefix) {
  const phone = `139${String(Math.floor(1e7 + Math.random() * 8e7))}`;
  const jar = new Map();
  const r = await call('POST', '/api/auth/register', { phone, password: PWD, nickname: prefix }, jar);
  if (r.status !== 200 && r.status !== 201) throw new Error(`注册失败 ${r.status}`);
  return { phone, jar };
}

async function placeOrder(buyer) {
  const list = (await call('GET', '/api/products?page=1&pageSize=1', undefined, buyer.jar)).data;
  const spuId = list.items[0].id;
  const detail = (await call('GET', `/api/products/${spuId}`, undefined, buyer.jar)).data;
  const skuId = detail.skus[0].id;
  await call('POST', '/api/cart/items', { skuId, quantity: 1 }, buyer.jar);
  const order = (
    await call('POST', '/api/orders', {
      receiverName: '张三',
      receiverPhone: '13800001111',
      receiverAddress: '上海市浦东新区世纪大道 100 号 1 号楼',
      idempotentKey: randomUUID(),
    }, buyer.jar)
  ).data;
  const pay = (
    await call('POST', `/api/orders/${order.orders[0].orderNo}/pay`, {
      channel: 'mock',
      idempotentKey: randomUUID(),
    }, buyer.jar)
  );
  return { orderNo: order.orders[0].orderNo, orderId: pay.data?.id ?? null, payStatus: pay.status };
}

step('准备夹具：卖家账号');

const seller = await newUser('卖家小李');
const me1 = await call('GET', '/api/users/me', undefined, seller.jar);
const sellerId = bigId(me1.raw);
check('卖家账号注册成功（取到雪花 ID，且未被 JSON 精度截断）', /^\d{15,}$/.test(sellerId), sellerId);

// E10：入驻改为「提交材料 → 平台审核 → 开店」状态机；业务夹具在库内直建 APPROVED 店铺
// （完整入驻链路的 e2e 是 E11 审核台的交付项）。夹具内部先实名（派生字段 realName 必须非空）。
await call('POST', '/api/users/me/realname', { realName: '李小商', idCard: '310101199005054321' }, seller.jar);
const fixture = await createSellerFixture({ phone: seller.phone, storeName: '小李宠物用品' });
check('卖家夹具直建成功（t_merchant APPROVED）', /^\d{15,}$/.test(fixture.merchantId), fixture.merchantId);

// 角色快照在 access token 里，必须重新登录才拿得到 SELLER
await call('POST', '/api/auth/login', { phone: seller.phone, password: PWD }, seller.jar);
const me2 = (await call('GET', '/api/users/me', undefined, seller.jar)).data;
check('重新登录后角色为 SELLER', (me2.role ?? '').toUpperCase() === 'SELLER', me2.role);

step('准备夹具：买家下单两笔并支付');

const buyer = await newUser('买家小王');
const a = await placeOrder(buyer);
const b = await placeOrder(buyer);
check('订单 A 支付成功（mock 渠道即时入账）', a.payStatus === 200, a.payStatus);
check('订单 B 支付成功', b.payStatus === 200, b.payStatus);

// 商品域没有 seller_id，所以「这笔单是谁的」只能即位此刻到位：订单与托管单一起改
const ownerSql = `UPDATE t_order o LEFT JOIN t_settlement s ON s.order_id = o.id
                  SET o.seller_id = ${sellerId}, s.seller_id = ${sellerId}
                  WHERE o.order_no IN ('${a.orderNo}','${b.orderNo}');`;
await sql('duoduo_trade', ownerSql);
check(
  '夹具：两笔订单已归属该卖家',
  await sql('duoduo_trade', `SELECT COUNT(*) FROM t_order WHERE order_no IN ('${a.orderNo}','${b.orderNo}') AND seller_id=${sellerId};`) === '2',
  null,
);

// 接口层先自检一次：若这一步就看不到订单，后面的 UI 断言失败会指向错误的方向
const listed = (await call('GET', '/api/seller/orders?page=1&pageSize=20', undefined, seller.jar)).data;
const listedNos = (listed?.items ?? []).map((o) => o.orderNo);
check(
  '（接口层预检）卖家订单列表能看到 A / B',
  listedNos.includes(a.orderNo) && listedNos.includes(b.orderNo),
  listedNos,
);

// 让订单 B 走到「已发货」，这样列表页的状态过滤与详情页都有东西可看
await call('POST', `/api/seller/orders/${b.orderNo}/ship`, { logisticsCompany: '中通快递', logisticsNo: 'ZT-UI-0001' }, seller.jar);

// ---------------- 2. UI：真实 Chromium 走四页 ----------------

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME ?? undefined, args: ['--no-sandbox'] });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
const page = await ctx.newPage();
const consoleErrors = [];
const httpErrors = [];
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
// 带上 URL 与方法的 HTTP 错误清单：只记「页面炸了没炸」是不够的，
// 排查时要知道到底哪个请求炸的（401 属预期的未登录探测，单独放过）
page.on('response', (r) => {
  if (r.status() >= 400) httpErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`);
});

const shot = async (name) => {
  await page.screenshot({ path: path.join(SHOTS, name), fullPage: true });
  console.log(`  [截图] ${SHOTS}/${name}`);
};
const textNow = () => page.locator('body').innerText();

step('UI：登录');
await page.goto(`${MERCHANT}/login`, { waitUntil: 'networkidle' });
await page.getByPlaceholder('11 位手机号').fill(seller.phone);
await page.getByPlaceholder('请输入密码').fill(PWD);
await page.getByRole('button', { name: /^登录$/ }).click();
await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
check('卖家登录成功并离开登录页', !page.url().includes('/login'), page.url());

step('UI：/seller 概览');
await page.goto(`${MERCHANT}/seller`, { waitUntil: 'networkidle' });
await page.waitForTimeout(800);
let t = await textNow();
check('概览页标题出现「商家端」', t.includes('商家端'), t.slice(0, 60));
check('概览页出现资金区', t.includes('资金'), null);
check('概览页出现订单状态分布', t.includes('订单状态分布'), null);
check('概览页显示了真实的待发货数（≥1）', /待发货[\s\S]{0,40}?1/.test(t), t.slice(0, 200));
await shot('sel_dashboard.png');

step('UI：/seller/orders 卖出订单');
await page.goto(`${MERCHANT}/seller/orders`, { waitUntil: 'networkidle' });
await page.waitForTimeout(800);
t = await textNow();
check('订单页出现「卖出订单」', t.includes('卖出订单'), t.slice(0, 60));
check('订单 A 出现在列表里', t.includes(a.orderNo), a.orderNo);
check('订单 B 出现在列表里', t.includes(b.orderNo), b.orderNo);
check('列表展示了买家收货信息（收货人）', t.includes('张三'), null);
await shot('sel_orders.png');

step('UI：状态过滤「待发货」');
await page.getByRole('button', { name: '待发货' }).first().click();
await page.waitForTimeout(900);
t = await textNow();
check('过滤待发货后只剩订单 A', t.includes(a.orderNo) && !t.includes(b.orderNo), null);
await shot('sel_orders_filter.png');

step('UI：一键发货');
await page.getByRole('button', { name: '立即发货' }).first().click();
await page.waitForTimeout(400);
await page.getByPlaceholder('如 顺丰速运').fill('顺丰速运');
await page.getByPlaceholder('如 SF1234567890').fill('SF-UI-20260918');
await page.getByRole('button', { name: '确认发货' }).click();
await page.waitForTimeout(1500);
t = await textNow();
check('发货成功后列表不再有该待发货单', !t.includes(a.orderNo), null);
check('发货后页面没有被重定向走（仍在订单页）', page.url().includes('/seller/orders'), page.url());
await shot('sel_orders_shipped.png');

step('UI：订单详情页');
await page.goto(`${MERCHANT}/seller/orders/${b.orderNo}`, { waitUntil: 'networkidle' });
await page.waitForTimeout(900);
t = await textNow();
check('详情页出现订单号', t.includes(b.orderNo), b.orderNo);
check('详情页出现物流信息（中通快递）', t.includes('中通快递'), null);
check('详情页出现资金区', t.includes('资金') || t.includes('托管'), null);
await shot('sel_order_detail.png');

step('UI：/seller/settlements 结算与提现');

// ★ E22 先做**接口层**预检，再谈页面。
//   理由：页面上「待结算 / 可提现」都是格式化后的字符串，从文本反推数值既脆弱又看不出量级；
//   而"页面显示了 0"与"接口根本没通、前端兜底显示 0"在肉眼上完全一样（铁律 2）。
//   所以在接口层先把口径钉住，UI 层只负责证明"这些数字真的渲染出来了"。
const walletApi = (await call('GET', '/api/seller/settlements/wallet', undefined, seller.jar)).data;
const ledgerApi = (await call('GET', '/api/seller/settlements?page=1&pageSize=20', undefined, seller.jar)).data;
check('★ E22：钱包接口返回售后期天数 7', walletApi?.holdDays === 7, walletApi?.holdDays);
check(
  '★ E22：钱包「待结算」= 台账「托管中·你可得」（同源，且都 > 0）',
  walletApi?.pendingAmount === ledgerApi?.custodySellerAmount && walletApi?.pendingAmount > 0,
  `${walletApi?.pendingAmount} vs ${ledgerApi?.custodySellerAmount}`,
);
check(
  '★ E22：可提现（净额）= max(0, 毛额 − 欠款)',
  walletApi?.netWithdrawableAmount === Math.max(0, walletApi.withdrawableAmount - walletApi.outstandingDebt),
  JSON.stringify(walletApi),
);
const billApi = (await call('GET', '/api/seller/settlements/bills', undefined, seller.jar)).data;
check(
  '★ E22：账单周期为本月，且本期入账笔数 ≥ 2（夹具刚下过两单）',
  /^\d{4}-\d{2}$/.test(billApi?.period ?? '') && billApi.orderCount >= 2,
  JSON.stringify({ period: billApi?.period, orderCount: billApi?.orderCount }),
);
check(
  '★ E22：账单快照段与钱包逐分一致（同源）',
  billApi?.pendingAmount === walletApi?.pendingAmount &&
    billApi?.withdrawableAmount === walletApi?.withdrawableAmount &&
    billApi?.outstandingDebt === walletApi?.outstandingDebt,
  JSON.stringify({ bill: billApi?.pendingAmount, wallet: walletApi?.pendingAmount }),
);
check(
  '★ E22：账单自洽 —— 卖家应得 = 订单金额 − 佣金',
  billApi?.sellerEarningAmount === billApi?.orderAmount - billApi?.platformFeeAmount,
  JSON.stringify({ o: billApi?.orderAmount, f: billApi?.platformFeeAmount, s: billApi?.sellerEarningAmount }),
);

await page.goto(`${MERCHANT}/seller/settlements`, { waitUntil: 'networkidle' });
await page.waitForTimeout(900);
t = await textNow();
check('对账页出现「结算与提现」', t.includes('结算与提现'), t.slice(0, 60));
// E22 · 钱包四栏必须都渲染出来。少任何一栏，用户就看不到"钱卡在哪一段"。
check('★ E22：页面渲染「可提现」', t.includes('可提现'), null);
check('★ E22：页面渲染「待结算」', t.includes('待结算'), null);
check('★ E22：页面渲染「冻结中」', t.includes('冻结中'), null);
check('★ E22：页面渲染「累计到账」', t.includes('累计到账'), null);
check('★ E22：页面解释冻结原因（售后期 N 天）', t.includes('售后期'), null);
check('★ E22：页面渲染本期账单区块', t.includes('账单'), null);
check('对账页列出这笔订单', t.includes(b.orderNo), b.orderNo);
check('对账页出现金额（¥）', t.includes('¥'), null);
await shot('sel_settlements.png');

step('UI：无权限提示（非卖家）');
const plain = await newUser('普通买家');
const ctx2 = await browser.newContext({ viewport: { width: 1280, height: 860 }, locale: 'zh-CN' });
const p2 = await ctx2.newPage();
// 第二个 context 也要监听：漏掉它就会漏掉「非卖家视角」页面的错误
p2.on('response', (r) => {
  if (r.status() >= 400) httpErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`);
});
await p2.goto(`${MERCHANT}/login`, { waitUntil: 'networkidle' });
await p2.getByPlaceholder('11 位手机号').fill(plain.phone);
await p2.getByPlaceholder('请输入密码').fill(PWD);
await p2.getByRole('button', { name: /^登录$/ }).click();
await p2.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
await p2.goto(`${MERCHANT}/seller`, { waitUntil: 'networkidle' });
await p2.waitForTimeout(900);
const t2 = await p2.locator('body').innerText();
check('非卖家访问商家端看到入驻/无权限提示，而不是空白页', t2.includes('卖家') || t2.includes('403'), t2.slice(0, 120));
await p2.screenshot({ path: path.join(SHOTS, 'sel_not_seller.png'), fullPage: true });
console.log('  [截图] ${SHOTS}/sel_not_seller.png');

step('收尾');
if (httpErrors.length) {
  console.log('  [HTTP 4xx/5xx 清单]');
  httpErrors.forEach((e) => console.log('      ' + e));
}
// 401 是预期内的：auth store 启动时会用 /users/me 探一次登录态，未登录就是 401，
// 前端据此显示「登录 / 注册」。把它算作失败只会让每次运行都红一次，反而掩盖真问题。
const unexpectedHttp = httpErrors.filter((e) => !/favicon/i.test(e) && !/(^|\s)401\s/.test(e));
check('无非预期的 HTTP 错误（401 未登录探测与 favicon 除外）', unexpectedHttp.length === 0, unexpectedHttp.slice(0, 5));
check(
  '无 console / pageerror（401 资源加载失败与 favicon 除外）',
  consoleErrors.filter((e) => !/favicon/i.test(e) && !/status of 401/.test(e)).length === 0,
  consoleErrors.slice(0, 4),
);

await browser.close();

if (!process.env.KEEP_FIXTURE) {
  // 复原：把两笔订单还给平台自营，避免留下「这单到底是谁的」的脏数据
  await sql('duoduo_trade', `UPDATE t_order o LEFT JOIN t_settlement s ON s.order_id = o.id
                       SET o.seller_id = 0, s.seller_id = 0
                       WHERE o.order_no IN ('${a.orderNo}','${b.orderNo}');`);
  console.log(`  （夹具已复原）订单 ${a.orderNo} / ${b.orderNo} 归还平台自营`);
}

console.log(`\n=== UI 自测结果：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  failures.forEach((f) => console.log(`  - ${f}`));
  process.exitCode = 1;
}

/**
 * 物流轨迹（A2）· 真实浏览器 UI 自测
 *
 * 为什么接口冒烟（logistics-smoke.ps1，79 项）之外还要跑浏览器：
 *   接口测的是「轨迹对不对」，测不出「买卖双方在页面上是否真的看得到这条轨迹」。
 *   轨迹这个功能的**全部价值**就在于「用户不用再去快递官网查单号」——
 *   数据在库里、在接口里都答得出来，页面上没有，这个功能就等于没做。
 *   而且 A2 的链路是「交易域发货 → 跨服务通知 → 履约域入库 → 前端渲染」四跳，
 *   接口冒烟只覆盖到第三跳，最后一跳只有真实浏览器能证。
 *
 * 这段脚本断言：
 *   · 发货后买家订单详情页真的出现「已揽收」，且带上卖家填的物流公司与单号；
 *   · 追加节点后（**乱序入库**）页面按发生时间正序排列，头部「当前」提示跟着走到最新一条；
 *   · 卖家订单详情页看到的是**同一份**轨迹（同一组件，不允许两边说法不一致）；
 *   · 未发货订单显示的是**说明性空态**，不是错误态、也不是一片空白；
 *   · 非当事人打开该订单页看不到任何物流信息（订单详情本就挡住，这里是第二道）；
 *   · 重复推送后页面节点数不变（幂等最终反映到 UI）；
 *   · ★ 买家确认收货（订单转 COMPLETED）后轨迹**仍然在** —— 守的是组件里
 *     `shipped` 的判定口径（同时看状态与 shippedAt）。只认 SHIPPED 的话，
 *     一确认收货轨迹就整块消失，而签收记录恰恰是纠纷时最需要回看的东西。
 *
 * 一个**已知边界**（刻意不做断言，因为它是设计而不是缺陷）：
 *   物流轨迹接口对运营是放行的（见 logistics-smoke.ps1「运营可查」），
 *   但买家订单详情页本身按归属拦截，运营在页面上打不开别人的订单 ——
 *   运营要看轨迹，应在运营工作台里单独开入口，那是另一个增量。
 *   把「运营在买家页看不到轨迹」写成断言，等于把设计记录成缺陷。
 *
 * 前置：后端 9 容器在跑（网关 :8080）、三站点容器（商城 8088 / 平台后台 8090 / 商家 8091）
 *
 * **E6b 改动**：卖家的订单详情页（`/seller/orders/:no`）此前由**商城入口**驱动；
 * E6 把商家从商城摘除后，商城站点上已无 `/seller/*` 路径，故改为**商家独立站点**（`MERCHANT`），
 * 卖家登录也改在商家站点自己的登录页 —— 三端会话互不共享（Cookie 是主机级作用域）。
 *
 * ⚠️ 必须用 http://localhost:<port>，不能用 http://127.0.0.1:<port>：
 *    网关 CORS_ORIGINS 白名单只认 `localhost`，带 127.0.0.1 的 Origin 会被判非法跨域（403）。
 *    curl 不带 Origin 头所以复现不出来 —— 只在浏览器里现形。
 *
 * ⚠️ 端口：约定 5173，但**本机 5173 会被 Windows 排除端口段占用**（Docker Desktop 启动后
 *    WinNAT 保留 5149-5248，listen 直接 EACCES；查 `netsh int ipv4 show excludedportrange protocol=tcp`）。
 *    改用 3000 即可 —— 它已经在 CORS 白名单默认值里，**不需要动任何配置**：
 *      cd frontend && node node_modules/vite/bin/vite.js --port 3000 --strictPort --host localhost
 *      UI_BASE=http://localhost:3000 node scripts/e2e/logistics-ui-check.mjs
 *
 * 用法：
 *   cd frontend && node node_modules/vite/bin/vite.js --port 3000   # 另开一个终端
 *   node scripts/e2e/logistics-ui-check.mjs
 * 环境变量覆盖：GATEWAY / FULFILMENT / INTERNAL_TOKEN(根密钥) / UI_BASE / CHROME_PATH / PW_CORE / KEEP_FIXTURE=1
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { shotsDir } from '../lib/e2e-shots.mjs';
import { createSellerFixture } from '../lib/e2e-seller-fixture.mjs';
import { randomUUID } from 'node:crypto';
import { mysql as mysqlExec } from '../lib/docker-exec.mjs';
import { newInternalToken } from '../lib/internal-token.mjs';

const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
// 内部摄入接口**直连**履约服务：网关里没有 /api/logistics/internal/** 这条路由，
// 且走网关会先被 JWT 过滤器拦成 401（那是 T-25 那类最难查的假象）
const FUL = process.env.FULFILMENT ?? 'http://127.0.0.1:8084';
const INTERNAL_TOKEN_HEADER = () => ({ 'X-Internal-Token': newInternalToken('POST') });
// 必须是 localhost，不能是 127.0.0.1 —— 见文件头注释
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
/** 第三方商家**独立站点**（卖家订单详情页在这里）。E6b：商家已从商城站点摘除。 */
const MERCHANT = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const here = path.dirname(fileURLToPath(import.meta.url)); // 脚本自身目录（下面 SHOTS / PW_CORE 都按它解析，不写死绝对路径）
const SHOTS = shotsDir(); // 默认落到 gitignored 的 scripts/e2e/.shots；要归档配图用 SHOTS=<路径> 覆盖
const CHROME =
  process.env.CHROME_PATH ?? null; // 不指定则交给 playwright 解析自己安装的那份浏览器
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;

const PWD = 'duoduo12345';

// 追加的 3 条节点（故意乱序推送：先「派送中」、再「运输中」、最后「到达分拨中心」）
const APPEND_NODES = [
  {
    nodeCode: 'OUT_FOR_DELIVERY',
    nodeText: '【上海浦东网点】快递员正在派送，请保持电话畅通',
    offsetMinutes: 3,
  },
  { nodeCode: 'IN_TRANSIT', nodeText: '【上海分拨中心】已发出，下一站南京', offsetMinutes: 1 },
  { nodeCode: 'ARRIVED_HUB', nodeText: '【南京分拨中心】已收入', offsetMinutes: 2 },
];
const EXPECTED_SEQ = ['PICKED_UP', 'IN_TRANSIT', 'ARRIVED_HUB', 'OUT_FOR_DELIVERY'];
const iso = (d) => d.toISOString().replace(/\.\d{3}Z$/, 'Z');

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

// ---------------- 接口层工具 ----------------

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

/** 直连履约域的内部摄入接口（带 X-Internal-Token） */
async function ingest(orderNo, nodes, source = 'COURIER') {
  const res = await fetch(`${FUL}/api/logistics/internal/${orderNo}/tracks`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...INTERNAL_TOKEN_HEADER() },
    body: JSON.stringify({ source, nodes }),
  });
  const raw = await res.text();
  let json = null;
  try {
    json = JSON.parse(raw);
  } catch {
    /* ignore */
  }
  return { status: res.status, data: json?.data, json };
}

/**
 * 从原始 JSON 文本里取大整数 ID。
 * 为什么不能直接 json.data.id：18 位雪花值超过 Number.MAX_SAFE_INTEGER，
 * JSON.parse 会改写成另一个数 —— 拿去改库就「改到根本不存在的卖家头上」。
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

/**
 * 登录到**指定站点**。E6b：三端独立部署后会话**不跨站点**（Cookie 是主机级作用域、不区分端口），
 * 卖家的订单详情页在商家独立站点上，所以卖家必须在**商家站点自己的登录页**登录，
 * 否则会停在登录页（表现是后续断言连片超时，很容易被误读成"页面挂了"）。
 *   · 默认 'mall'：商城站点（买家 / 他人视角）
 *   · 'merchant'：商家站点（卖家视角）
 */
const loginUi = async (p, phone, site = 'mall') => {
  const base = site === 'merchant' ? MERCHANT : UI;
  await p.goto(`${base}/login`, { waitUntil: 'networkidle' });
  await p.getByPlaceholder('11 位手机号').fill(phone);
  await p.getByPlaceholder('请输入密码').fill(PWD);
  await p.getByRole('button', { name: /^登录$/ }).click();
  await p.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
};

// ---------------- 1. 夹具 ----------------

step('准备夹具：买家 / 卖家 / 他人 / 运营 四个账号');
const buyer = await newUser('物流买家');
const seller = await newUser('物流卖家');
const stranger = await newUser('物流他人');
const operator = await newUser('物流运营');
check('四个账号注册成功', buyer.phone && seller.phone && stranger.phone && operator.phone, null);

// 卖家：实名 → 申请入驻 → 重登（角色是 token 快照，必须重登才拿到 SELLER）
await call('POST', '/api/users/me/realname', { realName: '王发货', idCard: '310101199006066789' }, seller.jar);
await call('POST', '/api/users/me/realname', { realName: '李小商', idCard: '310101199005054321' }, seller.jar);
await createSellerFixture({ phone: seller.phone, storeName: '物流夹具店铺' });
check('卖家夹具直建成功（E10 库内 APPROVED）', true, null);
await call('POST', '/api/auth/login', { phone: seller.phone, password: PWD }, seller.jar);
const sellerMe = await call('GET', '/api/users/me', undefined, seller.jar);
check('卖家重新登录后角色为 SELLER', (sellerMe.data.role ?? '').toUpperCase() === 'SELLER', sellerMe.data.role);
const sellerId = bigId(sellerMe.raw);

// 运营：库内提权 → 重登（没有自助入驻入口，与 logistics-smoke.ps1 同口径）
await sql('duoduo_user', `UPDATE t_user SET role = 'OPERATOR' WHERE phone = '${operator.phone}';`);
await call('POST', '/api/auth/login', { phone: operator.phone, password: PWD }, operator.jar);
const opMe = await call('GET', '/api/users/me', undefined, operator.jar);
check('运营账号提权并重登生效', (opMe.data.role ?? '').toUpperCase() === 'OPERATOR', opMe.data.role);

step('准备夹具：两笔订单（O1 发货 / O2 已付款未发货）');
const list = (await call('GET', '/api/products?page=1&pageSize=1', undefined, buyer.jar)).data;
const spuId = list.items[0].id;
const detail = (await call('GET', `/api/products/${spuId}`, undefined, buyer.jar)).data;
const skuId = detail.skus[0].id;
const stockBefore = Number(await sql('duoduo_product', `SELECT stock FROM t_sku WHERE id = ${skuId};`));

async function placeAndPay() {
  await call('POST', '/api/cart/items', { skuId, quantity: 1 }, buyer.jar);
  const created = (
    await call('POST', '/api/orders', {
      receiverName: '小林',
      receiverPhone: '13900000000',
      receiverAddress: '上海市浦东新区张江路 1 号',
      idempotentKey: randomUUID(),
    }, buyer.jar)
  ).data;
  const no = created.orders[0].orderNo;
  await call('POST', `/api/orders/${no}/pay`, { channel: 'mock', idempotentKey: randomUUID() }, buyer.jar);
  return no;
}

const orderNo = await placeAndPay();   // O1：发货
const orderNo2 = await placeAndPay();  // O2：不发货
check('两笔订单下单并支付成功', Boolean(orderNo && orderNo2), { orderNo, orderNo2 });

// 商品域没有 seller_id，「这笔单是谁的」只能即位此刻到位：订单与托管单一起改
await sql('duoduo_trade', `UPDATE t_order o LEFT JOIN t_settlement s ON s.order_id = o.id
                     SET o.seller_id = ${sellerId}, s.seller_id = ${sellerId}
                     WHERE o.order_no IN ('${orderNo}', '${orderNo2}');`);
check(
  '（夹具）两笔订单归属已划给卖家',
  await sql('duoduo_trade', `SELECT COUNT(*) FROM t_order WHERE order_no IN ('${orderNo}','${orderNo2}') AND seller_id = ${sellerId};`) === '2',
  null,
);

step('接口层前置：发货即揽收 / 未发货无轨迹');
const shipped = await call(
  'POST',
  `/api/seller/orders/${orderNo}/ship`,
  { logisticsCompany: '顺丰速运', logisticsNo: 'SF1234567890' },
  seller.jar,
);
check('卖家通过商家端发货成功（带物流公司与单号）', shipped.status === 200, shipped.status);
const orderBefore = (await call('GET', `/api/orders/${orderNo}`, undefined, buyer.jar)).data;
check('（前置）订单状态为 SHIPPED', orderBefore.status === 'SHIPPED', orderBefore.status);

check('（前置）发货后接口层已能查到 1 条节点', await waitForTrackCount(orderNo, 1), null);
const beforeAppend = (await call('GET', `/api/logistics/orders/${orderNo}/tracks`, undefined, buyer.jar)).data;
check('（前置）该节点是「已揽收」', beforeAppend.tracks[0]?.nodeCode === 'PICKED_UP', beforeAppend.tracks[0]);
const notShippedTracks = (
  await call('GET', `/api/logistics/orders/${orderNo2}/tracks`, undefined, buyer.jar)
).data;
check('（前置）未发货订单接口层轨迹为空且不是错误', notShippedTracks.total === 0, notShippedTracks.total);

/** 揽收节点由交易域在发货事务提交后补推，存在毫秒级延迟 —— 轮询几秒再断言 */
async function waitForTrackCount(no, expected) {
  for (let i = 0; i < 20; i++) {
    const r = await call('GET', `/api/logistics/orders/${no}/tracks`, undefined, buyer.jar);
    if ((r.data?.total ?? -1) >= expected) return true;
    await new Promise((res) => setTimeout(res, 400));
  }
  return false;
}

// ---------------- 2. UI ----------------

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME ?? undefined, args: ['--no-sandbox'] });
const httpErrors = [];
const consoleErrors = [];

/** 每个 context 都要挂监听：漏掉哪个角色就漏掉哪一端的错误 */
async function openCtx() {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1100 }, locale: 'zh-CN' });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
  page.on('response', (r) => {
    if (r.status() >= 400) httpErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`);
  });
  return page;
}

const shot = async (p, name) => {
  await p.screenshot({ path: path.join(SHOTS, name), fullPage: true });
  console.log(`  [截图] ${SHOTS}/${name}`);
};
const bodyText = (p) => p.locator('body').innerText();
const rows = (p) => p.locator('[data-testid="logistics-track-row"]');
const nodeCodes = (p) =>
  rows(p).evaluateAll((els) => els.map((e) => e.getAttribute('data-node-code')));

// ---- 买家：发货后能看到「已揽收」 ----
step('UI：买家订单详情页 · 发货后出现「已揽收」');
const pb = await openCtx();
await loginUi(pb, buyer.phone);

await pb.goto(`${UI}/orders/${orderNo}`, { waitUntil: 'networkidle' });
await pb.waitForTimeout(1200);
let t = await bodyText(pb);
check('订单详情页出现「物流轨迹」卡片', t.includes('物流轨迹'), t.slice(0, 120));
check('卡片头部提示当前状态「当前：已揽收」', t.includes('当前：已揽收'), null);
check('时间线里有 1 条节点', (await rows(pb).count()) === 1, await rows(pb).count());
check('该节点是 PICKED_UP', JSON.stringify(await nodeCodes(pb)) === JSON.stringify(['PICKED_UP']), await nodeCodes(pb));
check('页面显示服务端下发的节点文案「已揽收」', t.includes('已揽收'), null);
check(
  '页面显示卖家填写的物流公司与单号（不用再去快递官网查）',
  t.includes('顺丰速运') && t.includes('SF1234567890'),
  null,
);
await shot(pb, 'lg_buyer_picked_up.png');

// ---- 追加节点（乱序推送）后页面按发生时间正序 ----
step('UI：追加 3 条节点（乱序推送）后，页面按发生时间正序排列');
const base = Date.now();
const payload = APPEND_NODES.map((n) => ({
  nodeCode: n.nodeCode,
  nodeText: n.nodeText,
  occurredAt: iso(new Date(base + n.offsetMinutes * 60_000)),
}));
const pushed = await ingest(orderNo, payload);
check('内部批量上报 3 条节点返回 200', pushed.status === 200, pushed.status);
check('回执：新增 3、跳过 0', pushed.data?.inserted === 3 && pushed.data?.skipped === 0, pushed.data);

await pb.goto(`${UI}/orders/${orderNo}`, { waitUntil: 'networkidle' });
await pb.waitForTimeout(1200);
t = await bodyText(pb);
check('刷新后时间线有 4 条节点', (await rows(pb).count()) === 4, await rows(pb).count());
const seq = await nodeCodes(pb);
check(
  '节点顺序为发生时间正序（乱序入库也被排好）',
  JSON.stringify(seq) === JSON.stringify(EXPECTED_SEQ),
  seq,
);
check('头部「当前」提示跟着走到最新一条「当前：派送中」', t.includes('当前：派送中'), null);
check('页面显示追加节点的描述原文（分拨中心）', t.includes('【南京分拨中心】已收入'), null);
check('页面显示追加节点的描述原文（派送中）', t.includes('请保持电话畅通'), null);
check(
  '页面同时出现「运输中」「到达分拨中心」两个服务端文案',
  t.includes('运输中') && t.includes('到达分拨中心'),
  null,
);
await shot(pb, 'lg_buyer_full.png');

// ---- 幂等最终反映到页面 ----
step('UI：重复推送后页面节点数不变（幂等最终反映到 UI）');
const again = await ingest(orderNo, payload);
check('原样重推：inserted=0 / skipped=3', again.data?.inserted === 0 && again.data?.skipped === 3, again.data);
await pb.goto(`${UI}/orders/${orderNo}`, { waitUntil: 'networkidle' });
await pb.waitForTimeout(1000);
check('页面节点数仍是 4（没有出现重复行）', (await rows(pb).count()) === 4, await rows(pb).count());

// ---- 买家：未发货订单是空态而不是错误态 ----
step('UI：未发货订单显示说明性空态');
await pb.goto(`${UI}/orders/${orderNo2}`, { waitUntil: 'networkidle' });
await pb.waitForTimeout(1200);
t = await bodyText(pb);
check('未发货订单页同样出现「物流轨迹」卡片', t.includes('物流轨迹'), null);
check('空态说明「卖家尚未发货」（不是错误提示）', t.includes('卖家尚未发货'), t.slice(-200));
check('未发货时不渲染任何时间线节点', (await rows(pb).count()) === 0, await rows(pb).count());
check('空态下没有出现「加载失败」这类错误文案', !t.includes('加载失败'), null);
await shot(pb, 'lg_buyer_not_shipped.png');

// ---- 卖家：看到同一份轨迹 ----
step('UI：卖家订单详情页看到与买家一致的轨迹');
const ps = await openCtx();
await loginUi(ps, seller.phone, 'merchant');
await ps.goto(`${MERCHANT}/seller/orders/${orderNo}`, { waitUntil: 'networkidle' });
await ps.waitForTimeout(1200);
t = await bodyText(ps);
check('卖家订单详情页出现「物流轨迹」', t.includes('物流轨迹'), t.slice(0, 120));
const seqSeller = await nodeCodes(ps);
check(
  '卖家端节点顺序与买家端完全一致（同一组件，不存在两种说法）',
  JSON.stringify(seqSeller) === JSON.stringify(EXPECTED_SEQ),
  seqSeller,
);
check('卖家端也显示「当前：派送中」', t.includes('当前：派送中'), null);
check('卖家端显示收货地址（被买家问起时能对单）', t.includes('上海市浦东新区张江路 1 号'), null);
await shot(ps, 'lg_seller_full.png');

// ---- 非当事人：看不到任何物流信息 ----
step('UI：非当事人打开该订单页看不到任何物流信息');
const pn = await openCtx();
await loginUi(pn, stranger.phone);
await pn.goto(`${UI}/orders/${orderNo}`, { waitUntil: 'networkidle' });
await pn.waitForTimeout(1200);
t = await bodyText(pn);
check('他人打开该订单页被挡住（订单详情本身就不放行）', t.includes('订单加载失败') || t.includes('订单不存在'), t.slice(0, 200));
check('他人页面不出现任何物流节点文案', !t.includes('已揽收') && !t.includes('派送中'), null);
check('他人页面不出现物流公司与单号', !t.includes('顺丰速运') && !t.includes('SF1234567890'), null);
await shot(pn, 'lg_stranger_blocked.png');

// ---- 订单终结后轨迹仍在 ----
// 这一条守的是组件里 `shipped` 的判定口径：它**同时看状态与 shippedAt**。
// 若只认 status === 'SHIPPED'，买家一确认收货，页面上的轨迹就会整块消失 ——
// 而包裹的签收记录恰恰是纠纷时最需要回看的东西。
step('UI：买家确认收货后，轨迹仍然可见（订单终结 ≠ 轨迹消失）');
await call('POST', `/api/orders/${orderNo}/confirm`, undefined, buyer.jar);
const afterConfirm = (await call('GET', `/api/orders/${orderNo}`, undefined, buyer.jar)).data;
check('（前置）订单已流转为 COMPLETED', afterConfirm.status === 'COMPLETED', afterConfirm.status);

await pb.goto(`${UI}/orders/${orderNo}`, { waitUntil: 'networkidle' });
await pb.waitForTimeout(1200);
check('已完成订单页仍渲染 4 条轨迹节点', (await rows(pb).count()) === 4, await rows(pb).count());
check('已完成订单页仍是同一个顺序（轨迹不被终结流程重排）', JSON.stringify(await nodeCodes(pb)) === JSON.stringify(EXPECTED_SEQ), await nodeCodes(pb));
await shot(pb, 'lg_buyer_completed.png');

await ps.goto(`${MERCHANT}/seller/orders/${orderNo}`, { waitUntil: 'networkidle' });
await ps.waitForTimeout(1200);
check('卖家端在已完成订单上同样看得到轨迹（售后举证要用）', (await rows(ps).count()) === 4, await rows(ps).count());

// ---------------- 3. 收尾 ----------------
step('收尾：错误清单与夹具复原');
if (httpErrors.length) {
  console.log('  [HTTP 4xx/5xx 清单]');
  httpErrors.forEach((e) => console.log('      ' + e));
}
// 401 是预期内的：auth store 启动时用 /users/me 探一次登录态，未登录就是 401。
// 403/404 也是预期内的：本脚本刻意让「非当事人」打开别人的订单，订单详情本就该被挡住。
// ⚠️ 过滤只针对「资源加载失败」这一类浏览器提示（且只放 401/403/404）；
//    真正的 JS 异常（Uncaught TypeError / pageerror 等）一律仍然算失败 ——
//    否则这个断言会退化成「永远为真」，那比没有更糟。
const ignoredResourceLog = (e) =>
  /favicon/i.test(e) ||
  /Failed to load resource: the server responded with a status of 40[134]/.test(e);

const unexpectedHttp = httpErrors.filter((e) => !/favicon/i.test(e) && !/(^|\s)40[134]\s/.test(e));
check('无非预期的 HTTP 错误（401 探活 / 403 越权 / 404 除外）', unexpectedHttp.length === 0, unexpectedHttp.slice(0, 5));

const unexpectedConsole = consoleErrors.filter((e) => !ignoredResourceLog(e));
check('无 console / pageerror（401/403/404 资源加载提示与 favicon 除外）', unexpectedConsole.length === 0, unexpectedConsole.slice(0, 5));

await browser.close();

if (!process.env.KEEP_FIXTURE) {
  await sql('duoduo_fulfilment', `DELETE FROM t_logistics_track WHERE order_no IN ('${orderNo}','${orderNo2}');`);
  await sql('duoduo_trade', `UPDATE t_order o LEFT JOIN t_settlement s ON s.order_id = o.id
                       SET o.seller_id = 0, s.seller_id = 0
                       WHERE o.order_no IN ('${orderNo}','${orderNo2}');`);
  await sql('duoduo_product', `UPDATE t_sku SET stock = ${stockBefore}, frozen_stock = 0 WHERE id = ${skuId};`);
  await sql('duoduo_user', `UPDATE t_user SET role = 'USER' WHERE phone = '${operator.phone}';`);
  console.log(`  （夹具已复原）两笔订单的轨迹已删除、订单归还平台自营、SKU ${skuId} 库存复原为 ${stockBefore}、运营账号降回 USER`);
}

console.log(`\n=== UI 自测结果：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  failures.forEach((f) => console.log(`  - ${f}`));
  process.exitCode = 1;
}

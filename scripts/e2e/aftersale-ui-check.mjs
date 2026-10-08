/**
 * 售后仲裁（A3）· 真实浏览器 UI 自测
 *
 * 为什么接口冒烟（aftersale-smoke.ps1，101 项）之外还要跑浏览器：
 *   接口测的是「规则对不对」，测不出「页面是否真的把规则呈现出来了」。
 *   M6 那次就是教训：tsc 与 build 全绿，真实浏览器里却是字典码值错配、按钮点了没反应。
 *   售后这类**多方协作流程**（买家 → 卖家 → 运营）尤其如此 ——
 *   任何一个角色在自己那一步看不到东西，整条链就断了，而接口层根本发现不了。
 *
 * 这段脚本走完整一条链：
 *   买家下单 → 申请售后 → 卖家拒绝（必填原因）→ 买家申请平台仲裁 → 运营裁定支持退款
 * 并断言每一步在**页面上**真的能看到对应状态，最后回接口层确认钱真的退了。
 *
 * 前置：后端 9 容器在跑（网关 :8080）、三站点容器（商城 8088 / 平台后台 8090 / 商家 8091）
 *
 * **E6b 改动**：卖家页（`/seller/after-sales`）与运营页（`/admin/after-sales`）此前都由
 * **商城入口**驱动；E5/E6 把后台与商家从商城摘除后，商城站点上已无这两条路径，
 * 故改为各自的**独立站点**（商家 `MERCHANT` / 后台 `ADMIN_UI`），登录也按站点拆分 ——
 * 三端会话互不共享（Cookie 主机级作用域），在哪个站点操作就必须在那个站点登录。
 *
 * ⚠️ 必须用 http://localhost:<port>，不能用 http://127.0.0.1:<port>：
 *    网关 CORS_ORIGINS 白名单只认 `localhost`，带 127.0.0.1 的 Origin 会被判非法跨域（403）。
 *    curl 不带 Origin 头所以复现不出来 —— 只在浏览器里现形。
 *
 * ⚠️ 端口：约定 5173，但**本机 5173 会被 Windows 排除端口段占用**（Docker Desktop 启动后
 *    WinNAT 保留 5149-5248，listen 直接 EACCES；查 `netsh int ipv4 show excludedportrange protocol=tcp`）。
 *    改用 3000 即可 —— 它已经在 CORS 白名单默认值里，**不需要动任何配置**：
 *      cd frontend && node node_modules/vite/bin/vite.js --port 3000 --strictPort --host localhost
 *      UI_BASE=http://localhost:3000 node scripts/e2e/aftersale-ui-check.mjs
 *
 * 用法：
 *   cd frontend && node node_modules/vite/bin/vite.js --port 3000   # 另开一个终端
 *   node scripts/e2e/aftersale-ui-check.mjs
 * 环境变量覆盖：GATEWAY / UI_BASE / CHROME_PATH / PW_CORE / KEEP_FIXTURE=1
 */

import path from 'node:path';
import { createSellerFixture } from '../lib/e2e-seller-fixture.mjs';
import { randomUUID } from 'node:crypto';
import { mysql as mysqlExec } from '../lib/docker-exec.mjs';

const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
// 必须是 localhost，不能是 127.0.0.1 —— 见文件头注释
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
/** 平台管理后台**独立站点**（运营仲裁页在这里） */
const ADMIN_UI = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
/** 第三方商家**独立站点**（店铺售后页在这里） */
const MERCHANT = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const SHOTS = 'D:/智能体项目/duoduo-pet-mall-java/frontend/docs/screenshots';
const CHROME =
  process.env.CHROME_PATH ?? null; // 不指定则交给 playwright 解析自己安装的那份浏览器
const PW_CORE =
  process.env.PW_CORE ??
  'file:///D:/智能体项目/duoduo-pet-mall-java/scripts/e2e/node_modules/playwright-core/index.mjs';

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
 * 所以在哪个站点上操作，就必须在**那个站点自己的登录页**登录 —— 这正是本次要修的东西。
 *   · 默认 'mall'：商城站点（买家视角），placeholder「11 位手机号」
 *   · 'admin'：平台后台站点，placeholder「请输入平台账号手机号」，登录后落在 /admin
 *   · 'merchant'：商家站点，placeholder 与商城一致，登录后落在 /seller（或入驻引导）
 */
const loginUi = async (p, phone, site = 'mall') => {
  const base = site === 'admin' ? ADMIN_UI : site === 'merchant' ? MERCHANT : UI;
  // 后台入口取 /admin.html（真入口），未登录时后台外壳会落到自有登录页；另两个站点直接进 /login
  await p.goto(site === 'admin' ? `${base}/admin.html` : `${base}/login`, { waitUntil: 'networkidle' });
  await p.getByPlaceholder(site === 'admin' ? '请输入平台账号手机号' : '11 位手机号').fill(phone);
  await p.getByPlaceholder('请输入密码').fill(PWD);
  await p.getByRole('button', { name: /^登录$/ }).click();
  await p.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
};

step('准备夹具：买家 / 卖家 / 运营 三个账号');

const buyer = await newUser('售后买家');
const seller = await newUser('售后卖家');
const operator = await newUser('售后运营');

// 卖家：实名 → 申请入驻 → 重登（角色是 token 快照）
await call('POST', '/api/users/me/realname', { realName: '李小商', idCard: '310101199005054321' }, seller.jar);
await call('POST', '/api/users/me/realname', { realName: '李小商', idCard: '310101199005054321' }, seller.jar);
await createSellerFixture({ phone: seller.phone, storeName: '售后夹具店铺' });
check('卖家夹具直建成功（E10 库内 APPROVED）', true, null);
await call('POST', '/api/auth/login', { phone: seller.phone, password: PWD }, seller.jar);
const sellerMe = await call('GET', '/api/users/me', undefined, seller.jar);
check('卖家重新登录后角色为 SELLER', (sellerMe.data.role ?? '').toUpperCase() === 'SELLER', sellerMe.data.role);
const sellerId = bigId(sellerMe.raw);

// 运营：库内提权 → 重登（没有自助入驻入口，与 aftersale-smoke.ps1 同口径）
await sql('duoduo_user', `UPDATE t_user SET role = 'OPERATOR' WHERE phone = '${operator.phone}';`);
await call('POST', '/api/auth/login', { phone: operator.phone, password: PWD }, operator.jar);
const opMe = await call('GET', '/api/users/me', undefined, operator.jar);
check('运营账号提权并重登生效', (opMe.data.role ?? '').toUpperCase() === 'OPERATOR', opMe.data.role);

step('准备夹具：买家下单支付，订单归属卖家并发货');

const list = (await call('GET', '/api/products?page=1&pageSize=1', undefined, buyer.jar)).data;
const spuId = list.items[0].id;
const detail = (await call('GET', `/api/products/${spuId}`, undefined, buyer.jar)).data;
await call('POST', '/api/cart/items', { skuId: detail.skus[0].id, quantity: 1 }, buyer.jar);
const created = (
  await call('POST', '/api/orders', {
    receiverName: '小林',
    receiverPhone: '13900000000',
    receiverAddress: '上海市浦东新区张江路 1 号',
    idempotentKey: randomUUID(),
  }, buyer.jar)
).data;
const orderNo = created.orders[0].orderNo;
const paid = await call('POST', `/api/orders/${orderNo}/pay`, { channel: 'mock', idempotentKey: randomUUID() }, buyer.jar);
check('订单支付成功', paid.status === 200, paid.status);

// 商品域没有 seller_id，「这笔单是谁的」只能即位此刻到位：订单与托管单一起改
await sql('duoduo_trade', `UPDATE t_order o LEFT JOIN t_settlement s ON s.order_id = o.id
                     SET o.seller_id = ${sellerId}, s.seller_id = ${sellerId}
                     WHERE o.order_no = '${orderNo}';`);
const shipped = await call(
  'POST',
  `/api/seller/orders/${orderNo}/ship`,
  { logisticsCompany: '中通快递', logisticsNo: 'ZT-AS-0001' },
  seller.jar,
);
check('卖家发货成功（订单进入 SHIPPED，可申请售后）', shipped.status === 200, shipped.status);

const orderBefore = (await call('GET', `/api/orders/${orderNo}`, undefined, buyer.jar)).data;
check('（前置）订单状态为 SHIPPED', orderBefore.status === 'SHIPPED', orderBefore.status);

// ---------------- 2. UI：三端走完整链路 ----------------

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME ?? undefined, args: ['--no-sandbox'] });
const httpErrors = [];
const consoleErrors = [];

/** 每个 context 都要挂监听：漏掉哪个角色就漏掉哪一端的错误 */
async function openCtx() {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
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
  console.log(`  [截图] frontend/docs/screenshots/${name}`);
};
const bodyText = (p) => p.locator('body').innerText();

// ---- 买家：申请售后 ----
step('UI：买家登录并从订单发起售后');
const pb = await openCtx();
await loginUi(pb, buyer.phone);

await pb.goto(`${UI}/orders`, { waitUntil: 'networkidle' });
await pb.waitForTimeout(900);
let t = await bodyText(pb);
check('订单列表能看到这笔订单', t.includes(orderNo), orderNo);
check('订单卡片出现「申请售后」入口', t.includes('申请售后'), t.slice(0, 120));

await pb.getByRole('link', { name: '申请售后' }).first().click();
await pb.waitForURL(/\/after-sales\/new/, { timeout: 15000 });
await pb.waitForTimeout(1000);
t = await bodyText(pb);
check('进入申请页并展示订单信息', t.includes('申请售后') && t.includes(orderNo), null);
await shot(pb, 'as_buyer_new.png');

const REASON = '收到的猫粮外包装破损、内袋漏气，要求退款';
await pb.getByPlaceholder(/要求退款/).fill(REASON);
await pb.getByPlaceholder(/photo-1\.jpg/).fill('https://example.com/break-1.jpg');
await pb.getByRole('button', { name: '提交售后申请' }).click();
await pb.waitForURL(/\/after-sales\/\d+/, { timeout: 20000 });
await pb.waitForTimeout(1200);

const asId = pb.url().match(/\/after-sales\/(\d+)/)[1];
// 这条断言是精度守门：URL 里必须是完整的 18 位雪花串，
// 若哪天有人把 id 改回 number，这里会先炸，而不是等用户点开一个 404
check('售后单 id 在 URL 中是完整雪花串（≥15 位，未被 JSON 精度截断）', /^\d{15,}$/.test(asId), asId);

t = await bodyText(pb);
check('提交后落到售后详情页', t.includes('售后详情'), t.slice(0, 80));
check('详情页显示初始状态「待卖家处理」', t.includes('待卖家处理'), t.slice(0, 200));
check('详情页显示买家诉求原文', t.includes(REASON), null);
check('详情页显示举证图入口', t.includes('查看图片'), null);
check('详情页出现进度步骤条', t.includes('提交申请') && t.includes('卖家处理'), null);
await shot(pb, 'as_buyer_detail_pending.png');

// ---- 卖家：拒绝（必填原因） ----
step('UI：卖家在店铺售后页拒绝，且拒绝原因必填');
const ps = await openCtx();
await loginUi(ps, seller.phone, 'merchant');

await ps.goto(`${MERCHANT}/seller/after-sales`, { waitUntil: 'networkidle' });
await ps.waitForTimeout(1000);
t = await bodyText(ps);
check('卖家售后页出现「商家端 · 售后服务」', t.includes('售后服务'), t.slice(0, 80));
check('卖家能看到这笔售后单', t.includes(orderNo), orderNo);
check('列表展示买家诉求', t.includes(REASON), null);
await shot(ps, 'as_seller_list.png');

// 必须限定在表格行内点：页签里也有「待卖家处理」，
// 而 getByRole 的 name 是**子串匹配** —— 不限定就会点到页签上，弹窗根本没打开
const sellerRow = ps.locator('tbody tr', { hasText: orderNo });
await sellerRow.getByRole('button', { name: '处理', exact: true }).click();
await ps.waitForTimeout(500);
await ps.locator('input[name="after-sale-action"]').nth(1).check();
// 先空着说明提交一次：前端拦一次，省一个来回（后端同样会 422）
await ps.getByRole('button', { name: '确认拒绝' }).click();
await ps.waitForTimeout(700);
t = await bodyText(ps);
check('拒绝但未填说明时被拦下并给出提示', t.includes('拒绝售后必须填写说明原因'), t.slice(-160));
await shot(ps, 'as_seller_reject_no_reason.png');

const SELLER_NOTE = '商品已拆封使用，影响二次销售，不同意退款';
await ps.getByPlaceholder(/商品已拆封使用/).fill(SELLER_NOTE);
await ps.getByRole('button', { name: '确认拒绝' }).click();
await ps.waitForTimeout(1500);
t = await bodyText(ps);
check('拒绝成功后列表状态变为「卖家已拒绝」', t.includes('卖家已拒绝'), t.slice(0, 200));
// 同理不能用「页面里还有没有『处理』两个字」来判断 —— 页签「待卖家处理」永远在。
// 判据是**这一行**还有没有操作按钮
const restBtns = await sellerRow.getByRole('button', { name: '处理', exact: true }).count();
check('拒绝后该行不再有「处理」按钮（卖家已处理完，球回到买家）', restBtns === 0, restBtns);
await shot(ps, 'as_seller_rejected.png');

// ---- 买家：看到卖家说明并申请平台仲裁 ----
step('UI：买家看到卖家说明并申请平台仲裁');
await pb.goto(`${UI}/after-sales/${asId}`, { waitUntil: 'networkidle' });
await pb.waitForTimeout(1200);
t = await bodyText(pb);
check('买家详情页显示卖家处理说明', t.includes('卖家的处理说明') && t.includes(SELLER_NOTE), t.slice(0, 300));
check('出现「申请平台仲裁」按钮', t.includes('申请平台仲裁'), null);
await shot(pb, 'as_buyer_rejected.png');

await pb.getByRole('button', { name: '申请平台仲裁' }).click();
await pb.waitForTimeout(1600);
t = await bodyText(pb);
check('升级后状态变为「平台仲裁中」', t.includes('平台仲裁中'), t.slice(0, 200));
// 不能用「页面里还有没有这几个字」判断：成功 toast 的文案就是「已申请平台仲裁」，
// 会被子串命中。判据只能是**按钮还在不在**
const escBtns = await pb.getByRole('button', { name: '申请平台仲裁' }).count();
check('仲裁中不再显示申请入口（幂等，不是每点一次发一次）', escBtns === 0, escBtns);
await shot(pb, 'as_buyer_arbitrating.png');

// ---- 运营：裁定支持退款 ----
step('UI：运营在后台裁定支持退款');
const po = await openCtx();
await loginUi(po, operator.phone, 'admin');

await po.goto(`${ADMIN_UI}/admin/after-sales`, { waitUntil: 'networkidle' });
await po.waitForTimeout(1000);
t = await bodyText(po);
check('运营后台出现「售后仲裁」', t.includes('售后仲裁'), t.slice(0, 80));
check('运营能看到这笔仲裁中的单', t.includes(orderNo), orderNo);
check('运营侧能看到卖家说明（裁定依据）', t.includes(SELLER_NOTE), null);
await shot(po, 'as_admin_list.png');

const adminRow = po.locator('tbody tr', { hasText: orderNo });
await adminRow.getByRole('button', { name: '裁定', exact: true }).click();
await po.waitForTimeout(500);
// 弹窗默认结论是「驳回」（保守默认值：误点不会直接把钱退出去），
// 所以先用空说明提交一次，验证说明必填这条兜底
await po.getByRole('button', { name: '确认驳回' }).click();
await po.waitForTimeout(700);
t = await bodyText(po);
check('裁定未填说明时被拦下（后端 422 的前端兜底）', t.includes('裁定必须填写说明'), t.slice(-160));
await shot(po, 'as_admin_no_note.png');

const ARB_NOTE = '买家举证显示外包装破损、内袋漏气，属发货包装不当，支持退款';
await po.locator('input[name="arbitrate-result"]').nth(0).check();
await po.getByPlaceholder(/买家举证显示外包装破损/).fill(ARB_NOTE);
await po.getByRole('button', { name: '支持退款并执行' }).click();
await po.waitForTimeout(2000);
t = await bodyText(po);
check('裁定后列表状态变为「已退款」', t.includes('已退款'), t.slice(0, 200));
await shot(po, 'as_admin_refunded.png');

// ---- 回到接口层：确认钱真的退了、留痕完整 ----
step('接口层复核：退款真的执行、留痕完整');

const asAfter = await call('GET', `/api/aftersales/${asId}`, undefined, buyer.jar);
check('售后单状态为 REFUNDED', asAfter.data.status === 'REFUNDED', asAfter.data.status);
check('裁定结论为 APPROVE_REFUND', asAfter.data.arbitrateResult === 'APPROVE_REFUND', asAfter.data.arbitrateResult);
check('裁定说明已留痕', asAfter.data.arbitrateNote === ARB_NOTE, asAfter.data.arbitrateNote);
check('卖家说明仍可回看（全流程留痕）', asAfter.data.sellerReply === SELLER_NOTE, asAfter.data.sellerReply);
check(
  '退款金额等于订单实付（钱真的按实付退，不是拍脑袋）',
  String(asAfter.data.refundAmount) === String(orderBefore.payAmount),
  { refund: asAfter.data.refundAmount, pay: orderBefore.payAmount },
);

const orderAfter = (await call('GET', `/api/orders/${orderNo}`, undefined, buyer.jar)).data;
check('订单状态同步为 REFUNDED', orderAfter.status === 'REFUNDED', orderAfter.status);

// 两张表都有 status，必须限定别名 —— 「字段歧义」在 SQL 里是最容易漏的一类错误
const settleStatus = await sql(
  'duoduo_trade',
  `SELECT s.status FROM t_settlement s JOIN t_order o ON o.id = s.order_id WHERE o.order_no = '${orderNo}';`,
);
check('托管单同步为 REFUNDED（账本与订单一致）', settleStatus === 'REFUNDED', settleStatus);

// ---- 买家端终态展示 ----
step('UI：买家看到终态');
await pb.goto(`${UI}/after-sales/${asId}`, { waitUntil: 'networkidle' });
await pb.waitForTimeout(1200);
t = await bodyText(pb);
check('买家详情页显示已退款完成提示', t.includes('已退款完成'), t.slice(0, 200));
check('买家详情页显示平台裁定说明', t.includes('平台裁定说明') && t.includes(ARB_NOTE), null);
check('买家详情页显示已退款金额', t.includes('已退款金额'), null);
await shot(pb, 'as_buyer_refunded.png');

await pb.goto(`${UI}/after-sales`, { waitUntil: 'networkidle' });
await pb.waitForTimeout(1000);
t = await bodyText(pb);
check('「我的售后」列表能看到这笔单', t.includes(orderNo), orderNo);
check('列表显示状态标签「已退款」', t.includes('已退款'), null);
await shot(pb, 'as_buyer_list.png');

step('收尾');
if (httpErrors.length) {
  console.log('  [HTTP 4xx/5xx 清单]');
  httpErrors.forEach((e) => console.log('      ' + e));
}
// 401 是预期内的：auth store 启动时用 /users/me 探一次登录态，未登录就是 401。
// 把它算作失败只会让每次运行都红一次，反而掩盖真问题。
const unexpectedHttp = httpErrors.filter((e) => !/favicon/i.test(e) && !/(^|\s)401\s/.test(e));
check('无非预期的 HTTP 错误（401 未登录探测与 favicon 除外）', unexpectedHttp.length === 0, unexpectedHttp.slice(0, 5));
check(
  '无 console / pageerror（401 资源加载失败与 favicon 除外）',
  consoleErrors.filter((e) => !/favicon/i.test(e) && !/status of 401/.test(e)).length === 0,
  consoleErrors.slice(0, 4),
);

await browser.close();

if (!process.env.KEEP_FIXTURE) {
  await sql('duoduo_trade', `UPDATE t_order o LEFT JOIN t_settlement s ON s.order_id = o.id
                       SET o.seller_id = 0, s.seller_id = 0 WHERE o.order_no = '${orderNo}';`);
  await sql('duoduo_user', `UPDATE t_user SET role = 'USER' WHERE phone = '${operator.phone}';`);
  console.log(`  （夹具已复原）订单 ${orderNo} 归还平台自营；运营账号降回 USER`);
}

console.log(`\n=== UI 自测结果：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  failures.forEach((f) => console.log(`  - ${f}`));
  process.exitCode = 1;
}

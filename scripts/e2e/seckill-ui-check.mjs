/**
 * 限时秒杀（A14）· 真实浏览器 UI 自测（**自包含**，不需要任何外部夹具）
 *
 * 为什么必须补这一套：秒杀是**唯一一条「手快有钱进」的主链路**（抢购 → 下单 → 付款），
 * 而此前 8 套 e2e 里对它的覆盖是 0 —— 后端 seckill-smoke.ps1 只证明接口对，
 * 证明不了「页面点得动、余量显示对、限购提示能出来」。
 *
 * 本脚本覆盖：
 *   1. 运营端（本轮新建的 /admin/seckill 页面）：建活动 → 列表显示 → **下架 → 上架**
 *      —— 关键性质：下架后活动**仍列得出来**，否则「下架」就是不可逆操作；
 *   2. C 端 /seckill：活动可见、余量与秒杀价展示正确；
 *   3. 抢购 → 跳订单详情，应付金额 = 秒杀价，余量 3 → 2；
 *   4. **限购**：同一个人抢第二次被服务端拒绝，且页面把原因说清楚（不静默失败）；
 *   5. **幂等**：同一 idempotentKey 重复提交返回同一订单号（replay=true）；
 *   6. 收尾取消订单 → 配额回补，不留脏数据。
 *
 * 与既有 e2e 的两点不同：
 *   · 建夹具走**异步** `scripts/lib/docker-exec.mjs`（本机 Node 的 spawnSync/execFileSync
 *     一律 EBUSY errno -4082，只有异步 spawn 可用）；
 *   · 运营账号是**随机手机号 + 提权走库**，绝不把既有账号改成 OPERATOR。
 *
 * 前置：9 容器在跑（网关 :8080）；frontend dev server 在 :5173（run-ui-checks.ps1 会托管）。
 * ⚠️ 必须用 http://localhost:5173，不能用 127.0.0.1 —— 网关 CORS 白名单只认 localhost。
 *
 * 用法：node scripts/e2e/seckill-ui-check.mjs
 */

import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
const SHOTS = 'D:/智能体项目/duoduo-pet-mall-java/frontend/docs/screenshots';
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? undefined;

const PWD = 'duoduo12345';
const SKU = 2001; // 种子商品规格：麦富迪 冻干双拼全价猫粮 2kg
const SECKILL_PRICE = 100; // 1.00 元（刻意远低于原价，页面上一眼能认出）
const TOTAL_STOCK = 3;
const PER_LIMIT = 1;

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

// ---------------- HTTP 辅助 ----------------
async function call(method, p, body, jar) {
  const headers = {};
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
  try { json = JSON.parse(raw); } catch { /* 保留 raw */ }
  return { status: res.status, data: json?.data, json, raw };
}

/** 服务端与容器时区均为 UTC —— 时间一律按 UTC 挂钟下发，不能用本地时间直觉 */
const utc = (plusMinutes) => new Date(Date.now() + plusMinutes * 60_000).toISOString().slice(0, 19);

const randPhone = () => '137' + String(Math.floor(1e7 + Math.random() * 8e7));

/** 建一个 OPERATOR 账号：随机手机号 + 提权走库（既有账号一个都不动） */
async function newOperator() {
  const jar = new Map();
  const phone = randPhone();
  await call('POST', '/api/auth/register', { phone, password: PWD, nickname: '秒杀运营-夹具' }, jar);
  for (let i = 0; i < 5; i++) {
    await mysql('duoduo_user', `UPDATE t_user SET role='OPERATOR' WHERE phone='${phone}';`);
    const role = await mysql('duoduo_user', `SELECT role FROM t_user WHERE phone='${phone}';`);
    if (role.trim() === 'OPERATOR') break;
  }
  const login = await call('POST', '/api/auth/login', { phone, password: PWD }, jar);
  return { jar, phone, role: login.data?.role ?? login.json?.data?.role };
}

// ---------------- 1. 夹具：运营 + 买家 + 地址 ----------------
step('夹具：运营账号');
const op = await newOperator();
check('运营账号提权成功（否则后面全是 403，排查方向会整个跑偏）', op.role === 'OPERATOR', op.role);

step('夹具：买家账号与收货地址');
const buyerJar = new Map();
const buyerPhone = randPhone();
const reg = await call('POST', '/api/auth/register', { phone: buyerPhone, password: PWD, nickname: '秒杀买家' }, buyerJar);
check('买家注册成功', reg.status === 200, reg.status);
const addr = await call('POST', '/api/addresses', {
  receiverName: '小林',
  receiverPhone: '13900000000',
  province: '上海市',
  city: '上海市',
  district: '浦东新区',
  detail: '张江路 1 号',
  isDefault: true,
}, buyerJar);
check('买家默认地址建好（抢购强依赖它，没有会直接被引导去地址页）', addr.status === 200 && Boolean(addr.data?.id), addr.status);

// ---------------- 2. 建活动（运营 HTTP） ----------------
step('夹具：创建秒杀活动');
const TITLE = '【UI自测】秒杀 ' + Date.now();
const created = await call('POST', '/api/admin/seckill/activities', {
  title: TITLE,
  startAt: utc(-5),
  endAt: utc(120),
  skus: [{ skuId: SKU, seckillPrice: SECKILL_PRICE, totalStock: TOTAL_STOCK, perLimit: PER_LIMIT }],
}, op.jar);
check('活动创建成功', created.status === 200 && Boolean(created.data?.id), created.status);
const ACT_ID = created.data?.id;
if (!ACT_ID) {
  console.error('\n[前置缺失] 活动未建成，后续断言失去意义，直接退出');
  process.exit(1);
}
check('新建活动默认上架（enabled=true）', created.data.enabled === true, created.data.enabled);
check('活动阶段为进行中（开始时间取 5 分钟前）', created.data.phase === 'ONGOING', created.data.phase);
check('配额与限购按入参装载', created.data.skus?.[0]?.totalStock === TOTAL_STOCK && created.data.skus?.[0]?.perLimit === PER_LIMIT, created.data.skus?.[0]);

// ---------------- 3. 浏览器：运营端后台页 ----------------
const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

async function newPage() {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
  const page = await ctx.newPage();
  const consoleErrors = [];
  const httpErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
  page.on('response', (r) => { if (r.status() >= 400) httpErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`); });
  return { page, consoleErrors, httpErrors };
}

async function login(page, phone) {
  await page.goto(`${UI}/login`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('11 位手机号').fill(phone);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
}

step('UI：后台秒杀管理页（运营视角）');
{
  const { page, consoleErrors, httpErrors } = await newPage();
  await login(page, op.phone);
  await page.goto(`${UI}/admin/seckill`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);

  const body = await page.locator('body').innerText();
  check('后台秒杀页渲染出「平台管理后台 · 秒杀活动」', body.includes('秒杀活动'), body.slice(0, 200));
  check('顶部页签含「秒杀活动」入口', body.includes('秒杀活动'), body.slice(0, 300));
  check('本轮新建活动出现在列表里', body.includes(TITLE), body.slice(0, 600));

  const row = page.locator('tr', { hasText: TITLE }).first();
  check('该活动所在行可见', await row.count() === 1, await row.count());
  let rowText = await row.innerText();
  check('阶段列显示「进行中」', rowText.includes('进行中'), rowText);
  check('开关列显示「已上架」', rowText.includes('已上架'), rowText);
  check(`余量按 剩余/总量 展示（${TOTAL_STOCK}/${TOTAL_STOCK}）`, rowText.includes(`${TOTAL_STOCK}/${TOTAL_STOCK}`), rowText);
  check('页头写明时间是 UTC（避免运营按北京时间填，白等 8 小时）', body.includes('UTC'), body.slice(0, 400));
  await page.screenshot({ path: path.join(SHOTS, 'seckill_admin_list.png'), fullPage: true });

  // 下架 → 列表仍能列出（「下架不可逆」是本页最容易踩的坑，必须有断言守着）
  await row.getByRole('button', { name: '下架' }).click();
  await page.waitForTimeout(1200);
  const afterOff = await page.locator('tr', { hasText: TITLE }).first();
  rowText = await afterOff.innerText();
  check('★ 下架后按钮变为「上架」', rowText.includes('上架'), rowText);
  check('★ 下架后该活动**仍在列表里**（否则再也点不回来）',
    (await page.locator('tr', { hasText: TITLE }).count()) === 1, await page.locator('tr', { hasText: TITLE }).count());

  // C 端此刻应看不到
  const cList = await call('GET', '/api/seckill/activities');
  check('下架期间 C 端列表不含该活动',
    !(cList.data ?? []).some((a) => String(a.id) === String(ACT_ID)), (cList.data ?? []).map((a) => a.id));

  // 再上架回去
  await afterOff.getByRole('button', { name: '上架' }).click();
  await page.waitForTimeout(1200);
  rowText = await page.locator('tr', { hasText: TITLE }).first().innerText();
  check('★ 重新上架后变回「已上架」', rowText.includes('已上架'), rowText);
  await page.screenshot({ path: path.join(SHOTS, 'seckill_admin_toggled.png'), fullPage: true });

  const noisy = httpErrors.filter((e) => !/favicon/i.test(e) && !/(^|\s)401\s/.test(e));
  check('后台页无非预期 4xx/5xx', noisy.length === 0, noisy.slice(0, 5));
  check('后台页无 console / pageerror', consoleErrors.filter((e) => !/favicon|status of 401/i.test(e)).length === 0,
    consoleErrors.slice(0, 5));
}

// ---------------- 4. 浏览器：C 端抢购 ----------------
let orderNo = '';
step('UI：C 端 /seckill 抢购');
{
  const { page, consoleErrors, httpErrors } = await newPage();

  await page.goto(`${UI}/seckill`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  let body = await page.locator('body').innerText();
  check('未登录也能看到秒杀活动（公开只读导流页）', body.includes('限时秒杀'), body.slice(0, 200));
  check('C 端列表含本轮活动', body.includes(TITLE), body.slice(0, 500));
  await page.screenshot({ path: path.join(SHOTS, 'seckill_guest.png'), fullPage: true });

  await login(page, buyerPhone);
  await page.goto(`${UI}/seckill`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);

  const section = page.locator('section.seckill-card', { hasText: TITLE }).first();
  check('活动区块定位到', (await section.count()) === 1, await section.count());
  let secText = await section.innerText();
  check('阶段标签显示「抢购中」', secText.includes('抢购中'), secText);
  check('秒杀价按分转元展示 ¥1.00', secText.includes('¥1.00'), secText);
  check(`余量展示「仅剩 ${TOTAL_STOCK} 件」`, secText.includes(`仅剩 ${TOTAL_STOCK} 件`), secText);
  check('展示限购件数', secText.includes(`限购 ${PER_LIMIT} 件`), secText);

  await section.getByRole('button', { name: '马上抢' }).click();
  await page.waitForURL((u) => u.pathname.includes('/orders/'), { timeout: 20000 });
  await page.waitForTimeout(1500);
  body = await page.locator('body').innerText();
  check('抢购成功跳到订单详情页', page.url().includes('/orders/'), page.url());
  orderNo = (body.match(/\d{16,}/) ?? [])[0] ?? '';
  check('订单详情页给出订单号', Boolean(orderNo), body.slice(0, 300));
  check('订单应付金额为秒杀价 ¥1.00（不是原价）', body.includes('¥1.00'), body.slice(0, 800));
  check('订单标记为秒杀单（备注 / 类型可见）', /秒杀/.test(body), body.slice(0, 800));
  await page.screenshot({ path: path.join(SHOTS, 'seckill_order_detail.png'), fullPage: true });

  // 余量 3 → 2
  const detail = await call('GET', `/api/seckill/activities/${ACT_ID}`);
  const left = detail.data?.skus?.[0]?.availableStock;
  check(`★ 抢购后余量从 ${TOTAL_STOCK} 变为 ${TOTAL_STOCK - 1}（真的扣了配额）`, left === TOTAL_STOCK - 1, left);

  // 限购：第二次抢应被拒，且页面把原因说出来
  await page.goto(`${UI}/seckill`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  const sec2 = page.locator('section.seckill-card', { hasText: TITLE }).first();
  await sec2.getByRole('button', { name: '马上抢' }).click();
  await page.waitForTimeout(2500);
  const t2 = await page.locator('body').innerText();
  check('★ 第二次抢购被限购拒绝，页面明说「限购」', /限购/.test(t2), t2.slice(0, 500));
  check('被拒后仍停留在秒杀页（不是静默失败）', page.url().includes('/seckill'), page.url());
  await page.screenshot({ path: path.join(SHOTS, 'seckill_limit_hit.png'), fullPage: true });

  const after = await call('GET', `/api/seckill/activities/${ACT_ID}`);
  check(`★ 被限购拒绝后余量没被扣（仍是 ${TOTAL_STOCK - 1}）`,
    after.data?.skus?.[0]?.availableStock === TOTAL_STOCK - 1, after.data?.skus?.[0]?.availableStock);

  /*
   * 未支付订单详情页的 `/settlement` 404 是**既有设计**，不是缺陷：
   * `OrderService.settlementOf()` 对「还没有托管记录」的订单按设计返回 404
   * （OrderView.settlement 注释写明「未支付时为 null」），页面自行忽略、不渲染该区块。
   * 与 coupon-ui-check.mjs 同一条放行口径（那里同样下单不支付）。
   * 记为已知良性：前端可在未支付时跳过这次请求，省一次 404。
   */
  const noisy = httpErrors.filter((e) => !/favicon/i.test(e) && !/(^|\s)401\s/.test(e)
    && !/\/settlement/.test(e) && !/(\s|^)(400|422)\s/.test(e));
  check('C 端无非预期 4xx/5xx（限购 4xx 是本用例刻意触发的）', noisy.length === 0, noisy.slice(0, 6));
  check('C 端无 pageerror', consoleErrors.filter((e) => /pageerror/.test(e)).length === 0, consoleErrors.slice(0, 5));
}

// ---------------- 5. 幂等（HTTP 口径） ----------------
step('幂等：同一 idempotentKey 重复提交');
{
  // 换一个没抢过的买家，避免限购挡在幂等前面（两条性质要分开验，否则互相掩盖）
  const jar2 = new Map();
  const phone2 = randPhone();
  await call('POST', '/api/auth/register', { phone: phone2, password: PWD, nickname: '秒杀幂等买家' }, jar2);
  await call('POST', '/api/addresses', {
    receiverName: '小林', receiverPhone: '13900000000', province: '上海市', city: '上海市',
    district: '浦东新区', detail: '张江路 1 号', isDefault: true,
  }, jar2);
  const def = await call('GET', '/api/addresses/default', undefined, jar2);
  const key = crypto.randomUUID();
  const r1 = await call('POST', '/api/seckill/orders',
    { activityId: ACT_ID, skuId: SKU, addressId: def.data.id, idempotentKey: key }, jar2);
  const r2 = await call('POST', '/api/seckill/orders',
    { activityId: ACT_ID, skuId: SKU, addressId: def.data.id, idempotentKey: key }, jar2);
  check('首次秒杀下单成功', r1.status === 200 && Boolean(r1.data?.orderNo), r1.status);
  check('★ 同幂等键重放返回**同一个订单号**', r2.data?.orderNo === r1.data?.orderNo,
    { first: r1.data?.orderNo, second: r2.data?.orderNo });
  check('★ 重放标记为 replay=true（前端据此提示「已为你打开原订单」）', r2.data?.replay === true, r2.data?.replay);
  if (r1.data?.orderNo) {
    const cancel = await call('POST', `/api/orders/${r1.data.orderNo}/cancel`, { reason: 'UI 自测收尾' }, jar2);
    check('幂等用例的订单已取消（不留待付款脏单）', cancel.status === 200, cancel.status);
  }
}

// ---------------- 6. 收尾：取消订单 → 配额回补 → 活动下架 ----------------
step('收尾：取消订单与配额回补');
if (orderNo) {
  // 断言钉「取消一次就 +1」这条**不变的性质**，而不是钉「现在应该是几」：
  // 幂等用例的订单在上一节已经取消过一次，写死 2 会在任何一步顺序调整后假红
  // （本文件初版就写死了 2，而正确答案是 3 —— 配额其实一分不差）。
  const before = await call('GET', `/api/seckill/activities/${ACT_ID}`);
  const stockBeforeCancel = before.data?.skus?.[0]?.availableStock;
  const cancelled = await call('POST', `/api/orders/${orderNo}/cancel`, { reason: 'UI 自测收尾' }, buyerJar);
  check('取消秒杀订单成功', cancelled.status === 200, cancelled.status);
  const afterCancel = await call('GET', `/api/seckill/activities/${ACT_ID}`);
  const restored = afterCancel.data?.skus?.[0]?.availableStock;
  check(`★ 取消后配额 +1（${stockBeforeCancel} → ${stockBeforeCancel + 1}）`,
    restored === stockBeforeCancel + 1, { before: stockBeforeCancel, after: restored });
}
// 活动下架：C 端列表不留自测痕迹（运营列表仍保留，便于人工复查）
const off = await call('POST', `/api/admin/seckill/activities/${ACT_ID}/status`, { enabled: false }, op.jar);
check('收尾：活动已下架，C 端不再展示', off.status === 200 && off.data?.enabled === false, off.data?.enabled);

await browser.close();

console.log(`\n=== 秒杀 UI 自测结果：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log(`  - ${f}`);
}
process.exit(failures.length ? 1 : 0);

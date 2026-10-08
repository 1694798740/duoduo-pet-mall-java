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
 * 前置：9 容器在跑（网关 :8080）；三个站点容器（商城 8088 / 后台 8090 / 商家 8091）。
 * ⚠️ 不能用 127.0.0.1 访问站点 —— 网关 CORS 白名单只认 localhost 系主机名。
 *
 * **E6b 改动（重要）**：后台页此前是通过**商城入口**的 `/admin/seckill` 驱动的；
 * E5 把后台从商城摘除后，商城站点已**没有** `/admin/*` 路由，本套件第 3 段与 R74 段
 * 因此从「红」变成**预期必须改**：现在一律走后台**独立站点** `${ADMIN_UI}/admin/seckill`。
 * 登录也随之拆开：C 端抢购走商城登录（`loginMall`），后台管理走后台自己的登录页（`loginAdmin`）——
 * 这正是三端独立部署要验的性质（两边会话互不共享）。
 *
 * 用法：node scripts/e2e/seckill-ui-check.mjs
 */

import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
/** 商城站点（C 端）：容器化后为 http://localhost:8088；dev server 模式为 5173 */
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
/** 平台管理后台**独立站点**（E5/E6：不同主机名 → 独立会话 + 独立登录页） */
const ADMIN_UI = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
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

/** 商城站点登录（C 端买家） */
async function loginMall(page, phone) {
  await page.goto(`${UI}/login`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('11 位手机号').fill(phone);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
}

/**
 * 平台后台**独立站点**登录（运营视角）。
 * 入口取 `/admin.html`（真入口）而非直达 `/login`：未登录时后台外壳会自行落到自有登录页，
 * 这样顺带把「后台有自己的登录页、不依赖商城」这条性质也走了一遍。
 */
async function loginAdmin(page, phone) {
  await page.goto(`${ADMIN_UI}/admin.html`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('请输入平台账号手机号').fill(phone);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => u.pathname === '/admin', { timeout: 15000 });
}

step('UI：后台秒杀管理页（运营视角 · 后台独立站点）');
{
  const { page, consoleErrors, httpErrors } = await newPage();
  await loginAdmin(page, op.phone);
  await page.goto(`${ADMIN_UI}/admin/seckill`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);

  check('★ 后台站点已独立：URL 落在 admin.localhost（不再是商城主机名）',
    new URL(page.url()).hostname === new URL(ADMIN_UI).hostname, page.url());
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

  await loginMall(page, buyerPhone);
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

// ---------------- R74：后台「编辑 / 删除」（★ 必须排在「收尾取消订单」之前）----------------
// 为什么顺序重要：删活动被拒的判据是「还有未关闭订单」。收尾会把抢购订单取消掉，
// 那之后活动就**真的可以删**了 —— 若把本段排在收尾之后，「应当被拒」的断言会变成假红
// （而且删除会真的成功、弹窗立即关闭，后续按弹窗定位的断言会连片超时）。
// 这一段与结尾的「自清理删除」正好构成同一个守卫的两面。
step('UI：后台秒杀管理页 · 编辑与删除（R74）');
{
  const { page, consoleErrors, httpErrors } = await newPage();
  await loginAdmin(page, op.phone);
  await page.goto(`${ADMIN_UI}/admin/seckill`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);

  const EDITED = `【UI自测】已改名 ${Date.now() % 1000000}`;

  // ---- 编辑：只改标题与时间（商品/价格/配额刻意不开） ----
  const row = page.locator('tr', { hasText: TITLE }).first();
  check('★ 行内有「编辑」按钮（此前只有上下架）', (await row.getByRole('button', { name: '编辑' }).count()) === 1,
    await row.innerText());
  await row.getByRole('button', { name: '编辑' }).click();
  await page.waitForTimeout(500);
  const modal = page.locator('.modal').last();
  check('编辑弹窗标题为「编辑秒杀活动」',
    (await modal.locator('.modal-title').innerText()).includes('编辑秒杀活动'),
    await modal.locator('.modal-title').innerText());
  check('弹窗里显式说明商品/价格/配额不可改（避免运营找不到入口反复点）',
    (await modal.innerText()).includes('不可改'), (await modal.innerText()).slice(0, 300));
  check('★ 弹窗不给「改秒杀价 / 改配额」的输入框（只开标题与两个时间）',
    (await modal.locator('.modal-body input').count()) === 3,
    await modal.locator('.modal-body input').count());
  await modal.locator('.modal-body input.input').first().fill(EDITED);
  await modal.getByRole('button', { name: '保存' }).click();
  await page.waitForTimeout(1600);
  check('★ 编辑后列表显示新标题', (await page.locator('tr', { hasText: EDITED }).count()) === 1,
    await page.locator('tr', { hasText: EDITED }).count());
  const afterEditApi = await call('GET', '/api/admin/seckill/activities', undefined, op.jar);
  const editedView = (afterEditApi.data ?? []).find((a) => String(a.id) === String(ACT_ID));
  check('★ 编辑已真的落库（接口回读一致，不是只改了页面）', editedView?.title === EDITED, editedView?.title);
  await page.screenshot({ path: path.join(SHOTS, 'seckill_admin_edited.png'), fullPage: true });

  // ---- 删除：已有订单的活动必须被拒，且把「改用下架」说清楚 ----
  const row2 = page.locator('tr', { hasText: EDITED }).first();
  await row2.getByRole('button', { name: '删除' }).click();
  await page.waitForTimeout(500);
  const delModal = page.locator('.modal').last();
  const delText0 = await delModal.innerText();
  check('★ 删除弹窗默认不做危险动作（主按钮是「取消」，且可用 Esc 关掉）',
    (await delModal.locator('.modal-foot button').first().innerText()).includes('取消'), delText0.slice(0, 200));
  check('删除弹窗说明只删「没有未关闭订单」的活动', delText0.includes('没有未关闭订单'), delText0.slice(0, 300));
  await delModal.getByRole('button', { name: '确认删除' }).click();
  await page.waitForTimeout(1600);
  const delText1 = await delModal.innerText();
  check('★ 有「未关闭订单」的活动删除被拒，页面把原因显示出来（不静默失败）',
    delText1.includes('不能删除') && delText1.includes('下架'), delText1.slice(0, 300));
  check('★ 被拒后活动仍在列表里（没有半删）',
    (await page.locator('tr', { hasText: EDITED }).count()) === 1, '活动行消失');
  await page.keyboard.press('Escape');
  await page.waitForTimeout(400);

  // ---- 删除：无订单的活动必须真能删掉（同端点的另一面，证明守卫不是无条件拒绝） ----
  const THROW = `【UI自测】待删 ${Date.now() % 1000000}`;
  const madeThrow = await call('POST', '/api/admin/seckill/activities', {
    title: THROW,
    startAt: utc(-5),
    endAt: utc(120),
    skus: [{ skuId: SKU, seckillPrice: SECKILL_PRICE, totalStock: 1, perLimit: 1 }],
  }, op.jar);
  check('建一场「无人下单」的待删活动', madeThrow.status === 200 && Boolean(madeThrow.data?.id), madeThrow.status);
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(1000);
  const throwRow = page.locator('tr', { hasText: THROW }).first();
  check('待删活动出现在列表里', (await throwRow.count()) === 1, await throwRow.count());
  await throwRow.getByRole('button', { name: '删除' }).click();
  await page.waitForTimeout(500);
  await page.locator('.modal').last().getByRole('button', { name: '确认删除' }).click();
  await page.waitForTimeout(1600);
  check('★ 无订单的活动删除成功，行从列表消失',
    (await page.locator('tr', { hasText: THROW }).count()) === 0, '行仍在');
  const afterDelApi = await call('GET', '/api/admin/seckill/activities', undefined, op.jar);
  check('★ 接口回读确认已删除（不残留）',
    !(afterDelApi.data ?? []).some((a) => String(a.title) === THROW),
    (afterDelApi.data ?? []).map((a) => a.title).filter((t) => t.includes('待删')));
  await page.screenshot({ path: path.join(SHOTS, 'seckill_admin_deleted.png'), fullPage: true });

  const noisy = httpErrors.filter((e) => !/favicon/i.test(e) && !/(^|\s)(401|403|404|422)\s/.test(e));
  check('R74 段落无非预期 4xx/5xx（401/403/404/422 是本段刻意触发的拒绝路径 + 未登录态首屏拉资料）',
    noisy.length === 0, noisy.slice(0, 5));
  check('R74 段落无 console / pageerror（401 属设计内，按本仓既有口径过滤）',
    consoleErrors.filter((e) => !/favicon|status of (401|403|404|422)/i.test(e)).length === 0, consoleErrors.slice(0, 5));
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

// R74：订单全部已关闭之后，活动应当**可以删除** —— 本套件因此不留任何活动残留。
// （此前只能下架，导致「每跑一次自测就多一场永远清不掉的活动」，正是 R74 要解决的那个问题本身。）
const delSelf = await call('DELETE', `/api/admin/seckill/activities/${ACT_ID}`, undefined, op.jar);
check('★ 收尾：订单全已关闭 → 活动可删除（自测不留活动残留）', delSelf.status === 200,
  `${delSelf.status} ${delSelf.raw?.slice(0, 160)}`);
const afterSelfClean = await call('GET', '/api/admin/seckill/activities', undefined, op.jar);
check('★ 收尾：活动确已从运营列表消失',
  !(afterSelfClean.data ?? []).some((a) => String(a.id) === String(ACT_ID)),
  (afterSelfClean.data ?? []).map((a) => a.id));

// ---------------- 收尾：运营夹具降权（不留多余后台入口） ----------------
// 与 share-report-ui-check 同一处置：e2e 用「随机手机号 + 库提权」建运营账号后**必须收尾**，
// 否则每跑一次就在库里多留一个**永久 OPERATOR** —— 而 OPERATOR 正是能登录平台后台的角色，
// 等于给系统留了一个多余的后台入口（库内已积压数十个同类账号，本次一并开始收口）。
const opUserId = (await mysql('duoduo_user', `SELECT id FROM t_user WHERE phone='${op.phone}';`)).trim();
await mysql('duoduo_user', `UPDATE t_user SET role='USER' WHERE id=${opUserId};`);
const opRoleAfter = (await mysql('duoduo_user', `SELECT role FROM t_user WHERE id=${opUserId};`)).trim();
check('★ 收尾：运营夹具已降权回 USER（不留多余后台入口）', opRoleAfter === 'USER', opRoleAfter);

await browser.close();

console.log(`\n=== 秒杀 UI 自测结果：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log(`  - ${f}`);
}
process.exit(failures.length ? 1 : 0);

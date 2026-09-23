/**
 * 优惠券营销（A5）· 真实浏览器 UI 自测
 *
 * 为什么接口冒烟（coupon-smoke.ps1）145 项全绿还不够：
 *   接口对了不代表页面走通 —— **路由没挂**（本轮 A5 就新加了 `/coupons`）、
 *   按钮点不动、请求参数名写错、金额展示与后端口径不一致，
 *   这些只有真实浏览器里点一遍才现形（M9 的物流单号、M6 的字典码值都是这么发现的）。
 *
 * 本脚本覆盖：
 *   1. 未登录访客视角走 `/coupons`：能看到有什么券，但**标为不可领**且写明「登录后可领取」
 *      —— 这是本轮修掉的缺陷，必须在真实页面上验证一次；
 *   2. 登录后领券 → 我的券包 → 结算页自动选中推荐券并算对费用 → 取消选券/重选 → 下单；
 *   3. 订单详情显示优惠金额与券码；取消订单后券退回（顺带验解锁路径）；
 *   4. 收集 console 报错与非预期 HTTP 错误。
 *
 * ⚠️ 本脚本**刻意不碰数据库**：夹具的建/删由 `scripts/e2e/coupon-ui-fixture.ps1` 负责。
 *    原因：本机 Node 无法创建任何子进程（`spawnSync/execFileSync` 一律
 *    `EBUSY errno -4082`；cmd / powershell / docker 都试过，两版 Node、沙箱内外都一样），
 *    所以「在 Node 里 execFileSync('docker', ['exec','duoduo-mysql','mysql',...])」那种写法
 *    在这里跑不通（本文件初版就是这么写的，第一次跑通纯属侥幸，随后稳定失败）。
 *    SQL 交给 PowerShell —— `& docker exec` 是通的，coupon-smoke.ps1 一直这么干；
 *    Node 只做 HTTP + 浏览器，职责反而更干净。
 *
 * 前置：
 *   1. 9 容器在跑（网关 :8080、营销 :8085）；
 *   2. frontend dev server 在跑（:5173）；
 *   3. **先建夹具**（模板 id 经 `.tmp-coupon-ui-tpl-id` 或 `COUPON_TPL_ID` 传入）。
 *
 * ⚠️ 必须用 http://localhost:5173，不能用 127.0.0.1 —— 网关 CORS 白名单只认 localhost。
 *
 * 用法：
 *   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\coupon-ui-fixture.ps1 -Action create
 *   cd frontend && node node_modules/vite/bin/vite.js --port 5173     # 另开一个终端
 *   node scripts/e2e/coupon-ui-check.mjs
 *   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\coupon-ui-fixture.ps1 -Action drop
 */

import path from 'node:path';
import fs from 'node:fs';

const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
const SHOTS = 'D:/智能体项目/duoduo-pet-mall-java/frontend/docs/screenshots';
const TPL_ID_FILE = 'D:/智能体项目/duoduo-pet-mall-java/.tmp-coupon-ui-tpl-id';
const CHROME =
  process.env.CHROME_PATH ??
  'C:/Users/16947/AppData/Local/ms-playwright/chromium-1161/chrome-win/chrome.exe';
const PW_CORE =
  process.env.PW_CORE ??
  'file:///C:/Users/16947/.workbuddy/binaries/node/workspace/node_modules/playwright-core/index.mjs';
const INTERNAL_TOKEN = 'duoduo-local-dev-internal-token';
const PWD = 'duoduo12345';
const SKU = 2001;

const TPL_NAME = process.env.COUPON_TPL_NAME ?? '【UI自测】满 50 减 5';
const TPL_THRESHOLD = 5000; // 50 元
const TPL_DISCOUNT = 500; // 5 元

fs.mkdirSync(SHOTS, { recursive: true });

// 夹具模板 id：优先取环境变量，其次取夹具脚本留下的文件
const TPL_ID = Number(
  process.env.COUPON_TPL_ID ??
    (fs.existsSync(TPL_ID_FILE) ? fs.readFileSync(TPL_ID_FILE, 'utf8').trim() : ''),
);
if (!Number.isFinite(TPL_ID) || TPL_ID <= 0) {
  console.error(
    '\n[前置缺失] 未拿到夹具模板 id。请先运行：\n' +
      '  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\e2e\\coupon-ui-fixture.ps1 -Action create\n',
  );
  process.exit(1);
}

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

/** 内部只读接口：拿 SKU 快照（含可售库存），替代 SQL 查 t_sku */
async function skuSnapshot() {
  const r = await call('GET', `/api/products/internal/skus/batch?ids=${SKU}`, undefined, null, {
    'X-Internal-Token': INTERNAL_TOKEN,
  });
  return Array.isArray(r.data) ? r.data[0] : r.data;
}

// ---------------- 1. 夹具自检（模板由 ps1 建好） ----------------
step('夹具自检');
const snap = await skuSnapshot();
if (!snap?.spuId) throw new Error(`取 SKU 快照失败：${JSON.stringify(snap)}`);
const EXPECT_UNIT = snap.price;
const stockBefore = snap.availableStock;
check(`（夹具）SKU ${SKU} 单价为 ${EXPECT_UNIT} 分（高于券门槛，保证券可用）`, EXPECT_UNIT > TPL_THRESHOLD, EXPECT_UNIT);
check(`（夹具）已取得自建券模板 id=${TPL_ID}`, TPL_ID > 0, TPL_ID);

const tplList = await call('GET', '/api/coupons/templates');
const fixtureTpl = (tplList.data ?? []).find((t) => t.id === TPL_ID);
check('（夹具）自建券模板已在领券中心可见', Boolean(fixtureTpl), tplList.status);
check('（夹具）模板门槛 / 抵扣与预期一致',
  fixtureTpl?.thresholdAmount === TPL_THRESHOLD && fixtureTpl?.discountAmount === TPL_DISCOUNT,
  { threshold: fixtureTpl?.thresholdAmount, discount: fixtureTpl?.discountAmount });

step('夹具：买家账号');
const phone = `137${String(Math.floor(1e7 + Math.random() * 8e7))}`;
const jar = new Map();
const reg = await call('POST', '/api/auth/register', { phone, password: PWD, nickname: '券UI测试' }, jar);
check('买家注册成功', reg.status === 200, reg.status);

// ---------------- 2. 浏览器 ----------------
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
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
/** 夹具券所在的那张卡片；页面上还有种子券，断言必须限定在卡片内 */
const fixtureCard = () => page.locator('.card', { hasText: TPL_NAME }).first();

// ---------------- 3. 未登录访客视角 ----------------
step('UI：未登录访客走 /coupons');
await page.goto(`${UI}/coupons`, { waitUntil: 'networkidle' });
await page.waitForTimeout(900);

check('一级导航含「优惠券」入口', (await page.getByRole('link', { name: '优惠券' }).count()) >= 1, page.url());

let t = await textNow();
check('领券中心渲染出来（标题 + 副标题）', t.includes('优惠券') && t.includes('领券下单立减'), t.slice(0, 240));
check('公开只读：未登录也看得到自建券', t.includes(TPL_NAME), t.slice(0, 400));

const guestCard = fixtureCard();
check('未登录时该券的按钮**禁用**（不给一个注定 401 的按钮）',
  await guestCard.getByRole('button', { name: '立即领取' }).isDisabled(), await guestCard.innerText());
check('未登录时写明原因「登录后可领取」',
  (await guestCard.innerText()).includes('登录后可领取'), await guestCard.innerText());
await shot('coupon_center_guest.png');

await page.getByRole('button', { name: '我的券包' }).click();
await page.waitForTimeout(500);
t = await textNow();
check('未登录进「我的券包」看到「去登录」引导', t.includes('登录后查看我的券包'), t.slice(0, 300));

// ---------------- 4. 登录后领券 ----------------
step('UI：登录');
await page.goto(`${UI}/login`, { waitUntil: 'networkidle' });
await page.getByPlaceholder('11 位手机号').fill(phone);
await page.getByPlaceholder('请输入密码').fill(PWD);
await page.getByRole('button', { name: /^登录$/ }).click();
await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
check('登录取到登录态', !page.url().includes('/login'), page.url());

step('UI：领券');
await page.goto(`${UI}/coupons`, { waitUntil: 'networkidle' });
await page.waitForTimeout(900);
const loggedCard = fixtureCard();
check('登录后该券的按钮变为可点', !(await loggedCard.getByRole('button', { name: '立即领取' }).isDisabled()), await loggedCard.innerText());
check('登录后「登录后可领取」文案消失', !(await loggedCard.innerText()).includes('登录后可领取'), await loggedCard.innerText());

await loggedCard.getByRole('button', { name: '立即领取' }).click();
await page.waitForTimeout(1500);
t = await textNow();
check('领券成功后给出券码提示', /券码\s*CP/.test(t), t.slice(0, 400));
const afterClaim = await fixtureCard().innerText();
check('领完按钮不再可点', await fixtureCard().getByRole('button', { name: '立即领取' }).isDisabled(), afterClaim);
check('领完原因变为「你已领取过」', afterClaim.includes('你已领取过'), afterClaim);
await shot('coupon_center_claimed.png');

step('UI：我的券包');
await page.getByRole('button', { name: '我的券包' }).click();
await page.waitForTimeout(1200);
t = await textNow();
check('券包页签出现刚领的券', t.includes(TPL_NAME), t.slice(0, 400));
check('券状态为「未使用」', t.includes('未使用'), t.slice(0, 400));
const codeInBag = (t.match(/CP[A-Z2-9]{6,}/) ?? [])[0] ?? '';
check('券码以 CP 开头且不是雪花数字', /^CP[A-Z2-9]+$/.test(codeInBag), codeInBag);
await shot('coupon_mine.png');

// 与接口对账：UI 上看到的券码必须就是服务端那张
const mineApi = await call('GET', '/api/coupons/mine?status=UNUSED', undefined, jar);
const myCoupon = (mineApi.data ?? []).find((c) => c.templateId === TPL_ID);
check('接口与页面券码一致', Boolean(myCoupon) && myCoupon.code === codeInBag, { api: myCoupon?.code, ui: codeInBag });
const CODE = myCoupon?.code;

// ---------------- 5. 结算页：自动选券 + 费用口径 ----------------
step('UI：结算页自动选中推荐券');
await call('POST', '/api/cart/items', { skuId: SKU, quantity: 1 }, jar);
await page.goto(`${UI}/checkout`, { waitUntil: 'networkidle' });
await page.waitForTimeout(1500);
t = await textNow();
check('结算页出现「优惠券」区块', t.includes('优惠券'), t.slice(0, 300));
check('提供「不使用优惠券」选项', t.includes('不使用优惠券'), t.slice(0, 500));
check('列出刚领的券', t.includes(TPL_NAME), t.slice(0, 600));
check('该券被标注为「推荐」（服务端挑的最优券）', t.includes('推荐'), t.slice(0, 600));
await shot('coupon_checkout.png');

const EXPECT_PAY = EXPECT_UNIT - TPL_DISCOUNT;
const DISCOUNT_TEXT = `-¥${(TPL_DISCOUNT / 100).toFixed(2)}`;
const UNIT_TEXT = `¥${(EXPECT_UNIT / 100).toFixed(2)}`;
const PAY_TEXT = `¥${(EXPECT_PAY / 100).toFixed(2)}`;

const feeBlock = await page.locator('.card', { hasText: '费用明细' }).first().innerText();
check(`费用明细列出「优惠」一行为 ${DISCOUNT_TEXT}`, feeBlock.includes(DISCOUNT_TEXT), feeBlock);
check(`应付金额按 商品总额 - 抵扣 = ${PAY_TEXT} 展示`, feeBlock.includes(PAY_TEXT), feeBlock);
check(`费用明细里的商品总额为 ${UNIT_TEXT}`, feeBlock.includes(UNIT_TEXT), feeBlock);

// 取消选券 → 优惠归零、应付回到原价（验证「明确不用」这条分支）
await page.getByText('不使用优惠券').click();
await page.waitForTimeout(600);
const feeNoCoupon = await page.locator('.card', { hasText: '费用明细' }).first().innerText();
check('取消选券后不再有优惠（优惠行归零）', !feeNoCoupon.includes(DISCOUNT_TEXT), feeNoCoupon);
check(`取消选券后应付回到 ${UNIT_TEXT}`, feeNoCoupon.includes(UNIT_TEXT), feeNoCoupon);

// 重新选回该券（验证「点了还能选回来」，而不是被服务端推荐值锁死）
await fixtureCard().getByText(TPL_NAME).click();
await page.waitForTimeout(600);
const feeAgain = await page.locator('.card', { hasText: '费用明细' }).first().innerText();
check(`重新选券后优惠恢复 ${DISCOUNT_TEXT}`, feeAgain.includes(DISCOUNT_TEXT), feeAgain);

// ---------------- 6. 下单 → 订单详情 ----------------
step('UI：提交订单');
await page.getByPlaceholder('姓名').fill('小林');
await page.getByPlaceholder('11 位手机号').fill('13900000000');
await page.getByPlaceholder('省 / 市 / 区 + 详细地址').fill('上海市浦东新区张江路 1 号');
await page.getByRole('button', { name: '提交订单' }).click();
await page.waitForTimeout(2500);
t = await textNow();
check('下单成功页出现「下单成功」', t.includes('下单成功'), t.slice(0, 300));
const orderNo = (t.match(/\d{16,}/) ?? [])[0] ?? '';
check('下单成功页给出订单号', Boolean(orderNo), t.slice(0, 400));
await shot('coupon_order_placed.png');

step('UI：订单详情展示优惠与券码');
await page.getByRole('button', { name: '去支付 / 查看详情' }).first().click();
await page.waitForURL((u) => u.pathname.includes('/orders/'), { timeout: 15000 });
await page.waitForTimeout(1200);
t = await textNow();
check('订单详情出现「优惠券」一行', t.includes('优惠券'), t.slice(0, 500));
check(`订单详情显示优惠金额 ¥${(TPL_DISCOUNT / 100).toFixed(2)}`, t.includes(`¥${(TPL_DISCOUNT / 100).toFixed(2)}`), t.slice(0, 600));
check('订单详情显示本单券码', CODE ? t.includes(CODE) : false, { code: CODE, text: t.slice(0, 600) });
check(`订单详情应付金额为 ${PAY_TEXT}`, t.includes(PAY_TEXT), t.slice(0, 600));
await shot('coupon_order_detail.png');

// ---------------- 7. 取消订单 → 券退回（顺带验解锁路径） ----------------
step('取消订单后券退回');
const cancelled = await call('POST', `/api/orders/${orderNo}/cancel`, { reason: 'UI 自测收尾' }, jar);
check('取消订单成功', cancelled.status === 200, cancelled.status);
const mineAfter = await call('GET', '/api/coupons/mine?status=UNUSED', undefined, jar);
const backCoupon = (mineAfter.data ?? []).find((c) => c.templateId === TPL_ID);
check('★ 取消后券回到 UNUSED（用户立刻能用回来）', Boolean(backCoupon), (mineAfter.data ?? []).map((c) => c.status));
check('★ 券的锁定订单字段被清空', backCoupon ? backCoupon.lockedOrderNo === null : false, backCoupon?.lockedOrderNo);

// ---------------- 8. 库存复原（HTTP 口径） ----------------
step('库存复原自证');
const snapAfter = await skuSnapshot();
check(`★ SKU ${SKU} 可售库存已复原为测试前的 ${stockBefore}`, snapAfter?.availableStock === stockBefore,
  { before: stockBefore, after: snapAfter?.availableStock });

// ---------------- 9. 前端噪声 ----------------
await page.goto(`${UI}/coupons`, { waitUntil: 'networkidle' });
await page.waitForTimeout(600);

step('前端噪声');
/*
 * 断言口径与既有 e2e 脚本（review-ui-check.mjs:217-223）对齐：只盯**非预期**的错误。
 * 两类需要排除，各自都有明确理由（不是为了让断言变绿而放宽）：
 *
 * 1) **401 资源探测**：本脚本刻意先以未登录访客身份走一遍 `/coupons`（这本身就是一条要验的语义），
 *    而应用启动时会探测登录态与购物车角标，对匿名访客必然返回 401 —— 设计如此
 *    （refreshCart 内部已兜底为 0），不是缺陷。
 *
 * 2) **未支付订单详情页的 `/settlement` 404**：本脚本下单后**不支付**（券的核销用例在 coupon-smoke 里），
 *    而 `OrderService.settlementOf()` 对「没有托管记录」的订单是**按设计**返回 404
 *    —— `OrderView.settlement` 的注释写明「资金托管信息（未支付时为 null）」，
 *    所以这是既有行为，与本轮 A5 改动无关（页面会自行忽略、不渲染该区块）。
 *    记为此处已知良性：**P3 优化点** —— 前端可在未支付时直接跳过这次请求，省一次 404。
 */
const BENIGN = [/favicon/i, /\/api\/orders\/\d+\/settlement/];
const isBenign = (s) => BENIGN.some((re) => re.test(s));
const noisyHttp = httpErrors.filter((e) => !isBenign(e) && !/(^|\s)401\s/.test(e));
const noisyConsole = consoleErrors.filter((e) => !isBenign(e) && !/status of 401/.test(e) && !/status of 404/.test(e));
check('无非预期的 4xx/5xx 响应（401 探测 / 未支付单 settlement / favicon 除外）', noisyHttp.length === 0, noisyHttp.slice(0, 6));
check('无 console / pageerror（同上三类除外）', noisyConsole.length === 0, noisyConsole.slice(0, 6));

await browser.close();

console.log(`\n=== 优惠券 UI 自测结果：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log(`  - ${f}`);
}
// 夹具（券模板 + 本次买家的券）由 coupon-ui-fixture.ps1 -Action drop 负责清理
console.log('\n提醒：跑完请执行  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\e2e\\coupon-ui-fixture.ps1 -Action drop');
process.exit(failures.length ? 1 : 0);

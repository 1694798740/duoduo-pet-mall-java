/**
 * 收货地址簿（A8）· 真实浏览器 UI 自测
 *
 * 为什么接口冒烟（address-smoke.ps1）82 项全绿还不够：
 *   接口对了不代表页面走通。A8 有两条**只有真实页面才能验到**的语义：
 *     1. **路由与新入口**：本轮新加了 `/addresses` 路由与一级导航项，漏挂就是 404 白屏；
 *     2. **结算页的「二选一」交互**：表单里同时存在地址单选列表与手动填写表单，
 *        两种模式共用同一个 form id 挂提交按钮 —— 按钮点不动、
 *        把 addressId 和三个字段**一起**传给后端（多带一份来源不符的收货信息）、
 *        切换模式把用户选择重置回默认地址，这些接口层面全绿、页面层面全错。
 *
 * 本脚本覆盖：
 *   1. 未登录访客走 `/addresses`：被 `RequireAuth` 拦截，引导登录（不是白屏或 500）；
 *   2. 登录后空地址簿：渲染空态 + 「立即添加」，并且**「设为默认」复选框默认勾上**；
 *   3. 新增两条地址：列表展示、首条带「默认」标签、第二条不给「设为默认」按钮（已经是默认的才给）；
 *   4. 点「设为默认」：默认标签转移到第二条，且第一条重新出现「设为默认」按钮（唯一性在 UI 上可见）；
 *   5. 编辑地址：表单回填正确、**已是默认地址时复选框禁用**（后端没有「取消默认」动作）、
 *      保存后列表更新为新值，且**默认标签不因编辑而丢失**；
 *   6. 删除地址：确认框写明「历史订单不受影响」→ 删除默认地址后 **toast 说明默认已顺延**、标签落到剩下那条；
 *   7. 结算页：地址簿非空 → **自动预选默认地址**（不是第一条）、默认标签可见、
 *      填备注提交下单 → 成功页出现订单号；
 *   8. **★ 下单后订单详情展示的收货信息 == 地址簿内容**（且手机号未被脱敏、地址是服务端拼好的整段）；
 *   9. **★ 改地址簿后，该订单详情里的收货信息逐字不变**（快照语义的 UI 侧证明）；
 *  10. 结算页切到「手动填写其他地址」→ 三个字段出现 → 提交按钮**仍可点**（form id 没丢）；
 *  11. 收集 console 报错与非预期 HTTP 错误。
 *
 * ⚠️ 本脚本**刻意不碰数据库**：本机 Node 无法创建任何子进程
 *    （`spawnSync/execFileSync` 一律 `EBUSY errno -4082`，两版 Node、沙箱内外都一样），
 *    所以 fixtures 全部通过 HTTP 完成，脚本**自建自删**：
 *    · 账号：随机手机号注册（沿用既有 e2e 惯例，不清理 —— 用户表本就有历史测试账号）；
 *    · 地址：脚本结尾通过接口删除；
 *    · 订单：脚本结尾取消该订单（顺带验一次「取消成功」），库存由交易域自行释放。
 *    订单行本身不删（接口没有删除能力）—— 这是有意的：宁可留下可追溯的测试痕迹，
 *    也不去直连数据库做脚本无法自证的事情。
 *
 * 前置：
 *   1. 9 容器在跑（网关 :8080）；
 *   2. frontend dev server 在跑（:5173）。
 *
 * ⚠️ 必须用 http://localhost:5173，不能用 127.0.0.1 —— 网关 CORS 白名单只认 localhost。
 *
 * 用法：
 *   cd frontend && node node_modules/vite/bin/vite.js --port 5173     # 另开一个终端
 *   node scripts/e2e/address-ui-check.mjs
 */

import path from 'node:path';

const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
const SHOTS = 'D:/智能体项目/duoduo-pet-mall-java/frontend/docs/screenshots';
const CHROME =
  process.env.CHROME_PATH ??
  'C:/Users/16947/AppData/Local/ms-playwright/chromium-1161/chrome-win/chrome.exe';
const PW_CORE =
  process.env.PW_CORE ??
  'file:///C:/Users/16947/.workbuddy/binaries/node/workspace/node_modules/playwright-core/index.mjs';
const INTERNAL_TOKEN = 'duoduo-local-dev-internal-token';
const PWD = 'duoduo12345';
const SKU = 2001;

const { chromium } = await import(PW_CORE);
const fs = await import('node:fs');
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
    /* 保留 raw：雪花 ID 按原始文本提取 */
  }
  return { status: res.status, data: json?.data, json, raw };
}

/** SKU 快照（含可售库存）—— 走内部只读接口，不碰数据库 */
async function skuSnapshot() {
  const r = await call('GET', `/api/products/internal/skus/batch?ids=${SKU}`, undefined, null, {
    'X-Internal-Token': INTERNAL_TOKEN,
  });
  return Array.isArray(r.data) ? r.data[0] : r.data;
}

/** 雪花 id 一律按**字符串**比较：转过 Number 会尾数归零 */
const idOf = (resp) => String(resp?.data?.id ?? '');

// ---------------- 1. 夹具自检 ----------------
step('夹具自检');
const snap = await skuSnapshot();
if (!snap?.spuId) throw new Error(`取 SKU 快照失败：${JSON.stringify(snap)}`);
const stockBefore = snap.availableStock;
check(`（夹具）SKU ${SKU} 可售库存 >= 1（够下单一次）`, stockBefore >= 1, stockBefore);

step('夹具：买家账号');
const phone = `137${String(Math.floor(1e7 + Math.random() * 8e7))}`;
const jar = new Map();
const reg = await call('POST', '/api/auth/register', { phone, password: PWD, nickname: '地址UI测试' }, jar);
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
/** 地址卡片：页面上有多张卡，断言必须限定在卡片内，否则「默认」二字会串卡 */
const addrCard = (name) => page.locator('.card', { hasText: name }).first();

const ADDR_1 = {
  receiverName: 'UI张三',
  receiverPhone: '13900001111',
  province: '上海市',
  city: '上海市',
  district: '浦东新区',
  detail: '张江路 1 号 101 室',
  label: '家',
};
const ADDR_2 = {
  receiverName: 'UI李四',
  receiverPhone: '13900002222',
  province: '浙江省',
  city: '杭州市',
  district: '西湖区',
  detail: '文三路 99 号',
  label: '公司',
};

// ---------------- 3. 未登录访客 ----------------
step('UI：未登录访客走 /addresses');
await page.goto(`${UI}/addresses`, { waitUntil: 'networkidle' });
await page.waitForTimeout(800);
check('未登录访问 /addresses 被拦截（停在 /login 或提示登录），不是白屏',
  page.url().includes('/login') || (await textNow()).includes('登录'), page.url());
await shot('address_guest_blocked.png');

// ---------------- 4. 登录 ----------------
step('UI：登录');
await page.goto(`${UI}/login`, { waitUntil: 'networkidle' });
await page.getByPlaceholder('11 位手机号').fill(phone);
await page.getByPlaceholder('请输入密码').fill(PWD);
await page.getByRole('button', { name: /^登录$/ }).click();
await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
check('登录取到登录态', !page.url().includes('/login'), page.url());

// ---------------- 5. 空地址簿 ----------------
step('UI：新用户空地址簿');
await page.goto(`${UI}/addresses`, { waitUntil: 'networkidle' });
await page.waitForTimeout(1200);
check('一级导航含「收货地址」入口', (await page.getByRole('link', { name: '收货地址' }).count()) >= 1, page.url());
let t = await textNow();
check('页面渲染出来（标题 + 关联结算的说明）', t.includes('收货地址') && t.includes('结算'), t.slice(0, 240));
check('空地址簿给出空态文案', t.includes('还没有收货地址'), t.slice(0, 400));
check('空态提供「立即添加」主按钮', (await page.getByRole('button', { name: '立即添加' }).count()) >= 1, page.url());
await shot('address_empty.png');

// ---------------- 6. 新增第一条：首个自动默认 ----------------
step('UI：新增第一条地址（首个应自动成为默认）');
await page.getByRole('button', { name: '+ 新增地址' }).click();
await page.waitForTimeout(500);
check('新增弹窗打开', (await textNow()).includes('新增收货地址'), (await textNow()).slice(0, 200));
// 空地址簿时复选框应已被前端勾上（与后端「首条自动默认」对齐）
const defaultBox = page.locator('#address-form input[type=checkbox]');
check('★ 空地址簿新增时「设为默认地址」已预勾选', await defaultBox.isChecked(), await defaultBox.isChecked());

await page.getByPlaceholder('例如 张小三').fill(ADDR_1.receiverName);
await page.getByPlaceholder('11 位手机号').fill(ADDR_1.receiverPhone);
await page.getByPlaceholder('例如 浙江省').fill(ADDR_1.province);
await page.getByPlaceholder('例如 杭州市').fill(ADDR_1.city);
await page.getByPlaceholder('例如 西湖区').fill(ADDR_1.district);
await page.getByPlaceholder('例如 文三路 99 号 3 幢 501 室').fill(ADDR_1.detail);
await page.locator('#address-form').getByRole('button', { name: '家' }).click();
await page.getByRole('button', { name: '保存' }).click();
await page.waitForTimeout(1500);

t = await textNow();
check('地址①出现在列表', t.includes(ADDR_1.receiverName), t.slice(0, 400));
check('★ 直辖市拼接去重：页面上的完整地址不含「上海市上海市」', !t.includes('上海市上海市'), t.slice(0, 400));
check('★ 完整地址由服务端拼好（省+区+明细，市名只留一次）',
  t.includes('上海市浦东新区张江路 1 号 101 室'), t.slice(0, 500));
check('标签「家」渲染出来', (await addrCard(ADDR_1.receiverName).locator('.tag', { hasText: '家' }).count()) >= 1, await addrCard(ADDR_1.receiverName).innerText());
check('★ 第一条自动带「默认」标签', (await addrCard(ADDR_1.receiverName).locator('.tag', { hasText: '默认' }).count()) >= 1, await addrCard(ADDR_1.receiverName).innerText());
check('★ 已是默认的卡片**不显示**「设为默认」按钮（避免假交互）',
  (await addrCard(ADDR_1.receiverName).getByRole('button', { name: '设为默认' }).count()) === 0,
  await addrCard(ADDR_1.receiverName).innerText());
check('手机号完整展示（自己的数据不脱敏，编辑时要能核对）',
  (await addrCard(ADDR_1.receiverName).innerText()).includes(ADDR_1.receiverPhone),
  await addrCard(ADDR_1.receiverName).innerText());
await shot('address_one_default.png');

// ---------------- 7. 新增第二条：不抢默认 ----------------
step('UI：新增第二条地址（不应抢默认）');
await page.getByRole('button', { name: '+ 新增地址' }).click();
await page.waitForTimeout(500);
check('★ 已有地址后新增时「设为默认」不再预勾选', !(await defaultBox.isChecked()), await defaultBox.isChecked());
await page.getByPlaceholder('例如 张小三').fill(ADDR_2.receiverName);
await page.getByPlaceholder('11 位手机号').fill(ADDR_2.receiverPhone);
await page.getByPlaceholder('例如 浙江省').fill(ADDR_2.province);
await page.getByPlaceholder('例如 杭州市').fill(ADDR_2.city);
await page.getByPlaceholder('例如 西湖区').fill(ADDR_2.district);
await page.getByPlaceholder('例如 文三路 99 号 3 幢 501 室').fill(ADDR_2.detail);
await page.locator('#address-form').getByRole('button', { name: '公司' }).click();
await page.getByRole('button', { name: '保存' }).click();
await page.waitForTimeout(1500);

t = await textNow();
check('地址②出现在列表', t.includes(ADDR_2.receiverName), t.slice(0, 500));
check('★ 地址②没有「默认」标签（不抢默认）',
  (await addrCard(ADDR_2.receiverName).locator('.tag', { hasText: '默认' }).count()) === 0,
  await addrCard(ADDR_2.receiverName).innerText());
check('★ 地址②显示「设为默认」按钮', (await addrCard(ADDR_2.receiverName).getByRole('button', { name: '设为默认' }).count()) >= 1,
  await addrCard(ADDR_2.receiverName).innerText());
check('非直辖市拼接为 省+市+区+明细', t.includes('浙江省杭州市西湖区文三路 99 号'), t.slice(0, 500));
await shot('address_two_items.png');

// ---------------- 8. 设为默认：唯一性在 UI 上可见 ----------------
step('UI：把地址②设为默认');
await addrCard(ADDR_2.receiverName).getByRole('button', { name: '设为默认' }).click();
await page.waitForTimeout(1500);
check('★ 默认标签转移到地址②',
  (await addrCard(ADDR_2.receiverName).locator('.tag', { hasText: '默认' }).count()) >= 1,
  await addrCard(ADDR_2.receiverName).innerText());
check('★ 地址①的「默认」标签已消失（唯一性）',
  (await addrCard(ADDR_1.receiverName).locator('.tag', { hasText: '默认' }).count()) === 0,
  await addrCard(ADDR_1.receiverName).innerText());
check('★ 地址①重新出现「设为默认」按钮',
  (await addrCard(ADDR_1.receiverName).getByRole('button', { name: '设为默认' }).count()) >= 1,
  await addrCard(ADDR_1.receiverName).innerText());
// 与接口对账：页面上看到的默认归属必须就是服务端认定的那一条
const listApi = await call('GET', '/api/addresses', undefined, jar);
const def = (listApi.data ?? []).find((a) => a.isDefault);
check('接口认定的默认地址是地址②（页面与服务端一致）', def?.receiverName === ADDR_2.receiverName, def?.receiverName);
await shot('address_default_switched.png');

// ---------------- 9. 编辑：已默认的复选框应禁用 ----------------
step('UI：编辑默认地址');
await addrCard(ADDR_2.receiverName).getByRole('button', { name: '编辑' }).click();
await page.waitForTimeout(500);
check('编辑弹窗打开且标题为「编辑收货地址」', (await textNow()).includes('编辑收货地址'), (await textNow()).slice(0, 200));
const nameInput = page.getByPlaceholder('例如 张小三');
check('表单已回填原值', (await nameInput.inputValue()) === ADDR_2.receiverName, await nameInput.inputValue());
check('★ 已是默认地址时复选框**禁用**（后端没有「取消默认」动作，可点就是假交互）',
  await defaultBox.isDisabled(), await defaultBox.isDisabled());
check('并写明改由其他地址切换默认', (await textNow()).includes('当前已是默认'), (await textNow()).slice(0, 300));
const NEW_NAME = 'UI李四改';
await nameInput.fill(NEW_NAME);
await page.getByPlaceholder('例如 文三路 99 号 3 幢 501 室').fill('文一西路 18 号 2001 室');
await page.getByRole('button', { name: '保存' }).click();
await page.waitForTimeout(1500);
t = await textNow();
check('编辑后列表出现新姓名', t.includes(NEW_NAME), t.slice(0, 500));
check('编辑后详细地址已更新', t.includes('浙江省杭州市西湖区文一西路 18 号 2001 室'), t.slice(0, 600));
check('★ 编辑保存后默认标签仍在（编辑不该把默认弄丢）',
  (await addrCard(NEW_NAME).locator('.tag', { hasText: '默认' }).count()) >= 1,
  await addrCard(NEW_NAME).innerText());
await shot('address_edited.png');

// ---------------- 10. 删除默认地址 → 顺延提示 ----------------
step('UI：删除默认地址（默认应自动顺延并明确告知）');
await addrCard(NEW_NAME).getByRole('button', { name: '删除' }).click();
await page.waitForTimeout(600);
const confirmText = await page.locator('.modal', { hasText: '确认删除地址' }).innerText();
check('删除前弹确认框', confirmText.includes('确认删除地址'), confirmText);
check('★ 确认框说明历史订单不受影响（快照语义）', confirmText.includes('历史订单'), confirmText);
await page.getByRole('button', { name: '确认删除' }).click();
await page.waitForTimeout(1500);
t = await textNow();
check('删除后该地址从列表消失', !t.includes(NEW_NAME), t.slice(0, 500));
check('★ toast 明确告知「默认地址已自动顺延」（不让用户以为默认没了）',
  t.includes('顺延'), t.slice(0, 600));
check('★ 默认标签顺延到剩下的地址①',
  (await addrCard(ADDR_1.receiverName).locator('.tag', { hasText: '默认' }).count()) >= 1,
  await addrCard(ADDR_1.receiverName).innerText());
await shot('address_deleted_default.png');

// ---------------- 11. 结算页：自动预选默认地址 + 下单 ----------------
step('UI：结算页自动选中默认地址并下单');
await call('POST', '/api/cart/items', { skuId: SKU, quantity: 1 }, jar);
await page.goto(`${UI}/checkout`, { waitUntil: 'networkidle' });
await page.waitForTimeout(1800);
t = await textNow();
check('结算页出现「收货信息」区块', t.includes('收货信息'), t.slice(0, 300));
check('★ 结算页列出地址簿条目', t.includes(ADDR_1.receiverName), t.slice(0, 600));
check('★ 地址簿非空时不显示「还没有保存的收货地址」', !t.includes('还没有保存的收货地址'), t.slice(0, 600));
const checkedRadio = page.locator('input[name=address]:checked');
check('★ 默认地址被自动预选（不是要用户自己点一下）', (await checkedRadio.count()) === 1, await checkedRadio.count());
const radioValueOk = await page.evaluate(() => {
  const r = document.querySelector('input[name=address]:checked');
  if (!r) return false;
  const label = r.closest('label');
  return Boolean(label && label.innerText.includes('UI张三'));
});
check('★ 且预选的就是那条默认地址（UI张三）', radioValueOk, radioValueOk);
check('★ 用已存地址时不渲染手动填写的三个输入框', (await page.getByPlaceholder('省 / 市 / 区 + 详细地址').count()) === 0, page.url());
check('订单备注在两种模式下都可填', (await page.getByPlaceholder(/选填，例如/).count()) >= 1, page.url());
await shot('address_checkout_selected.png');

await page.getByPlaceholder(/选填，例如/).fill('A8 UI 自测下单');
await page.getByRole('button', { name: '提交订单' }).click();
await page.waitForTimeout(3000);
t = await textNow();
check('★ 下单成功页出现（提交按钮在「用地址簿」模式下仍有效）', t.includes('下单成功'), t.slice(0, 400));
const orderNo = (t.match(/\d{16,}/) ?? [])[0] ?? '';
check('下单成功页给出订单号', Boolean(orderNo), t.slice(0, 500));
await shot('address_order_placed.png');

// ---------------- 12. 订单详情：收货信息 == 地址簿 ----------------
step('UI：订单详情展示的收货信息 == 地址簿');
await page.getByRole('button', { name: '去支付 / 查看详情' }).first().click();
await page.waitForURL((u) => u.pathname.includes('/orders/'), { timeout: 15000 });
await page.waitForTimeout(1500);
t = await textNow();
check('订单详情显示收货人（== 地址簿）', t.includes(ADDR_1.receiverName), t.slice(0, 700));
check('★ 订单详情显示完整收货地址（服务端拼好的整段）', t.includes('上海市浦东新区张江路 1 号 101 室'), t.slice(0, 800));
check('★ 订单详情收货手机号完整可见（不是脱敏成 139****1111）', t.includes(ADDR_1.receiverPhone), t.slice(0, 800));
await shot('address_order_detail.png');

// ---------------- 13. ★ 改地址簿后订单快照不变（UI 侧证明） ----------------
step('UI：改地址簿后，历史订单收货信息不变');
// ⚠ 必须**实时重新拉取**：第 271 行的 listApi 是旧快照，中间第 10 段删过默认地址、
//   默认已经顺延到另一条，旧快照里的 id 早已失效（会得到 404）。
//   另外 idOf() 接收的是"整个响应对象"（内部走 resp.data.id），所以这里先 .find() 再取 .id。
const defResp = await call('GET', '/api/addresses/default', undefined, jar);
const defaultAddrId = String(defResp?.data?.id ?? '');
const editResp = await call('PUT', `/api/addresses/${defaultAddrId}`, {
  receiverName: 'UI张三改名',
  receiverPhone: '13900009999',
  province: '上海市',
  city: '上海市',
  district: '黄浦区',
  detail: '南京东路 1 号',
}, jar);
check('（前置）改动地址簿成功', editResp.status === 200, editResp.status);
await page.reload({ waitUntil: 'networkidle' });
await page.waitForTimeout(1500);
t = await textNow();
check('★ 订单详情**仍**是下单当时的收货人（未被地址簿联动）', t.includes(ADDR_1.receiverName) && !t.includes('UI张三改名'), t.slice(0, 700));
check('★ 订单详情**仍**是下单当时的详细地址（未被地址簿联动）', t.includes('张江路 1 号 101 室') && !t.includes('南京东路 1 号'), t.slice(0, 800));
check('★ 订单详情**仍**是下单当时的手机号', t.includes(ADDR_1.receiverPhone) && !t.includes('13900009999'), t.slice(0, 800));
await shot('address_order_snapshot_kept.png');

// ---------------- 14. 切到手动填写：提交按钮不能变成死按钮 ----------------
step('UI：切换到「手动填写其他地址」');
await call('POST', '/api/cart/items', { skuId: SKU, quantity: 1 }, jar);
await page.goto(`${UI}/checkout`, { waitUntil: 'networkidle' });
await page.waitForTimeout(1800);
await page.getByText('手动填写其他地址').click();
await page.waitForTimeout(600);
check('★ 切到手动后出现收货人输入框', (await page.getByPlaceholder('姓名').count()) === 1, page.url());
check('★ 切到手动后出现收货地址输入框', (await page.getByPlaceholder('省 / 市 / 区 + 详细地址').count()) === 1, page.url());
const submitBtn = page.getByRole('button', { name: '提交订单' });
check('★ 提交按钮仍可点（两种模式都必须存在 form#checkout-form，否则按钮变死）',
  await submitBtn.isEnabled(), await submitBtn.isEnabled());
await submitBtn.click();
await page.waitForTimeout(1200);
check('★ 手动模式未填完 → 前端拦下并给出可读文案（没有把空字段发出去）',
  (await textNow()).includes('请填写收货人姓名'), (await textNow()).slice(0, 400));
await shot('address_checkout_manual.png');

// ---------------- 15. 清理 ----------------
step('清理脚本自建的地址');
const finalList = await call('GET', '/api/addresses', undefined, jar);
for (const a of finalList.data ?? []) {
  const r = await call('DELETE', `/api/addresses/${a.id}`, undefined, jar);
  check(`删除自建地址「${a.receiverName}」`, r.status === 200, r.status);
}
const afterClean = await call('GET', '/api/addresses', undefined, jar);
check('地址簿已清空（脚本复原）', (afterClean.data ?? []).length === 0, (afterClean.data ?? []).length);

step('取消本次自测订单（释放库存占用）');
const cancel = await call('POST', `/api/orders/${orderNo}/cancel`, { reason: 'A8 UI 自测收尾' }, jar);
check('自测订单已取消', cancel.status === 200, cancel.status);
const snapAfter = await skuSnapshot();
check(`SKU ${SKU} 可售库存已回到测试前的 ${stockBefore}`, snapAfter?.availableStock === stockBefore,
  { before: stockBefore, after: snapAfter?.availableStock });

// ---------------- 16. 前端噪声 ----------------
step('前端噪声');
await page.goto(`${UI}/addresses`, { waitUntil: 'networkidle' });
await page.waitForTimeout(600);
/*
 * 口径与既有 e2e 脚本对齐：只看**非预期**的错误。两类刻意排除，各有明确理由：
 *  1) **401 探测**：本脚本先以未登录身份走一遍 /addresses（这本身就是一条要验的语义），
 *     而应用启动时会探测登录态与购物车角标，匿名必然 401 —— 设计如此（内部已兜底），不是缺陷。
 *  2) **未支付订单的 /settlement 404**：本脚本下单后只做取消、不支付，而
 *     OrderService.settlementOf() 对「没有托管记录」的订单按设计返回 404
 *     （OrderView.settlement 注释写明「未支付时为 null」），属既有行为。
 */
const BENIGN = [/favicon/i, /\/api\/orders\/\d+\/settlement/];
const isBenign = (s) => BENIGN.some((re) => re.test(s));
const noisyHttp = httpErrors.filter((e) => !isBenign(e) && !/(^|\s)401\s/.test(e));
const noisyConsole = consoleErrors.filter(
  (e) => !isBenign(e) && !/status of 401/.test(e) && !/status of 404/.test(e),
);
check('无非预期的 4xx/5xx 响应（401 探测 / 未支付单 settlement / favicon 除外）', noisyHttp.length === 0, noisyHttp.slice(0, 6));
check('无 console / pageerror（同上三类除外）', noisyConsole.length === 0, noisyConsole.slice(0, 6));

await browser.close();

console.log(`\n=== 收货地址簿 UI 自测结果：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log(`  - ${f}`);
}
process.exit(failures.length ? 1 : 0);

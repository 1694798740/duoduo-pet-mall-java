/**
 * 个人中心（Me.tsx）· 真实浏览器 UI 自测 —— 2026-10-21 布局重排 + 撤销注销表单修正
 *
 * <h2>为什么要专门为这一页写一套 e2e</h2>
 * 本轮动了三件事，都只有真实页面才能验到：
 *   1. **布局分区**：旧版左列只有一张「基本资料」卡、右列从上到下堆五张卡，
 *      左列下半整屏空白。重排成「身份 → 信用与认证 → 账号与隐私」三区后，
 *      分区是否真的落到了两栏、信用分与实名是否同一行、注销是否全宽收尾，
 *      接口冒烟永远测不到 —— 必须量 bounding box；
 *   2. **撤销注销表单不预填**：此前输入框被浏览器自动填充塞进了当前用户名和密码
 *      （不是代码赋值，是没声明 autoComplete）。这属于"渲染后 DOM 属性"断言，
 *      只能上浏览器。账号正常时撤销区**不渲染**，所以这条在 e2e 里验
 *      "正常账号看不到撤销区"，autoComplete 属性本身由静态守卫 me-page-guard 负责；
 *   3. **头像上传链路端到端**：真实选一次文件 → 等成功回执 → 用接口回读
 *      /api/users/me 确认 avatar 确实落库、再用网关 GET 那个 URL 确认**能显示**。
 *      此前 COS 欠费（HTTP 451）导致"上传必败 + 已存图全裂"，充值恢复后这条
 *      就是头像"能显示、能保存"的回归断言。
 *
 * 前置：9 容器在跑（网关 :8080）；mall dev server（UI_BASE，默认 :5174）。
 * ⚠️ 必须 localhost，不能 127.0.0.1（网关 CORS 白名单）。
 *
 * 用法：cd frontend/apps/mall && node ../../node_modules/vite/bin/vite.js --port 5174
 *       node scripts/e2e/me-page-ui-check.mjs
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { shotsDir } from '../lib/e2e-shots.mjs';

const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
const here = path.dirname(fileURLToPath(import.meta.url));
const SHOTS = shotsDir();
const CHROME = process.env.CHROME_PATH ?? null;
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const PWD = 'duoduo12345';

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
  try { json = JSON.parse(raw); } catch { /* 保留 raw */ }
  return { status: res.status, data: json?.data, json, raw };
}

// ---------------- 1. 夹具：测试账号 ----------------
step('夹具：注册测试账号（自建自弃，沿用 e2e 惯例不清理）');
const phone = `138${String(Math.floor(1e7 + Math.random() * 8e7))}`;
const jar = new Map();
const reg = await call('POST', '/api/auth/register', { phone, password: PWD, nickname: '个人中心UI测试' }, jar);
check('账号注册成功', reg.status === 200, reg.status);

// ---------------- 2. 浏览器 ----------------
const browser = await chromium.launch({ executablePath: CHROME ?? undefined, args: ['--no-sandbox'] });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
const page = await ctx.newPage();
// console / pageerror 收集器**登录完成后再挂**：未登录时 Layout 的会话引导会打
// /api/users/me、/api/cart/count、/api/auth/refresh 各拿一个 401 —— 那是
// "探测登录态"的预期行为，不是页面缺陷，不该算进失败项。
const consoleErrors = [];

const shot = async (name) => {
  await page.screenshot({ path: path.join(SHOTS, name), fullPage: true });
  console.log(`  [截图] ${SHOTS}/${name}`);
};
const textNow = () => page.locator('body').innerText();
/** 页面有多张卡，断言必须限定在「标题含此文字」的那张卡内，否则会串卡 */
const cardByTitle = (title) => page.locator('.card', { has: page.locator(`h3:text-is("${title}")`) }).first();

// ---------------- 3. 登录 ----------------
step('UI：登录');
await page.goto(`${UI}/login`, { waitUntil: 'networkidle' });
await page.getByPlaceholder('11 位手机号').fill(phone);
await page.getByPlaceholder('请输入密码').fill(PWD);
await page.getByRole('button', { name: /^登录$/ }).click();
await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
check('登录取到登录态', !page.url().includes('/login'), page.url());
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));

// ---------------- 4. 布局分区 ----------------
step('UI：三区布局（身份 → 信用与认证 → 账号与隐私）');
await page.goto(`${UI}/me`, { waitUntil: 'networkidle' });
await page.waitForTimeout(1200);

const box = async (title) => {
  const el = cardByTitle(title);
  const n = await el.count();
  if (!n) return null;
  return el.boundingBox();
};
const profile = await box('基本资料');
const status = await box('账号状态');
const quick = await box('快捷入口');
const credit = await box('信用分');
const realname = await box('实名认证');
const delAcc = await box('账号注销');
check('六张卡全部渲染', !!(profile && status && quick && credit && realname && delAcc),
  { profile: !!profile, status: !!status, quick: !!quick, credit: !!credit, realname: !!realname, delAcc: !!delAcc });

if (profile && status && credit && realname && delAcc) {
  // 第一区：基本资料（左）与 账号状态（右）同一行
  check('「基本资料」在左列', profile.x < status.x, { profileX: profile.x, statusX: status.x });
  check('「基本资料」与「账号状态」同一行（顶边差 < 40px）', Math.abs(profile.y - status.y) < 40,
    { profileY: profile.y, statusY: status.y });
  check('「快捷入口」跟在「账号状态」下面（同列）', quick && quick.x > profile.x + 100 && quick.y > status.y,
    { quickY: quick?.y, statusY: status.y });

  // 第二区：信用分与实名认证另起一行，都低于第一区顶边
  check('「信用分」与「实名认证」同一行', Math.abs(credit.y - realname.y) < 40,
    { creditY: credit.y, realnameY: realname.y });
  check('「信用分」行在「账号状态」行之下', credit.y > status.y + 40, { creditY: credit.y, statusY: status.y });

  // 第三区：注销卡全宽收尾（比半栏宽得多）
  check('「账号注销」在最后（低于信用分行顶边）', delAcc.y > credit.y + 40, { delY: delAcc.y, creditY: credit.y });
  check('「账号注销」是全宽卡（宽 > 实名卡的 1.4 倍）', delAcc.width > realname.width * 1.4,
    { delW: delAcc.width, realW: realname.width });
  await shot('me_layout.png');
}

// ---------------- 5. 撤销注销区：正常账号不出现 ----------------
step('UI：账号正常时撤销区不渲染');
check('正常账号看不到「撤销注销」表单（只在冷静期出现）',
  !(await textNow()).includes('撤销注销 ——'), (await textNow()).includes('撤销注销 ——'));

// ---------------- 6. 注销面板能展开（预检可达） ----------------
step('UI：注销面板展开 + 预检');
await cardByTitle('账号注销').getByRole('button', { name: '注销账号' }).click();
// 预检是异步的：轮询等它出结果（密码框 = 无阻塞；阻塞清单 = 有阻塞），不能 sleep 定值
let t = '';
for (let i = 0; i < 30; i++) {
  await page.waitForTimeout(500);
  t = await textNow();
  if (t.includes('登录密码') || t.includes('没处理完')) break;
}
check('面板展开后出现密码输入（说明预检通过、没有阻塞项）',
  t.includes('登录密码') && t.includes('我已了解'), t.slice(t.indexOf('账号注销'), t.indexOf('账号注销') + 300));
check('未提交前不出现「撤销注销 ——」说明行',
  !t.includes('撤销注销 ——'), null);
await shot('me_delete_panel.png');

// ---------------- 7. 头像上传：COS 欠费的报错必须可行动 ----------------
step('UI：头像上传端到端（选图 → 落库 → 经网关可显示）');
// 1x1 PNG（最小合法图片；只关心链路，不关心内容）
const pngB64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';
const pngPath = path.join(SHOTS, '_me-probe.png');
fs.writeFileSync(pngPath, Buffer.from(pngB64, 'base64'));
await cardByTitle('基本资料').locator('.img-uploader input[type=file]').setInputFiles(pngPath);
// 先弹的是上传器自己的「已上传 1 张图片」，随后 saveAvatar 落库完才弹「头像已更新」；
// 两条 toast 有先后，必须轮询等第二条
let toastText = '';
let bodyText = '';
for (let i = 0; i < 30; i++) {
  await page.waitForTimeout(500);
  bodyText = await textNow();
  if (bodyText.includes('头像已更新')) break;
}
try {
  const toast = page.locator('.toast, [class*=toast]').first();
  if (await toast.isVisible()) toastText = (await toast.innerText()) || '';
} catch { /* 容器全文已兜底 */ }
check('上传成功回执出现（「头像已更新」）',
  bodyText.includes('头像已更新'), { tail: bodyText.slice(-200) });

// 用接口独立复核两件事（UI 说了算不算数，要回源）：
const me = await call('GET', '/api/users/me', undefined, jar);
const avatarUrl = me.data?.avatar ?? '';
check('avatar 已落库（/api/users/me 回读非空）', !!avatarUrl, avatarUrl);
if (avatarUrl) {
  const imgPath = avatarUrl.replace(/^https?:\/\/[^/]+/, '');
  const img = await fetch(`http://127.0.0.1:8080${imgPath}`);
  check('上传后的头像经网关能取到（HTTP 200 + 图片类型）',
    img.status === 200 && (img.headers.get('content-type') ?? '').startsWith('image/'),
    { status: img.status, type: img.headers.get('content-type') });
}
await shot('me_avatar_ok.png');

// ---------------- 8. 收尾 ----------------
check('无 console 报错（除上传链路预期的 5xx 之外）', consoleErrors.length === 0, consoleErrors.slice(0, 3));

console.log(`\n=== 个人中心 UI 自测：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exit(1);
}
await browser.close();
process.exit(0);

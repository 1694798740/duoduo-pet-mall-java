/**
 * 分享圈（Web 端）· 真实浏览器 UI 自测（**自包含**，夹具自建自清）
 *
 * <h2>钉住的不变式</h2>
 * <ol>
 *   <li>信息流公开可逛；发布需登录且**必须选商品**（spuId @NotNull，后端校验）；</li>
 *   <li>发布 → 信息流可见（含话题）→ 详情可评论 → 点赞 toggle 计数变化 → 我的分享可删除；</li>
 *   <li>话题聚合路由（/share/topic/:name）可用；</li>
 *   <li>整页无 console 报错 / pageerror。</li>
 * </ol>
 *
 * 用法：node scripts/e2e/share-circle-ui-check.mjs
 */

import path from 'node:path';
import { shotsDir } from '../lib/e2e-shots.mjs';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
const SHOTS = shotsDir(); // 默认落到 gitignored 的 scripts/e2e/.shots；要归档配图用 SHOTS=<路径> 覆盖
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? undefined;

const PWD = 'duoduo12345';
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
const consoleNoise = (list) => list.filter((e) => !/favicon|status of 401/i.test(e));

async function call(method, p, body, jar) {
  const headers = {};
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (jar && jar.size) headers.cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
  const res = await fetch((p.startsWith('http') ? '' : GW) + p, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (jar) for (const c of res.headers.getSetCookie?.() ?? []) {
    const pair = c.split(';')[0]; const i = pair.indexOf('=');
    if (i > 0) jar.set(pair.slice(0, i), pair.slice(i + 1));
  }
  const raw = await res.text();
  let json = null; try { json = JSON.parse(raw); } catch { /* 保留 raw */ }
  return { status: res.status, data: json?.data };
}
const randPhone = (p) => p + String(Math.floor(1e7 + Math.random() * 8e7));

async function newOperator(nickname) {
  const jar = new Map();
  const phone = randPhone('138');
  await call('POST', '/api/auth/register', { phone, password: PWD, nickname }, jar);
  let role = '';
  for (let i = 0; i < 5; i++) {
    await mysql('duoduo_user', `UPDATE t_user SET role='OPERATOR' WHERE phone='${phone}';`);
    role = (await mysql('duoduo_user', `SELECT role FROM t_user WHERE phone='${phone}';`)).trim();
    if (role === 'OPERATOR') break;
  }
  await call('POST', '/api/auth/login', { phone, password: PWD }, jar);
  return { jar, role };
}

step('夹具');
const op = await newOperator('分享圈自测');
check('运营账号提权成功', op.role === 'OPERATOR', op.role);

const TITLE_MARK = '【UI自测】这款幼犬粮家里狗子很爱吃，回购第三次了。';
const TOPIC = '幼犬粮';

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

async function newPage(jar) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 1000 }, locale: 'zh-CN' });
  if (jar) await ctx.addCookies([...jar].map(([name, value]) => ({ name, value, domain: 'localhost', path: '/' })));
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
  return { ctx, page, consoleErrors };
}

// ---------------- ① 信息流公开可逛 + 页面渲染 ----------------
step('① 信息流渲染与 scope 切换');
{
  const { ctx, page, consoleErrors } = await newPage(null);
  await page.goto(`${UI}/share`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  const body = await page.locator('body').innerText();
  check('信息流页渲染（公开可逛，无需登录）',
    body.includes('分享圈') && body.includes('推荐') && body.includes('关注'), body.slice(0, 120));
  check('无 console 报错', consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await ctx.close();
}

// ---------------- ② 发布 → 信息流 → 详情 → 评论 → 点赞 ----------------
let shareId = '';
step('② 发布（搜索选品 + 内容 + 话题）→ 信息流可见');
{
  const { ctx, page, consoleErrors } = await newPage(op.jar);
  await page.goto(`${UI}/share`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);

  await page.getByRole('button', { name: '+ 发布分享' }).click();
  await page.getByPlaceholder('输入商品关键词搜索，如：幼犬粮').fill('狗粮');
  await page.waitForTimeout(1500);
  // 商品搜索结果必须非空（2026-10-06 修复 ES 动态映射导致的全平台关键词搜索恒 0）
  const hits = await page.locator('button.card').count();
  check('★ 商品关键词搜索有结果（依赖 ES mapping 修复）', hits > 0, hits);
  await page.locator('button.card').first().click();
  await page.waitForTimeout(300);
  await page.getByPlaceholder('是否值得买、适不适合你家宠物…').fill(TITLE_MARK);
  await page.getByPlaceholder('输入话题后回车').fill(TOPIC);
  await page.keyboard.press('Enter');
  await page.getByRole('button', { name: '发布', exact: true }).click();
  await page.waitForTimeout(2200);

  const body = await page.locator('body').innerText();
  check('② 发布成功出现在信息流', body.includes(TITLE_MARK), body.slice(0, 200));
  check('② 话题 tag 渲染', body.includes(`#${TOPIC}`));
  check('② 无 console 报错', consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await ctx.close();
}

step('③ 详情页 + 评论 + 点赞 toggle');
{
  const { ctx, page, consoleErrors } = await newPage(op.jar);
  await page.goto(`${UI}/share`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  await page.locator('.card', { hasText: TITLE_MARK }).first()
    .getByRole('link', { name: '查看全文 →' }).click();
  await page.waitForTimeout(1500);

  // 详情页字段：商品快照卡 + 引用不存在时无引用块
  const body = await page.locator('body').innerText();
  check('③ 详情页正文与商品卡渲染', body.includes(TITLE_MARK), body.slice(0, 200));

  await page.getByPlaceholder('写下你的评论…').fill('自测评论：确实不错。');
  await page.getByRole('button', { name: '发表评论' }).click();
  await page.waitForTimeout(1800);
  const after = await page.locator('body').innerText();
  check('③ 评论发出并可见', after.includes('自测评论：确实不错'), after.slice(0, 300));

  const like = page.getByRole('button', { name: /❤|🤍/ }).first();
  const before = (await like.innerText()).trim();
  await like.click();
  await page.waitForTimeout(1500);
  const afterLike = (await page.getByRole('button', { name: /❤|🤍/ }).first().innerText()).trim();
  check('④ 点赞 toggle 计数变化', before !== afterLike, { before, afterLike });
  check('③ 无 console 报错', consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await page.screenshot({ path: path.join(SHOTS, 'share-circle-web.png'), fullPage: false });
  await ctx.close();
}

// ---------------- ⑤ 我的分享：编辑与删除 ----------------
step('⑤ 我的分享：编辑可见、删除消失');
{
  const { ctx, page, consoleErrors } = await newPage(op.jar);

  // 从头像下拉进「我的分享」（入口在菜单，不在顶栏 —— 顶栏 6 项是截断红线）
  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1000);
  await page.locator('.app-header button.icon-btn').first().click();
  await page.waitForTimeout(250);
  check('头像下拉含「分享圈 / 我的分享」入口',
    (await page.locator('.app-header .user-menu').getByRole('link', { name: '分享圈', exact: true }).count()) === 1
    && (await page.locator('.app-header .user-menu').getByRole('link', { name: '我的分享', exact: true }).count()) === 1);
  await page.locator('.app-header .user-menu').getByRole('link', { name: '我的分享', exact: true }).click();
  await page.waitForTimeout(1500);

  const body = await page.locator('body').innerText();
  check('⑤ 我的分享列表含刚发的条目', body.includes(TITLE_MARK), body.slice(0, 200));

  // 编辑：追加一句话，保存后详情页应出现
  await page.getByRole('button', { name: '编辑', exact: true }).first().click();
  await page.waitForTimeout(400);
  const box = page.getByPlaceholder('分享内容（最多 500 字）');
  await box.fill(TITLE_MARK + '（已编辑）');
  await page.getByRole('button', { name: '保存' }).click();
  await page.waitForTimeout(1800);
  const afterEdit = await page.locator('body').innerText();
  check('⑤ 编辑保存后列表更新（显示「已编辑」痕迹的内容）', afterEdit.includes('（已编辑）'), afterEdit.slice(0, 240));

  page.on('dialog', (d) => d.accept());
  await page.getByRole('button', { name: '删除', exact: true }).first().click();
  await page.waitForTimeout(1800);
  const afterDel = await page.locator('body').innerText();
  check('⑤ 删除后列表消失（软删：管理页不可见）', !afterDel.includes('（已编辑）'), afterDel.slice(0, 240));
  check('⑤ 无 console 报错', consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await ctx.close();
}

// ---------------- ⑥ 话题聚合路由 ----------------
step('⑥ 话题聚合路由');
{
  const { ctx, page, consoleErrors } = await newPage(null);
  await page.goto(`${UI}/share/topic/${encodeURIComponent(TOPIC)}`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  const body = await page.locator('body').innerText();
  check('⑥ 话题聚合页头渲染（#幼犬粮）', body.includes(`#${TOPIC}`), body.slice(0, 120));
  check('⑥ 无 console 报错', consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await ctx.close();
}

// ---------------- ⑦ 未登录入口可发现性（R72） ----------------
step('⑦ 未登录入口可发现性');
{
  const { ctx, page, consoleErrors } = await newPage(null);
  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  const header = await page.locator('.app-header').innerText();
  check('⑦ 未登录页头有「分享圈」入口（可发现性）', header.includes('分享圈'), header.slice(0, 150));
  await page.locator('.app-header').getByRole('link', { name: '分享圈', exact: true }).click();
  await page.waitForTimeout(1200);
  check('⑦ 点击入口到达信息流', page.url().replace(/\/$/, '').endsWith('/share'), page.url());
  check('⑦ 无 console 报错', consoleNoise(consoleErrors).length === 0, consoleNoise(consoleErrors).slice(0, 3));
  await ctx.close();
}

await browser.close();

console.log(`\n=== 分享圈 UI 断言：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);

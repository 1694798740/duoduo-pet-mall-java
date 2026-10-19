/**
 * 秒杀价规则 · 真实浏览器 UI 自测（2026-10-19）
 *
 * 被验证的不变式：
 *   ① 选商品后，默认秒杀价**落在合法区间**（> 0.01 元 且 < 原价）——运营不改也能存；
 *   ② 秒杀价 = 原价 → **在弹窗里**给出明确中文提示（不是"点了没反应"）；
 *   ③ 秒杀价 = 0.01 元 → 同上，提示「必须大于 0.01 元」；
 *   ④ 合法价格可创建，且 **时间只被换算一次**（运营填 20:00 北京时间 → 库里 12:00 UTC）——
 *      这条守的是同一天发现的「前端已转 UTC、后端又按北京时间再减 8 小时」的双重换算回归。
 *
 * 服务端硬校验另有 seckill-smoke.ps1 §5.6b 覆盖（绕过页直打接口）；这里只测"页面这一层"。
 *
 * 用法：node scripts/e2e/seckill-price-ui-check.mjs
 * 环境变量：GATEWAY / ADMIN_UI_BASE / PW_CORE / CHROME_PATH / KEEP_FIXTURE=1
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { shotsDir } from '../lib/e2e-shots.mjs';
import { mysql as mysqlExec } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const ADMIN_UI = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
const SHOTS = shotsDir();
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? undefined;
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
const sql = (db, q) => mysqlExec(db, q);

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
    /* 保留 raw */
  }
  return { status: res.status, data: json?.data, raw };
}

/** 北京时间的今天 + 指定时分（用于填 datetime-local，也让断言能算出期望的 UTC） */
const bjDate = () => new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 10);

// ---------------- 1. 夹具：临时运营 ----------------
step('夹具：临时运营账号（库内提权，跑完删号）');
const phone = `136${String(Math.floor(1e7 + Math.random() * 8e7))}`;
const jar = new Map();
const reg = await call('POST', '/api/auth/register', { phone, password: PWD, nickname: '价格自测运营' }, jar);
check('临时运营账号注册成功', reg.status === 200 || reg.status === 201, reg.status);
await sql('duoduo_user', `UPDATE t_user SET role = 'OPERATOR' WHERE phone = '${phone}';`);
// 重新登录让 token 里的角色生效（角色是签发时快照）
await call('POST', '/api/auth/login', { phone, password: PWD }, jar);
const me = await call('GET', '/api/users/me', undefined, jar);
check('账号角色为 OPERATOR', (me.data?.role ?? '').toUpperCase() === 'OPERATOR', me.data?.role);

const TITLE = `【价格自测】${Date.now()}`;
const DAY = bjDate();

// ---------------- 2. 浏览器 ----------------
const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME ?? undefined, args: ['--no-sandbox'] });
const page = await (await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' })).newPage();
const consoleErrors = [];
const httpErrors = [];
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text());
});
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
page.on('response', (r) => {
  if (r.status() >= 400) httpErrors.push(`${r.status()} ${r.url()}`);
});

step('UI：后台登录并打开秒杀管理页');
await page.goto(`${ADMIN_UI}/admin.html`, { waitUntil: 'networkidle' });
await page.getByPlaceholder('请输入平台账号手机号').fill(phone);
await page.getByPlaceholder('请输入密码').fill(PWD);
await page.getByRole('button', { name: /^登录$/ }).click();
await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
await page.goto(`${ADMIN_UI}/admin/seckill`, { waitUntil: 'networkidle' });
await page.waitForTimeout(1000);

step('弹窗：填标题 → 选商品（搜索 → 看规格 → 用这个）');
await page.getByRole('button', { name: '+ 新建活动' }).click();
await page.getByPlaceholder('如：周末猫粮狂欢').fill(TITLE);

// 2026-10-19：手输 SKU ID 已移除，唯一入口是「选商品」面板（原「选商品回填」按钮随之删除）
await page.getByRole('button', { name: '选商品', exact: true }).click();
await page.getByPlaceholder('搜商品名（如：犬 / 猫 / 洁齿骨）').fill('猫');
await page.getByRole('button', { name: '搜索', exact: true }).click();
await page.waitForTimeout(1500);
// 搜索结果是 SPU 卡片，需先「看规格」下钻到 SKU 列表
if ((await page.getByRole('button', { name: '用这个' }).count()) === 0) {
  await page.getByRole('button', { name: '看规格' }).first().click();
  await page.waitForTimeout(1200);
}
await page.getByRole('button', { name: '用这个' }).first().click();
await page.waitForTimeout(800);

const modal = page.locator('.modal').first();
const priceInput = page.locator('input[type="number"]').nth(0);
const originalInput = page.locator('input[type="number"]').nth(1);
const original = Number(await originalInput.inputValue());
const defaultPrice = Number(await priceInput.inputValue());
check('选商品后回填了原价（元）', Number.isFinite(original) && original > 0, original);
check(
  '默认秒杀价落在合法区间（> 0.01 且 < 原价）',
  defaultPrice > 0.01 && defaultPrice < original,
  { 默认秒杀价: defaultPrice, 原价: original },
);

step('规则 ②：秒杀价 = 原价 → 弹窗内明确提示');
await priceInput.fill(String(original));
await page.getByRole('button', { name: '创建' }).click();
await page.waitForTimeout(600);
let modalText = await modal.innerText();
check(
  `提示点名「必须低于原售价（${original.toFixed(2)} 元）」`,
  modalText.includes('必须低于原售价') && modalText.includes(original.toFixed(2)),
  modalText.slice(-200),
);
check('提示出现在**弹窗内**（不是被遮罩挡住的页面顶部）', modalText.includes('必须低于原售价'), null);

step('规则 ③：秒杀价 = 0.01 元 → 提示必须大于 0.01 元');
await priceInput.fill('0.01');
await page.getByRole('button', { name: '创建' }).click();
await page.waitForTimeout(600);
modalText = await modal.innerText();
check('提示「秒杀价必须大于 0.01 元」', modalText.includes('必须大于 0.01 元'), modalText.slice(-200));

step('规则 ④：合法价格可创建，且时间只换算一次');
const GOOD_PRICE = Math.max(0.02, Math.floor(original * 0.5 * 100) / 100);
await priceInput.fill(String(GOOD_PRICE));
// 运营按北京时间填 20:00–23:59（页面明示"北京时间"）
await page.locator('input[type="datetime-local"]').nth(0).fill(`${DAY}T20:00`);
await page.locator('input[type="datetime-local"]').nth(1).fill(`${DAY}T23:59`);
await page.getByRole('button', { name: '创建' }).click();
// 创建成功后弹窗关闭、列表出现该活动
await page.waitForTimeout(2500);
const bodyText = await page.locator('body').innerText();
check('创建成功（列表出现该活动）', bodyText.includes(TITLE), bodyText.slice(0, 200));

const row = (await sql(
  'duoduo_trade',
  `SELECT CONCAT(id,'|',start_at,'|',end_at) FROM t_seckill_activity WHERE title='${TITLE}' ORDER BY id DESC LIMIT 1;`,
)) ?? '';
const [actId, startAt, endAt] = row.split('|');
check('活动已落库', Boolean(actId), row);
check(
  `北京时间 20:00 → 库里 12:00 UTC（没有被减两次 8 小时）`,
  (startAt ?? '').includes(' 12:00:00'),
  startAt,
);
check(`北京时间 23:59 → 库里 15:59 UTC`, (endAt ?? '').includes(' 15:59:00'), endAt);
const skuRow = (await sql(
  'duoduo_trade',
  `SELECT CONCAT(seckill_price,'|',original_price) FROM t_seckill_sku WHERE activity_id=${actId} LIMIT 1;`,
)) ?? '';
const [sPrice, sOriginal] = skuRow.split('|');
check(
  '落库价格＝页面填的元 ×100（分），且低于原价',
  Number(sPrice) === Math.round(GOOD_PRICE * 100) && Number(sPrice) < Number(sOriginal),
  { sPrice, sOriginal },
);

await page.screenshot({ path: path.join(SHOTS, 'seckill_price_ui.png'), fullPage: false });

step('收尾：删除自测活动与临时账号');
await browser.close();
if (!process.env.KEEP_FIXTURE) {
  const del = await call('DELETE', `/api/admin/seckill/activities/${actId}`, undefined, jar);
  check('自测活动已删除', del.status === 200, `${del.status}/${del.raw.slice(0, 120)}`);
  await sql('duoduo_user', `DELETE FROM t_refresh_token WHERE user_id=(SELECT id FROM t_user WHERE phone='${phone}');`);
  await sql('duoduo_user', `DELETE FROM t_user WHERE phone='${phone}';`);
  const left = await sql('duoduo_trade', `SELECT COUNT(*) FROM t_seckill_activity WHERE title='${TITLE}';`);
  const leftUser = await sql('duoduo_user', `SELECT COUNT(*) FROM t_user WHERE phone='${phone}';`);
  const orphan = await sql(
    'duoduo_trade',
    `SELECT COUNT(*) FROM t_seckill_sku s LEFT JOIN t_seckill_activity a ON a.id=s.activity_id WHERE a.id IS NULL;`,
  );
  check('活动残留 0', left === '0', left);
  check('账号残留 0', leftUser === '0', leftUser);
  check('秒杀 SKU 无孤儿行', orphan === '0', orphan);
}

const noisy = httpErrors.filter((e) => !/favicon/i.test(e) && !/(^|\s)401\s/.test(e));
check('无非预期 4xx/5xx（401 未登录探测与 favicon 除外）', noisy.length === 0, noisy.slice(0, 5));
check(
  '无 console / pageerror',
  consoleErrors.filter((e) => !/favicon|status of 401/i.test(e)).length === 0,
  consoleErrors.slice(0, 4),
);

console.log(`\n=== UI 自测结果：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  failures.forEach((f) => console.log(`  - ${f}`));
  process.exitCode = 1;
}

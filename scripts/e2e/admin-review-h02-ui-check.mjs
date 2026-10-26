/**
 * H-02 · 运营后台「评价管理」UI 验收（2026-10-26）。
 *
 * 打容器产物（admin.localhost:8090），不是 dev server。覆盖：
 *  ① 后台标签栏出现「评价管理」，页面可打开；
 *  ② 关键词搜索命中夹具评价，状态/星级展示正确；
 *  ③ 下架必须填原因（站内弹窗 + 就地报错，不是原生 prompt）；
 *  ④ 下架后状态变「已下架」，回库 audit_status/audit_reason/audited_by/audited_at 正确；
 *  ⑤ 「已下架」筛选可命中；恢复走站内确认，恢复后原因清空、状态回「已通过」；
 *  ⑥ 全程无原生 window.confirm/prompt/alert、无页面级 JS 异常。
 *
 * 夹具：直接插入一条 APPROVED 评价（UI 只关心管理动作；真实链路由 H-02 接口冒烟覆盖）。
 * 前置：容器在跑（8090 / 8080）；fulfilment 已部署 H-02；运营 13684295254 可用。
 * 用法：NO_PROXY="*" node scripts/e2e/admin-review-h02-ui-check.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { internalHeaders } from '../lib/internal-token.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const ADMIN = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const OPERATOR = '13684295254';
const PRODUCT_DB = 'duoduo_product';
const FULFIL_DB = 'duoduo_fulfilment';
const USER_DB = 'duoduo_user';

let pass = 0;
const failures = [];
function check(name, ok, got) {
  if (ok) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    failures.push(name);
    console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`.slice(0, 500));
  }
}
const step = (t) => console.log(`\n> ${t}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function sql(db, query) {
  const out = execFileSync('docker', [
    'exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B',
    '--default-character-set=utf8mb4', '-D', db, '-e', query,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return out.split(/\r?\n/).filter((line) => line && !line.includes('Warning'));
}
const one = (db, query) => sql(db, query)[0] ?? '';

const stamp = String(Date.now()).slice(-7);
const marker = `H02UI-${stamp}-评价内容`;
const reviewId = String(BigInt(Date.now()) * 1000000n + BigInt(Math.floor(Math.random() * 1000000)));

// ---------------- 1. 夹具 ----------------
step('夹具：选一个在售商品与真实用户，插入一条 APPROVED 评价');
const spuRow = one(PRODUCT_DB,
  `SELECT CONCAT(id,'|',title,'|',seller_id) FROM t_spu
   WHERE status='ON_SALE' AND audit_status='APPROVED' AND deleted_at IS NULL ORDER BY id LIMIT 1`);
const [spuId, productTitle, sellerId] = spuRow.split('|');
const userId = one(USER_DB, `SELECT id FROM t_user WHERE role='USER' ORDER BY id LIMIT 1`);
check('取到在售商品与用户夹具', Boolean(spuId) && Boolean(userId), { spuId, productTitle, userId });
if (!spuId || !userId) process.exit(1);

const preRating = one(PRODUCT_DB, `SELECT CONCAT(IFNULL(rating,0),'|',IFNULL(rating_count,0)) FROM t_spu WHERE id=${spuId}`);
const titleSql = productTitle.replace(/'/g, "''");
sql(FULFIL_DB, `INSERT INTO t_review
  (id, order_id, order_no, user_id, seller_id, spu_id, sku_id, product_title, product_image,
   rating, content, images, anonymous, audit_status, created_at, updated_at)
  VALUES (${reviewId}, ${Date.now()}, 'H02UI-${stamp}', ${userId}, ${Number(sellerId) || 0}, ${spuId}, NULL,
   '${titleSql}', NULL, 2, '${marker}', NULL, 0, 'APPROVED', UTC_TIMESTAMP(), UTC_TIMESTAMP())`);
check('夹具评价已插入（APPROVED / 2 星）',
  one(FULFIL_DB, `SELECT CONCAT(audit_status,'|',rating,'|',content) FROM t_review WHERE id=${reviewId}`)
    === `APPROVED|2|${marker}`);

async function cleanup() {
  sql(FULFIL_DB, `DELETE FROM t_review WHERE id=${reviewId}`);
  // 恢复商品评分原值（takedown/restore 会触发真实回写链）
  const [r, c] = preRating.split('|').map(Number);
  await fetch(`${API}/api/products/internal/ratings/recompute`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...internalHeaders('POST') },
    body: JSON.stringify([{ spuId, avgRating: r, ratingCount: c }]),
  }).catch(() => undefined);
}

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH ?? undefined, args: ['--no-sandbox'] });
const page = await browser.newPage({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
const nativeDialogs = [];
const pageErrors = [];
page.on('dialog', async (d) => {
  nativeDialogs.push(`${d.type()}:${d.message()}`);
  await d.dismiss().catch(() => {});
});
page.on('pageerror', (e) => pageErrors.push(e.message));

try {
  step('后台登录');
  await page.goto(`${ADMIN}/login`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('请输入平台账号手机号').fill(OPERATOR);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
  check('运营登录成功', !page.url().includes('/login'), page.url());

  step('进入评价管理并搜索夹具');
  await page.goto(`${ADMIN}/admin/reviews`, { waitUntil: 'networkidle' });
  check('标签栏含「评价管理」',
    await page.getByRole('link', { name: '评价管理' }).isVisible().catch(() => false));
  const search = page.locator('input[placeholder="搜评价内容 / 商品标题 / 订单号"]');
  await search.fill(stamp);
  // 输入即搜（useDebounced 350ms），页面不把关键词写进 URL —— 直接等目标行出现
  const row = page.locator('tbody tr', { hasText: marker });
  await row.waitFor({ timeout: 20000 });
  check('搜索结果含夹具评价', await row.isVisible());
  check('状态展示为「已通过」', /已通过/.test(await row.innerText()));
  check('星级展示为 2 星', /2 星/.test(await row.innerText()));
  check('评价人列展示真实用户 id', (await row.innerText()).includes(userId));

  step('下架：原因必填（站内弹窗）');
  await row.getByRole('button', { name: '下架', exact: true }).click();
  const takeDialog = page.locator('[role="dialog"]', { hasText: '下架评价' });
  await takeDialog.waitFor({ timeout: 10000 });
  await takeDialog.getByRole('button', { name: '确认下架' }).click();
  await takeDialog.getByText('下架评价必须填写原因').waitFor({ timeout: 5000 });
  check('未填原因时就地报错（不是原生弹窗）', true);
  const reason = 'H02 UI 验收：评价内容违规';
  await takeDialog.locator('textarea').fill(reason);
  await takeDialog.getByRole('button', { name: '确认下架' }).click();
  await page.waitForFunction(
    (m) => [...document.querySelectorAll('tbody tr')].some((tr) => tr.innerText.includes(m) && tr.innerText.includes('已下架')),
    marker, { timeout: 20000 });
  const rowRejected = page.locator('tbody tr', { hasText: marker });
  check('下架后列表状态变「已下架」', /已下架/.test(await rowRejected.innerText()));
  const dbRejected = one(FULFIL_DB,
    `SELECT CONCAT(audit_status,'|',audit_reason,'|',audited_by,'|',IF(audited_at IS NULL,'NULL','SET'))
     FROM t_review WHERE id=${reviewId}`);
  check('回库：REJECTED + 原因 + 操作人 + 审核时间',
    dbRejected.startsWith(`REJECTED|${reason}|`) && dbRejected.endsWith('|SET'), dbRejected);

  step('按「已下架」筛选 → 恢复（站内确认）');
  await page.getByRole('button', { name: '已下架', exact: true }).click();
  await page.waitForFunction(
    (m) => [...document.querySelectorAll('tbody tr')].some((tr) => tr.innerText.includes(m) && tr.innerText.includes('已下架')),
    marker, { timeout: 15000 });
  await page.locator('tbody tr', { hasText: marker }).getByRole('button', { name: '恢复', exact: true }).click();
  const restoreDialog = page.locator('[role="dialog"]', { hasText: '恢复评价' });
  await restoreDialog.waitFor({ timeout: 10000 });
  check('恢复确认弹窗说明会重新参与评分', /重新参与评分/.test(await restoreDialog.innerText()));
  await restoreDialog.getByRole('button', { name: '恢复', exact: true }).click();
  await page.waitForFunction(
    (m) => ![...document.querySelectorAll('tbody tr')].some((tr) => tr.innerText.includes(m)),
    marker, { timeout: 20000 });
  check('恢复后该行从「已下架」筛选结果消失',
    await page.locator('tbody tr', { hasText: marker }).count() === 0);
  const dbRestored = one(FULFIL_DB,
    `SELECT CONCAT(audit_status,'|',IFNULL(audit_reason,'NULL')) FROM t_review WHERE id=${reviewId}`);
  check('回库：APPROVED + 原因清空', dbRestored === 'APPROVED|NULL', dbRestored);

  await page.getByRole('button', { name: '已通过', exact: true }).click();
  await page.locator('tbody tr', { hasText: marker }).waitFor({ timeout: 20000 });
  check('「已通过」筛选重新命中',
    /已通过/.test(await page.locator('tbody tr', { hasText: marker }).innerText()));

  step('截图与前端异常检查');
  const shot = process.env.H02_UI_SHOT ?? path.join(here, 'verify-h02-reviews.png');
  await page.screenshot({ path: shot, fullPage: true });
  console.log(`  [截图] ${shot}`);
  check('全程无原生 window.confirm/prompt/alert', nativeDialogs.length === 0, nativeDialogs);
  check('无页面级 JS 异常', pageErrors.length === 0, pageErrors);
} finally {
  await browser.close();
  await cleanup();
  check('评价夹具已删除', one(FULFIL_DB, `SELECT COUNT(*) FROM t_review WHERE id=${reviewId}`) === '0');
}

console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exitCode = 1;
}

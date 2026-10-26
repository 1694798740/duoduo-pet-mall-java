/**
 * A-15 · 评价机审 UI 验收（2026-10-26）。
 *
 * 覆盖两端：
 *  ① 买家「我的评价」能看到机审转人工的评价，并显示「审核中」而不是以为评价丢了；
 *  ② 运营后台「待审核」Tab 能筛到机审转人工的评价，展示来源「机审自动」与命中原因；
 *  ③ 运营点「通过」走站内确认弹窗，通过后状态变「已通过」、来源变「人工审核」、原因清空；
 *  ④ 通过后再下架仍走原因必填弹窗，状态变「已下架」；
 *  ⑤ 全程无原生 window.confirm/prompt/alert、无页面级 JS 异常。
 *
 * 夹具：直接插入一条 PENDING + AUTO 的评价（真实提交链路由 A-15 接口冒烟覆盖），
 * 买家账号现场注册，商品评分原值先记录、收尾复位。
 * 前置：容器在跑（8088 / 8090 / 8080）；fulfilment 已部署 A-15；运营 13684295254 可用。
 * 用法：NO_PROXY="*" node scripts/e2e/admin-review-auto-a15-ui-check.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { internalHeaders } from '../lib/internal-token.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const MALL = process.env.UI_BASE ?? 'http://localhost:8088';
const ADMIN = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const OPERATOR = '13684295254';
const PRODUCT_DB = 'duoduo_product';
const FULFIL_DB = 'duoduo_fulfilment';
const USER_DB = 'duoduo_user';
const TRADE_DB = 'duoduo_trade';

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
const count = (db, query) => Number(sql(db, query)[0] ?? 0);

const stamp = String(Date.now()).slice(-7);
const marker = `A15UI-${stamp}-审核中评价`;
const reason = '机审命中：联系方式（手机号 138****8000）';
const reviewId = String(BigInt(Date.now()) * 1000000n + BigInt(Math.floor(Math.random() * 1000000)));
const buyerPhone = `137${String(Math.floor(1e7 + Math.random() * 8e7))}`;

// ---------------- 1. 夹具 ----------------
step('夹具：在售商品 + 现场注册买家 + 插入 PENDING/AUTO 评价');
const spuRow = one(PRODUCT_DB,
  `SELECT CONCAT(id,'|',title,'|',seller_id) FROM t_spu
   WHERE status='ON_SALE' AND audit_status='APPROVED' AND deleted_at IS NULL ORDER BY id LIMIT 1`);
const [spuId, productTitle, sellerId] = spuRow.split('|');
if (!spuId) {
  console.log('  [FAIL] 缺少在售商品夹具，无法继续');
  process.exit(1);
}
const preRating = one(PRODUCT_DB, `SELECT CONCAT(IFNULL(rating,0),'|',IFNULL(rating_count,0)) FROM t_spu WHERE id=${spuId}`);

const reg = await fetch(`${API}/api/auth/register`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ phone: buyerPhone, password: PWD, nickname: 'A15UI买家' }),
});
check('买家注册成功（用于登录我的评价页）', reg.status === 200 || reg.status === 201, reg.status);
const userId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${buyerPhone}'`);
check('取到买家 userId', Boolean(userId), userId);
if (!userId) process.exit(1);

const titleSql = productTitle.replace(/'/g, "''");
sql(FULFIL_DB, `INSERT INTO t_review
  (id, order_id, order_no, user_id, seller_id, spu_id, sku_id, product_title, product_image,
   rating, content, images, anonymous, audit_status, audit_reason, audited_by, audited_at, audit_source,
   created_at, updated_at)
  VALUES (${reviewId}, ${Date.now()}, 'A15UI-${stamp}', ${userId}, ${Number(sellerId) || 0}, ${spuId}, NULL,
   '${titleSql}', NULL, 2, '${marker}', NULL, 0, 'PENDING',
   '${reason}', NULL, UTC_TIMESTAMP(), 'AUTO', UTC_TIMESTAMP(), UTC_TIMESTAMP())`);
check('夹具评价已插入（PENDING / AUTO / 2 星）',
  one(FULFIL_DB, `SELECT CONCAT(audit_status,'|',audit_source,'|',rating) FROM t_review WHERE id=${reviewId}`)
    === 'PENDING|AUTO|2');

async function cleanup() {
  sql(FULFIL_DB, `DELETE FROM t_review WHERE id=${reviewId}`);
  // 通过评价会触发真实评分回写链；收尾删除评价后把商品评分复位到夹具前
  const [r, c] = preRating.split('|').map(Number);
  await fetch(`${API}/api/products/internal/ratings/recompute`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...internalHeaders('POST') },
    body: JSON.stringify([{ spuId, avgRating: r, ratingCount: c }]),
  }).catch(() => undefined);
  sql(USER_DB, `DELETE FROM t_user_address WHERE user_id=${userId}`);
  sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${userId}`);
  sql(TRADE_DB, `DELETE FROM t_cart_item WHERE user_id=${userId}`);
  sql(USER_DB, `DELETE FROM t_user WHERE id=${userId}`);
}

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH ?? undefined, args: ['--no-sandbox'] });
const nativeDialogs = [];
const pageErrors = [];
const watch = (page) => {
  page.on('dialog', async (d) => {
    nativeDialogs.push(`${d.type()}:${d.message()}`);
    await d.dismiss().catch(() => {});
  });
  page.on('pageerror', (e) => pageErrors.push(e.message));
};

try {
  // ---------------- 2. 买家端 ----------------
  step('买家：登录并查看「我的评价」');
  const buyerCtx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
  const buyerPage = await buyerCtx.newPage();
  watch(buyerPage);
  await buyerPage.goto(`${MALL}/login`, { waitUntil: 'networkidle' });
  await buyerPage.getByPlaceholder('11 位手机号').fill(buyerPhone);
  await buyerPage.getByPlaceholder('请输入密码').fill(PWD);
  await buyerPage.getByRole('button', { name: /^登录$/ }).click();
  await buyerPage.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
  check('买家登录成功', !buyerPage.url().includes('/login'), buyerPage.url());
  await buyerPage.goto(`${MALL}/reviews/mine`, { waitUntil: 'networkidle' });
  const card = buyerPage.locator('.card', { hasText: marker });
  await card.waitFor({ timeout: 20000 });
  const cardText = await card.innerText();
  check('我的评价展示「审核中」标签', /审核中/.test(cardText), cardText);
  check('我的评价说明「通过后会展示在商品评价区」', /通过后会展示在商品评价区/.test(cardText), cardText);
  check('审核中的评价不会出现在公开评价区',
    !(await buyerPage.evaluate(async ({ id, spu }) => {
      const res = await fetch(`/api/reviews/product/${spu}?page=1&pageSize=50`);
      const body = await res.json();
      return (body?.data?.items ?? []).some((x) => String(x.id) === String(id));
    }, { id: reviewId, spu: spuId })));
  await buyerCtx.close();

  // ---------------- 3. 运营后台 ----------------
  step('后台：登录并进入评价管理');
  const adminCtx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
  const page = await adminCtx.newPage();
  watch(page);
  await page.goto(`${ADMIN}/login`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('请输入平台账号手机号').fill(OPERATOR);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
  check('运营登录成功', !page.url().includes('/login'), page.url());
  await page.goto(`${ADMIN}/admin/reviews`, { waitUntil: 'networkidle' });
  check('标签栏含「评价管理」', await page.getByRole('link', { name: '评价管理' }).isVisible().catch(() => false));
  check('页面说明机审只转人工、不自动下架', /机器不会自动下架/.test(await page.locator('body').innerText()));

  step('待审核 Tab：筛选、来源与命中原因');
  await page.getByRole('button', { name: '待审核', exact: true }).click();
  const search = page.locator('input[placeholder="搜评价内容 / 商品标题 / 订单号"]');
  await search.fill(stamp);
  const row = page.locator('tbody tr', { hasText: marker });
  await row.waitFor({ timeout: 20000 });
  const rowText = await row.innerText();
  check('待审核筛选命中夹具', /待审核/.test(rowText), rowText);
  check('状态列展示来源「机审自动」', /机审自动/.test(rowText), rowText);
  await row.getByRole('button', { name: '查看', exact: true }).click();
  const detail = page.locator('[role="dialog"]', { hasText: '评价详情' });
  await detail.waitFor({ timeout: 10000 });
  const detailText = await detail.innerText();
  check('详情展示机审转人工原因', /机审转人工/.test(detailText) && detailText.includes(reason), detailText);
  check('详情展示审核来源「机审自动」', /审核来源：机审自动/.test(detailText), detailText);
  // 先关详情再点行内「通过」：否则页面上同时存在详情与确认两个 dialog，
  // 按文案定位确认框会撞 strict mode（详情里也有「通过评价」按钮）
  await detail.getByRole('button', { name: '关闭', exact: true }).last().click();

  step('通过评价：站内确认 → APPROVED + MANUAL');
  await row.getByRole('button', { name: '通过', exact: true }).click();
  const approveDialog = page.locator('[role="dialog"]', { hasText: '通过评价' });
  await approveDialog.waitFor({ timeout: 10000 });
  check('通过确认弹窗说明会展示并参与评分', /展示在商品评价区/.test(await approveDialog.innerText()));
  await approveDialog.getByRole('button', { name: '通过', exact: true }).click();
  await page.waitForFunction(
    (m) => ![...document.querySelectorAll('tbody tr')].some((tr) => tr.innerText.includes(m)),
    marker, { timeout: 20000 });
  check('通过后该行从「待审核」筛选结果消失',
    await page.locator('tbody tr', { hasText: marker }).count() === 0);
  const opUserId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${OPERATOR}'`);
  const dbApproved = one(FULFIL_DB,
    `SELECT CONCAT(audit_status,'|',audit_source,'|',IFNULL(audit_reason,'NULL'),'|',IFNULL(audited_by,'NULL'),'|',IF(audited_at IS NULL,'NULL','SET'))
     FROM t_review WHERE id=${reviewId}`);
  check('回库：APPROVED + MANUAL + 原因清空 + 操作人/时间落库',
    dbApproved === `APPROVED|MANUAL|NULL|${opUserId}|SET`, dbApproved);

  step('已通过 Tab：来源变人工，随后下架');
  await page.getByRole('button', { name: '已通过', exact: true }).click();
  const approvedRow = page.locator('tbody tr', { hasText: marker });
  await approvedRow.waitFor({ timeout: 20000 });
  const approvedText = await approvedRow.innerText();
  check('已通过筛选命中且状态为「已通过」', /已通过/.test(approvedText), approvedText);
  check('状态列来源变为「人工审核」', /人工审核/.test(approvedText), approvedText);
  await approvedRow.getByRole('button', { name: '下架', exact: true }).click();
  const takeDialog = page.locator('[role="dialog"]', { hasText: '下架评价' });
  await takeDialog.waitFor({ timeout: 10000 });
  await takeDialog.locator('textarea').fill('A15 UI 验收：人工复核后仍下架');
  await takeDialog.getByRole('button', { name: '确认下架' }).click();
  await page.waitForFunction(
    (m) => ![...document.querySelectorAll('tbody tr')].some((tr) => tr.innerText.includes(m)),
    marker, { timeout: 20000 });
  const dbRejected = one(FULFIL_DB,
    `SELECT CONCAT(audit_status,'|',audit_source,'|',audit_reason) FROM t_review WHERE id=${reviewId}`);
  check('回库：下架后 REJECTED + MANUAL + 原因',
    dbRejected === 'REJECTED|MANUAL|A15 UI 验收：人工复核后仍下架', dbRejected);

  step('前端异常检查');
  const shot = process.env.A15_UI_SHOT ?? path.join(here, 'verify-a15-reviews.png');
  await page.screenshot({ path: shot, fullPage: true });
  console.log(`  [截图] ${shot}`);
  check('全程无原生 window.confirm/prompt/alert', nativeDialogs.length === 0, nativeDialogs);
  check('无页面级 JS 异常', pageErrors.length === 0, pageErrors);
} finally {
  await browser.close();
  await cleanup();
  check('评价夹具已删除', count(FULFIL_DB, `SELECT COUNT(*) FROM t_review WHERE id=${reviewId}`) === 0);
  check('临时买家零残留', count(USER_DB, `SELECT COUNT(*) FROM t_user WHERE id=${userId}`) === 0);
}

console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exitCode = 1;
}

/**
 * A-16 · 商家回复评价 UI 验收（2026-10-26）。
 *
 * 覆盖两端：
 *  ① 商家端出现「评价管理」入口：待回复 / 已回复 / 全部三个页签能正确筛选；
 *  ② 商家点「回复评价」走站内弹窗，发布后卡片上出现「商家回复」，按钮变为「已回复，不可修改」；
 *  ③ 匿名评价在商家端只显示「匿名用户」，页面文本不含买家真实昵称；
 *  ④ 买家/访客在商品详情页能看到商家回复与回复时间；
 *  ⑤ 全程无原生 window.confirm/prompt/alert、无页面级 JS 异常。
 *
 * 夹具：直接插入两条 APPROVED 评价（正常 / 匿名），商品用真实在售商品（卖家 13600003333），
 * 买家账号现场注册；收尾删除评价与买家，商品评分复位。
 * 前置：容器在跑（8088 / 8091 / 8080）；fulfilment 已部署 A-16；卖家 13600003333 可用。
 * 用法：NO_PROXY="*" node scripts/e2e/seller-review-reply-a16-ui-check.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { internalHeaders } from '../lib/internal-token.mjs';
import { shotsDir } from '../lib/e2e-shots.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const MALL = process.env.UI_BASE ?? 'http://localhost:8088';
const MERCHANT = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const BUYER_PWD = 'Passw0rd!23';
const SELLER = '13600003333';
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
const markerNormal = `A16UI-${stamp}-正常评价，猫粮很香`;
const markerAnon = `A16UI-${stamp}-匿名评价，包装完好`;
const replyNormal = `A16UI-${stamp}-感谢反馈，已为您安排补发`;
const replyAnon = `A16UI-${stamp}-感谢您对包装的认可`;
const normalBuyerPhone = `199${String(Date.now()).slice(-8)}`;
const anonBuyerPhone = `198${String(Date.now()).slice(-8)}`;
const normalNickname = `A16UI真实昵称${stamp}`;
const anonNickname = `A16UI匿名真人${stamp}`;
const idBase = BigInt(Date.now()) * 1000n;
const reviewNormalId = (idBase + 1n).toString();
const reviewAnonId = (idBase + 2n).toString();

// ---------------- 1. 夹具 ----------------
step('夹具：取卖家在售商品 + 注册两个买家 + 插入两条 APPROVED 评价');
const spuRow = one(PRODUCT_DB,
  `SELECT CONCAT(s.id,'|',s.title,'|',s.seller_id) FROM t_spu s
   JOIN duoduo_user.t_user u ON u.id = s.seller_id
   WHERE u.phone='${SELLER}' AND s.status='ON_SALE' AND s.audit_status='APPROVED' AND s.deleted_at IS NULL
   ORDER BY s.id LIMIT 1`);
const [spuId, productTitle, sellerId] = spuRow.split('|');
if (!spuId) {
  console.log('  [FAIL] 缺少该卖家的在售商品夹具，无法继续');
  process.exit(1);
}
const preRating = one(PRODUCT_DB, `SELECT CONCAT(IFNULL(rating,0),'|',IFNULL(rating_count,0)) FROM t_spu WHERE id=${spuId}`);
check('取到卖家在售商品', Boolean(spuId), spuRow);

async function register(phone, nickname) {
  const res = await fetch(`${API}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone, password: BUYER_PWD, nickname }),
  });
  return res.status;
}
const regNormal = await register(normalBuyerPhone, normalNickname);
const regAnon = await register(anonBuyerPhone, anonNickname);
check('两个买家账号注册成功', (regNormal === 200 || regNormal === 201) && (regAnon === 200 || regAnon === 201),
  { regNormal, regAnon });
const normalUserId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${normalBuyerPhone}'`);
const anonUserId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${anonBuyerPhone}'`);
check('取到两个买家 userId', Boolean(normalUserId) && Boolean(anonUserId), { normalUserId, anonUserId });
if (!normalUserId || !anonUserId) process.exit(1);

const titleSql = productTitle.replace(/'/g, "''");
function insertReview(id, userId, content, anonymous) {
  sql(FULFIL_DB, `INSERT INTO t_review
    (id, order_id, order_no, user_id, seller_id, spu_id, sku_id, product_title, product_image,
     rating, content, images, anonymous, audit_status, audited_at, audit_source,
     created_at, updated_at)
    VALUES (${id}, ${Date.now()}, 'A16UI-${stamp}', ${userId}, ${sellerId}, ${spuId}, NULL,
     '${titleSql}', NULL, ${anonymous ? 4 : 5}, '${content.replace(/'/g, "''")}', NULL,
     ${anonymous ? 1 : 0}, 'APPROVED', UTC_TIMESTAMP(), 'AUTO',
     UTC_TIMESTAMP(), UTC_TIMESTAMP())`);
}
insertReview(reviewNormalId, normalUserId, markerNormal, false);
insertReview(reviewAnonId, anonUserId, markerAnon, true);
const fixtureRows = sql(FULFIL_DB,
  `SELECT CONCAT(audit_status,'|',anonymous) FROM t_review WHERE id=${reviewNormalId} OR id=${reviewAnonId}`);
check('两条评价夹具已插入（APPROVED）',
  fixtureRows.length === 2 && fixtureRows.includes('APPROVED|0') && fixtureRows.includes('APPROVED|1'),
  fixtureRows);

async function cleanup() {
  sql(FULFIL_DB, `DELETE FROM t_review WHERE id IN (${reviewNormalId},${reviewAnonId})`);
  const [r, c] = preRating.split('|').map(Number);
  await fetch(`${API}/api/products/internal/ratings/recompute`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...internalHeaders('POST') },
    body: JSON.stringify([{ spuId, avgRating: r, ratingCount: c }]),
  }).catch(() => undefined);
  for (const uid of [normalUserId, anonUserId].filter(Boolean)) {
    sql(USER_DB, `DELETE FROM t_user_address WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_refresh_token WHERE user_id=${uid}`);
    sql(TRADE_DB, `DELETE FROM t_cart_item WHERE user_id=${uid}`);
    sql(USER_DB, `DELETE FROM t_user WHERE id=${uid}`);
  }
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

async function login(p, phone, site) {
  const base = site === 'merchant' ? MERCHANT : MALL;
  await p.goto(`${base}/login`, { waitUntil: 'networkidle' });
  await p.getByPlaceholder('11 位手机号').fill(phone);
  await p.getByPlaceholder('请输入密码').fill(site === 'merchant' ? PWD : BUYER_PWD);
  await p.getByRole('button', { name: /^登录$/ }).click();
  await p.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
}

try {
  // ---------------- 2. 商家端 ----------------
  step('商家端：登录并进入「评价管理」');
  const sellerCtx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
  const page = await sellerCtx.newPage();
  watch(page);
  await login(page, SELLER, 'merchant');
  check('卖家登录成功', !page.url().includes('/login'), page.url());
  check('主导航含「评价管理」入口',
    await page.getByRole('link', { name: '评价管理' }).isVisible().catch(() => false));
  await page.goto(`${MERCHANT}/seller/reviews`, { waitUntil: 'networkidle' });
  check('进入商家评价管理页', /商家端 · 评价管理/.test(await page.locator('body').innerText()));
  check('页面说明「每条评价只能回复一次」「匿名不暴露身份」',
    /只能回复一次/.test(await page.locator('body').innerText())
      && /匿名评价不会向商家暴露买家身份/.test(await page.locator('body').innerText()));

  step('待回复：两条夹具都在，匿名已脱敏');
  const cardNormal = page.locator('.card', { hasText: markerNormal });
  const cardAnon = page.locator('.card', { hasText: markerAnon });
  await cardNormal.waitFor({ timeout: 20000 });
  await cardAnon.waitFor({ timeout: 10000 });
  check('正常评价卡片展示真实昵称', (await cardNormal.innerText()).includes(normalNickname));
  const anonText = await cardAnon.innerText();
  check('匿名评价卡片展示「匿名用户」', /匿名用户/.test(anonText), anonText);
  check('匿名评价的真实昵称不出现在页面', !(await page.locator('body').innerText()).includes(anonNickname));
  check('匿名评价卡片带「匿名」标记', /匿名/.test(anonText));

  step('回复正常评价：站内弹窗 → 卡片出现商家回复');
  await cardNormal.getByRole('button', { name: '回复评价', exact: true }).click();
  const dialog = page.locator('[role="dialog"]', { hasText: '回复评价' });
  await dialog.waitFor({ timeout: 10000 });
  check('回复弹窗说明「只能回复一次、不可修改」', /只能回复一次、不可修改/.test(await dialog.innerText()));
  await dialog.locator('textarea').fill(replyNormal);
  await dialog.getByRole('button', { name: '发布回复', exact: true }).click();
  // 发布成功后弹窗关闭，再切页签，避免与 reload 竞态
  await dialog.waitFor({ state: 'detached', timeout: 20000 });
  // 回复成功后该项会从「待回复」页签消失，必须切到「已回复」才能看到结果
  await page.getByRole('button', { name: '已回复', exact: true }).click();
  await page.locator('.card', { hasText: markerNormal }).waitFor({ timeout: 20000 });
  const cardNormalReplied = page.locator('.card', { hasText: markerNormal });
  const repliedText = await cardNormalReplied.innerText();
  check('卡片展示商家回复内容', repliedText.includes(replyNormal), repliedText);
  check('回复后按钮变为「已回复，不可修改」', /已回复，不可修改/.test(repliedText), repliedText);
  const sellerUserId = one(USER_DB, `SELECT id FROM t_user WHERE phone='${SELLER}'`);
  check('回库：回复内容 + 时间 + 操作人 = 卖家',
    one(FULFIL_DB, `SELECT CONCAT(IFNULL(reply_content,'NULL'),'|',IF(reply_at IS NULL,'-','SET'),'|',IFNULL(replied_by,'-'))
      FROM t_review WHERE id=${reviewNormalId}`) === `${replyNormal}|SET|${sellerUserId}`);

  step('回复匿名评价：仍不泄露身份');
  await page.getByRole('button', { name: '待回复', exact: true }).click();
  const cardAnon2 = page.locator('.card', { hasText: markerAnon });
  await cardAnon2.waitFor({ timeout: 20000 });
  await cardAnon2.getByRole('button', { name: '回复评价', exact: true }).click();
  const dialog2 = page.locator('[role="dialog"]', { hasText: '回复评价' });
  await dialog2.waitFor({ timeout: 10000 });
  await dialog2.locator('textarea').fill(replyAnon);
  await dialog2.getByRole('button', { name: '发布回复', exact: true }).click();
  await dialog2.waitFor({ state: 'detached', timeout: 20000 });
  await page.getByRole('button', { name: '已回复', exact: true }).click();
  const cardAnonReplied = page.locator('.card', { hasText: markerAnon });
  await cardAnonReplied.getByText(replyAnon, { exact: false }).waitFor({ timeout: 20000 });
  const anonReplied = await cardAnonReplied.innerText();
  check('匿名评价回复后仍显示「匿名用户」', /匿名用户/.test(anonReplied), anonReplied);
  check('匿名评价回复后仍不含真实昵称', !(await page.locator('body').innerText()).includes(anonNickname));

  step('页签筛选：待回复清零、已回复命中两条');
  check('「已回复」包含两条夹具',
    await page.locator('.card', { hasText: markerNormal }).count() === 1
      && await page.locator('.card', { hasText: markerAnon }).count() === 1);
  check('已回复卡片没有可点的「回复评价」按钮',
    await page.locator('.card', { hasText: markerNormal }).getByRole('button', { name: '回复评价' }).count() === 0);
  await page.getByRole('button', { name: '待回复', exact: true }).click();
  await page.waitForTimeout(1200);
  check('「待回复」不再包含两条夹具',
    await page.locator('.card', { hasText: markerNormal }).count() === 0
      && await page.locator('.card', { hasText: markerAnon }).count() === 0);

  // ---------------- 3. 买家端商品详情 ----------------
  step('买家/访客：商品详情页展示商家回复');
  const buyerCtx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
  const buyerPage = await buyerCtx.newPage();
  watch(buyerPage);
  await buyerPage.goto(`${MALL}/product/${spuId}`, { waitUntil: 'networkidle' });
  await buyerPage.getByText(markerNormal, { exact: false }).waitFor({ timeout: 20000 });
  const bodyText = await buyerPage.locator('body').innerText();
  check('商品页能看到两条夹具评价', bodyText.includes(markerNormal) && bodyText.includes(markerAnon));
  check('商品页展示「商家回复」与两条回复内容',
    /商家回复/.test(bodyText) && bodyText.includes(replyNormal) && bodyText.includes(replyAnon));
  check('商品页匿名评价仍显示「匿名用户」', /匿名用户/.test(bodyText));
  check('商品页不出现匿名评价的真实昵称', !bodyText.includes(anonNickname));

  step('前端异常检查');
  const shot = process.env.A16_UI_SHOT ?? path.join(shotsDir(), 'verify-a16-reviews.png');
  await page.screenshot({ path: shot, fullPage: true });
  console.log(`  [截图] ${shot}`);
  check('全程无原生 window.confirm/prompt/alert', nativeDialogs.length === 0, nativeDialogs);
  check('无页面级 JS 异常', pageErrors.length === 0, pageErrors);
} finally {
  await browser.close();
  await cleanup();
  check('评价夹具已删除',
    count(FULFIL_DB, `SELECT COUNT(*) FROM t_review WHERE id IN (${reviewNormalId},${reviewAnonId})`) === 0);
  check('两个临时买家零残留',
    count(USER_DB, `SELECT COUNT(*) FROM t_user WHERE id IN (${normalUserId ?? 0},${anonUserId ?? 0})`) === 0);
}

console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exitCode = 1;
}

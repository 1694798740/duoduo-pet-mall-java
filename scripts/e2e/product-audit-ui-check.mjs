/**
 * 第三方商品上架审核 · 真实浏览器 UI 自测（E14，**自包含**）
 *
 * 覆盖总纲交付项：**发布 → 前台不可见 → 审核通过 → 前台可见 → 内容变更再审 → 驳回 → 重提 → 再通过**。
 *
 *   S1 夹具：卖家（注册 + 库内直建 APPROVED 店铺 + 重登换令牌）
 *   S2 商家端 UI：真实「发布商品」弹窗提交 → 列表出现「待审核」
 *   S3 可见性（未过审）：公开详情 404；**索引里已存在但检索不命中**（ES 审计过滤）
 *   S4 上架门禁：卖家把待审商品下架再上架 → 422（不能绕过审核自己上架）
 *   S5 后台 UI：审核台待审队列可见 → 通过
 *   S6 通过后可见：门禁放行（可重新上架）→ 公开详情 200；重索引后检索命中
 *   S7 内容变更再审：卖家改标题 → 回 PENDING → 前台**再次**不可见
 *   S8 驳回链路：运营 UI 驳回（必填原因）→ 商家 UI 看到原因 → 点「重新提交」→ 回待审
 *   S9 审核台边界：队列里只有第三方商品（自营免审，不干扰待办）
 *   S10 UI 卫生：无 pageerror / 5xx
 *
 * 口径：
 *   · 审核用**主运营账号**在后台站点登录（E8：ent=admin 才能调 /api/admin/**）——
 *     不新建 OPERATOR，避免"测试收尾漏降权"再攒出永久后台入口（E26 根因）
 *   · **检索断言先 reindex**：sync 是 15s 周期补偿，不重索引的话"新商品查不到"
 *     可能是因为它根本没进索引 —— 那这条断言就假绿了。先全量重建让文档**确实存在**，
 *     再断言"存在但被 auditStatus 过滤掉"，才真正测到 ES 的可见性出口。
 *   · 收尾删掉本套件造的商品（SPU + SKU）：**留着一条 PENDING 商品会永久占着审核队列**，
 *     污染后续套件与运营待办。卖家夹具是 SELLER 角色（无后台入口），与 seller/aftersale 套件同口径不清理。
 *
 * 前置：10 容器在跑（含 duoduo-es）。
 * 用法：node scripts/e2e/product-audit-ui-check.mjs
 */

import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
const MALL_HTTP = process.env.MALL_HTTP_BASE ?? 'http://127.0.0.1:8088';
/** 平台管理后台**独立站点**：浏览器用主机名（Cookie 作用域），fetch 用 127.0.0.1 + 端口 */
const ADMIN_UI = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
/** 商家端**独立站点**（E6）：Cookie 按主机名隔离，浏览器必须走 merchant.localhost */
const MERCHANT_UI = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const SHOTS = process.env.SHOTS ?? 'D:/智能体项目/duoduo-pet-mall-java/frontend/docs/screenshots';
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? undefined;
const PWD = 'duoduo12345';
const OPERATOR_PHONE = '13684295254';

/** 库内已有的叶子类目 / 品牌（脚本里硬编码会随种子变更而失效，这里取真实存在的） */
const LEAF_CATEGORY = '101';
const BRAND = '1';

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

async function call(method, p, body, jar, base) {
  const headers = {};
  if (body !== undefined && !(body instanceof FormData)) headers['content-type'] = 'application/json';
  if (jar && jar.size) headers.cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
  const res = await fetch((p.startsWith('http') ? '' : (base ?? GW)) + p, {
    method,
    headers,
    body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
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
  return { status: res.status, data: json?.data, json };
}

const randPhone = () => '137' + String(Math.floor(1e7 + Math.random() * 8e7));

// ---------------- 浏览器 ----------------
const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

function watch(page) {
  const consoleErrors = [];
  const httpErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
  page.on('response', (r) => { if (r.status() >= 500) httpErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`); });
  return { consoleErrors, httpErrors };
}

async function loginUI(page, base, phone) {
  await page.goto(`${base}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[placeholder*="手机号"]').fill(phone);
  await page.locator('input[type="password"]').fill(PWD);
  await page.getByRole('button', { name: /登录/ }).click();
  await page.waitForURL((u) => !String(u).includes('/login'), { timeout: 15000 });
}

/** 公开检索（仅 MySQL 语义下由 ES/降级决定，重索引后再断言才可信） */
const searchTitles = async (kw) => {
  const r = await call('GET', `/api/products?keyword=${encodeURIComponent(kw)}&page=1&pageSize=20`);
  return (r.data?.items ?? []).map((it) => it.title);
};

let spuId = null;
const owner = { phone: randPhone(), jar: new Map() };
const op = { jar: new Map() };

try {
  // ---------------- S1 夹具 ----------------
  step('S1 夹具：卖家注册 + 库内直建 APPROVED 店铺 + 重登换令牌');
  check('卖家注册', (await call('POST', '/api/auth/register',
    { phone: owner.phone, password: PWD, nickname: '上架审核夹具' }, owner.jar)).status === 200);
  const { createSellerFixture } = await import('../lib/e2e-seller-fixture.mjs');
  const fixture = await createSellerFixture({ phone: owner.phone, storeName: '上架审核夹具店', doRealname: true, callFn: call, jar: owner.jar });
  check('店铺直建成功', /^\d{15,}$/.test(fixture.merchantId), fixture.merchantId);
  // ★ 夹具改的是库里的角色，令牌仍是注册时签发的 USER 身份 → 必须重登换令牌
  await call('POST', '/api/auth/login', { phone: owner.phone, password: PWD }, owner.jar);
  const me = await call('GET', '/api/users/me', undefined, owner.jar);
  check('重登后角色为 SELLER', (me.data?.role ?? '') === 'SELLER', me.data?.role);

  // 运营登录（审核动作 + 重索引都走它）
  await call('POST', '/api/auth/login', { phone: OPERATOR_PHONE, password: PWD }, op.jar);

  // ---------------- S2 商家端 UI 发布 ----------------
  step('S2 商家端 UI：真实「发布商品」弹窗提交');
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
  const page = await ctx.newPage();
  const w = watch(page);
  await loginUI(page, MERCHANT_UI, owner.phone);
  await page.goto(`${MERCHANT_UI}/seller/products`, { waitUntil: 'networkidle' });
  await page.getByRole('button', { name: /发布商品/ }).click();
  await page.getByText('发布商品', { exact: true }).waitFor({ timeout: 10000 });

  // ★ E28 起：内容干净的商品会**自动放行**（不再进人工队列）。本套件测的是「审核台」，
  // 所以夹具必须**故意命中风险规则**（标题里放联系方式）→ 转人工 → 才落 PENDING。
  // 用「微信abc12345」：既命中 SpuRiskJudge 的 CONTACT_PATTERN（微信 + 4 位以上 \w），
  // 又保持标题主体是中文（后面多处断言按 title 变量匹配，不受影响）。
  // 若哪天这条不再转人工，下面第一条断言就会红 —— 那正是我们要的提醒。
  const title = `E14审核夹具商品-微信abc12345-${Date.now()}`;
  await page.locator('input[placeholder*="麦富迪"]').fill(title);
  // 类目 / 品牌是两个 <select>（顺序：类目在前）
  const selects = page.locator('.modal select, [role="dialog"] select');
  await selects.nth(0).selectOption(LEAF_CATEGORY);
  await selects.nth(1).selectOption(BRAND);
  await page.locator('input[placeholder*="图片地址"]').fill('/img/products/e14-audit.svg');
  await page.locator('input[placeholder="0.00"]').first().fill('199');
  await page.locator('input[placeholder="0"]').first().fill('20');
  await page.getByRole('button', { name: '提交审核' }).click();
  await page.getByText(/正在等待平台审核/).waitFor({ timeout: 10000 });
  check('发布成功并提示「等待平台审核」', true);
  await page.screenshot({ path: `${SHOTS}/e14_publish_done.png` });

  // 回填 id：从商家商品列表（HTTP 口径）取
  const mine = await call('GET', `/api/seller/products?keyword=${encodeURIComponent(title)}&page=1&pageSize=10`, undefined, owner.jar);
  const mineItem = mine.data?.items?.find((it) => it.title === title);
  spuId = mineItem?.id;
  check('新商品落「在售 + 待审核」', mineItem?.status === 'ON_SALE' && mineItem?.auditStatus === 'PENDING',
    { status: mineItem?.status, auditStatus: mineItem?.auditStatus });

  // 列表 UI 出现「待审核」标签
  await page.goto(`${MERCHANT_UI}/seller/products?auditStatus=PENDING`, { waitUntil: 'networkidle' });
  const row = page.getByRole('row', { name: new RegExp(title) }).first();
  await row.waitFor({ timeout: 10000 });
  check('商家列表「待审核」页签可见该商品', (await row.innerText()).includes('待审核'), await row.innerText());
  await page.screenshot({ path: `${SHOTS}/e14_merchant_pending.png` });

  // ---------------- S3 可见性（未过审） ----------------
  step('S3 可见性：未过审 → 公开详情 404 / 检索不命中');
  check('★ 公开详情 404（未过审视同不存在，防状态码探测）',
    (await call('GET', `/api/products/${spuId}`)).status === 404);
  // 先全量重建索引：让文档**确实进索引**（auditStatus=PENDING），
  // 这样"检索不含它"才是 ES 过滤在工作，而不是"它压根没进索引"
  const re1 = await call('POST', '/api/admin/search/reindex', undefined, op.jar);
  check('重索引成功（运营）', re1.status === 200, re1.status);
  const inIndex = (await mysql('duoduo_product',
    `SELECT COUNT(*) FROM t_spu WHERE id=${spuId} AND audit_status='PENDING';`)).trim();
  check('库内该商品审核态为 PENDING', inIndex === '1', inIndex);
  check('★ 检索不含未过审商品（索引有文档、被 auditStatus 过滤）',
    !(await searchTitles(title)).includes(title));

  // ---------------- S4 上架门禁 ----------------
  step('S4 上架门禁：待审商品不能自己上架');
  check('先下架成功', (await call('POST', `/api/seller/products/${spuId}/status`, { action: 'OFF_SHELF' }, owner.jar)).status === 200);
  const onSale = await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, owner.jar);
  check('★ 待审状态下重新上架 → 422', onSale.status === 422, onSale.status);
  check('门禁提示指向「审核通过后才能上架」', /审核/.test(onSale.json?.message ?? ''), onSale.json?.message);

  // ---------------- S5 后台 UI 审核台 → 通过 ----------------
  step('S5 后台 UI：审核台待审 → 通过');
  const ctxOp = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
  const pageOp = await ctxOp.newPage();
  const wOp = watch(pageOp);
  await loginUI(pageOp, ADMIN_UI, OPERATOR_PHONE);
  await pageOp.goto(`${ADMIN_UI}/admin/product-audits`, { waitUntil: 'networkidle' });
  const card = pageOp.locator('.card', { hasText: title }).first();
  await card.waitFor({ timeout: 10000 });
  check('审核台默认「待审核」页签可见该商品', await card.isVisible());
  check('卡片展示审核态徽标', (await card.innerText()).includes('待审核'), (await card.innerText()).slice(0, 120));
  await pageOp.screenshot({ path: `${SHOTS}/e14_admin_pending.png` });
  await card.getByRole('button', { name: '通过', exact: true }).click();
  await pageOp.getByText('已通过审核').waitFor({ timeout: 10000 });
  check('运营通过审核成功', true);
  await pageOp.screenshot({ path: `${SHOTS}/e14_admin_approved.png` });

  // ---------------- S6 通过后可见 ----------------
  step('S6 通过后：门禁放行 → 上架 → 公开详情 200 + 重索引后检索命中');
  // ★ S4 为了测门禁把这个商品手动下架了，而**审核通过不动 status**（尊重卖家的下架选择）——
  //   所以现在它仍是「下架 + 已通过」。先验证门禁已放行（能重新上架），再断言"上架后前台可见"。
  //   直接断言"通过即可见"会 404，那是**测试前提错了**，不是缺陷。
  const reopen = await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, owner.jar);
  check('★ 通过后上架门禁放行（200）', reopen.status === 200, reopen.status);
  const d200 = await call('GET', `/api/products/${spuId}`);
  check('★ 上架后公开详情 200', d200.status === 200, d200.status);
  check('详情标题与提交一致', d200.data?.card?.title === title, d200.data?.card?.title);
  await call('POST', '/api/admin/search/reindex', undefined, op.jar);
  check('★ 重索引后检索命中该商品', (await searchTitles(title)).includes(title));

  // ---------------- S7 内容变更再审 ----------------
  step('S7 内容变更再审：改标题 → 回 PENDING → 前台再次不可见');
  const newTitle = `${title}-改`;
  const upd = await call('PUT', `/api/seller/products/${spuId}`, { title: newTitle }, owner.jar);
  check('编辑商品成功（200）', upd.status === 200, upd.status);
  check('★ 内容变更后审核态回 PENDING', upd.data?.auditStatus === 'PENDING', upd.data?.auditStatus);
  check('★ 变更后公开详情再次 404', (await call('GET', `/api/products/${spuId}`)).status === 404);
  await call('POST', '/api/admin/search/reindex', undefined, op.jar);
  check('★ 变更后检索也不再命中', !(await searchTitles(newTitle)).includes(newTitle));

  // ---------------- S8 驳回链路 ----------------
  step('S8 驳回链路：运营 UI 驳回 → 商家 UI 重提 → 回待审');
  await pageOp.reload({ waitUntil: 'networkidle' });
  const card2 = pageOp.locator('.card', { hasText: newTitle }).first();
  await card2.waitFor({ timeout: 10000 });
  await card2.getByRole('button', { name: '驳回', exact: true }).click();
  const modal = pageOp.locator('.modal, [role="dialog"]').first();
  await modal.locator('textarea').fill('主图与类目不符，请更换合规主图后重新提交');
  await pageOp.getByRole('button', { name: '确认驳回' }).click();
  await pageOp.getByText('已驳回').first().waitFor({ timeout: 10000 });
  check('运营驳回成功', true);
  await pageOp.screenshot({ path: `${SHOTS}/e14_admin_rejected.png` });

  // 商家视角：看到驳回原因 → 点「重新提交」
  await page.goto(`${MERCHANT_UI}/seller/products?auditStatus=REJECTED`, { waitUntil: 'networkidle' });
  const rowR = page.getByRole('row', { name: new RegExp(newTitle) }).first();
  await rowR.waitFor({ timeout: 10000 });
  const rowText = await rowR.innerText();
  check('★ 商家列表可见「已驳回」+ 驳回原因', rowText.includes('已驳回') && rowText.includes('主图与类目不符'), rowText);
  await rowR.getByRole('button', { name: '重新提交' }).click();
  await page.getByText(/已重新提交/).waitFor({ timeout: 10000 });
  const afterResubmit = await call('GET', `/api/seller/products/${spuId}`, undefined, owner.jar);
  check('★ 重提后审核态为 PENDING', afterResubmit.data?.auditStatus === 'PENDING', afterResubmit.data?.auditStatus);
  check('PENDING 阶段公开详情仍 404', (await call('GET', `/api/products/${spuId}`)).status === 404);

  // 二审通过 → 前台可见（真闭环）
  const approve2 = await call('POST', `/api/admin/products/${spuId}/audit`, { action: 'APPROVE' }, op.jar);
  check('二审通过', approve2.status === 200 && approve2.data?.auditStatus === 'APPROVED', approve2.data?.auditStatus);
  check('★ 闭环：二审通过后公开详情 200', (await call('GET', `/api/products/${spuId}`)).status === 200);

  // ---------------- S9 审核台边界 ----------------
  step('S9 审核台边界：队列只含第三方商品（自营免审）');
  const queue = await call('GET', '/api/admin/products?page=1&pageSize=50', undefined, op.jar);
  check('审核台可访问且返回列表', queue.status === 200 && Array.isArray(queue.data?.items), queue.status);
  check('★ 队列里没有自营商品（supplyType 全为 merchant）',
    (queue.data?.items ?? []).every((it) => it.supplyType === 'merchant'),
    (queue.data?.items ?? []).map((it) => it.supplyType).filter((t) => t !== 'merchant'));
  const anon = await fetch(`${GW}/api/admin/products`);
  check('★ 未登录访问审核台 → 401', anon.status === 401, anon.status);

  // ---------------- S10 UI 卫生 ----------------
  step('S10 UI 卫生：无 pageerror / 5xx');
  check('商家端会话无 pageerror / 5xx', w.httpErrors.length === 0, w.httpErrors);
  check('后台会话无 pageerror / 5xx', wOp.httpErrors.length === 0, wOp.httpErrors);

  await ctx.close();
  await ctxOp.close();
} finally {
  await browser.close();
  // ---------------- 收尾 ----------------
  // ★ 留在库里的一条 PENDING 商品会永久占用审核队列（E26 教训的同型问题：
  //   "测试造出来的东西不收尾，会伪装成真实待办"）。SKU 无级联删除，按序清。
  if (spuId) {
    try {
      await mysql('duoduo_product', `DELETE FROM t_sku WHERE spu_id=${spuId};`);
      await mysql('duoduo_product', `DELETE FROM t_spu WHERE id=${spuId};`);
      const remain = (await mysql('duoduo_product',
        `SELECT COUNT(*) FROM t_spu WHERE id=${spuId};`)).trim();
      check('收尾：夹具商品已清理', remain === '0', remain);
    } catch (e) {
      console.log(`  [WARN] 夹具清理失败（需人工核查 spuId=${spuId}）：${e.message}`);
    }
  }
}

console.log(`\n=== 商品上架审核自测：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log('  - ' + f);
}
process.exit(failures.length ? 1 : 0);

/**
 * 资质有效期与续期 · 真实浏览器 UI 自测（E15，**自包含**）
 *
 * 覆盖总纲交付项：**临期提醒（30/7 天）→ 过期自动停业 + 商品联动下架 → 上架门禁 → 续期 → 恢复营业**。
 *
 *   S1 夹具：卖家注册 + 库内直建 APPROVED 店铺 + 重登换令牌；运营登录
 *   S2 长期有效基线：NULL ≠ 已过期 —— 不得出现任何「即将到期/已停业」提示
 *   S3 T30 档：材料有效期进入 30 天窗口 → 商家端横幅 + 后台列表标「即将到期」
 *      → 巡检发出一条提醒（含材料名）+ 记档 T30 → **再巡检不重复发**（去重）
 *   S4 T7 档：进入 7 天窗口 → 升档再发一条（【紧急】），共 2 条
 *   S5 过期停业：巡检 → status=SUSPENDED + 原因留痕 + 审计日志 + **在售商品联动下架**
 *      → 上架被拦 422 → 后台列表/详情标「已停业」+ 原因 → 商家端停业横幅
 *   S6 续期不自动通过：商家端提交新有效期 → 回 SUBMITTED，**上架仍被拦**（判据是状态而非"没过期"）
 *   S7 续期通过 → 恢复营业：运营 start-review + approve → 上架放行、档位归零
 *   S8 续期拒绝"仍在过去"的有效期：不产生任何状态变更
 *   S9 UI 卫生：无 pageerror / 5xx
 *
 * 口径：
 *   · **到期日与档位都由服务端算**，测试只构造「材料有效期」这一个输入，
 *     再去断言服务端得出的档位/提醒/状态 —— 不自己重算一遍日期，否则测的是测试自己。
 *   · **提醒去重靠档位**：断言「第二次巡检 reminded=0 且通知条数不变」，
 *     而不是断言 reminded 恰好等于几 —— 定时任务也可能在这一瞬间跑过同一批数据
 *     （每 6 小时一轮），钉住"不重复打扰"这个性质才不会被调度时序搞成偶发红。
 *   · 巡检走**运营手动入口**（`POST /api/admin/merchants/license-sweep`）：
 *     定时任务 6 小时一轮，等它的话这个套件得跑半天。该入口与定时器共用 sweepOnce，
 *     所以测到的就是生产那条路径。
 *   · 库内直改**仅用于构造输入**（写材料有效期 / 伪造过期），所有断言都走
 *     HTTP 或真实浏览器 —— 直改派生列是为了与"服务端维护的派生列"保持一致，
 *     否则后台列表（读派生列）会显示旧值，看起来像缺陷。
 *
 * 前置：10 容器在跑（含 duoduo-social、duoduo-product）。
 * 用法：node scripts/e2e/merchant-license-ui-check.mjs
 */

import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
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

/** 库内已有的叶子类目 / 品牌（硬编码会随种子变更失效，这里用 product-audit 套件同一对） */
const LEAF_CATEGORY = 101;
const BRAND = 1;

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
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (jar && jar.size) headers.cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
  const res = await fetch((p.startsWith('http') ? '' : (base ?? GW)) + p, {
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

const randPhone = () => '137' + String(Math.floor(1e7 + Math.random() * 8e7));

/** UTC 日历日的日期串；与后端 LicenseExpiry.remainingDays（日历日、UTC）同口径 */
const utcDate = (offsetDays) =>
  new Date(Date.now() + offsetDays * 86400000).toISOString().slice(0, 10);
/** 落库口径：到期日含当日有效 → 23:59:59（存 00:00:00 会让商家在到期日零点被停业） */
const utcStamp = (offsetDays) => `${utcDate(offsetDays)} 23:59:59`;

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

/** 商家侧通知（收件人数据，必须带店主 Cookie） */
const merchantNotices = async (jar) => {
  const r = await call('GET', '/api/socials/notices?category=MERCHANT&page=1&pageSize=50', undefined, jar);
  return r.data?.items ?? [];
};

/** 运营手动触发一轮资质巡检 */
const sweep = async (opJar) => call('POST', '/api/admin/merchants/license-sweep', undefined, opJar);

const owner = { phone: randPhone(), jar: new Map() };
const op = { jar: new Map() };
let merchantId = null;
let ownerUserId = null;
let spuId = null;
const materialId = () => (BigInt(Date.now()) * 1000n + 7n).toString();

try {
  // ---------------- S1 夹具 ----------------
  step('S1 夹具：卖家注册 + 库内直建 APPROVED 店铺 + 重登换令牌');
  check('卖家注册', (await call('POST', '/api/auth/register',
    { phone: owner.phone, password: PWD, nickname: '资质有效期夹具' }, owner.jar)).status === 200);
  const { createSellerFixture } = await import('../lib/e2e-seller-fixture.mjs');
  const fixture = await createSellerFixture({
    phone: owner.phone, storeName: '资质有效期夹具店', doRealname: true, callFn: call, jar: owner.jar,
  });
  merchantId = fixture.merchantId;
  check('店铺直建成功', /^\d{15,}$/.test(merchantId), merchantId);
  // ★ 夹具改的是库里的角色，令牌仍是注册时签发的 USER 身份 → 必须重登换令牌
  await call('POST', '/api/auth/login', { phone: owner.phone, password: PWD }, owner.jar);
  const me = await call('GET', '/api/users/me', undefined, owner.jar);
  check('重登后角色为 SELLER', (me.data?.role ?? '') === 'SELLER', me.data?.role);
  ownerUserId = (await mysql('duoduo_user',
    `SELECT id FROM t_user WHERE phone='${owner.phone}';`)).trim();
  check('拿到店主 userId', /^\d{15,}$/.test(ownerUserId), ownerUserId);

  // 运营登录（巡检动作走它）
  await call('POST', '/api/auth/login', { phone: OPERATOR_PHONE, password: PWD }, op.jar);

  const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
  const page = await ctx.newPage();
  const w = watch(page);
  await loginUI(page, MERCHANT_UI, owner.phone);

  // ---------------- S2 长期有效基线 ----------------
  step('S2 基线：材料未填有效期 = 长期有效，不得出现任何到期提示');
  const base = await call('GET', '/api/users/me/merchant-application', undefined, owner.jar);
  check('店铺状态 APPROVED', base.data?.status === 'APPROVED', base.data?.status);
  check('★ 无到期日 → licenseExpireAt 为 null（NULL 不是"已过期"）',
    base.data?.licenseExpireAt === null, base.data?.licenseExpireAt);
  check('档位为 NONE', base.data?.licenseStage === 'NONE', base.data?.licenseStage);
  check('剩余天数为 null（不是 0）', base.data?.licenseRemainingDays === null,
    base.data?.licenseRemainingDays);

  await page.goto(`${MERCHANT_UI}/apply`, { waitUntil: 'networkidle' });
  check('商家端显示「资质长期有效」',
    await page.getByText(/资质长期有效/).first().isVisible().catch(() => false));
  check('★ 基线不得出现「即将到期」横幅',
    (await page.getByText(/资质即将到期|资质将于.*到期/).count()) === 0);
  check('★ 基线不得出现「店铺已停业」横幅',
    (await page.getByText(/店铺已停业/).count()) === 0);
  await page.screenshot({ path: `${SHOTS}/e15_s2_longterm.png` });

  // 构造输入：写一份带 20 天后有效期的营业执照（同时对齐派生列，理由见文件头）
  await mysql('duoduo_user', `
INSERT INTO t_merchant_material (id, merchant_id, material_type, file_url, expire_at, original_name, uploaded_by, created_at, updated_at)
VALUES (${materialId()}, ${merchantId}, 'BUSINESS_LICENSE',
        '/api/merchant-materials/${merchantId}/e15-fixture.png', '${utcStamp(20)}', 'e15-fixture.png',
        ${ownerUserId}, UTC_TIMESTAMP(), UTC_TIMESTAMP());`);
  await mysql('duoduo_user',
    `UPDATE t_merchant SET license_expire_at='${utcStamp(20)}', expire_notify_stage=NULL WHERE id=${merchantId};`);

  // ---------------- S3 T30 档 ----------------
  step('S3 T30 档：进入 30 天窗口 → 横幅 + 后台列 + 发一条提醒 + 记档 + 去重');
  const t30 = await call('GET', '/api/users/me/merchant-application', undefined, owner.jar);
  check('★ 服务端算出档位 T30', t30.data?.licenseStage === 'T30', t30.data?.licenseStage);
  check('剩余天数为 20', t30.data?.licenseRemainingDays === 20, t30.data?.licenseRemainingDays);
  check('材料有效期回填为可编辑日期串',
    t30.data?.materialExpiry?.BUSINESS_LICENSE === utcDate(20),
    t30.data?.materialExpiry);

  await page.reload({ waitUntil: 'networkidle' });
  check('商家端出现「即将到期」提示',
    await page.getByText(/即将到期|将于.*到期/).first().isVisible().catch(() => false));
  check('提示里说清是哪张证（营业执照）',
    (await page.getByText(/营业执照/).count()) > 0);
  await page.screenshot({ path: `${SHOTS}/e15_s3_t30_banner.png` });

  const sweep1 = await sweep(op.jar);
  check('运营手动巡检可调用（200）', sweep1.status === 200, sweep1.status);
  check('巡检报告字段齐全',
    sweep1.data && typeof sweep1.data.suspended === 'number' && typeof sweep1.data.reminded === 'number',
    sweep1.data);
  const stage1 = (await mysql('duoduo_user',
    `SELECT COALESCE(expire_notify_stage,'NULL') FROM t_merchant WHERE id=${merchantId};`)).trim();
  check('★ 已记提醒档位 T30', stage1 === 'T30', stage1);

  const notices1 = await merchantNotices(owner.jar);
  const expiring1 = notices1.filter((n) => n.bizType === 'MERCHANT_LICENSE_EXPIRING');
  check('★ 店主收到 1 条临期提醒（跨域通知通道打通）', expiring1.length === 1, expiring1.length);
  check('提醒标题含材料名与剩余天数',
    /营业执照/.test(expiring1[0]?.title ?? '') && /20 天/.test(expiring1[0]?.title ?? ''),
    expiring1[0]?.title);
  check('提醒正文含到期日与续期指引',
    (expiring1[0]?.content ?? '').includes(utcDate(20)) && /续期/.test(expiring1[0]?.content ?? ''),
    expiring1[0]?.content);
  check('提醒不带小程序落地页（续期在商家端网页办，留空是刻意的）',
    (expiring1[0]?.jumpPage ?? null) === null, expiring1[0]?.jumpPage);

  const sweep2 = await sweep(op.jar);
  const notices2 = await merchantNotices(owner.jar);
  check('★ 同档位不重复提醒（reminded=0）', sweep2.data?.reminded === 0, sweep2.data?.reminded);
  check('★ 通知条数不变（还是 1 条）',
    notices2.filter((n) => n.bizType === 'MERCHANT_LICENSE_EXPIRING').length === 1,
    notices2.filter((n) => n.bizType === 'MERCHANT_LICENSE_EXPIRING').length);

  // 后台审核台：列表要能一眼看出临期
  const ctxOp = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
  const pageOp = await ctxOp.newPage();
  const wOp = watch(pageOp);
  await loginUI(pageOp, ADMIN_UI, OPERATOR_PHONE);
  await pageOp.goto(`${ADMIN_UI}/admin/merchants`, { waitUntil: 'networkidle' });
  await pageOp.getByRole('button', { name: '已通过', exact: true }).click();
  const rowT30 = pageOp.getByRole('row', { name: new RegExp(fixture.storeName) }).first();
  await rowT30.waitFor({ timeout: 10000 });
  const rowT30Text = await rowT30.innerText();
  check('★ 后台列表「资质」列标出即将到期 + 到期日',
    rowT30Text.includes('即将到期') && rowT30Text.includes(utcDate(20)), rowT30Text);
  await pageOp.screenshot({ path: `${SHOTS}/e15_s3_admin_t30.png` });

  const adminDetail = await call('GET', `/api/admin/merchants/${merchantId}`, undefined, op.jar);
  check('审核台详情回填材料级有效期（供运营逐份比对证件）',
    adminDetail.data?.materialExpiry?.BUSINESS_LICENSE === utcDate(20),
    adminDetail.data?.materialExpiry);

  // ---------------- S4 T7 档 ----------------
  step('S4 T7 档：进入 7 天窗口 → 升档再发一条（【紧急】）');
  await mysql('duoduo_user',
    `UPDATE t_merchant SET license_expire_at='${utcStamp(3)}' WHERE id=${merchantId};`);
  await mysql('duoduo_user',
    `UPDATE t_merchant_material SET expire_at='${utcStamp(3)}' WHERE merchant_id=${merchantId} AND material_type='BUSINESS_LICENSE';`);
  const sweep3 = await sweep(op.jar);
  check('★ 升档后确实又发了一条（reminded>=1）', (sweep3.data?.reminded ?? 0) >= 1, sweep3.data?.reminded);
  const stage2 = (await mysql('duoduo_user',
    `SELECT COALESCE(expire_notify_stage,'NULL') FROM t_merchant WHERE id=${merchantId};`)).trim();
  check('★ 档位升到 T7', stage2 === 'T7', stage2);
  const notices3 = await merchantNotices(owner.jar);
  const expiring3 = notices3.filter((n) => n.bizType === 'MERCHANT_LICENSE_EXPIRING');
  check('★ 累计 2 条提醒（30 档一条 + 7 档一条）', expiring3.length === 2, expiring3.length);
  check('7 天档标题为【紧急】', expiring3.some((n) => /【紧急】/.test(n.title)), expiring3.map((n) => n.title));

  // ---------------- S5 过期停业 ----------------
  step('S5 过期自动停业：状态 + 原因 + 审计 + 商品联动下架 + 上架门禁');
  // 先建一个在售商品：停业要联动下架，没有商品就测不到这条连锁
  const created = await call('POST', '/api/seller/products', {
    title: `E15资质夹具商品-${Date.now()}`,
    categoryId: LEAF_CATEGORY,
    brandId: BRAND,
    mainImage: '/img/products/e15-license.svg',
    skus: [{ specName: '默认', price: 19900, stock: 20 }],
  }, owner.jar);
  check('卖家新建商品成功', created.status === 200, created.status);
  spuId = created.data?.id;
  check('新商品初始为在售', created.data?.status === 'ON_SALE', created.data?.status);
  // ★ 必须先把**商品审核**过掉，否则上架会先被 E14 那道门禁拦下（提示「正在审核中」），
  //   本套件要验的是 E15 资质那道门 —— 两道门都在，得先把不测的那道放行，
  //   否则一个断言红掉时无法区分是哪道门在生效（这正是产品代码里"两道门禁"的固有歧义）。
  const audit = await call('POST', `/api/admin/products/${spuId}/audit`, { action: 'APPROVE' }, op.jar);
  check('运营先通过商品审核（隔离出资质这道门）',
    audit.status === 200 && audit.data?.auditStatus === 'APPROVED', audit.data?.auditStatus);

  await mysql('duoduo_user',
    `UPDATE t_merchant SET license_expire_at='${utcStamp(-1)}' WHERE id=${merchantId};`);
  await mysql('duoduo_user',
    `UPDATE t_merchant_material SET expire_at='${utcStamp(-1)}' WHERE merchant_id=${merchantId} AND material_type='BUSINESS_LICENSE';`);
  const sweep4 = await sweep(op.jar);
  check('★ 巡检停业了 1 家（过期即停）', (sweep4.data?.suspended ?? 0) >= 1, sweep4.data);
  check('★ 商品联动下架未失败（跨域调用成功）', sweep4.data?.offShelfFailed === 0, sweep4.data?.offShelfFailed);

  const afterSuspend = await mysql('duoduo_user',
    `SELECT status, COALESCE(suspended_reason,'NULL'), COALESCE(expire_notify_stage,'NULL') FROM t_merchant WHERE id=${merchantId};`).then((s) => s.trim().split('\t'));
  check('★ 状态变为 SUSPENDED', afterSuspend[0] === 'SUSPENDED', afterSuspend[0]);
  check('★ 停业原因写清「已过期 + 到期日」',
    /已过期/.test(afterSuspend[1] ?? '') && afterSuspend[1].includes(utcDate(-1)), afterSuspend[1]);
  check('档位记为 EXPIRED', afterSuspend[2] === 'EXPIRED', afterSuspend[2]);

  const spuStatus = (await mysql('duoduo_product',
    `SELECT status FROM t_spu WHERE id=${spuId};`)).trim();
  check('★ 该店在售商品被联动下架（ON_SALE → OFF_SHELF）', spuStatus === 'OFF_SHELF', spuStatus);

  const auditRow = (await mysql('duoduo_user',
    `SELECT COUNT(*) FROM t_audit_log WHERE action='MERCHANT_LICENSE_SUSPEND' AND target_id=${merchantId};`)).trim();
  check('★ 平台单方面停业留有审计记录', auditRow === '1', auditRow);

  const suspendNotices = (await merchantNotices(owner.jar))
    .filter((n) => n.bizType === 'MERCHANT_LICENSE_EXPIRED');
  check('店主收到 1 条「已停业」通知', suspendNotices.length === 1, suspendNotices.length);
  check('停业通知说清原因与下一步（续期）',
    /已过期/.test(suspendNotices[0]?.title ?? '')
      && (suspendNotices[0]?.content ?? '').includes(utcDate(-1))
      && /续期/.test(suspendNotices[0]?.content ?? '')
      && /已售出订单不受影响/.test(suspendNotices[0]?.content ?? ''),
    { title: suspendNotices[0]?.title, content: suspendNotices[0]?.content });

  const gate = await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, owner.jar);
  check('★ 停业店铺不能重新上架（422）', gate.status === 422, gate.status);
  check('门禁提示是中文原因而不是错误码',
    /停业|资质/.test(gate.json?.message ?? ''), gate.json?.message);

  // 商家端横幅 + 后台列表/详情
  await page.goto(`${MERCHANT_UI}/apply`, { waitUntil: 'networkidle' });
  check('★ 商家端显示「店铺已停业」横幅', await page.getByText(/店铺已停业/).first().isVisible().catch(() => false));
  check('横幅带停业原因', (await page.getByText(/经营资质已过期/).count()) > 0);
  check('横幅说明已售出订单不受影响',
    (await page.getByText(/已售出订单不受影响/).count()) > 0);
  await page.screenshot({ path: `${SHOTS}/e15_s5_merchant_suspended.png` });

  // 工作台（商家每天第一眼看到的页）也必须给出同一个结论 —— 只有一个页面提示
  // 等于「商家不点进申请页就不知道店停了」
  await page.goto(`${MERCHANT_UI}/seller`, { waitUntil: 'networkidle' });
  check('★ 商家工作台横幅同样告知已停业',
    await page.getByText(/店铺已停业/).first().isVisible().catch(() => false));
  check('工作台横幅提供续期入口',
    (await page.getByRole('link', { name: /去提交续期|立即续期|提前续期/ }).count()) > 0);
  await page.screenshot({ path: `${SHOTS}/e15_s5_merchant_dashboard.png` });

  await pageOp.goto(`${ADMIN_UI}/admin/merchants`, { waitUntil: 'networkidle' });
  await pageOp.getByRole('button', { name: '全部', exact: true }).click();
  const rowSus = pageOp.getByRole('row', { name: new RegExp(fixture.storeName) }).first();
  await rowSus.waitFor({ timeout: 10000 });
  const rowSusText = await rowSus.innerText();
  check('★ 后台列表状态标「已停业」', rowSusText.includes('已停业'), rowSusText);
  check('★ 后台列表直接给出停业原因（运营不必点开详情）',
    rowSusText.includes('经营资质已过期'), rowSusText);
  await pageOp.screenshot({ path: `${SHOTS}/e15_s5_admin_suspended.png` });

  // ---------------- S6 续期不自动通过 ----------------
  step('S6 续期：提交新有效期 → 回 SUBMITTED，但**上架仍被拦**');
  const future = utcDate(365);
  // ★ 必须先回到申请页：上一节为了验工作台横幅离开了它，留在概览页上找日期框会直接超时
  await page.goto(`${MERCHANT_UI}/apply`, { waitUntil: 'networkidle' });
  await page.locator('input[type="date"]').first().fill(future);
  await page.getByRole('button', { name: /提交续期申请/ }).click();
  await page.getByText(/续期材料已提交/).waitFor({ timeout: 10000 });
  check('商家端提交续期成功', true);
  await page.screenshot({ path: `${SHOTS}/e15_s6_renewal_submitted.png` });

  const renewed = await call('GET', '/api/users/me/merchant-application', undefined, owner.jar);
  check('★ 续期后状态回 SUBMITTED（不是直接 APPROVED）', renewed.data?.status === 'SUBMITTED', renewed.data?.status);
  check('续期后到期日更新为一年后', (renewed.data?.licenseExpireAt ?? '').startsWith(future),
    renewed.data?.licenseExpireAt);
  const cleared = (await mysql('duoduo_user',
    `SELECT COALESCE(expire_notify_stage,'NULL'), COALESCE(suspended_reason,'NULL') FROM t_merchant WHERE id=${merchantId};`))
    .trim().split('\t');
  check('★ 新周期提醒档位清零（下轮重新计数）', cleared[0] === 'NULL', cleared[0]);
  check('进入续期审核后停业原因不再展示', cleared[1] === 'NULL', cleared[1]);

  const gate2 = await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, owner.jar);
  check('★ 续期审核中仍不能上架（判据是 status==APPROVED，不是"没过期"）',
    gate2.status === 422, gate2.status);
  check('此时拒绝理由指向「资质审核中」而不是「已停业」',
    /资质审核中/.test(gate2.json?.message ?? ''), gate2.json?.message);

  // ---------------- S7 续期通过 → 恢复营业 ----------------
  step('S7 续期通过 → 恢复营业 + 上架放行');
  const startReview = await call('POST', `/api/admin/merchants/${merchantId}/start-review`, undefined, op.jar);
  check('运营开始审核', startReview.status === 200, startReview.status);
  const approved = await call('POST', `/api/admin/merchants/${merchantId}/approve`, undefined, op.jar);
  check('运营通过 → APPROVED', approved.data?.status === 'APPROVED', approved.data?.status);
  const gate3 = await call('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, owner.jar);
  check('★ 恢复营业后可重新上架（200）', gate3.status === 200, gate3.status);
  const stage3 = await call('GET', '/api/users/me/merchant-application', undefined, owner.jar);
  check('档位回到 NONE（有效期还远）', stage3.data?.licenseStage === 'NONE', stage3.data?.licenseStage);

  // ---------------- S8 拒绝"仍在过去"的续期 ----------------
  step('S8 续期拒绝仍在过去的有效期（不产生状态变更）');
  await page.goto(`${MERCHANT_UI}/apply`, { waitUntil: 'networkidle' });
  await page.locator('input[type="date"]').first().fill(utcDate(-30));
  await page.getByRole('button', { name: /提交续期申请/ }).click();
  await page.getByText(/仍在过去/).waitFor({ timeout: 10000 });
  check('★ 提交过去日期被拒并给出可执行提示', true);
  await page.screenshot({ path: `${SHOTS}/e15_s8_past_rejected.png` });
  const stillApproved = (await mysql('duoduo_user',
    `SELECT status FROM t_merchant WHERE id=${merchantId};`)).trim();
  check('★ 被拒后状态未变（仍 APPROVED，没有半途进入审核）', stillApproved === 'APPROVED', stillApproved);
  // 拒的是「写进材料后又发现仍在过去」这种情况，所以必须验证那次写入被回滚 ——
  // 不回滚的话库里就留着一份刚提交的过期证件，而界面显示的还是旧日期（两边不一致且无人察觉）
  const rolledBack = (await mysql('duoduo_user',
    `SELECT expire_at FROM t_merchant_material WHERE merchant_id=${merchantId} AND material_type='BUSINESS_LICENSE';`)).trim();
  check('★ 被拒时材料写入已回滚（有效期仍是上次成功提交的值）',
    rolledBack.startsWith(future), rolledBack);

  // ---------------- S9 UI 卫生 ----------------
  step('S9 UI 卫生：无 pageerror / 5xx');
  check('商家端会话无 5xx', w.httpErrors.length === 0, w.httpErrors);
  check('商家端无 pageerror', w.consoleErrors.filter((e) => e.startsWith('pageerror')).length === 0,
    w.consoleErrors.filter((e) => e.startsWith('pageerror')));
  check('后台会话无 5xx', wOp.httpErrors.length === 0, wOp.httpErrors);
  check('后台无 pageerror', wOp.consoleErrors.filter((e) => e.startsWith('pageerror')).length === 0,
    wOp.consoleErrors.filter((e) => e.startsWith('pageerror')));

  await ctx.close();
  await ctxOp.close();
} finally {
  await browser.close();
  // ---------------- 收尾 ----------------
  // ★ 本套件会在库里留下一个「已停业/临期」的店铺与一条在售商品。不清理的话：
  //   ① 审核台列表里多出一家看起来需要处理的店（E26 教训的同型问题：测试造的东西
  //      会伪装成真实待办）；② 下一轮全量回归的巡检计数里混进它。
  if (merchantId) {
    try {
      if (spuId) {
        await mysql('duoduo_product', `DELETE FROM t_sku WHERE spu_id=${spuId};`);
        await mysql('duoduo_product', `DELETE FROM t_spu WHERE id=${spuId};`);
      }
      await mysql('duoduo_user', `DELETE FROM t_merchant_material WHERE merchant_id=${merchantId};`);
      await mysql('duoduo_user', `DELETE FROM t_merchant_member WHERE merchant_id=${merchantId};`);
      await mysql('duoduo_user', `DELETE FROM t_merchant WHERE id=${merchantId};`);
      await mysql('duoduo_user', `DELETE FROM t_audit_log WHERE action='MERCHANT_LICENSE_SUSPEND' AND target_id=${merchantId};`);
      if (ownerUserId) {
        await mysql('duoduo_social', `DELETE FROM t_social_notice WHERE user_id=${ownerUserId};`);
        await mysql('duoduo_social', `DELETE FROM t_social_notice_read WHERE user_id=${ownerUserId};`);
      }
      const remain = (await mysql('duoduo_user',
        `SELECT COUNT(*) FROM t_merchant WHERE id=${merchantId};`)).trim();
      check('收尾：夹具店铺已清理', remain === '0', remain);
    } catch (e) {
      console.log(`  [WARN] 夹具清理失败（需人工核查 merchantId=${merchantId}）：${e.message}`);
    }
  }
}

console.log(`\n=== 资质有效期与续期自测：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log('  - ' + f);
}
process.exit(failures.length ? 1 : 0);

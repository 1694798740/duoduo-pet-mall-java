/**
 * 审核 SLA 事后抽查 · 真实浏览器 UI 自测（E28，**自包含**）
 *
 * 覆盖总纲交付项：**抽查队列 → 复核判定 → 指标自证 → 两域切换 → 权限边界**。
 *
 *   S1 夹具：卖家（注册 + 库内直建 APPROVED 店铺 + 重登换令牌）+ 经接口发布一件干净商品
 *            （自动放行 → audit_at 有值，这是抽查候选集的唯一锚点）
 *   S2 直插两条 PENDING 抽查任务（商品域 1 条 / 入驻域 1 条）——**并回读复核**
 *   S3 后台 UI：抽查页可达；默认域「第三方商品」；四个状态页签齐全；六张指标卡都渲染出数值
 *   S4 队列：能看到本次任务（标题 + 抽中理由中文说明 + 抽中批次）
 *   S5 复核弹窗真守卫：未填依据时「判定违规并处置」**禁用**（不是样式问题，是拦真处置）
 *   S6 判定合规：toast「已判定合规」→ 切「复核通过」页签能看到该行 + 徽标 + 处理人 + 耗时
 *   S7 域切换（入驻店铺）：队列能看到店铺名；弹窗文案切换为「停业」口径（两域差异）
 *   S8 手动触发入口：「立即抽查（昨天那批）」→ toast 报出 候选/新建/幂等跳过/比例
 *   S9 权限边界（**两级都断言**）：后台登录页拒绝非运营账号；会话在场但非运营 → 页面级拒绝
 *   S10 UI 卫生：无 pageerror / 5xx
 *
 * 口径：
 *   · 审核动作用**主运营账号**登录后台（E8：ent=admin 才调得动 /api/admin/**）——
 *     不新建 OPERATOR，避免"测试收尾漏降权"再攒出永久后台入口（E26 根因）
 *   · **队列断言不赌概率**：sweep 抽的是**昨天**那批自动放行商品，而夹具商品是今天放行的，
 *     所以「等 sweep 抽中我」必然空转。夹具改为直插 PENDING 任务，把「任务 → 复核」这条
 *     确定为可测；抽样规则本身由 AuditSlaPolicyTest（35 例）覆盖。
 *   · **队列断言不赌库里恰好有多少条**：待复核按 created_at 升序、每页 10 条，
 *     库内若已有 ≥10 条 PENDING，直插的行会落到第二页 —— 那时断言会红，
 *     但它红的原因是「别人刚好有存量」而不是缺陷。所以插入时把 created_at 设成
 *     「早于当前最老的一条 PENDING 1 秒」（库内没有 PENDING 时就用现在），
 *     这样不管存量多少，本行的位置都是确定的。
 *   · 收尾删掉本套件造的两条任务、商品（SPU+SKU）、店铺与成员，并逐项回读自证。
 *     卖家账号是 SELLER（无后台入口），与 seller / aftersale 套件同口径不清理。
 *
 * 前置：容器在跑（含 duoduo-admin-web）；后台站点可达（bash scripts/web-up.sh）。
 * 用法：node scripts/e2e/audit-sla-ui-check.mjs
 */

import path from 'node:path';
import { shotsDir } from '../lib/e2e-shots.mjs';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
/** 平台管理后台**独立站点**：浏览器用主机名（Cookie 作用域），fetch 用 127.0.0.1 + 端口 */
const ADMIN_UI = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
/** 商家端**独立站点**（E6）：发布商品走它 */
const MERCHANT_UI = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const SHOTS = shotsDir(); // 默认落到 gitignored 的 scripts/e2e/.shots；要归档配图用 SHOTS=<路径> 覆盖
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? undefined;
const PWD = 'duoduo12345';
const OPERATOR_PHONE = '13684295254';

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

const randPhone = () => '138' + String(Math.floor(1e7 + Math.random() * 8e7));

/**
 * 抽查任务 id：走**专属段位 94**（SPU 用 97 / 闲置 98 / SKU 99）。
 * 16 位、大于雪花量级下界，便于「按 id 前缀一眼认出是测试造的」并在收尾时兜底清理。
 */
let sampleSeq = 0;
const sampleId = () => `94${String(Date.now()).slice(-13)}${++sampleSeq % 10}`;

const todayUtc = () => new Date().toISOString().slice(0, 10);

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

const created = { spuId: null, skuIds: [], sampleSpu: null, sampleMerchant: null, merchantId: null, memberIds: [] };
const owner = { phone: randPhone(), jar: new Map() };
const op = { jar: new Map() };

try {
  // ---------------- S1 夹具 ----------------
  step('S1 夹具：卖家注册 + 直建 APPROVED 店铺 + 重登 + 发布一件干净商品');
  const reg = await call('POST', '/api/auth/register',
    { phone: owner.phone, password: PWD, nickname: '抽查复核夹具' }, owner.jar);
  check('卖家注册', reg.status === 200, { status: reg.status, msg: reg.json?.message });
  const { createSellerFixture } = await import('../lib/e2e-seller-fixture.mjs');
  const fixture = await createSellerFixture({
    phone: owner.phone, storeName: '抽查复核夹具店', doRealname: true, callFn: call, jar: owner.jar,
  });
  created.merchantId = fixture.merchantId;
  check('夹具店铺建成（直建 APPROVED）', /^\d{15,}$/.test(fixture.merchantId), fixture.merchantId);
  // 建不起来就显式报红：否则后面「队列里有它」会因库内本来就是 0 条而静默通过
  const mchCheck = (await mysql('duoduo_user',
    `SELECT CONCAT(status,'|',IFNULL(owner_user_id,'NULL')) FROM t_merchant WHERE id=${fixture.merchantId};`)).trim();
  check('夹具回读：店铺存在且 APPROVED', mchCheck.startsWith('APPROVED|'), mchCheck);

  // ★ 角色是 access token 里的快照：改库之后必须重登才生效
  await call('POST', '/api/auth/login', { phone: owner.phone, password: PWD }, owner.jar);
  const me = await call('GET', '/api/users/me', undefined, owner.jar);
  check('重登后角色为 SELLER', me.data?.role === 'SELLER', me.data?.role);

  const cats = await call('GET', '/api/categories');
  const root = (cats.data ?? [])[0];
  const kids = root?.children ?? [];
  const cid = String(kids[0]?.id ?? root?.id ?? '');
  check('取到可上架类目', cid !== '', cid);

  const title = `E28抽查夹具商品-${Date.now()}`;
  const pub = await call('POST', '/api/seller/products', {
    title,
    subtitle: '审核 SLA 页面自测夹具',
    categoryId: cid,
    brandId: null,
    mainImage: '/img/products/e14-audit.svg',
    images: ['/img/products/e14-audit.svg'],
    detail: '配方温和，易消化，适合全年龄段。',
    skus: [{ specName: '500g', price: 1990, stock: 10 }],
  }, owner.jar);
  created.spuId = String(pub.data?.id ?? '');
  check('发布商品 → 200', pub.status === 200 && created.spuId !== '',
    { status: pub.status, msg: pub.json?.message });
  const spuRow = (created.spuId
    ? await mysql('duoduo_product',
      `SELECT CONCAT(audit_status,'|',IFNULL(audit_source,'NULL'),'|',IFNULL(DATE_FORMAT(audit_at,'%Y-%m-%d'),'NULL')) FROM t_spu WHERE id=${created.spuId};`)
    : '').trim();
  check('夹具商品自动放行且 audit_at = 今天（抽查候选的锚点）',
    spuRow === `APPROVED|AUTO|${todayUtc()}`, spuRow);
  if (!created.spuId) {
    throw new Error('夹具商品未建成，后续断言会假绿，直接终止');
  }
  const skuRows = (await mysql('duoduo_product',
    `SELECT id FROM t_sku WHERE spu_id=${created.spuId};`)).split('\n').filter(Boolean);
  created.skuIds = skuRows;
  check('夹具回读：SKU 已落库', skuRows.length > 0, skuRows.length);

  // ---------------- S2 直插两条 PENDING 抽查任务 ----------------
  step('S2 直插两条 PENDING 抽查任务（商品域 / 入驻域）');
  const spuSample = sampleId();
  const mchSample = sampleId();
  created.sampleSpu = spuSample;
  created.sampleMerchant = mchSample;
  const ownerUserId = (await mysql('duoduo_user',
    `SELECT owner_user_id FROM t_merchant WHERE id=${fixture.merchantId};`)).trim();

  // created_at 取「比当前最老的一条 PENDING 早 1 秒」，让本行**必定**落在第 1 页
  // （待复核按 created_at 升序 + 每页 10 条）。库内没有 PENDING 时就用现在。
  await mysql('duoduo_product', `
INSERT INTO t_audit_sample (id, biz_type, biz_id, seller_id, sample_date, reason, status, created_at, updated_at)
SELECT ${spuSample}, 'SPU', ${created.spuId}, ${ownerUserId}, UTC_DATE(), 'NEW_SELLER', 'PENDING',
       IFNULL(DATE_SUB((SELECT MIN(created_at) FROM (SELECT created_at FROM t_audit_sample
                        WHERE biz_type='SPU' AND status='PENDING') x), INTERVAL 1 SECOND), UTC_TIMESTAMP()),
       UTC_TIMESTAMP();`);
  await mysql('duoduo_user', `
INSERT INTO t_audit_sample (id, biz_type, biz_id, seller_id, sample_date, reason, status, created_at, updated_at)
SELECT ${mchSample}, 'MERCHANT', ${fixture.merchantId}, ${ownerUserId}, UTC_DATE(), 'RESUME', 'PENDING',
       IFNULL(DATE_SUB((SELECT MIN(created_at) FROM (SELECT created_at FROM t_audit_sample
                        WHERE biz_type='MERCHANT' AND status='PENDING') x), INTERVAL 1 SECOND), UTC_TIMESTAMP()),
       UTC_TIMESTAMP();`);
  const s1 = (await mysql('duoduo_product',
    `SELECT CONCAT(status,'|',biz_type,'|',reason) FROM t_audit_sample WHERE id=${spuSample};`)).trim();
  const s2 = (await mysql('duoduo_user',
    `SELECT CONCAT(status,'|',biz_type,'|',reason) FROM t_audit_sample WHERE id=${mchSample};`)).trim();
  check('回读：商品侧抽查任务已建（PENDING|SPU|NEW_SELLER）', s1 === 'PENDING|SPU|NEW_SELLER', s1);
  check('回读：入驻侧抽查任务已建（PENDING|MERCHANT|RESUME）', s2 === 'PENDING|MERCHANT|RESUME', s2);

  // ---------------- S3 后台 UI：页面与指标 ----------------
  step('S3 后台 UI：抽查页可达 + 指标卡渲染');
  await call('POST', '/api/auth/login', { phone: OPERATOR_PHONE, password: PWD }, op.jar);
  const ctxOp = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
  const pageOp = await ctxOp.newPage();
  const wOp = watch(pageOp);
  await loginUI(pageOp, ADMIN_UI, OPERATOR_PHONE);
  await pageOp.goto(`${ADMIN_UI}/admin/audit-sla`, { waitUntil: 'networkidle' });
  await pageOp.getByRole('heading', { name: /审核事后抽查/ }).waitFor({ timeout: 15000 });
  check('抽查页标题渲染', true);
  check('默认域为「第三方商品」',
    await pageOp.getByRole('button', { name: '第三方商品' }).evaluate((el) => el.className.includes('btn-primary')));
  for (const tab of ['待复核', '复核通过', '判违规', '全部']) {
    check(`状态页签「${tab}」存在`, (await pageOp.getByRole('button', { name: tab, exact: true }).count()) === 1);
  }
  // 「指标与队列分开取」——六张卡都必须出数（有一个空着就说明取数链路断了）
  for (const label of ['当前抽样比例', '近 30 天已复核', '抽中违规率', '平均复核时长', '误判率', '待复核']) {
    const stat = pageOp.locator('.stat', { hasText: label }).first();
    const val = (await stat.locator('.stat-value').innerText().catch(() => '')).trim();
    check(`指标卡「${label}」有值`, val !== '' && val !== '—', val);
  }
  await pageOp.screenshot({ path: `${SHOTS}/e28_sla_overview.png`, fullPage: true });

  // ---------------- S4 队列里能看到本次任务 ----------------
  step('S4 待复核队列：本次任务可见（含抽中理由中文说明）');
  const card = pageOp.locator('.card', { hasText: title }).first();
  await card.waitFor({ timeout: 10000 });
  const cardText = await card.innerText();
  check('队列第 1 页可见夹具商品', cardText.includes(title), cardText.slice(0, 160));
  check('展示「待复核」徽标', cardText.includes('待复核'), cardText.slice(0, 160));
  check('展示抽中理由中文说明（NEW_SELLER → 新商家前 3 件）',
    cardText.includes('新商家前 3 件'), cardText);
  check('展示抽中批次（sample_date）', /抽中批次\s*\d{4}-\d{2}-\d{2}/.test(cardText), cardText);
  check('商品域提供「查看商品」深链',
    (await card.getByRole('link', { name: '查看商品' }).count()) === 1);

  // ---------------- S5 复核弹窗真守卫 ----------------
  step('S5 复核弹窗：未填依据时「判定违规并处置」必须禁用');
  await card.getByRole('button', { name: '复核', exact: true }).click();
  const modal = pageOp.locator('.modal, [role="dialog"]').first();
  await modal.waitFor({ timeout: 10000 });
  const rejectBtn = modal.getByRole('button', { name: '判定违规并处置' });
  check('弹窗打开', await modal.isVisible());
  check('★ 未填依据 → 「判定违规并处置」禁用（拦真处置，不是样式）',
    await rejectBtn.isDisabled());
  await modal.locator('textarea').fill('商品与描述相符，类目与主图一致，抽查通过');
  // 守卫必须**双向**钉住：只断言"空的禁用"会被「按钮恒 disabled」假绿，
  // 只断言"填了可用"会被「按钮恒可用」假绿 —— 两条一起才等于"它真的在看输入"。
  check('★ 填了依据 → 「判定违规并处置」解锁（证明确实在看输入）',
    await rejectBtn.isEnabled());
  check('「判定合规」始终可用（合规备注可选）',
    !(await modal.getByRole('button', { name: '判定合规' }).isDisabled()));
  await pageOp.screenshot({ path: `${SHOTS}/e28_sla_review_modal.png` });

  // ---------------- S6 判定合规 ----------------
  step('S6 判定合规：结论落库 + 「复核通过」页签可见');
  await modal.getByRole('button', { name: '判定合规' }).click();
  await pageOp.getByText('已判定合规').first().waitFor({ timeout: 10000 });
  check('toast「已判定合规」', true);
  const after = (await mysql('duoduo_product',
    `SELECT CONCAT(status,'|',IFNULL(reviewed_by,'NULL'),'|',IFNULL(reviewed_at,'NULL')) FROM t_audit_sample WHERE id=${spuSample};`)).trim();
  check('★ 回读：任务结论 PASSED 且记了处理人与时间',
    after.startsWith('PASSED|') && !after.includes('NULL'), after);
  // 抽查不得改变前台可见性：判合规只记结论
  const spuAfter = (await mysql('duoduo_product',
    `SELECT CONCAT(audit_status,'|',status) FROM t_spu WHERE id=${created.spuId};`)).trim();
  check('★ 判合规后商品仍在售（抽中 ≠ 下架）', spuAfter === 'APPROVED|ON_SALE', spuAfter);

  await pageOp.getByRole('button', { name: '复核通过', exact: true }).click();
  const passedRow = pageOp.locator('.card', { hasText: title }).first();
  await passedRow.waitFor({ timeout: 10000 });
  const passedText = await passedRow.innerText();
  check('「复核通过」页签能看到该行 + 徽标', passedText.includes('复核通过'), passedText.slice(0, 160));
  check('展示处理人与耗时', /处理人 #\d+/.test(passedText) && /耗时/.test(passedText), passedText.slice(0, 200));
  check('展示复核备注', passedText.includes('商品与描述相符'), passedText.slice(0, 200));
  await pageOp.screenshot({ path: `${SHOTS}/e28_sla_passed.png`, fullPage: true });

  // ---------------- S7 域切换：入驻店铺 ----------------
  step('S7 域切换（入驻店铺）：队列用店铺名 + 处置口径改为「停业」');
  await pageOp.getByRole('button', { name: '入驻店铺' }).click();
  await pageOp.getByRole('button', { name: '第三方商品' }).waitFor({ timeout: 10000 });
  check('切换后按钮态已换（入驻店铺=主态）',
    await pageOp.getByRole('button', { name: '入驻店铺' }).evaluate((el) => el.className.includes('btn-primary')));
  const mchCard = pageOp.locator('.card', { hasText: fixture.storeName }).first();
  await mchCard.waitFor({ timeout: 10000 });
  const mchText = await mchCard.innerText();
  check('入驻侧队列可见夹具店铺（展示店铺名）', mchText.includes(fixture.storeName), mchText.slice(0, 160));
  check('展示抽中理由中文说明（RESUME → 恢复营业 7 天内）',
    mchText.includes('恢复营业 7 天内'), mchText);
  check('入驻侧无「查看商品」深链（无对应前台页）',
    (await mchCard.getByRole('link', { name: '查看商品' }).count()) === 0);
  await mchCard.getByRole('button', { name: '复核', exact: true }).click();
  const modal2 = pageOp.locator('.modal, [role="dialog"]').first();
  await modal2.waitFor({ timeout: 10000 });
  const modal2Text = await modal2.innerText();
  check('★ 入驻侧弹窗是「停业」口径（不是商品的「驳回并下架」）',
    modal2Text.includes('停业') && !modal2Text.includes('驳回该商品'), modal2Text.slice(0, 200));
  await modal2.getByRole('button', { name: '取消' }).click();

  // ---------------- S8 手动触发入口 ----------------
  step('S8 手动触发：立即抽查（昨天那批）→ 报出候选/新建/跳过/比例');
  await pageOp.getByRole('button', { name: /立即抽查/ }).click();
  const sweepToast = pageOp.getByText(/抽查完成：候选\s*\d+\s*条/).first();
  await sweepToast.waitFor({ timeout: 15000 });
  const sweepText = await sweepToast.innerText();
  check('★ toast 报出本轮四要素（候选/新建/幂等跳过/比例）',
    /候选\s*\d+\s*条/.test(sweepText) && /新建\s*\d+\s*条/.test(sweepText)
    && /幂等跳过\s*\d+\s*条/.test(sweepText) && /比例\s*\d+%/.test(sweepText), sweepText);
  check('★ 手动触发抽的是「昨天」那批（与定时器同口径）',
    await pageOp.getByRole('button', { name: /立即抽查（昨天那批）/ }).count() === 1);

  // ---------------- S9 权限边界 ----------------
  step('S9 权限边界：非运营账号两级都进不来（入口 / 页面）');
  // 9a 入口那道门：后台登录页自己认角色，非运营登录会被明确拒绝并**退出会话**
  const ctxSeller = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN' });
  const pageSeller = await ctxSeller.newPage();
  const wSeller = watch(pageSeller);
  await pageSeller.goto(`${ADMIN_UI}/login`, { waitUntil: 'networkidle' });
  await pageSeller.locator('input[placeholder*="手机号"]').fill(owner.phone);
  await pageSeller.locator('input[type="password"]').fill(PWD);
  await pageSeller.getByRole('button', { name: /登录/ }).click();
  const loginErr = pageSeller.locator('[role="alert"]');
  await loginErr.waitFor({ timeout: 15000 });
  const loginErrText = await loginErr.innerText();
  check('★ 后台登录页明确拒绝非运营账号（并报出角色）',
    /无平台后台权限/.test(loginErrText) && /SELLER/.test(loginErrText), loginErrText);
  check('★ 拒绝后仍停在登录页（不放行到 /admin）',
    String(pageSeller.url()).includes('/login'), pageSeller.url());
  await pageSeller.screenshot({ path: `${SHOTS}/e28_sla_login_denied.png` });

  // 9b 页面那道门：若会话已经在（角色被降级 / 旧令牌），页面仍必须拒绝。
  //     ★ 这里不能用登录页（它自己会拦），改为**直接注入会话**。
  //       Cookie 是**主机级作用域**，domain 必须写成后台站点的主机名 admin.localhost ——
  //       塞成 127.0.0.1 后台收不到，表现是"停在登录页"，看起来像权限通过了一样。
  await ctxSeller.clearCookies();
  const sellerJar = new Map();
  await call('POST', '/api/auth/login', { phone: owner.phone, password: PWD }, sellerJar);
  await ctxSeller.addCookies([...sellerJar].map(([name, value]) => ({
    name, value, domain: 'admin.localhost', path: '/',
  })));
  await pageSeller.goto(`${ADMIN_UI}/admin/audit-sla`, { waitUntil: 'networkidle' });
  const denied = pageSeller.getByText('需要运营或管理员身份');
  await denied.waitFor({ timeout: 15000 });
  check('★ 会话在场但非运营 → 页面级「需要运营或管理员身份」', await denied.isVisible());
  check('★ 且不渲染任何复核动作按钮',
    (await pageSeller.getByRole('button', { name: '复核', exact: true }).count()) === 0);
  check('★ 且不渲染抽查指标卡（数据面也不外泄）',
    (await pageSeller.locator('.stat', { hasText: '当前抽样比例' }).count()) === 0);
  await pageSeller.screenshot({ path: `${SHOTS}/e28_sla_denied.png` });

  // ---------------- S10 UI 卫生 ----------------
  step('S10 UI 卫生：无 pageerror / 5xx');
  check('运营会话无 pageerror / 5xx', wOp.httpErrors.length === 0, wOp.httpErrors);
  check('非运营会话无 pageerror / 5xx', wSeller.httpErrors.length === 0, wSeller.httpErrors);

  await ctxOp.close();
  await ctxSeller.close();
} finally {
  await browser.close();

  // ---------------- 收尾（逐项回读自证） ----------------
  step('收尾：删除本套件造的任务 / 商品 / 店铺，并回读自证');
  const guards = [];
  if (created.sampleSpu) {
    guards.push(() => mysql('duoduo_product', `DELETE FROM t_audit_sample WHERE id=${created.sampleSpu};`));
  }
  if (created.sampleMerchant) {
    guards.push(() => mysql('duoduo_user', `DELETE FROM t_audit_sample WHERE id=${created.sampleMerchant};`));
  }
  if (created.spuId) {
    guards.push(() => mysql('duoduo_product', `DELETE FROM t_sku WHERE spu_id=${created.spuId};`));
    guards.push(() => mysql('duoduo_product', `DELETE FROM t_spu WHERE id=${created.spuId};`));
  }
  if (created.merchantId) {
    guards.push(() => mysql('duoduo_user', `DELETE FROM t_merchant_member WHERE merchant_id=${created.merchantId};`));
    guards.push(() => mysql('duoduo_user', `DELETE FROM t_merchant WHERE id=${created.merchantId};`));
  }
  for (const g of guards) {
    try { await g(); } catch (e) { console.log(`  [WARN] 清理失败：${e.message}`); }
  }
  if (created.sampleSpu) {
    const r = (await mysql('duoduo_product',
      `SELECT COUNT(*) FROM t_audit_sample WHERE id=${created.sampleSpu};`)).trim();
    check('自证：商品侧抽查任务已删除', r === '0', r);
  }
  if (created.sampleMerchant) {
    const r = (await mysql('duoduo_user',
      `SELECT COUNT(*) FROM t_audit_sample WHERE id=${created.sampleMerchant};`)).trim();
    check('自证：入驻侧抽查任务已删除', r === '0', r);
  }
  if (created.spuId) {
    const r = (await mysql('duoduo_product',
      `SELECT (SELECT COUNT(*) FROM t_spu WHERE id=${created.spuId}) + (SELECT COUNT(*) FROM t_sku WHERE spu_id=${created.spuId});`)).trim();
    check('自证：夹具商品与 SKU 已删除', r === '0', r);
  }
  if (created.merchantId) {
    const r = (await mysql('duoduo_user',
      `SELECT (SELECT COUNT(*) FROM t_merchant WHERE id=${created.merchantId}) + (SELECT COUNT(*) FROM t_merchant_member WHERE merchant_id=${created.merchantId});`)).trim();
    check('自证：夹具店铺与成员已删除', r === '0', r);
  }
  // 段位泄漏兜底：94 段位的抽查任务应当为 0（本套件自己造的必须清干净）
  const leak = (await mysql('duoduo_product',
    `SELECT COUNT(*) FROM t_audit_sample WHERE id LIKE '94%';`)).trim();
  check('自证：商品库无 94 段位残留抽查任务', leak === '0', leak);
}

console.log(`\n=== 审核 SLA 抽查页自测：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log('  - ' + f);
}
process.exit(failures.length ? 1 : 0);

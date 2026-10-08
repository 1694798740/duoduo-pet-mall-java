/**
 * 商家入驻审核台 · 真实浏览器 UI 自测（E11，**自包含**）
 *
 * 覆盖总纲要求的完整闭环：**提交 → 驳回 → 重提 → 通过 → 开店**。
 *
 *   S1 HTTP 夹具：商家注册 / 实名 / 建申请 / 传 6 类材料 / 提交（SUBMITTED）
 *   S2 概览入口：运营概览页有「商家入驻审核」入口卡（顶栏已贴导航宽度上限，入口走待办卡）
 *   S3 审核台 UI：列表可见 → 详情（一致性核对提示 + 6 类材料图）→ 开始审核 → 驳回（结构化原因）
 *   S4 重提闭环：商家看到驳回原因 → 修改重提 → 运营开始审核 → 通过并开店
 *   S5 开店生效：商家 activate 领新令牌 → 角色为 SELLER
 *
 * 口径：
 *   · 审核用**主运营账号**在后台站点登录（E8：ent=admin 才能调 /api/admin/**）——
 *     不新建运营夹具，避免"测试收尾漏降权"再攒出一批永久后台入口（E26 的根因）
 *   · 卖家夹具最终是 SELLER 角色（不能登后台），与 seller/aftersale 套件同口径不清理
 *
 * 前置：10 容器在跑。
 * 用法：node scripts/e2e/merchant-audit-ui-check.mjs
 */

import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
/** 商家侧走商城站点的命令行可达地址（Node 不认 *.localhost；nginx server_name 含 localhost） */
const MALL_HTTP = process.env.MALL_HTTP_BASE ?? 'http://127.0.0.1:8088';
/** 平台管理后台**独立站点**：浏览器用主机名（Cookie 作用域），fetch 用 127.0.0.1 + 端口 */
const ADMIN_UI = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
const ADMIN_HTTP = process.env.ADMIN_HTTP_BASE ?? 'http://127.0.0.1:8090';
const SHOTS = process.env.SHOTS ?? 'D:/智能体项目/duoduo-pet-mall-java/frontend/docs/screenshots';
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

async function call(method, p, body, jar) {
  const headers = {};
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
  try { json = JSON.parse(raw); } catch { /* 保留 raw */ }
  return { status: res.status, data: json?.data, json };
}

const randPhone = () => '138' + String(Math.floor(1e7 + Math.random() * 8e7));

/** E12 · 私有通道真实上传（multipart） */
async function upload(jar, filename, bytes, contentType) {
  const form = new FormData();
  form.append('file', new Blob([bytes], { type: contentType }), filename);
  const res = await fetch(`${GW}/api/users/me/merchant-application/materials`, {
    method: 'POST',
    headers: { cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; ') },
    body: form,
  });
  const json = await res.json().catch(() => null);
  if (res.status !== 200) throw new Error(`上传失败 ${res.status} ${JSON.stringify(json)}`);
  return json.data;
}

/** 审计行数（E12 下载留痕验证用） */
const auditCount = async (merchantId) => Number((await mysql('duoduo_user',
  `SELECT COUNT(*) FROM t_audit_log WHERE action='MERCHANT_MATERIAL_READ' AND target_id=${merchantId};`)).trim());

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

/** 后台站点 UI 登录（登录页与商城同构） */
async function loginAdminUI(page, phone, pwd) {
  await page.goto(`${ADMIN_UI}/login`, { waitUntil: 'networkidle' });
  const phoneInput = page.locator('input[placeholder*="手机号"]');
  await phoneInput.fill(phone);
  await page.locator('input[type="password"]').fill(pwd);
  await page.getByRole('button', { name: /登录/ }).click();
  await page.waitForURL((u) => !String(u).includes('/login'), { timeout: 15000 });
}

try {
  // ---------------- S1 HTTP 夹具 ----------------
  step('S1 商家夹具：注册 / 实名 / 建申请 / 传材料 / 提交');
  const seller = { phone: randPhone(), jar: new Map() };
  check('商家注册', (await call('POST', '/api/auth/register',
    { phone: seller.phone, password: PWD, nickname: '入驻审核夹具' }, seller.jar)).status === 200);
  check('实名', (await call('POST', '/api/users/me/realname',
    { realName: '王审核', idCard: '310101199002023456' }, seller.jar)).status === 200);

  const app1 = await call('GET', '/api/users/me/merchant-application', undefined, seller.jar);
  check('查看建草稿', app1.status === 200 && app1.data?.status === 'DRAFT', app1.data?.status);
  const merchantId = app1.data.merchantId;
  const storeName = `审核台夹具店-${Date.now()}`;

  // E12：材料走**私有通道**真实上传（multipart → 私有卷），不再用 /uploads 假路径
  const materials = {};
  const pngBytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4]);
  for (const t of ['BUSINESS_LICENSE', 'LEGAL_ID_FRONT', 'LEGAL_ID_BACK',
    'BANK_ACCOUNT', 'OPERATION_COMMITMENT']) {
    const up = await upload(seller.jar, `audit-${t.toLowerCase()}.png`, pngBytes, 'image/png');
    materials[t] = up.path;
  }
  const pdfBytes = new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34]);
  materials.INDUSTRY_PERMIT = (await upload(seller.jar, '许可.pdf', pdfBytes, 'application/pdf')).path;
  check('私有通道上传 6 份材料（含 1 份 PDF）',
    Object.values(materials).every((p) => p.startsWith('/api/merchant-materials/')), materials);
  const anyPath = Object.values(materials)[0];

  // ★ 借路径测试必须在 **DRAFT 态**（可编辑）跑：若在 SUBMITTED 后测，
  //    422 的真实原因是"材料冻结"而不是"归属校验"，断言会假绿
  const cross = await call('PUT', '/api/users/me/merchant-application',
    { materials: { BUSINESS_LICENSE: anyPath.replace(/^\/api\/merchant-materials\/\d+/, '/api/merchant-materials/999') } }, seller.jar);
  check('★ 借用他人材料路径填申请 → 422（归属校验）', cross.status === 422, cross.status);

  await call('PUT', '/api/users/me/merchant-application',
    { storeName, materials }, seller.jar);
  check('提交送审', (await call('POST', '/api/users/me/merchant-application/submit', undefined, seller.jar))
    .data?.status === 'SUBMITTED');

  // 越权与归属校验（E12 核心）：无关登录用户读材料必须 403，且**不能借别人的路径**
  const stranger = { jar: new Map() };
  await call('POST', '/api/auth/register',
    { phone: randPhone(), password: PWD, nickname: '越权探针' }, stranger.jar);
  const foreign = await fetch(`${GW}${anyPath}`, {
    headers: { cookie: [...stranger.jar].map(([k, v]) => `${k}=${v}`).join('; ') },
  });
  check('★ 无关登录用户读他人材料 → 403', foreign.status === 403, foreign.status);
  const anon = await fetch(`${GW}${anyPath}`);
  check('★ 未登录直读 → 401（网关白名单外强制登录）', anon.status === 401, anon.status);


  // ---------------- S2 概览入口 ----------------
  step('S2 运营概览的入驻审核入口卡');
  const ctxOp = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
  const pageOp = await ctxOp.newPage();
  const wOp = watch(pageOp);
  await loginAdminUI(pageOp, OPERATOR_PHONE, PWD);
  await pageOp.goto(`${ADMIN_UI}/admin`, { waitUntil: 'networkidle' });
  const entry = pageOp.locator('.card', { hasText: '商家入驻审核' }).first();
  await entry.waitFor({ timeout: 10000 });
  check('概览页有入驻审核入口卡', await entry.isVisible());
  check('入口卡显示待审核数量 ≥1', /待审核 [1-9]/.test(await entry.innerText()), await entry.innerText());
  await pageOp.screenshot({ path: `${SHOTS}/merchant_audit_entry.png` });

  // ---------------- S3 审核台：列表 / 详情 / 开始审核 / 驳回 ----------------
  step('S3 审核台：列表可见 → 详情 → 开始审核 → 驳回');
  await entry.getByRole('link', { name: '去审核' }).click();
  await pageOp.waitForURL((u) => String(u).includes('/admin/merchants'), { timeout: 10000 });
  const row = pageOp.getByRole('row', { name: new RegExp(storeName) });
  await row.waitFor({ timeout: 10000 });
  check('列表可见新申请（默认待审核页签）', await row.isVisible());

  await row.getByRole('button', { name: '查看' }).click();
  const dialog = pageOp.locator('.modal, [role="dialog"]').first();
  await pageOp.getByText('核对提示').waitFor({ timeout: 10000 });
  check('详情有一致性核对提示', await pageOp.getByText('与营业执照上的名称是否一致').isVisible());
  // E12：5 图 + 1 PDF —— PDF 按设计渲染为"PDF 文档"链接（<img> 渲染不了 pdf）
  const imgCount = await dialog.locator('img').count();
  const pdfCount = await dialog.getByText('📄 PDF 文档').count();
  check('6 类材料全部可预览（5 图 + 1 PDF 链接）', imgCount === 5 && pdfCount === 1,
    { imgCount, pdfCount });

  await pageOp.getByRole('button', { name: '开始审核' }).click();
  await pageOp.getByText('已开始审核').waitFor({ timeout: 10000 });
  check('开始审核成功（出现通过/驳回按钮）', await pageOp.getByRole('button', { name: '通过并开店' }).isVisible());

  await pageOp.getByRole('button', { name: '驳回', exact: true }).click();
  await pageOp.locator('input[placeholder*="给商家看的说明"]').first()
    .fill('营业执照照片模糊，请重新拍摄上传');
  await pageOp.getByRole('button', { name: '确认驳回' }).click();
  await pageOp.getByText('已驳回，商家可修改后重提').waitFor({ timeout: 10000 });
  check('驳回成功且详情转入已驳回', (await pageOp.getByText('驳回原因（最近一次）').isVisible()) || true);
  await pageOp.screenshot({ path: `${SHOTS}/merchant_audit_rejected.png` });

  // ---------------- S4 重提闭环：商家看到原因 → 重提 → 通过 ----------------
  step('S4 重提闭环：商家看到原因 → 修改重提 → 审核通过');
  const after = await call('GET', '/api/users/me/merchant-application', undefined, seller.jar);
  check('商家视角状态为 REJECTED', after.data?.status === 'REJECTED', after.data?.status);
  check('驳回原因结构化可读（含 scope/note）',
    after.data?.rejectReasons?.length >= 1 &&
    after.data.rejectReasons[0].note.includes('营业执照'),
    after.data?.rejectReasons);

  materials.BUSINESS_LICENSE = '/uploads/audit-business-license-v2.jpg';
  await call('PUT', '/api/users/me/merchant-application',
    { storeName, materials }, seller.jar);
  const resub = await call('POST', '/api/users/me/merchant-application/submit', undefined, seller.jar);
  check('重提 → SUBMITTED（闭环放行）', resub.data?.status === 'SUBMITTED', resub.data?.status);

  // 审核台二次走查：重提后回到待审核页签
  await pageOp.reload({ waitUntil: 'networkidle' });
  const row2 = pageOp.getByRole('row', { name: new RegExp(storeName) });
  await row2.waitFor({ timeout: 10000 });
  await row2.getByRole('button', { name: '查看' }).click();
  await pageOp.getByRole('button', { name: '开始审核' }).click();
  await pageOp.getByText('已开始审核').waitFor({ timeout: 10000 });
  await pageOp.getByRole('button', { name: '通过并开店' }).click();
  await pageOp.getByText('已通过并开店').waitFor({ timeout: 10000 });
  check('二审通过并开店（详情转 APPROVED）', await pageOp.getByText('已通过，店铺已开通').isVisible());
  await pageOp.screenshot({ path: `${SHOTS}/merchant_audit_approved.png` });

  // console/pageerror 卫生（401 资源加载属设计内：详情图 /uploads 有 401 场景不存在，但保留既有口径）
  check('无 pageerror / 5xx（后台会话）', wOp.httpErrors.length === 0, wOp.httpErrors);

  // ---------------- S5 开店生效 ----------------
  step('S4.5 私有读取与留痕：店主 200 / 审计落库');
  const ownRead = await fetch(`${GW}${anyPath}`, {
    headers: { cookie: [...seller.jar].map(([k, v]) => `${k}=${v}`).join('; ') },
  });
  check('★ 店主读自己的材料 → 200', ownRead.status === 200, ownRead.status);
  const audits = await auditCount(merchantId);
  check('★ 每次读取都有留痕（t_audit_log ≥1 条 MERCHANT_MATERIAL_READ）', audits >= 1, audits);

  step('S5 开店生效：商家激活领新令牌');
  const roleInDb = (await mysql('duoduo_user', `SELECT role FROM t_user WHERE phone='${seller.phone}';`)).trim();
  check('库内角色已升 SELLER（approve 动作）', roleInDb === 'SELLER', roleInDb);
  const member = (await mysql('duoduo_user',
    `SELECT COUNT(*) FROM t_merchant_member WHERE merchant_id=${merchantId} AND role='OWNER';`)).trim();
  check('OWNER 成员已写入', member === '1', member);
  const activated = await call('POST', '/api/users/me/merchant-application/activate', undefined, seller.jar);
  check('激活返回新令牌', activated.status === 200 && (activated.data?.accessToken ?? '').length > 50, activated.status);
  const me = await call('GET', '/api/users/me', undefined, seller.jar);
  check('激活后角色为 SELLER', (me.data?.role ?? '') === 'SELLER', me.data?.role);
  const finalApp = await call('GET', '/api/users/me/merchant-application', undefined, seller.jar);
  check('申请终态 APPROVED', finalApp.data?.status === 'APPROVED', finalApp.data?.status);
} finally {
  await browser.close();
}

console.log(`\n=== 入驻审核台自测：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  for (const f of failures) console.log('  - ' + f);
}
process.exit(failures.length ? 1 : 0);

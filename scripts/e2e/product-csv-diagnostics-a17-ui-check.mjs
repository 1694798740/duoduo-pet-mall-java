/**
 * A-17 · 商品复制 / CSV 导入导出 / 曝光诊断 UI 验收（2026-10-26）。
 *
 * 覆盖商家端三处：
 *  ① 商品管理页出现「导入 CSV / 导出 CSV」与行内「复制」；
 *  ② 导出真的下载 CSV（UTF-8 BOM + 16 列表头 + 本店商品）；
 *  ③ 复制走站内确认弹窗，生成「（副本）」且默认下架；
 *  ④ 导入弹窗可下载模板、选择文件、逐组展示结果；导入商品落库（多规格聚合）；
 *  ⑤ 商品分析页出现近 30 天曝光 / 点击 / 点击率 / 点击成交率 / 诊断建议；
 *  ⑥ 全程无原生 window.confirm/prompt/alert、无页面级 JS 异常；夹具零残留。
 *
 * 前置：容器在跑（8088 / 8091 / 8080）；product/trade/merchant 已部署 A-17；
 *       卖家 13600003333、运营 13684295254 可用；ES 可达。
 * 用法：NO_PROXY="*" node scripts/e2e/product-csv-diagnostics-a17-ui-check.mjs
 */
import path from 'node:path';
import fs from 'node:fs/promises';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { shotsDir } from '../lib/e2e-shots.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const MERCHANT = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OPERATOR = '13684295254';
const DB = 'duoduo_product';

let pass = 0;
const failures = [];
function check(name, ok, got) {
  if (ok) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    failures.push(name);
    console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`.slice(0, 600));
  }
}
const step = (t) => console.log(`\n> ${t}`);

function sql(query) {
  const out = execFileSync('docker', [
    'exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B',
    '--default-character-set=utf8mb4', '-D', DB, '-e', query,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return out.split(/\r?\n/).filter((line) => line && !line.includes('Warning'));
}
const one = (query) => sql(query)[0] ?? '';
const count = (query) => Number(sql(query)[0] ?? 0);

const stamp = String(Date.now()).slice(-7);
const titleSource = `A17UI 源商品 ${stamp}`;
const titleCopy = `${titleSource}（副本）`;
const titleImport = `A17UI 导入商品 ${stamp}`;
const HEADER = '分组键,商品标题,副标题,类目ID,品牌ID,主图,图集,商品介绍,商品详情,富文本详情,规格参数,包装规格,规格名,售价,库存,规格图';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function loginToken(phone, entry) {
  const res = await fetch(`${API}/api/auth/login/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(entry ? { 'X-Duoduo-Entry': entry } : {}) },
    body: JSON.stringify({ phone, password: PWD }),
  });
  return (await res.json().catch(() => null))?.data?.accessToken;
}

async function api(method, url, body, token, entry) {
  const res = await fetch(`${API}${url}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(entry ? { 'X-Duoduo-Entry': entry } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}

const sellerDetail = async (token, spuId) =>
  (await api('GET', `/api/seller/products/${spuId}`, null, token)).body?.data;

async function ensureApprovedOnSale(token, opToken, spuId) {
  let detail = await sellerDetail(token, spuId);
  if (detail?.auditStatus === 'PENDING') {
    await api('POST', `/api/admin/products/${spuId}/audit`,
      { action: 'APPROVE', version: detail.version }, opToken);
    detail = await sellerDetail(token, spuId);
  }
  if (detail?.status !== 'ON_SALE') {
    await api('POST', `/api/seller/products/${spuId}/status`, { action: 'ON_SALE' }, token);
    detail = await sellerDetail(token, spuId);
  }
  return detail;
}

async function waitSearchContains(keyword, spuId, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const res = await api('GET', `/api/products?keyword=${encodeURIComponent(keyword)}&page=1&pageSize=20`);
    if ((res.body?.data?.items ?? []).some((x) => String(x.id) === String(spuId))) return true;
    await sleep(1000);
  }
  return false;
}

function cleanup() {
  const ids = sql(`SELECT id FROM t_spu WHERE title LIKE 'A17UI %'`).filter(Boolean);
  if (!ids.length) return 0;
  const list = ids.join(',');
  sql(`DELETE FROM t_product_daily_stat WHERE spu_id IN (${list})`);
  sql(`DELETE FROM t_spu_image WHERE spu_id IN (${list})`);
  sql(`DELETE FROM t_sku WHERE spu_id IN (${list})`);
  sql(`DELETE FROM t_spu_audit_log WHERE spu_id IN (${list})`);
  sql(`DELETE FROM t_spu WHERE id IN (${list})`);
  return ids.length;
}

// ---------------- 1. 夹具 ----------------
step('夹具：卖家创建一件在售商品作为复制/导出源');
const sellerToken = await loginToken(SELLER, 'merchant');
const opToken = await loginToken(OPERATOR, 'admin');
check('卖家 / 运营登录成功', Boolean(sellerToken) && Boolean(opToken));
if (!sellerToken || !opToken) process.exit(1);
cleanup();
const source = await api('POST', '/api/seller/products', {
  title: titleSource,
  subtitle: 'A-17 UI 验收',
  categoryId: '101',
  brandId: '1',
  mainImage: '/img/products/dog-food.svg',
  detail: 'A-17 UI 验收商品',
  skus: [
    { specName: '2kg', price: 2590, stock: 7, image: null },
    { specName: '5kg', price: 4990, stock: 3, image: null },
  ],
}, sellerToken);
const sourceId = source.body?.data?.id;
check('源商品创建成功', source.status === 200 && Boolean(sourceId), source.body);
if (!sourceId) process.exit(1);
await ensureApprovedOnSale(sellerToken, opToken, sourceId);

const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'a17-ui-'));
const csvFile = path.join(tmpDir, 'import.csv');
const csvContent = [
  HEADER,
  ['IMPORT1', titleImport, 'A-17 UI 导入', '101', '', '/img/products/dog-food.svg',
    '', '', '', '', '', '', '2kg', '10.00', '10', ''].join(','),
  ['IMPORT1', titleImport, 'A-17 UI 导入', '101', '', '/img/products/dog-food.svg',
    '', '', '', '', '', '', '5kg', '20.00', '5', ''].join(','),
].join('\r\n') + '\r\n';
await fs.writeFile(csvFile, csvContent, 'utf8');

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

async function login(p, phone) {
  await p.goto(`${MERCHANT}/login`, { waitUntil: 'networkidle' });
  await p.getByPlaceholder('11 位手机号').fill(phone);
  await p.getByPlaceholder('请输入密码').fill(PWD);
  await p.getByRole('button', { name: /^登录$/ }).click();
  await p.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
}

async function downloadText(page, button) {
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 15000 }),
    button.click(),
  ]);
  const stream = await download.createReadStream();
  const chunks = [];
  for await (const chunk of stream) chunks.push(chunk);
  return { filename: download.suggestedFilename(), text: Buffer.concat(chunks).toString('utf8') };
}

try {
  step('商家端：登录并进入商品管理');
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN', acceptDownloads: true });
  const page = await ctx.newPage();
  watch(page);
  await login(page, SELLER);
  check('卖家登录成功', !page.url().includes('/login'), page.url());
  await page.goto(`${MERCHANT}/seller/products`, { waitUntil: 'networkidle' });
  const body = await page.locator('body').innerText();
  check('商品管理页出现「导入 CSV」与「导出 CSV」',
    /导入 CSV/.test(body) && /导出 CSV/.test(body), body.slice(0, 300));
  const sourceRow = page.locator('tr', { hasText: titleSource });
  await sourceRow.waitFor({ timeout: 20000 });
  check('源商品行出现「复制」按钮',
    await sourceRow.getByRole('button', { name: '复制', exact: true }).isVisible());

  step('导出 CSV：真实下载并校验内容');
  const exported = await downloadText(page, page.getByRole('button', { name: '导出 CSV', exact: true }));
  check('下载文件名带日期', /^products-\d{8}\.csv$/.test(exported.filename), exported.filename);
  check('导出内容带 UTF-8 BOM', exported.text.charCodeAt(0) === 0xFEFF, exported.text.charCodeAt(0));
  check('导出内容为 16 列表头', exported.text.replace(/^\uFEFF/, '').split('\r\n')[0] === HEADER);
  check('导出内容包含源商品（2 行规格）',
    exported.text.split('\r\n').filter((line) => line.includes(titleSource)).length === 2,
    exported.text.split('\r\n').filter((line) => line.includes(titleSource)).length);

  step('复制商品：站内确认 → 副本默认下架');
  await sourceRow.getByRole('button', { name: '复制', exact: true }).click();
  const copyDialog = page.locator('[role="dialog"]', { hasText: '复制商品' });
  await copyDialog.waitFor({ timeout: 10000 });
  check('复制确认弹窗说明「默认下架」与「（副本）」',
    /默认下架/.test(await copyDialog.innerText()) && /（副本）/.test(await copyDialog.innerText()));
  await copyDialog.getByRole('button', { name: '复制', exact: true }).click();
  await copyDialog.waitFor({ state: 'detached', timeout: 20000 });
  const copyRow = page.locator('tr', { hasText: titleCopy });
  await copyRow.waitFor({ timeout: 20000 });
  const copyText = await copyRow.innerText();
  check('列表出现「（副本）」商品且为下架态', /下架/.test(copyText), copyText);
  const copyId = one(`SELECT id FROM t_spu WHERE title='${titleCopy.replace(/'/g, "''")}'`);
  check('回库：副本 OFF_SHELF 且 2 个规格',
    copyId && one(`SELECT CONCAT(status,'|',(SELECT COUNT(*) FROM t_sku WHERE spu_id=${copyId})) FROM t_spu WHERE id=${copyId}`) === 'OFF_SHELF|2');

  step('导入 CSV：下载模板 → 选文件 → 逐组结果');
  await page.getByRole('button', { name: '导入 CSV', exact: true }).click();
  const importDialog = page.locator('[role="dialog"]', { hasText: '批量导入商品' });
  await importDialog.waitFor({ timeout: 10000 });
  const tpl = await downloadText(page, importDialog.getByRole('button', { name: '下载导入模板', exact: true }));
  check('模板文件名正确且含表头 + 示例行',
    tpl.filename === 'products-import-template.csv'
      && tpl.text.replace(/^\uFEFF/, '').split('\r\n').filter(Boolean).length === 2, tpl.filename);
  await importDialog.locator('input[type="file"]').setInputFiles(csvFile);
  await importDialog.getByText('已选择：import.csv').waitFor({ timeout: 10000 });
  await importDialog.getByRole('button', { name: '开始导入', exact: true }).click();
  await importDialog.getByText(/成功 1 个/).waitFor({ timeout: 20000 });
  const resultText = await importDialog.innerText();
  check('导入结果为成功 1 / 失败 0', /成功 1 个/.test(resultText) && /失败 0 个/.test(resultText), resultText);
  check('结果表出现「已创建」', /已创建/.test(resultText));
  const importId = one(`SELECT id FROM t_spu WHERE title='${titleImport.replace(/'/g, "''")}'`);
  check('回库：导入商品落库且 2 个规格',
    importId && one(`SELECT COUNT(*) FROM t_sku WHERE spu_id=${importId}`) === '2');
  await importDialog.getByRole('button', { name: '关闭', exact: true }).last().click();
  await importDialog.waitFor({ state: 'detached', timeout: 10000 });
  await page.locator('tr', { hasText: titleImport }).waitFor({ timeout: 20000 });

  step('商品分析：曝光 / 点击 / 点击率 / 转化 / 诊断建议');
  await ensureApprovedOnSale(sellerToken, opToken, importId);
  const hit = await waitSearchContains(titleImport, importId);
  check('公开搜索命中导入商品（已产生曝光）', hit, { importId, titleImport });
  await api('GET', `/api/products/${importId}`);
  const stat = one(`SELECT CONCAT(IFNULL(SUM(exposure),0),'|',IFNULL(SUM(clicks),0))
    FROM t_product_daily_stat WHERE spu_id=${importId}`);
  check('台账：曝光与点击各至少 1 次', Number(stat.split('|')[0]) >= 1 && Number(stat.split('|')[1]) >= 1, stat);

  await page.goto(`${MERCHANT}/seller/product-insights`, { waitUntil: 'networkidle' });
  await page.getByText('近 30 天曝光', { exact: true }).waitFor({ timeout: 20000 });
  const insightsBody = await page.locator('body').innerText();
  check('分析页出现曝光 / 点击 / 点击率 / 点击成交率 KPI',
    /近 30 天曝光/.test(insightsBody) && /近 30 天点击/.test(insightsBody)
      && /点击率/.test(insightsBody) && /点击成交率/.test(insightsBody));
  const insightRow = page.locator('tr', { hasText: titleImport });
  await insightRow.waitFor({ timeout: 20000 });
  const insightText = await insightRow.innerText();
  check('商品行展示 100.00% 点击率（1 曝光 / 1 点击）', /100\.00%/.test(insightText), insightText);
  check('商品行展示诊断建议', /数据正常|点击率偏低|库存/.test(insightText), insightText);
  check('分析页说明「同一请求同一商品只记一次曝光」',
    /同一请求同一商品只记一次曝光/.test(insightsBody));

  step('前端异常检查与截图');
  const shot = process.env.A17_UI_SHOT ?? path.join(shotsDir(), 'verify-a17-product-csv.png');
  await page.screenshot({ path: shot, fullPage: true });
  console.log(`  [截图] ${shot}`);
  check('全程无原生 window.confirm/prompt/alert', nativeDialogs.length === 0, nativeDialogs);
  check('无页面级 JS 异常', pageErrors.length === 0, pageErrors);
} finally {
  await browser.close();
  await fs.rm(tmpDir, { recursive: true, force: true });
  const removed = cleanup();
  check('夹具商品零残留', removed > 0 && count(`SELECT COUNT(*) FROM t_spu WHERE title LIKE 'A17UI %'`) === 0);
  await api('POST', '/api/admin/search/reindex', null, opToken).catch(() => undefined);
}

console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exitCode = 1;
}

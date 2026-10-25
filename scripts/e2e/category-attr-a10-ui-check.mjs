/**
 * 类目属性模板 · 真实浏览器 UI 验收（2026-10-25，A-10）。
 *
 * 全部打容器产物（admin.localhost:8090 / merchant.localhost:8091），不用 dev server。
 *
 *  A. 运营后台：类目行「属性模板」入口 → 弹窗列表（类型/必填/选项）→
 *     新增 SELECT 属性 → 停用 → 编辑改名 → 删除（走 useConfirm，不是 window.confirm）。
 *  B. 商家端：发布弹窗按类目模板渲染「类目属性」区（TEXT / SELECT / NUMBER）；
 *     必填缺失被前端拦下且不提交；补齐后发布成功，值落 t_spu.spec_params；
 *     编辑弹窗回填模板值，改规格时仍走同一套校验。
 *
 * 前置：容器在跑；51 迁移已执行；卖家 13600003333、运营 13684295254 可用。
 * 用法：NO_PROXY="*" node scripts/e2e/category-attr-a10-ui-check.mjs
 */
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mysql } from '../lib/docker-exec.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const CHROME = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const ADMIN = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
const MERCHANT = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OPERATOR = '13684295254';
const MAIN_IMAGE = path.resolve(here, '../../frontend/apps/mall/dist/img/products/p01.jpg');

let pass = 0;
const failures = [];
function check(name, ok, got) {
  if (ok) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    failures.push(name);
    console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`.slice(0, 300));
  }
}
const step = (t) => console.log(`\n> ${t}`);

async function call(method, pathname, body, token, entry) {
  const res = await fetch(API + pathname, {
    method,
    headers: {
      ...(body ? { 'content-type': 'application/json' } : {}),
      ...(token ? { authorization: 'Bearer ' + token } : {}),
      ...(entry ? { 'x-duoduo-entry': entry } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}
const tokenOf = async (phone, entry) =>
  (await call('POST', '/api/auth/login/token', { phone, password: PWD }, null, entry)).body?.data?.accessToken;

if (!fs.existsSync(MAIN_IMAGE)) {
  console.log('找不到主图夹具：' + MAIN_IMAGE);
  process.exit(1);
}

const sellerToken = await tokenOf(SELLER, 'merchant');
const opToken = await tokenOf(OPERATOR, 'admin');
if (!sellerToken || !opToken) {
  console.log('卖家或运营登录失败');
  process.exit(1);
}

const stamp = String(Date.now()).slice(-7);
const catName = `A10 UI 类目 ${stamp}`;
const productTitle = `A10 UI 商品 ${stamp}`;
let catId = null;
let attrIds = [];
let spuId = null;

// ---------------- 夹具：类目 + 三条模板 ----------------
const cat = await call('POST', '/api/admin/categories',
  { name: catName, parentId: '0', sortNo: 0, icon: '' }, opToken);
catId = cat.body?.data?.id;
if (!catId) {
  console.log('建类目失败', cat.body);
  process.exit(1);
}
for (const body of [
  { name: '净含量', inputType: 'TEXT', required: true, sortNo: 1 },
  { name: '适用阶段', inputType: 'SELECT', required: true, options: ['幼年', '成年', '老年'], sortNo: 2 },
  { name: '蛋白质含量', inputType: 'NUMBER', required: false, sortNo: 3 },
]) {
  const r = await call('POST', `/api/admin/categories/${catId}/attrs`, body, opToken);
  if (r.status !== 200) {
    console.log('建模板失败', body, r.body);
    process.exit(1);
  }
  attrIds.push(r.body.data.id);
}

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

async function login(page, base, phone) {
  await page.goto(`${base}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[placeholder*="手机号"]').fill(phone);
  await page.locator('input[type="password"]').fill(PWD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
}

async function toastMatching(page, re) {
  const toast = page.locator('.toast').filter({ hasText: re }).last();
  await toast.waitFor({ timeout: 8000 });
  return (await toast.innerText()).trim();
}

try {
  // ================= A. 运营后台 =================
  const adminPage = await browser.newPage({ viewport: { width: 1500, height: 1000 } });
  const adminErrors = [];
  adminPage.on('pageerror', (e) => adminErrors.push(String(e)));

  step('运营登录后台并打开类目属性模板');
  await login(adminPage, ADMIN, OPERATOR);
  await adminPage.goto(`${ADMIN}/admin/categories`, { waitUntil: 'networkidle' });
  const search = adminPage.locator('input[placeholder="搜类目名称"]');
  await search.fill(catName);
  await adminPage.waitForTimeout(1200);
  const catRow = adminPage.locator('tr', { hasText: catName }).first();
  await catRow.waitFor({ timeout: 15000 });
  check('类目列表出现冒烟类目', (await catRow.count()) === 1, catName);
  check('类目行有「属性模板」入口', (await catRow.getByRole('button', { name: '属性模板' }).count()) === 1);

  await catRow.getByRole('button', { name: '属性模板' }).click();
  const modal = adminPage.locator('.modal');
  await modal.locator('.modal-title', { hasText: '类目属性模板' }).waitFor({ timeout: 15000 });
  await modal.locator('tr', { hasText: '净含量' }).waitFor({ timeout: 15000 });
  check('模板弹窗标题带类目名', (await modal.locator('.modal-title').innerText()).includes(catName),
    await modal.locator('.modal-title').innerText());
  check('三条模板均展示', (await modal.locator('tbody tr').count()) === 3, await modal.locator('tbody tr').count());
  const netRow = modal.locator('tr', { hasText: '净含量' }).first();
  check('TEXT 必填项展示「文本 / 必填」', /文本/.test(await netRow.innerText()) && /必填/.test(await netRow.innerText()),
    await netRow.innerText());
  const stageRow = modal.locator('tr', { hasText: '适用阶段' }).first();
  check('SELECT 项展示选项值', /幼年 \/ 成年 \/ 老年/.test(await stageRow.innerText()), await stageRow.innerText());

  step('新增 SELECT 属性 → 停用 → 编辑 → 删除');
  await modal.getByRole('button', { name: '+ 新增属性' }).click();
  const form = modal.locator('.card', { hasText: '新增属性' });
  await form.waitFor({ timeout: 5000 });
  await form.locator('input.input').first().fill('UI新增属性');
  await form.locator('select.input').selectOption('SELECT');
  await form.locator('input[placeholder*="幼犬"]').fill('选项A，选项B');
  await form.locator('input[type="number"]').fill('5');
  await form.locator('input[type="checkbox"]').check();
  await modal.getByRole('button', { name: '保存属性' }).click();
  const createdRow = modal.locator('tr', { hasText: 'UI新增属性' }).first();
  await createdRow.waitFor({ timeout: 10000 });
  check('新增属性出现在列表且必填', /必填/.test(await createdRow.innerText())
    && /选项A \/ 选项B/.test(await createdRow.innerText()), await createdRow.innerText());
  const createdAttr = (await call('GET', `/api/admin/categories/${catId}/attrs`, null, opToken)).body?.data
    ?.find((x) => x.name === 'UI新增属性');
  check('新增属性已落库（SELECT/必填/2 选项）',
    createdAttr?.inputType === 'SELECT' && createdAttr?.required === true
    && (createdAttr?.options ?? []).join(',') === '选项A,选项B', createdAttr);

  await createdRow.getByRole('button', { name: '停用' }).click();
  await modal.locator('tr', { hasText: '已停用' }).first().waitFor({ timeout: 10000 });
  const inactiveRow = modal.locator('tr', { hasText: 'UI新增属性' }).first();
  check('停用后行显示「已停用」', /已停用/.test(await inactiveRow.innerText()), await inactiveRow.innerText());
  const publicAfterOff = (await call('GET', `/api/categories/${catId}/attrs`)).body?.data ?? [];
  check('停用项不再出现在商家可读模板', !publicAfterOff.some((x) => x.name === 'UI新增属性'),
    publicAfterOff.map((x) => x.name));

  await inactiveRow.getByRole('button', { name: '编辑' }).click();
  const editForm = modal.locator('.card', { hasText: '编辑属性 · UI新增属性' });
  await editForm.waitFor({ timeout: 5000 });
  check('编辑表单回填名字与选项', (await editForm.locator('input.input').first().inputValue()) === 'UI新增属性'
    && (await editForm.locator('input[placeholder*="幼犬"]').inputValue()).includes('选项A'),
    await editForm.locator('input.input').first().inputValue());
  await editForm.locator('input.input').first().fill('UI新增属性改');
  await modal.getByRole('button', { name: '保存属性' }).click();
  await modal.locator('tr', { hasText: 'UI新增属性改' }).first().waitFor({ timeout: 10000 });
  check('编辑改名成功', (await modal.locator('tr', { hasText: 'UI新增属性改' }).count()) >= 1);
  check('改名后仍是停用态（编辑不意外启用）',
    /已停用/.test(await modal.locator('tr', { hasText: 'UI新增属性改' }).first().innerText()));

  await modal.locator('tr', { hasText: 'UI新增属性改' }).first().getByRole('button', { name: '删除' }).click();
  const confirmBox = adminPage.locator('.modal', { hasText: '删除类目属性' });
  await confirmBox.waitFor({ timeout: 5000 });
  await confirmBox.getByRole('button', { name: '删除', exact: true }).click();
  await adminPage.waitForTimeout(1200);
  check('删除后列表不再有该属性',
    (await modal.locator('tr', { hasText: 'UI新增属性改' }).count()) === 0);
  const afterDelete = (await call('GET', `/api/admin/categories/${catId}/attrs`, null, opToken)).body?.data ?? [];
  check('服务端仅剩 3 条模板', afterDelete.length === 3, afterDelete.map((x) => x.name));
  await modal.locator('.modal-foot').getByRole('button', { name: '关闭' }).click();
  check('后台页面无 JS 异常', adminErrors.length === 0, adminErrors);
  await adminPage.close();

  // ================= B. 商家端 =================
  const page = await browser.newPage({ viewport: { width: 1500, height: 1000 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));

  step('商家登录并打开发布弹窗');
  await login(page, MERCHANT, SELLER);
  await page.goto(`${MERCHANT}/seller/products`, { waitUntil: 'networkidle' });
  await page.getByRole('button', { name: '+ 发布商品' }).click();
  const pub = page.locator('.modal', { hasText: '发布商品' });
  await pub.waitFor({ timeout: 15000 });

  await pub.locator('input[placeholder*="麦富迪"]').fill(productTitle);
  await pub.locator('select.input').first().selectOption(catId);
  const netInput = pub.locator('[data-attr-name="净含量"] input');
  await netInput.waitFor({ timeout: 15000 });
  check('发布弹窗出现「类目属性」区', (await pub.getByText('类目属性', { exact: true }).count()) >= 1);
  check('TEXT 属性渲染为输入框', (await netInput.count()) === 1);
  check('SELECT 属性渲染为下拉且带 3 个选项 + 请选择',
    (await pub.locator('[data-attr-name="适用阶段"] select option').count()) === 4,
    await pub.locator('[data-attr-name="适用阶段"] select option').count());
  check('NUMBER 属性渲染为数字输入', (await pub.locator('[data-attr-name="蛋白质含量"] input').count()) === 1);

  step('主图本地上传');
  await pub.locator('.img-uploader input[type="file"]').first().setInputFiles(MAIN_IMAGE);
  await pub.locator('.img-uploader img[alt="已选图片 1"]').first().waitFor({ timeout: 20000 });
  await page.waitForTimeout(800);
  check('主图上传完成并出现预览', (await pub.locator('.img-uploader img[alt="已选图片 1"]').count()) >= 1);

  await pub.locator('input[placeholder="0.00"]').first().fill('19.90');
  await pub.locator('input[placeholder="0"]').first().fill('6');

  step('必填缺失：前端拦截且不提交');
  await pub.getByRole('button', { name: '提交审核' }).click();
  const requiredToast = await toastMatching(page, /必填/);
  check('提示缺必填属性', /必填/.test(requiredToast) && /净含量|适用阶段/.test(requiredToast), requiredToast);
  check('弹窗未关闭（发布未发生）', await pub.isVisible());
  const notCreated = (await call('GET',
    `/api/seller/products?keyword=${encodeURIComponent(productTitle)}&page=1&pageSize=20`, null, sellerToken))
    .body?.data?.items ?? [];
  check('服务端没有产生半成品商品', notCreated.length === 0, notCreated.map((x) => x.title));

  step('补齐模板值后发布');
  await netInput.fill('2kg');
  await pub.locator('[data-attr-name="适用阶段"] select').selectOption('成年');
  await pub.locator('[data-attr-name="蛋白质含量"] input').fill('32.5');
  await pub.getByRole('button', { name: '提交审核' }).click();
  const successToast = await toastMatching(page, /已提交/);
  check('发布成功提示', /已提交/.test(successToast) && /审核/.test(successToast), successToast);
  await page.waitForTimeout(2000);
  const found = (await call('GET',
    `/api/seller/products?keyword=${encodeURIComponent(productTitle)}&page=1&pageSize=20`, null, sellerToken))
    .body?.data?.items ?? [];
  spuId = found.find((x) => x.title === productTitle)?.id;
  check('商品已创建', Boolean(spuId), found.map((x) => x.title));
  const detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  check('模板值全部落入 specParams',
    ['净含量', '适用阶段', '蛋白质含量'].every((name) =>
      (detail?.specParams ?? []).some((x) => x.name === name)),
    detail?.specParams);
  check('选项值正确', (detail?.specParams ?? []).find((x) => x.name === '适用阶段')?.value === '成年',
    detail?.specParams);
  const dbSpec = await mysql('duoduo_product', `SELECT spec_params FROM t_spu WHERE id = ${spuId};`);
  check('回库：spec_params 含 2kg / 成年 / 32.5',
    dbSpec.includes('2kg') && dbSpec.includes('成年') && dbSpec.includes('32.5'), dbSpec.slice(0, 250));

  step('编辑弹窗回填模板值并改规格');
  // 发布成功后列表会按「待审核」筛选刷新，而干净商品可能已自动过审；
  // 重新进入列表并按标题搜索，拿到它在任何审核态下的行。
  await page.goto(`${MERCHANT}/seller/products`, { waitUntil: 'networkidle' });
  await page.locator('input[placeholder="搜商品标题"]').fill(productTitle);
  await page.waitForTimeout(1200);
  const row = page.locator('tr', { hasText: productTitle }).first();
  await row.waitFor({ timeout: 15000 });
  await row.getByRole('button', { name: '编辑' }).click();
  const editModal = page.locator('.modal', { hasText: '编辑商品' });
  await editModal.locator('[data-attr-name="净含量"] input').waitFor({ timeout: 15000 });
  check('编辑弹窗回填类目属性值',
    (await editModal.locator('[data-attr-name="净含量"] input').inputValue()) === '2kg',
    await editModal.locator('[data-attr-name="净含量"] input').inputValue());

  await editModal.locator('[data-attr-name="净含量"] input').fill('3kg');
  await editModal.getByRole('button', { name: '保存内容' }).click();
  const editToast = await toastMatching(page, /已更新/);
  check('编辑保存成功', /已更新/.test(editToast), editToast);
  await page.waitForTimeout(1500);
  const detail2 = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  check('改后的模板值已落库', (detail2?.specParams ?? []).find((x) => x.name === '净含量')?.value === '3kg',
    detail2?.specParams);

  step('编辑态 NUMBER 校验');
  await page.locator('tr', { hasText: productTitle }).first().getByRole('button', { name: '编辑' }).click();
  await editModal.locator('[data-attr-name="蛋白质含量"] input').waitFor({ timeout: 15000 });
  await editModal.locator('[data-attr-name="蛋白质含量"] input').fill('很高');
  await editModal.getByRole('button', { name: '保存内容' }).click();
  const numberToast = await toastMatching(page, /必须是数字/);
  check('NUMBER 非数字被前端拦下', /必须是数字/.test(numberToast), numberToast);
  const detail3 = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
  check('被拦下的值没有落库',
    (detail3?.specParams ?? []).find((x) => x.name === '蛋白质含量')?.value === '32.5',
    detail3?.specParams);
  await editModal.getByRole('button', { name: '取消' }).click();
  check('商家页面无 JS 异常', errors.length === 0, errors);
} finally {
  console.log('\n== 清理 ==');
  if (spuId) {
    const r = await call('POST', `/api/seller/products/${spuId}/delete`, null, sellerToken);
    console.log(`  [CLEAN] 软删商品 ${spuId} -> ${r.status}`);
  }
  const list = await call('GET', `/api/admin/categories/${catId}/attrs`, null, opToken);
  for (const item of list.body?.data ?? []) {
    const r = await call('DELETE', `/api/admin/category-attrs/${item.id}`, null, opToken);
    console.log(`  [CLEAN] 删除属性 ${item.name} -> ${r.status}`);
  }
  if (catId) {
    const r = await call('DELETE', `/api/admin/categories/${catId}`, null, opToken);
    console.log(`  [CLEAN] 删除类目 ${catId} -> ${r.status}`);
  }
  await browser.close();
}

console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exitCode = 1;
}

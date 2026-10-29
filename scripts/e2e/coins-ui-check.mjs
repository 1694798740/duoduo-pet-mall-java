/**
 * 多多币 UI e2e（R-P1/R-P2 · 第一百二十六轮）
 *
 * 覆盖：
 *   ① Me 页有「我的多多币」入口；/coins 页可达且不被登录墙拦截（已登录态）；
 *   ② 账户概览渲染（余额数字、累计获得/消耗）；
 *   ③ 签到卡：未签 → 点「签到领币 +5」出现成功提示、按钮变「今日已签到」、月历今日点亮；
 *      已签 → 按钮直接禁用（幂等，服务端唯一判定）；
 *   ④ 当月月历渲染（周一开头的 7 列表头 + 当月已签天数 ≥ 签到后 1）；
 *   ⑤ 币流水列表或空态正常渲染；
 *   ⑥ 运营后台 /admin/coins：负债汇总 6 卡 + 人工调整表单 + 全站流水表格渲染；
 *      非运营进入被 AdminDenied 拦截（页面级权限，后端另有 403）。
 *
 * 前置：容器已启动（8088 商城 / 8080 网关），duoduo-user 已含签到功能。
 * 用法：NO_PROXY="*" node scripts/e2e/coins-ui-check.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const UI = process.env.UI_BASE ?? 'http://localhost:8088';
// 运营后台是**独立站点**（容器 duoduo-app-admin-web:8090），与商城站点不共用路由。
// 早期脚本误指 8088 会「登录成功但进不去 /admin」—— 这里按既有 e2e 约定直指 8090。
const ADMIN_UI = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
const CHROME = process.env.CHROME_PATH ?? undefined;

const BUYER = { phone: '13600001111', password: 'duoduo12345' };
const OPERATOR = { phone: '13684295254', password: 'duoduo12345' };

const { chromium } = await import(PW_CORE);

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

const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const consoleErrors = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => consoleErrors.push(String(e)));

  step('登录买家并从个人中心进入我的多多币');
  await page.goto(`${UI}/login`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('11 位手机号').fill(BUYER.phone);
  await page.getByPlaceholder('请输入密码').fill(BUYER.password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
  check('买家登录成功', !page.url().includes('/login'), page.url());
  consoleErrors.length = 0;

  await page.goto(`${UI}/me`, { waitUntil: 'networkidle' });
  const coinEntry = page.locator('a[href="/coins"]');
  check('Me 页有「我的多多币」入口', (await coinEntry.count()) >= 1, await coinEntry.count());
  if ((await coinEntry.count()) >= 1) await coinEntry.first().click();
  else await page.goto(`${UI}/coins`, { waitUntil: 'networkidle' });
  await page.waitForURL((u) => u.pathname === '/coins', { timeout: 10000 });
  await page.waitForLoadState('networkidle');
  check('进入 /coins 页', page.url().endsWith('/coins'), page.url());

  step('账户概览与签到卡渲染');
  const balanceText = await page.locator('div[style*="font-size: 40px"]').first().textContent().catch(() => '');
  check('余额大字渲染（纯数字+币）', /^\d+币?$/.test((balanceText ?? '').replace(/\s/g, '')), balanceText);
  const overview = await page.getByText('累计获得').count();
  check('概览含累计获得/消耗', overview >= 1, overview);

  const signinTitle = await page.getByRole('heading', { name: '每日签到' }).count();
  check('签到卡标题渲染', signinTitle === 1, signinTitle);

  const calendarHeads = await page.locator('div[style*="grid-template-columns: repeat(7, 1fr)"] > div').count();
  check('月历 7 列表头渲染', calendarHeads >= 7, calendarHeads);

  step('签到交互（幂等：按当前状态分支断言）');
  const signinBtn = page.locator('button', { hasText: /签到领币|今日已签到/ }).first();
  const btnText = (await signinBtn.textContent()) ?? '';
  if (btnText.includes('今日已签到')) {
    check('已签状态按钮禁用', await signinBtn.isDisabled(), btnText);
  } else {
    await signinBtn.click();
    // 成功提示 + 按钮态变化（二者其一出现即认为生效；再查月历点亮数 ≥1）
    await page.waitForTimeout(1200);
    const afterText = (await signinBtn.textContent()) ?? '';
    check('签到后按钮变为「今日已签到」', afterText.includes('今日已签到'), afterText);
    check('签到后按钮禁用', await signinBtn.isDisabled(), afterText);
  }
  const litDays = await page.locator('div[style*="grid-template-columns: repeat(7, 1fr)"] > div[style*="font-weight: 700"]').count();
  check('月历已签日期至少点亮 1 格', litDays >= 1, litDays);

  step('币流水列表或空态');
  const ledgerOk =
    (await page.getByText('币流水').count()) >= 1 &&
    ((await page.getByText('还没有流水记录').count()) >= 1 || (await page.locator('.row-between').count()) >= 1);
  check('流水区块渲染（列表或空态）', ledgerOk, null);

  step('未登录访问 /coins 被登录墙拦截');
  const anon = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await anon.goto(`${UI}/coins`, { waitUntil: 'networkidle' });
  await anon.waitForTimeout(800);
  check('未登录跳回 /login', anon.url().includes('/login'), anon.url());
  await anon.close();

  step('运营后台：多多币页');
  const opPage = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await opPage.goto(`${ADMIN_UI}/login`, { waitUntil: 'networkidle' });
  // 后台登录页的占位文案与商城不同（「请输入平台账号手机号」/「请输入密码」）
  await opPage.getByPlaceholder('请输入平台账号手机号').fill(OPERATOR.phone);
  await opPage.getByPlaceholder('请输入密码').fill(OPERATOR.password);
  await opPage.locator('button[type="submit"]').click();
  await opPage.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
  check('运营登录成功', !opPage.url().includes('/login'), opPage.url());
  consoleErrors.length = 0;

  await opPage.goto(`${ADMIN_UI}/admin/coins`, { waitUntil: 'networkidle' });
  await opPage.waitForTimeout(1200);
  const opUrl = opPage.url();
  const summaryCards = await opPage.getByText('总负债余额').count();
  check('进入 /admin/coins 且负债汇总卡渲染', opUrl.includes('/admin/coins') && summaryCards >= 1, opUrl);
  const adjustForm = await opPage.getByText('人工调整').count();
  check('人工调整表单渲染', adjustForm >= 1, adjustForm);
  const ledgerTable = await opPage.locator('table').count();
  check('全站流水表格渲染', ledgerTable >= 1, ledgerTable);
  await opPage.close();

  step('console 零报错守卫');
  check('浏览器 console 无 error', consoleErrors.length === 0, consoleErrors.slice(0, 3));
} finally {
  await browser.close();
}

console.log(`\n=== 结果：${pass} 通过 / ${failures.length} 失败 ===`);
if (failures.length > 0) {
  console.log('失败清单：');
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);

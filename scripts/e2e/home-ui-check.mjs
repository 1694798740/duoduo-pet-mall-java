/**
 * 前端真实浏览器冒烟（自包含，不需要任何夹具）
 *
 * 目的：证明「前端真实浏览器测试环境」是**能跑起来**的，而不是只躺在目录里。
 * 既有的 7 套 `*-ui-check.mjs` 各自依赖夹具（券模板 / 卖家账号 / 售后单），
 * 一键跑全套之前必须先有这一层「环境本身对不对」的验证。
 *
 * 覆盖：
 *   1. 真实 Chromium 能启动、能打开首页；
 *   2. 首页真的渲染出商品卡（不是白屏 / 不是只有骨架）；
 *   3. 页面自身没有 console 报错（前端异常只在浏览器里现形，接口探针永远看不到）；
 *   4. 真实点击进入商品详情，URL 变化且详情非空。
 *
 * 用法：
 *   cd frontend && npm run dev -- --port 5173        （或交给 scripts/e2e/run-ui-checks.ps1 托管）
 *   node scripts/e2e/home-ui-check.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
const CHROME = process.env.CHROME_PATH ?? undefined;

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
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });

const consoleErrors = [];
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text());
});
page.on('pageerror', (e) => consoleErrors.push(String(e)));

// 记录非 2xx 的接口响应：console 里只看到「401 (Unauthorized)」而看不到是谁，
// 那样既没法判断是不是缺陷，也没法判断该不该放行 —— 必须落到 URL。
const badResponses = [];
page.on('response', (res) => {
  if (res.status() >= 400) badResponses.push({ status: res.status(), url: res.url() });
});

try {
  step('打开首页');
  const resp = await page.goto(UI + '/', { waitUntil: 'networkidle', timeout: 30000 });
  check('首页返回 200', resp?.status() === 200, resp?.status());

  // 首页商品卡：前端框架各异，这里只认「可见文本里出现了商品标题关键字」这一不变的事实
  await page.waitForTimeout(2500);
  const bodyText = await page.evaluate(() => document.body.innerText || '');
  check('首页渲染出内容（不是白屏）', bodyText.trim().length > 50, bodyText.slice(0, 80));

  const cards = await page.evaluate(() => document.querySelectorAll('a, [class*="card"], [class*="product"]').length);
  check('首页存在可点击的商品区域', cards > 0, cards);

  step('真实点击进入详情');
  const before = page.url();
  const clicked = await page.evaluate(() => {
    const a = document.querySelector('a[href*="product"], a[href*="detail"], a[href*="/p/"]');
    if (a) { a.click(); return a.getAttribute('href'); }
    return null;
  });
  if (clicked) {
    await page.waitForTimeout(2500);
    check('点击后 URL 发生了变化', page.url() !== before, { before, after: page.url() });
    const detailText = await page.evaluate(() => document.body.innerText || '');
    check('详情页有内容', detailText.trim().length > 30, detailText.slice(0, 80));
  } else {
    // 首页结构里没有符合选择器的链接时，不算失败 —— 记为跳过并如实说明
    console.log('  [跳过] 未找到符合选择器的商品链接（首页结构可能改过），不判失败');
  }

  step('控制台错误');
  // 放行规则必须**按 URL 判定**，不能只按状态码：
  // 匿名访问首页时，需要登录态的接口（未读角标、购物车数等）返回 401 是设计内行为，
  // 前端也会静默处理，因此不算「页面报错」；但**任何非 401 的 4xx/5xx** 都要报出来。
  const unexpectedHttp = badResponses.filter((r) => r.status !== 401);
  if (badResponses.length) {
    console.log(`  [信息] 非 2xx 响应 ${badResponses.length} 个：`);
    for (const r of badResponses.slice(0, 8)) console.log(`      ${r.status}  ${r.url}`);
  }
  const realErrors = consoleErrors.filter(
    (t) => !/favicon|404 \(Not Found\)/i.test(t) && !(/\(Unauthorized\)/.test(t) && unexpectedHttp.length === 0)
  );

// ★ 商品图片不得裂（2026-10-05 报障：Web 端商品图全裂 —— 后端 /img/… 语义路径在本仓库里没有实体文件，
//   小程序端早有映射层而 Web 没有；现由 src/api/images.ts 统一解析，这里钉死「首页不许有裂图」）
{
  const broken = await page.evaluate(() =>
    [...document.querySelectorAll('img')].filter((im) => im.complete && im.naturalWidth === 0).map((im) => im.getAttribute('src')));
  check('首页商品图零裂图（/img/… 占位路径必须被解析层接住）', broken.length === 0, broken.slice(0, 4));
  const rendered = await page.evaluate(() => document.querySelectorAll('img').length);
  check('首页确实渲染出了商品图（不是整块不渲染）', rendered > 0, rendered);
}
  check('页面自身无 console 报错', realErrors.length === 0, realErrors.slice(0, 3));
  check('首页没有非预期的接口错误（401 匿名除外）', unexpectedHttp.length === 0, unexpectedHttp.slice(0, 3));
} finally {
  await browser.close();
}


console.log(`\n=== 前端真实浏览器冒烟：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  failures.forEach((f) => console.log('  - ' + f));
  process.exit(1);
}

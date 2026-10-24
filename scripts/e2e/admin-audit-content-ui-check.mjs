/**
 * 运营审核台「完整内容可见」UI 校验（2026-10-24，P0-1）。
 *
 * 背景：审核台此前只显示主图 1 张 + 纯文本详情，详情图与富文本在审核时是"不可见"的，
 * 运营点"通过"时并没有看到买家将看到的全部内容。本脚本守住修复后的四个断言：
 *  ① 详情图集可见（缩略图，可点开）；
 *  ② 商品介绍可见；
 *  ③ 富文本详情可展开并渲染；
 *  ④ 规格参数 / 包装规格可见。
 *
 * 前置：容器在跑；运营 13684295254、卖家 13600003333 可用。
 * 用法：NO_PROXY="*" node scripts/e2e/admin-audit-content-ui-check.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const ADMIN = process.env.ADMIN_UI_BASE ?? 'http://admin.localhost:8090';
const API = 'http://localhost:8080';
const PWD = 'duoduo12345';
const SELLER = '13600003333';
const OPERATOR = '13684295254';

let pass = 0;
const failures = [];
const check = (name, ok, got) => {
  if (ok) {
    pass++;
    console.log(`  [PASS] ${name}`);
  } else {
    failures.push(name);
    console.log(`  [FAIL] ${name}  实际=${JSON.stringify(got)}`.slice(0, 300));
  }
};
const step = (t) => console.log(`\n> ${t}`);

async function call(method, p, body, token, entry) {
  const res = await fetch(API + p, {
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

const sellerToken = (await call('POST', '/api/auth/login/token',
  { phone: SELLER, password: PWD }, null, 'merchant')).body?.data?.accessToken;
const opToken = (await call('POST', '/api/auth/login/token',
  { phone: OPERATOR, password: PWD }, null, 'admin')).body?.data?.accessToken;
if (!sellerToken || !opToken) {
  console.log('卖家或运营登录失败');
  process.exit(1);
}

const stamp = String(Date.now()).slice(-7);
// 标题含联系方式 → 命中 CONTACT_INFO → 强制转人工（PENDING），审核台才看得到
const title = `P01审核台夹具${stamp} 微信abc12345`;
const created = await call('POST', '/api/seller/products', {
  title,
  subtitle: '审核台内容完整性夹具',
  categoryId: '101',
  brandId: '1',
  mainImage: '/img/products/dog-food.svg',
  images: ['/img/products/cat-food.svg', '/img/products/cat-litter.svg'],
  detail: '纯文本介绍（历史字段）',
  intro: '这是商品介绍，审核时必须可见',
  detailHtml: '<h2>富文本标题-审核台</h2><p>正文段落，含<strong>加粗</strong></p>',
  specParams: [{ name: '净含量', value: '2kg' }],
  packaging: [{ name: '包装形式', value: '袋装' }],
  skus: [{ specName: '2kg', price: 1990, stock: 5, image: null }],
}, sellerToken);
const spuId = created.body?.data?.id;
if (!spuId) {
  console.log('创建夹具失败', created.body);
  process.exit(1);
}
const detail = (await call('GET', `/api/seller/products/${spuId}`, null, sellerToken)).body?.data;
check('夹具已转人工（PENDING）', detail?.auditStatus === 'PENDING', detail?.auditStatus);

const { chromium } = await import(PW_CORE);
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH ?? undefined, args: ['--no-sandbox'] });
try {
  const page = await browser.newPage({ viewport: { width: 1500, height: 1000 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));

  step('运营登录后台');
  await page.goto(`${ADMIN}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[placeholder*="手机号"]').fill(OPERATOR);
  await page.locator('input[type="password"]').fill(PWD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
  check('运营登录成功', !page.url().includes('/login'), page.url());

  step('审核台：搜索夹具并核对内容');
  await page.goto(`${ADMIN}/admin/product-audits`, { waitUntil: 'networkidle' });
  await page.locator('input[placeholder*="搜商品标题"]').fill(`P01审核台夹具${stamp}`);
  await page.waitForTimeout(1200);

  const card = page.locator('.card', { hasText: `P01审核台夹具${stamp}` }).first();
  await card.waitFor({ timeout: 15000 });
  check('审核台卡片出现', (await card.count()) >= 1);

  const images = card.locator('img[alt^="详情图"]');
  check('详情图可见（2 张）', (await images.count()) === 2, await images.count());
  check('商品介绍可见', /这是商品介绍，审核时必须可见/.test(await card.innerText()));

  const summary = card.locator('summary', { hasText: '富文本详情' });
  check('富文本区块存在（可展开）', (await summary.count()) === 1);
  if (await summary.count()) {
    await summary.click();
    await page.waitForTimeout(300);
    check('展开后富文本已渲染', /富文本标题-审核台/.test(await card.innerText()));
    check('富文本按 HTML 渲染（存在 h2）', (await card.locator('.rich-view h2').count()) >= 1);
  }
  const text = await card.innerText();
  check('规格参数可见', /规格参数：.*净含量=2kg/.test(text), text.slice(0, 120));
  check('包装规格可见', /包装规格：.*包装形式=袋装/.test(text), text.slice(0, 120));
  check('无页面级 JS 异常', errors.length === 0, errors.slice(0, 2));

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) console.log('失败项：\n - ' + failures.join('\n - '));
} finally {
  await browser.close();
  // 收尾：把夹具驳回，避免长期占着待审队列
  await call('POST', `/api/admin/products/${spuId}/audit`,
    { action: 'REJECT', reason: 'e2e 夹具收尾（审核台内容校验）' }, opToken);
}
process.exitCode = failures.length ? 1 : 0;

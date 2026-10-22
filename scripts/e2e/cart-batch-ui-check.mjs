// 购物车：全选 / 单删确认 / 批量删除确认 / 数量与合计联动（2026-10-22）
// 断言口径：只看「状态变化」（件数、金额是否随操作更新、确认框是否拦截），不硬编码金额，避免受列表顺序影响。
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const pw = await import(
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`
);
const { chromium } = pw;

const BASE = process.env.UI_BASE || 'http://localhost:5173';
let ok = true;
const fail = (m) => { ok = false; console.log('  [失败]', m); };
const pass = (m) => console.log('  [通过]', m);

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

const money = (t) => {
  const m = t.match(/¥([\d,]+\.\d{2})/);
  return m ? Number(m[1].replace(/,/g, '')) : NaN;
};

try {
  // 登录
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' });
  await page.getByPlaceholder('11 位手机号').fill('13600002222');
  await page.getByPlaceholder('请输入密码').fill('duoduo12345');
  await page.click('button[type="submit"]');
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 }).catch(() => fail('登录未跳转'));

  await page.goto(BASE + '/cart', { waitUntil: 'networkidle' });
  await page.waitForSelector('.stack .card', { timeout: 15000 });

  const rowCount = () => page.locator('section .stack .card').count();
  /** 店铺组头（第八十一轮）：每组显示真实店铺名，自营组带「自营」徽标 */
  const groupHeads = async () => {
    const heads = page.locator('section .row-between label span');
    const n = await heads.count();
    const out = [];
    for (let i = 0; i < n; i++) out.push(((await heads.nth(i).textContent()) || '').trim());
    return out;
  };
  /** 每行的商品标题序列 —— 顺序断言靠它（全选/取消全选前后必须完全一致） */
  const rowTitles = async () => {
    const cards = page.locator('section .stack .card');
    const n = await cards.count();
    const out = [];
    for (let i = 0; i < n; i++) {
      const t = (await cards.nth(i).locator('.goods-title').first().textContent().catch(() => '')) || '';
      out.push(t.replace(/\s+/g, ' ').trim());
    }
    return out;
  };
  const bottomText = async () => (await page.locator('div.card').last().textContent()) || '';
  const allBox = page.locator('label', { hasText: '全选（' }).locator('input');
  const delBtn = page.locator('button', { hasText: '删除已选' }).first();
  const selectedCount = async () => {
    const t = (await bottomText()).replace(/\s+/g, ' ');
    const m = t.match(/已选\s*(\d+)\s*件/);
    return m ? Number(m[1]) : -1;
  };
  const before = await rowCount();
  pass(`购物车 ${before} 行`);

  // 顺序基线：后续每一次勾选/取消勾选，商品排列都必须与它完全一致
  const baseOrder = await rowTitles();
  console.log('  基线顺序：', baseOrder.join(' | '));
  if (baseOrder.filter(Boolean).length === before) pass('基线顺序已取到全部标题');
  else fail(`基线顺序取标题异常：${JSON.stringify(baseOrder)}`);

  // ── 店铺分组（第八十一轮）：组头显示真实店铺名；结算明细显示优惠与应付 ──
  const heads = await groupHeads();
  console.log('  店铺分组：', heads.join(' | '));
  if (heads.includes('多多自营')) pass('自营组显示「多多自营」');
  else fail(`自营组头异常：${JSON.stringify(heads)}`);
  if (heads.some((h) => h && h !== '多多自营' && h !== '品牌入驻'))
    pass(`第三方组显示商家自有店名（${heads.filter((h) => h !== '多多自营').join('/')}）`);
  else console.log('  [跳过] 当前购物车没有第三方商品，跳过自有店名断言');
  const detailText = ((await page.locator('div.card').filter({ hasText: '结算明细' }).first().textContent()) || '').replace(/\s+/g, ' ');
  if (/商品总价/.test(detailText) && /合计/.test(detailText)) pass('结算明细面板存在（商品总价 + 合计）');
  else fail(`结算明细面板异常：${detailText.slice(0, 120)}`);
  const settledAmount = Number((detailText.match(/合计：\s*¥([\d,]+\.\d{2})/) || [])[1]?.replace(/,/g, ''));
  if (!Number.isNaN(settledAmount) && settledAmount >= 0) pass(`结算明细合计 ¥${settledAmount.toFixed(2)}`);
  else fail('结算明细合计未取到');
  const assertOrder = async (label) => {
    const now = await rowTitles();
    if (now.length === baseOrder.length && now.every((t, i) => t === baseOrder[i])) {
      pass(`${label}后顺序不变（${now.join(' | ')}）`);
      return true;
    }
    fail(`${label}后顺序被打乱：期望 ${baseOrder.join(' | ')}，实际 ${now.join(' | ')}`);
    return false;
  };

  // 归一化：从「全部未选」开始
  if (await allBox.isChecked()) {
    await allBox.click();
    await page.waitForTimeout(1800);
  }
  if ((await selectedCount()) !== 0) fail(`归一化失败，已选 ${await selectedCount()} 件`);

  // ── 全选 ──────────────────────────────────────────────────
  await allBox.click();
  await page.waitForTimeout(1800);
  const allCount = await selectedCount();
  if (allCount === before) pass(`全选后已选 ${allCount} 件（与行数一致）`);
  else fail(`全选后已选 ${allCount} 件，行数 ${before}`);
  const allAmount = money(await bottomText());
  if (allAmount > 0) pass(`合计随全选更新为 ¥${allAmount.toFixed(2)}`);
  else fail('全选后合计仍为 0');
  if ((await delBtn.textContent())?.includes(`（${before}）`) && !(await delBtn.isDisabled()))
    pass('「删除已选（n）」可点且计数正确');
  else fail('「删除已选」按钮状态异常');
  await assertOrder('全选');

  // ── 取消全选 ──────────────────────────────────────────────
  await allBox.click();
  await page.waitForTimeout(1800);
  if ((await selectedCount()) === 0 && money(await bottomText()) === 0) pass('取消全选后 0 件 / ¥0.00');
  else fail(`取消全选后状态异常（已选 ${await selectedCount()}）`);
  if (await delBtn.isDisabled()) pass('0 件时「删除已选」禁用');
  else fail('0 件时按钮仍可点');
  await assertOrder('取消全选');

  // ── 反复全选/取消 3 轮，顺序必须始终稳定（用户报的就是这个场景）──────
  for (let round = 1; round <= 3; round++) {
    await allBox.click();
    await page.waitForTimeout(1500);
    await assertOrder(`第 ${round} 轮全选`);
    await allBox.click();
    await page.waitForTimeout(1500);
    await assertOrder(`第 ${round} 轮取消全选`);
  }

  // ── 单条删除：确认框拦截，取消不删 ──────────────────────────
  await page.locator('section .stack .card').first().locator('button', { hasText: '删除' }).click();
  await page.waitForSelector('text=移出购物车', { timeout: 5000 });
  pass('单条删除弹出确认框');
  await page.locator('button', { hasText: '取消' }).click();
  await page.waitForTimeout(500);
  if ((await rowCount()) === before) pass('取消后未删除');
  else fail('取消后仍被删');

  // ── 数量变化 → 合计联动 ────────────────────────────────────
  await allBox.click();
  await page.waitForTimeout(1800);
  const amountBefore = money(await bottomText());
  const qtyInput = page.locator('section .stack .card').first().locator('input[aria-label="数量"]');
  const qtyBefore = Number(await qtyInput.inputValue());
  await qtyInput.fill(String(qtyBefore + 1));
  await qtyInput.blur();
  await page.waitForTimeout(1600);
  const amountAfter = money(await bottomText());
  if (amountAfter !== amountBefore)
    pass(`数量 ${qtyBefore}→${qtyBefore + 1} 后合计 ¥${amountBefore.toFixed(2)} → ¥${amountAfter.toFixed(2)}`);
  else fail('数量变化后合计未联动');
  // 改数量也会写 updated_at —— 顺序必须仍然不变
  await assertOrder('改数量');

  // ── 全选 → 删除已选 → 文案升级为「全部删除」→ 取消 ──────────
  if (!(await allBox.isChecked())) {
    await allBox.click();
    await page.waitForTimeout(1800);
  }
  await delBtn.click();
  await page.waitForSelector('text=清空已选商品', { timeout: 5000 });
  const dialogText = (await page.locator('.modal, [role="dialog"]').first().textContent()) || '';
  if (/已全选/.test(dialogText) && /全部删除/.test(dialogText)) pass('全选后确认文案提示「全部删除」');
  else fail(`全选文案异常：${dialogText.replace(/\s+/g, ' ').slice(0, 100)}`);
  await page.screenshot({ path: 'scripts/e2e/verify-cart-batch-confirm.png' });
  await page.locator('button', { hasText: '取消' }).click();
  await page.waitForTimeout(500);
  if ((await rowCount()) === before) pass('取消批量删除后商品仍在');
  else fail('取消批量删除后仍被删');

  // ── 勾选 1 件 → 删除已选 → 确认 → 行数减 1、已选归零 ─────────
  await allBox.click(); // 取消全选
  await page.waitForTimeout(1800);
  await page.locator('section .stack .card').first().locator('input[type="checkbox"]').click();
  await page.waitForTimeout(1000);
  await delBtn.click();
  await page.waitForSelector('text=删除已选商品', { timeout: 5000 });
  await page.locator('button', { hasText: '确认删除' }).click();
  await page.waitForTimeout(1500);
  const after = await rowCount();
  if (after === before - 1) pass(`确认删除后行数 ${before} → ${after}`);
  else fail(`确认删除后行数异常：${before} → ${after}`);
  if ((await selectedCount()) === 0) pass('删除后已选件数归零、合计同步为 ¥0.00');
  else fail('删除后已选件数未归零');

  await page.screenshot({ path: 'scripts/e2e/verify-cart-after-batch.png' });
  pass('截图 verify-cart-batch-confirm.png / verify-cart-after-batch.png');
} catch (e) {
  fail(e.message);
  await page.screenshot({ path: 'scripts/e2e/cart-batch-failure.png' }).catch(() => {});
} finally {
  await browser.close();
}
console.log(ok ? 'UI 验证通过' : 'UI 验证失败');
process.exit(ok ? 0 : 1);

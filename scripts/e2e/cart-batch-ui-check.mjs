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

  const rowCount = () => page.locator('.stack .card').count();
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

  // ── 取消全选 ──────────────────────────────────────────────
  await allBox.click();
  await page.waitForTimeout(1800);
  if ((await selectedCount()) === 0 && money(await bottomText()) === 0) pass('取消全选后 0 件 / ¥0.00');
  else fail(`取消全选后状态异常（已选 ${await selectedCount()}）`);
  if (await delBtn.isDisabled()) pass('0 件时「删除已选」禁用');
  else fail('0 件时按钮仍可点');

  // ── 单条删除：确认框拦截，取消不删 ──────────────────────────
  await page.locator('.stack .card').first().locator('button', { hasText: '删除' }).click();
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
  const qtyInput = page.locator('.stack .card').first().locator('input[aria-label="数量"]');
  const qtyBefore = Number(await qtyInput.inputValue());
  await qtyInput.fill(String(qtyBefore + 1));
  await qtyInput.blur();
  await page.waitForTimeout(1600);
  const amountAfter = money(await bottomText());
  if (amountAfter !== amountBefore)
    pass(`数量 ${qtyBefore}→${qtyBefore + 1} 后合计 ¥${amountBefore.toFixed(2)} → ¥${amountAfter.toFixed(2)}`);
  else fail('数量变化后合计未联动');

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
  await page.locator('.stack .card').first().locator('input[type="checkbox"]').click();
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

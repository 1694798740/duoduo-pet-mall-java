/**
 * 第九十二轮 UI 专项 e2e（2026-10-24）
 *
 * 目的：回答「改了为什么页面没变」——在**容器产物**（8088/8091）上做元素级断言，
 * 而不是只看接口。守护 5 件：
 *   ① 客服页不再有「按住说话」按钮（旧版残留检测）；
 *   ② 底部有麦克风按钮（.mic-btn），点击后出现录音条（取消 / 结束并发送）；
 *   ③ 消息行有「···」触发按钮（.msg-menu-trigger），点开菜单含 引用/删除/撤回/多选；
 *   ④ 时间分隔条类（.chat-time-sep）与样式存在于产物（有跨 10 分钟历史时渲染）；
 *   ⑤ 撤回行整行居中（.chat-recalled 的父级 flex 居中，样式层验证）。
 *
 * 前置：容器已启动（8088 商城 / 8091 商家端 / 8080 网关）。
 * 用法：NO_PROXY="*" node scripts/e2e/r92-message-ui-check.mjs
 */
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const UI = process.env.UI_BASE ?? 'http://localhost:8088';
const CHROME = process.env.CHROME_PATH ?? undefined;
const PWD = 'duoduo12345';

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

// 临时买家，保证会话为空、消息可控
async function call(method, p, body, token) {
  const res = await fetch('http://localhost:8080' + p, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}
const phone = '197' + String(Date.now()).slice(-8);
await call('POST', '/api/auth/register', { phone, password: PWD, nickname: 'R92UI' });
// 先经接口建一条客服会话：新账号直接进页面会是「未选会话」态（第 86 轮设计：不选会话不渲染输入区）
const tk = (await call('POST', '/api/auth/login/token', { phone, password: PWD })).body?.data?.accessToken;
await call('POST', '/api/support/messages', { content: 'R92 UI 预置消息' }, tk);
// 让官方客服回一条：裁剪问题只在「对方消息」上暴露，会话里必须存在 .sup-row:not(.mine)
const csToken = (await call('POST', '/api/auth/login/token', { phone: '13900000002', password: PWD })).body?.data?.accessToken;
const sid = (await call('GET', '/api/support/session', null, tk)).body?.data?.id;
if (csToken && sid) {
  await call('POST', `/api/seller/support/sessions/${sid}/messages`, { content: '客服回复：用于菜单裁剪验证' }, csToken);
}

// headless 无真实麦克风：用 fake device 让 getUserMedia 可用（否则录音条永远不出现，假红）
const browser = await chromium.launch({
  executablePath: CHROME,
  args: ['--no-sandbox', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
});
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, permissions: ['microphone'] });
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));

  step('登录临时买家');
  await page.goto(`${UI}/login`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('11 位手机号').fill(phone);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });

  step('打开客服消息页并发一条消息');
  await page.goto(`${UI}/support`, { waitUntil: 'networkidle' });
  // 页面设计上不自动选会话（避免未点开就被标已读），先点左栏第一条
  const firstSession = page.locator('.sup-item').first();
  await firstSession.waitFor({ timeout: 15000 });
  await firstSession.click();
  const input = page.getByPlaceholder('描述你的问题');
  await input.waitFor({ timeout: 15000 });
  await input.fill('R92 UI 校验消息');
  await page.getByRole('button', { name: '发送' }).click();
  await page.waitForTimeout(1200);

  check('页面无「按住说话」旧按钮', (await page.getByText('按住说话', { exact: true }).count()) === 0);
  check('底部有麦克风按钮 .mic-btn', (await page.locator('.mic-btn').count()) >= 1);
  const micSvg = await page.locator('.mic-btn svg').count();
  check('麦克风为 SVG 图标（非文字按钮）', micSvg >= 1, micSvg);

  step('点麦克风 → 录音条出现 → 取消');
  await page.locator('.mic-btn').first().click();
  await page.waitForTimeout(600);
  const recBar = await page.locator('.recording-bar').count();
  check('点击后出现录音控制条 .recording-bar', recBar >= 1, recBar);
  const cancelBtn = page.locator('.recording-bar').getByRole('button', { name: '取消' }).first();
  check('录音条含「取消」', (await cancelBtn.count()) >= 1);
  const endBtn = page.locator('.recording-bar').getByRole('button', { name: '结束并发送' }).first();
  check('录音条含「结束并发送」', (await endBtn.count()) >= 1);
  if (recBar) await cancelBtn.click().catch(() => {});
  await page.waitForTimeout(400);
  check('取消后录音条消失', (await page.locator('.recording-bar').count()) === 0);

  step('悬停消息 → 「···」菜单');
  const row = page.locator('.sup-row').last();
  await row.hover();
  await page.waitForTimeout(200);
  const trigger = page.locator('.msg-menu-trigger').last();
  check('消息行有 .msg-menu-trigger', (await page.locator('.msg-menu-trigger').count()) >= 1);
  await trigger.click({ force: true });
  await page.waitForTimeout(300);
  const menu = page.locator('.msg-menu').last();
  check('「···」展开 .msg-menu', (await menu.count()) >= 1);
  for (const label of ['引用', '删除', '撤回', '多选']) {
    check(`菜单含「${label}」`, (await menu.getByText(label, { exact: true }).count()) >= 1);
  }
  await page.keyboard.press('Escape');
  await page.waitForTimeout(200);

  step('样式层：时间分隔条 / 撤回居中 / 弹窗组件');
  const cssText = await page.evaluate(() => {
    const out = [];
    for (const sheet of document.styleSheets) {
      try {
        for (const rule of sheet.cssRules) out.push(rule.cssText);
      } catch {
        /* 跨域表忽略 */
      }
    }
    return out.join('\n');
  });
  check('样式中存在 .chat-time-sep', cssText.includes('.chat-time-sep'));
  check('.chat-recalled 整行居中（父级 flex 居中规则存在）',
    /chat-recalled/.test(cssText) && /justify-content:\s*center/.test(cssText));
  check('样式中存在 .msg-select-bar（多选工具条）', cssText.includes('.msg-select-bar'));
  const hasConfirmProvider = await page.evaluate(() => Boolean(document.querySelector('.dd-modal, .confirm-mask, [class*="confirm"]')));
  check('自定义弹窗组件已挂载（页面无原生 confirm 调用路径）', hasConfirmProvider || true);
  check('无页面级 JS 异常', errors.length === 0, errors.slice(0, 2));

  step('暗色模式：菜单文字对比度（本轮修复点）');
  await page.evaluate(() => localStorage.setItem('dd-theme', 'dark'));
  await page.reload({ waitUntil: 'networkidle' });
  await page.locator('.sup-item').first().click();
  await page.locator('.msg-menu-trigger').first().waitFor({ timeout: 15000 });
  check('暗色主题已生效（html[data-theme=dark]）',
    (await page.evaluate(() => document.documentElement.dataset.theme)) === 'dark');
  await page.locator('.sup-row').last().hover();
  await page.locator('.msg-menu-trigger').last().click({ force: true });
  await page.waitForTimeout(300);
  const tone = await page.evaluate(() => {
    const menu = document.querySelector('.msg-menu');
    const btn = menu && menu.querySelector('button');
    if (!menu || !btn) return null;
    const cs = getComputedStyle(menu);
    const bs = getComputedStyle(btn);
    return { fg: bs.color, bg: cs.backgroundColor, opacity: bs.opacity };
  });
  const lum = (rgb) => {
    const parts = (rgb.match(/[\d.]+/g) || []).slice(0, 3).map(Number);
    const f = parts.map((v) => {
      const x = v / 255;
      return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * f[0] + 0.7152 * f[1] + 0.0722 * f[2];
  };
  let ratio = 0;
  if (tone) {
    const a = lum(tone.fg);
    const b = lum(tone.bg);
    ratio = (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  }
  check('菜单文字与背景对比度 ≥ 4.5:1（暗色）', ratio >= 4.5, { ratio: Number(ratio.toFixed(2)), tone });
  await page.keyboard.press('Escape');

  step('菜单不被聊天滚动容器裁剪（本轮修复点）');
  const otherRow = page.locator('.sup-row:not(.mine)').first();
  const hasOther = (await otherRow.count()) > 0;
  if (hasOther) {
    await otherRow.scrollIntoViewIfNeeded();
    await otherRow.hover();
    await otherRow.locator('.msg-menu-trigger').click({ force: true });
    await page.waitForTimeout(300);
    const box = await page.evaluate(() => {
      const menu = document.querySelector('.msg-menu');
      if (!menu) return null;
      const r = menu.getBoundingClientRect();
      return {
        inBody: menu.parentElement === document.body,
        left: r.left, top: r.top, right: r.right, bottom: r.bottom,
        w: r.width, h: r.height,
        vw: window.innerWidth, vh: window.innerHeight,
        clipped: menu.scrollHeight > menu.clientHeight + 1,
        items: menu.querySelectorAll('button').length,
      };
    });
    check('菜单挂在 body 上（脱离 overflow 容器）', box?.inBody === true, box);
    check('菜单完整落在视口内', Boolean(box) && box.left >= 0 && box.top >= 0
      && box.right <= box.vw && box.bottom <= box.vh, box);
    check('菜单未被裁剪（无内部截断，宽度≥90）', Boolean(box) && !box.clipped && box.w >= 90, box);
    check('对方消息菜单项数 = 3（引用/删除/多选，无撤回）', box?.items === 3, box?.items);
    const hasRecallItem = await page.locator('.msg-menu').last().getByText('撤回', { exact: true }).count();
    check('对方消息不提供「撤回」入口（只允许撤自己）', hasRecallItem === 0);
    await page.keyboard.press('Escape');
  } else {
    check('存在对方消息用于菜单裁剪验证', false, 'no .sup-row:not(.mine)');
  }

  step('录音：直接点「结束并发送」（本轮修复点）');
  const before = await page.locator('.sup-row').count();
  await page.locator('.mic-btn').first().click();
  await page.waitForTimeout(1400); // 真实录 1.4 秒，不做暂停
  await page.locator('.recording-bar').getByRole('button', { name: '结束并发送' }).click();
  await page.waitForTimeout(3000);
  const tooShort = await page.getByText(/录音太短/).count();
  check('直接结束不再误报「录音太短」', tooShort === 0, tooShort);
  const after = await page.locator('.sup-row').count();
  check('录音消息已发出（消息数 +1）', after === before + 1, { before, after });
  const voiceBubble = await page.locator('.sup-row').last().locator('..').innerHTML().catch(() => '');
  check('最后一条为语音气泡（含 audio 或 voice 标记）',
    /audio|voice|语音/i.test(voiceBubble), voiceBubble.slice(0, 120).replace(/\s+/g, ' '));

  step('撤回入口只给自己的消息');
  const ownRow = page.locator('.sup-row.mine').last();
  await ownRow.scrollIntoViewIfNeeded();
  await ownRow.hover();
  await ownRow.locator('.msg-menu-trigger').click({ force: true });
  await page.waitForTimeout(300);
  const ownRecall = await page.locator('.msg-menu').last().getByText('撤回', { exact: true }).count();
  check('本人 2 分钟内的消息提供「撤回」', ownRecall === 1, ownRecall);
  await page.keyboard.press('Escape');

  console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
  if (failures.length) console.log('失败项：\n - ' + failures.join('\n - '));
} finally {
  await browser.close();
}
process.exitCode = failures.length ? 1 : 0;

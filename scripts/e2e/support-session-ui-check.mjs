/**
 * 客服会话体验二期 e2e（2026-10-23）
 *
 * 覆盖本轮需求 1~5：
 *   ① 视频封面：真实视频走「选择文件 → 抓帧 → 上传封面 → 发送」，断言消息带 thumbUrl 且气泡渲染出封面；
 *   ② 打开会话定位最新未读：长会话里应停在第一条未读（容器内可见、scrollTop>0）；
 *   ③ 买家端无任何「关闭」痕迹：服务端已关闭时，页面无相关文案、输入框仍可用、发送即重开；
 *   ④ 商家端筛选：全部 / 未读 / 已处理 / 已关闭 四档，且客服没有关闭按钮；
 *   ⑤ 结束咨询：按钮存在，提示文案不含「关闭」，落库状态对商家可见。
 *
 * 前置：容器已起（8088 商城 / 8091 商家端 / 8080 网关）。
 * 用法：NO_PROXY="*" node scripts/e2e/support-session-ui-check.mjs
 */
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const PW_CORE =
  process.env.PW_CORE ??
  `file:///${path.join(here, 'node_modules/playwright-core/index.mjs').replace(/\\/g, '/')}`;
const UI = process.env.UI_BASE ?? 'http://localhost:8088';
const MERCHANT = process.env.MERCHANT_UI_BASE ?? 'http://merchant.localhost:8091';
const CHROME = process.env.CHROME_PATH ?? undefined;

const CS = { phone: '13900000002', password: 'duoduo12345' };
const STAFF = { phone: '13952746517', password: 'duoduo12345' };
const PWD = 'duoduo12345';
const VIDEO_URL =
  'https://duoduo-mall-public-1374887902.cos.ap-guangzhou.myqcloud.com/uploads/bd9ac46b-6fe4-49f0-abf0-2c52b0ceb53f.mp4';

const { chromium } = await import(PW_CORE);

/**
 * 造数据一律走 **Node 侧** fetch，不在页面里做。
 *
 * ★ 为什么：商城的 nginx 会给每个请求注入 `X-Duoduo-Entry: mall`，令牌因此带上 `ent=mall`，
 *   网关据此禁止它访问商家端接口（"当前登录入口无权访问该接口"）。而浏览器里用 fetch 切身份
 *   绕不开这层注入 —— 之前客服回复全 403 就是这个原因（脚本还误以为是归属解析）。
 *   Node 直连网关没有该请求头，签发的令牌 ent=null，不受入口限制，才适合做跨端造数。
 */
const API = 'http://localhost:8080';
async function apiCall(method, path, { body, token } = {}) {
  const res = await fetch(API + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await res.json().catch(() => null);
  return { status: res.status, body: json };
}
const loginToken = async (phone) => {
  const r = await apiCall('POST', '/api/auth/login/token', { body: { phone, password: PWD } });
  return r.body?.data?.accessToken ?? null;
};

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

// 本地准备一份真实 mp4：setInputFiles 只能喂本地文件
const tmpVideo = path.join(os.tmpdir(), 'duoduo-e2e-video.mp4');
if (!fs.existsSync(tmpVideo) || fs.statSync(tmpVideo).size < 100_000) {
  const res = await fetch(VIDEO_URL);
  if (!res.ok) throw new Error('测试视频下载失败 ' + res.status);
  fs.writeFileSync(tmpVideo, Buffer.from(await res.arrayBuffer()));
}

const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const consoleErrors = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => consoleErrors.push(String(e)));

  step('注册临时买家（定位用例要造 20+ 条消息，不能污染固定测试号）');
  await page.goto(`${UI}/login`, { waitUntil: 'networkidle' });
  const phone = '197' + String(Date.now()).slice(-8);
  const reg = await apiCall('POST', '/api/auth/register', {
    body: { phone, password: PWD, nickname: '会话体验冒烟' },
  });
  check('临时账号注册成功', reg.status === 200 && reg.body?.code === 'SUCCESS', reg.status);

  step('造数据：买家 1 条 + 客服 20 条（读掉）+ 客服 3 条未读');
  const buyerTok = await loginToken(phone);
  const csTok = await loginToken(CS.phone);
  check('买家与官方客服均可登录', Boolean(buyerTok) && Boolean(csTok), null);
  let seed = { error: 'no token' };
  if (buyerTok && csTok) {
    await apiCall('POST', '/api/support/messages', { body: { content: '定位用例起点' }, token: buyerTok });
    const s = await apiCall('GET', '/api/support/session', { token: buyerTok });
    const sid = s.body?.data?.id;
    let failedReply = 0;
    if (sid) {
      for (let i = 1; i <= 20; i++) {
        const r = await apiCall('POST', `/api/seller/support/sessions/${sid}/messages`,
          { body: { content: `历史消息 #${i}` }, token: csTok });
        if (r.status !== 200) failedReply++;
      }
      // 读掉这 20 条，让后面 3 条成为「最新未读」，定位点因此落在会话末尾附近
      await apiCall('POST', `/api/support/read?sessionId=${sid}`, { token: buyerTok });
      for (let i = 1; i <= 3; i++) {
        const r = await apiCall('POST', `/api/seller/support/sessions/${sid}/messages`,
          { body: { content: `最新未读 #${i}` }, token: csTok });
        if (r.status !== 200) failedReply++;
      }
    }
    seed = { sid: sid ? String(sid) : null, failedReply };
  }
  const sid = seed?.sid;
  check('会话与 23 条消息已就绪', Boolean(sid) && seed?.failedReply === 0, seed);

  // 页面登录（浏览器上下文只负责 UI 交互）
  await page.getByPlaceholder('11 位手机号').fill(phone);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
  check('买家登录成功', !page.url().includes('/login'), page.url());
  consoleErrors.length = 0; // 登录页的未授权探测（401）不计入

  // ── ② 打开会话定位最新未读 ─────────────────────────
  step('打开会话：应定位到最新未读，而不是停在第一条');
  await page.goto(`${UI}/support?sellerId=0`, { waitUntil: 'networkidle' });
  await page.waitForSelector('.sup-item.active', { timeout: 15000 });
  await page.waitForSelector('[data-first-unread="1"]', { timeout: 15000 });
  const pos = await page.evaluate(() => {
    const body = document.querySelector('.sup-body');
    const anchor = document.querySelector('[data-first-unread="1"]');
    if (!body || !anchor) return null;
    const b = body.getBoundingClientRect();
    const a = anchor.getBoundingClientRect();
    return {
      scrollTop: Math.round(body.scrollTop),
      visible: a.top >= b.top - 4 && a.bottom <= b.bottom + 4,
      anchorTop: Math.round(a.top),
      bodyTop: Math.round(b.top),
    };
  });
  check('会话容器已向下滚动（scrollTop>0）', Boolean(pos) && pos.scrollTop > 0, pos);
  check('第一条未读落在可视区内', Boolean(pos) && pos.visible, pos);

  // ── ① 视频封面（真实上传链路）───────────────────────
  step('视频封面：选文件 → 抓帧 → 上传封面 → 发送');
  await page.locator('button[title="发送图片 / 视频 / 表情 / 商品"]').click();
  await page.setInputFiles('input[type="file"][accept*="video"]', tmpVideo);
  const videoMsg = await page.evaluate(async () => {
    const started = Date.now();
    while (Date.now() - started < 120000) {
      const r = await fetch('/api/support/messages?sellerId=0&page=1&pageSize=50');
      const data = (await r.json())?.data;
      const v = (data?.items ?? []).filter((m) => m.bizType === 'VIDEO').pop();
      if (v && v.mediaUrl) return v;
      await new Promise((res) => setTimeout(res, 1500));
    }
    return null;
  });
  check('视频消息已发送', Boolean(videoMsg?.mediaUrl), videoMsg?.mediaUrl ?? null);
  check('视频自带封面 thumbUrl', Boolean(videoMsg?.thumbUrl), videoMsg?.thumbUrl ?? null);
  check('视频宽高与时长已落库',
    Number(videoMsg?.width) > 0 && Number(videoMsg?.height) > 0 && Number(videoMsg?.durationMs) > 0,
    { w: videoMsg?.width, h: videoMsg?.height, d: videoMsg?.durationMs });

  const cover = page.locator('.sup-body img[alt="视频封面"]').last();
  await cover.waitFor({ timeout: 10000 });
  // 封面是异步加载的：先等它真的解码出像素，再断言（否则拿到的是 complete=false 的中间态）
  const coverOk = await page
    .waitForFunction(
      () => {
        const imgs = Array.from(document.querySelectorAll('.sup-body img[alt="视频封面"]'));
        const img = imgs[imgs.length - 1];
        return Boolean(img && img.complete && img.naturalWidth > 0);
      },
      { timeout: 20000 },
    )
    .then(() => true)
    .catch(() => false);
  check('气泡渲染出真实封面（naturalWidth>0）', coverOk, coverOk);

  // ── ③ 买家端无关闭痕迹 ─────────────────────────────
  step('关闭状态对买家完全不可见');
  await apiCall('POST', `/api/support/close?sessionId=${sid}`, { token: buyerTok });
  await page.goto(`${UI}/support?sellerId=0`, { waitUntil: 'networkidle' });
  await page.waitForSelector('.sup-item.active', { timeout: 15000 });
  const bodyText = await page.locator('body').innerText();
  check('买家页面没有「已关闭」字样', !bodyText.includes('已关闭'), bodyText.slice(0, 120));
  check('买家页面没有「会话关闭」提示', !/会话.*关闭/.test(bodyText), null);
  const inputUsable = await page.locator('.sup-foot input.input').isEnabled();
  check('关闭后输入框仍可用', inputUsable, inputUsable);

  const beforeSend = await page.locator('.sup-bubble').last().innerText();
  await page.locator('.sup-foot input.input').fill('关闭后继续提问');
  await page.locator('.sup-foot input.input').press('Enter');
  await page.waitForFunction(
    () => document.body.innerText.includes('关闭后继续提问'),
    { timeout: 15000 },
  );
  check('关闭后买家仍能发送（自动重开）', (await page.locator('.sup-bubble').last().innerText()) !== beforeSend, null);

  // ── ⑤ 结束咨询 ────────────────────────────────────
  step('买家「结束咨询」');
  const endBtn = page.getByRole('button', { name: '结束咨询' });
  check('存在「结束咨询」按钮', (await endBtn.count()) === 1, await endBtn.count());
  await endBtn.click();
  await page.waitForTimeout(1200);
  const toastText = await page.locator('body').innerText();
  check('结束提示不含「关闭」字样', !toastText.includes('会话已关闭'), null);

  const closedList = await apiCall('GET', '/api/seller/support/sessions?filter=CLOSED&page=1&pageSize=50', {
    token: csTok,
  });
  const closedInSeller = (closedList.body?.data?.items ?? []).some((x) => String(x.id) === String(sid));
  check('结束后商家侧「已关闭」可见该会话', closedInSeller, closedList.status);

  // ── ④ 商家端筛选 ─────────────────────────────────
  step('商家工作台：四档筛选 + 无关闭按钮');
  const mctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const mp = await mctx.newPage();
  await mp.goto(`${MERCHANT}/login`, { waitUntil: 'networkidle' });
  await mp.getByPlaceholder('11 位手机号').fill(STAFF.phone);
  await mp.getByPlaceholder('请输入密码').fill(STAFF.password);
  await mp.getByRole('button', { name: /^登录$/ }).click();
  await mp.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
  await mp.goto(`${MERCHANT}/seller/support`, { waitUntil: 'networkidle' });
  await mp.waitForTimeout(1200);
  for (const label of ['全部', '未读', '已处理', '已关闭']) {
    check(`筛选按钮「${label}」存在`, (await mp.getByRole('button', { name: label, exact: true }).count()) === 1, null);
  }
  check('客服端没有「关闭会话」按钮',
    (await mp.getByRole('button', { name: /关闭会话|重新开启/ }).count()) === 0,
    await mp.getByRole('button', { name: /关闭会话|重新开启/ }).count());

  await mp.getByRole('button', { name: '已关闭', exact: true }).click();
  await mp.waitForTimeout(900);
  const closedRows = await mp.locator('.sup-item').count();
  const closedTags = await mp.locator('.sup-item .tag', { hasText: '已关闭' }).count();
  check('「已关闭」列表里的会话都带已关闭标签（或为空）',
    closedRows === 0 || closedTags === closedRows,
    { closedRows, closedTags });

  await mp.getByRole('button', { name: '已处理', exact: true }).click();
  await mp.waitForTimeout(900);
  check('「已处理」列表不带未读角标', (await mp.locator('.sup-item .sup-badge').count()) === 0,
    await mp.locator('.sup-item .sup-badge').count());
  await mctx.close();

  check('全程无 console 报错', consoleErrors.length === 0, consoleErrors.slice(0, 3));
} catch (e) {
  failures.push('脚本异常：' + (e?.message ?? String(e)));
  console.log('\n[异常] ' + (e?.stack ?? e));
} finally {
  await browser.close();
}

console.log(`\n结果：${pass} 通过 / ${failures.length} 失败`);
if (failures.length) {
  console.log('失败项：\n - ' + failures.join('\n - '));
  process.exit(1);
}

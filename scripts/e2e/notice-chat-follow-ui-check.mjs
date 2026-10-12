/**
 * 通知中心 / 私信 / 关注 —— 真实浏览器 UI 自测（R74-4，**自包含**，不需要外部夹具）
 *
 * 为什么必须补这一套：这三条都是**用户每天会走**的动线，而 16 套 e2e 里对它们的覆盖是 0
 * （`scripts/e2e` 下此前没有任何 notice / message / follow 相关脚本）。
 * 更具体的代价：Web 通知页的分类 chip 从 R67 起就是错的（写成了后端不存在的
 * AFTERSALE / SOCIAL / NOTICE 三个键），**选「售后 / 互动 / 公告」一律筛出空列表，真实存在的
 * FRIEND / MERCHANT 只能显示英文原名** —— 这类「接口对、页面点不动/筛不出」的问题，
 * 只有真实浏览器点一遍才会现形，而因为没有套件，它一直没人发现。本套件把它钉住。
 *
 * 覆盖：
 *   ① 通知中心 `/notifications`：夹具通知可见 → 分类 chip **逐个真筛**（含曾经失效的那几个）
 *      → 单条点开标已读 → 全部已读后未读标记消失；
 *   ② 私信 `/messages` 与 `/messages/:peerId`：会话列表出现对方与未读 → 进会话看到内容 →
 *      发一条（右侧自己的气泡）→ **非互关「每人只能发一条」被服务端拒绝且页面禁用输入并说明原因**
 *      → 互关后恢复可发；头像菜单里出现「私信」入口；
 *   ③ 关注与粉丝 `/follow`：关注列表 / 粉丝列表分别出现对方，互相回关后私信 is 可发。
 *
 * 收尾（自清理）：删除本套件造的**通知 / 私信 / 关注**三类数据（走 SQL：通知没有删除端点，
 * 夹具自毁是套件的责任；用户账号与既有数据不动）。
 *
 * 前置：容器在跑（网关 :8080）；frontend dev server 在 :5173。
 * ⚠️ 必须用 http://localhost:5173，不能用 127.0.0.1 —— 网关 CORS 白名单只认 localhost。
 * ⚠️ dev server 必须与**同一条命令**里启动（后台进程不跨工具调用存活）：
 *    cd frontend && (npx vite --port 5173 --strictPort &) ; sleep 12 ; cd .. && node scripts/e2e/notice-chat-follow-ui-check.mjs
 */

import path from 'node:path';
import { mysql as mysqlExec } from '../lib/docker-exec.mjs';
import { internalHeaders } from '../lib/internal-token.mjs';

const GW = process.env.GATEWAY ?? 'http://127.0.0.1:8080';
/**
 * 社交服务直连地址（**内部端点不走网关**）。
 *
 * `/api/socials/internal/**` 是服务间端点，**刻意不进网关白名单** ——
 * 它只认内部签名（`X-Internal-Token`），没有用户会话；经网关调用会被网关自己判成
 * 「未登录」直接 401（本套件首版就是这么红的），而不是内部过滤器给的 403。
 * compose 把 social 发布了宿主机端口 `8087:8087`，因此这里直连即可。
 */
const SOCIAL = process.env.SOCIAL_BASE ?? 'http://127.0.0.1:8087';
const UI = process.env.UI_BASE ?? 'http://localhost:5173';
const SHOTS = 'D:/智能体项目/duoduo-pet-mall-java/frontend/docs/screenshots';
const PW_CORE =
  process.env.PW_CORE ??
  'file:///D:/智能体项目/duoduo-pet-mall-java/scripts/e2e/node_modules/playwright-core/index.mjs';
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

async function call(method, p, body, jar, extraHeaders) {
  const headers = { ...(extraHeaders ?? {}) };
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
  try {
    json = JSON.parse(raw);
  } catch {
    /* 保留 raw */
  }
  return { status: res.status, data: json?.data, json, raw };
}

const sql = (db, q) => mysqlExec(db, q);
const randPhone = (prefix) => prefix + String(Math.floor(1e7 + Math.random() * 8e7));

async function register(phone, nickname) {
  const jar = new Map();
  const reg = await call('POST', '/api/auth/register', { phone, password: PWD, nickname }, jar);
  return { jar, phone, nickname, status: reg.status, raw: reg.raw };
}

const MARK = `E2E-${Date.now() % 1000000}`;

// ---------------- 夹具 ----------------
step('夹具：两个账号');
const me = await register(randPhone('139'), `通知私信甲${MARK.slice(-4)}`);
check('账号甲注册成功（本套用例的主体）', me.status === 200, me.raw?.slice(0, 160));
const peer = await register(randPhone('138'), `通知私信乙${MARK.slice(-4)}`);
check('账号乙注册成功（对话/关注对象）', peer.status === 200, peer.raw?.slice(0, 160));

const meId = (await sql('duoduo_user', `SELECT id FROM t_user WHERE phone='${me.phone}';`)).trim();
const peerId = (await sql('duoduo_user', `SELECT id FROM t_user WHERE phone='${peer.phone}';`)).trim();
check('取到双方 userId', /^\d+$/.test(meId) && /^\d+$/.test(peerId), { meId, peerId });

// 注意：收件人 id 必须按**字符串**传（雪花 19 位超出 JS 安全整数，Number() 会静默改写末位，
// 服务端随即查不到这个用户 —— 首版就是这么得到 404「用户不存在」的，属 T-39 老坑）。
step('夹具：定向推送 3 条通知（内部签名端点，直连 social 不经网关）');
const ORDER_TITLE = `${MARK} 订单已发货`;
const FRIEND_TITLE = `${MARK} 好友消息`;
const ANNOUNCE_TITLE = `${MARK} 公告样式通知`;
const push = await call('POST', `${SOCIAL}/api/socials/internal/notices`, {
  items: [
    { userId: meId, category: 'ORDER', title: ORDER_TITLE, content: '你的订单已由商家发出', bizType: 'ORDER_SHIPPED', bizId: '202601010000000000001' },
    { userId: meId, category: 'FRIEND', title: FRIEND_TITLE, content: '有人给你发了私信', bizType: 'MESSAGE', bizId: 'conv-e2e' },
    { userId: meId, category: 'ANNOUNCEMENT', title: ANNOUNCE_TITLE, content: '这是一条按公告分类推送的定向通知（用于验证公告 chip 能筛出东西）' },
  ],
}, null, internalHeaders('POST'));
check('内部推送接受 3 条通知', push.status === 200, `${push.status} ${push.raw?.slice(0, 200)}`);

step('夹具：乙方给甲方发一条私信');
const sent = await call('POST', '/api/socials/messages', {
  receiverId: meId,
  content: `${MARK} 你好，猫粮还有货吗？`,
}, peer.jar);
check('乙方发信成功（非互关下每人第一条允许）', sent.status === 200, `${sent.status} ${sent.raw?.slice(0, 200)}`);

const browser = await chromium.launch({ args: ['--no-sandbox'] });
async function newPage() {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' });
  const page = await ctx.newPage();
  const consoleErrors = [];
  const httpErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
  page.on('response', (r) => { if (r.status() >= 400) httpErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`); });
  return { page, consoleErrors, httpErrors };
}
async function login(page, phone) {
  await page.goto(`${UI}/login`, { waitUntil: 'networkidle' });
  await page.getByPlaceholder('11 位手机号').fill(phone);
  await page.getByPlaceholder('请输入密码').fill(PWD);
  await page.getByRole('button', { name: /^登录$/ }).click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
}
const shot = (page, name) => page.screenshot({ path: path.join(SHOTS, name), fullPage: true });

// ---------------- ① 通知中心 ----------------
step('① 通知中心：可见 / 分类筛选 / 已读');
{
  const { page, consoleErrors, httpErrors } = await newPage();
  await login(page, me.phone);
  await page.goto(`${UI}/notifications`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1000);

  let body = await page.locator('body').innerText();
  check('通知页渲染出「通知中心」', body.includes('通知中心'), body.slice(0, 160));
  check('夹具通知（订单类）可见', body.includes(ORDER_TITLE), body.slice(0, 400));
  check('夹具通知（好友类）可见', body.includes(FRIEND_TITLE), body.slice(0, 400));
  check('未读标记出现（新推的通知未读）', body.includes('未读'), body.slice(0, 400));

  const chips = ['全部', '订单', '好友', '商家', '系统', '公告'];
  const missing = [];
  for (const c of chips) {
    if ((await page.getByRole('button', { name: c, exact: true }).count()) === 0) missing.push(c);
  }
  check('分类 chip 与服务端白名单一致（6 项：全部/订单/好友/商家/系统/公告）',
    missing.length === 0, missing);
  check('★ 页面不再出现后端不存在的分类 chip（售后 / 互动 —— R67 起的缺陷）',
    (await page.getByRole('button', { name: '售后', exact: true }).count()) === 0
    && (await page.getByRole('button', { name: '互动', exact: true }).count()) === 0,
    '仍存在过期 chip');
  await shot(page, 'notice_list.png');

  // 逐类真筛：筛选后必须只剩该类，且该类夹具通知仍在
  const filterBy = async (label) => {
    await page.getByRole('button', { name: label, exact: true }).click();
    await page.waitForTimeout(900);
    return page.locator('body').innerText();
  };

  body = await filterBy('订单');
  check('★ 选「订单」只剩订单类（好友类夹具通知被筛掉）',
    body.includes(ORDER_TITLE) && !body.includes(FRIEND_TITLE), body.slice(0, 300));

  body = await filterBy('好友');
  check('★ 选「好友」能筛出好友类通知（此前该键不存在、必然筛空）',
    body.includes(FRIEND_TITLE) && !body.includes(ORDER_TITLE), body.slice(0, 300));

  body = await filterBy('公告');
  check('★ 选「公告」（ANNOUNCEMENT）能筛出公告类通知（此前 chip 写的是 NOTICE，必然筛空）',
    body.includes(ANNOUNCE_TITLE) && !body.includes(ORDER_TITLE), body.slice(0, 300));

  body = await filterBy('系统');
  check('选「系统」筛出空态而不是报错（该分类下确实没有数据）',
    body.includes('暂无通知') || !body.includes(ORDER_TITLE), body.slice(0, 240));

  body = await filterBy('商家');
  check('选「商家」不报错（空态或数据均可）', body.includes('通知中心'), body.slice(0, 200));

  // 全部已读
  await page.getByRole('button', { name: '全部', exact: true }).click();
  await page.waitForTimeout(800);
  await page.getByRole('button', { name: '全部已读' }).click();
  await page.waitForTimeout(1200);
  const notifApi = await call('GET', '/api/socials/notices/unread-count', undefined, me.jar);
  check('★ 全部已读后通知未读数归零', (notifApi.data?.count ?? -1) === 0, notifApi.data);

  const noisy = httpErrors.filter((e) => !/favicon/i.test(e) && !/(^|\s)(401|403|404)\s/.test(e));
  check('通知页无非预期 4xx/5xx', noisy.length === 0, noisy.slice(0, 4));
  check('通知页无 console / pageerror',
    consoleErrors.filter((e) => !/favicon|status of (401|403|404)/i.test(e)).length === 0, consoleErrors.slice(0, 4));
}

// ---------------- ② 私信 ----------------
step('② 私信：列表 / 会话 / 发送 / 非互关限一条');
{
  const { page, consoleErrors, httpErrors } = await newPage();
  await login(page, me.phone);

  // 入口：头像菜单里应有「私信」（含未读数）
  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
  await page.locator('.app-header button.icon-btn[aria-haspopup="menu"]').click();
  await page.waitForTimeout(600);
  const menuText = await page.locator('.app-header .user-menu').innerText();
  check('★ 头像菜单里有「私信」入口（Web 端此前完全没有私信页面）', menuText.includes('私信'), menuText.slice(0, 200));
  check('私信入口带未读数（甲方未读 1 条）', /私信（\d+）/.test(menuText), menuText.slice(0, 200));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);

  await page.goto(`${UI}/messages`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1000);
  let body = await page.locator('body').innerText();
  check('会话列表渲染出页面标题', body.includes('私信'), body.slice(0, 160));
  check('★ 会话列表出现对方昵称', body.includes(peer.nickname), body.slice(0, 300));
  check('该会话显示未读条数', /未读/.test(body), body.slice(0, 300));
  await shot(page, 'message_list.png');

  // 进会话
  await page.locator('.card', { hasText: peer.nickname }).first().click();
  await page.waitForURL((u) => u.pathname.startsWith('/messages/'), { timeout: 10000 });
  await page.waitForTimeout(1000);
  body = await page.locator('body').innerText();
  check('★ 会话页显示对方发来的内容', body.includes('猫粮还有货吗'), body.slice(0, 400));
  check('会话页标出互相关注状态', body.includes('未互关') || body.includes('互相关注'), body.slice(0, 200));

  // 打开即标已读：未读数应归零
  await page.waitForTimeout(600);
  const unreadAfterOpen = await call('GET', '/api/socials/messages/unread-count', undefined, me.jar);
  check('★ 打开会话后私信未读归零（markRead 生效）', (unreadAfterOpen.data?.count ?? -1) === 0, unreadAfterOpen.data);

  // 发第一条（允许）
  const myText = `${MARK} 有货的，今天下单明天发`;
  await page.locator('textarea').fill(myText);
  await page.getByRole('button', { name: '发送' }).click();
  await page.waitForTimeout(1400);
  body = await page.locator('body').innerText();
  check('★ 自己发的消息出现在会话里', body.includes('有货的，今天下单明天发'), body.slice(0, 500));
  await shot(page, 'message_sent.png');

  // 发完第一条后，非互关 → canSend=false，页面必须禁用输入并说明原因
  await page.waitForTimeout(400);
  const disabled = await page.locator('textarea').isDisabled();
  const sendDisabled = await page.getByRole('button', { name: '发送' }).isDisabled();
  check('★ 非互关发完一条后输入框被禁用（不让人白点一次再失败）', disabled && sendDisabled,
    { textarea: disabled, send: sendDisabled });
  check('★ 页面说明了「只能发一条」的原因', /只能发一条|回关/.test(body), body.slice(-300));

  // 服务端也必须拒（页面禁用只是体验层，正确性在服务端）
  const second = await call('POST', '/api/socials/messages', {
    receiverId: peerId, content: `${MARK} 第二条应该被拒`,
  }, me.jar);
  check('★ 服务端同样拒绝非互关的第二条（4xx，不是静默成功）',
    second.status >= 400 && second.status < 500, `${second.status} ${second.raw?.slice(0, 160)}`);

  const noisy = httpErrors.filter((e) => !/favicon/i.test(e) && !/(^|\s)(401|403|404|422)\s/.test(e));
  check('私信页无非预期 4xx/5xx（401/403/404/422 为刻意触发）', noisy.length === 0, noisy.slice(0, 4));
  check('私信页无 console / pageerror',
    consoleErrors.filter((e) => !/favicon|status of (401|403|404|422)/i.test(e)).length === 0, consoleErrors.slice(0, 4));
}

// ---------------- ③ 关注与粉丝 ----------------
step('③ 关注与粉丝：两个名单 + 互关后私信恢复可发');
{
  const f1 = await call('POST', `/api/socials/follows/${peerId}`, undefined, me.jar);
  check('甲关注乙成功', f1.status === 200, `${f1.status} ${f1.raw?.slice(0, 160)}`);
  const f2 = await call('POST', `/api/socials/follows/${meId}`, undefined, peer.jar);
  check('乙回关甲成功', f2.status === 200, `${f2.status} ${f2.raw?.slice(0, 160)}`);

  const { page, consoleErrors, httpErrors } = await newPage();
  await login(page, me.phone);
  await page.goto(`${UI}/follow`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1000);
  let body = await page.locator('body').innerText();
  check('关注页渲染出来', body.includes('关注') || body.includes('粉丝'), body.slice(0, 200));
  check('★ 「关注」名单里出现乙', body.includes(peer.nickname), body.slice(0, 400));
  await shot(page, 'follow_page.png');

  // 切到粉丝名单：甲被乙关注 → 粉丝里应出现乙
  const fanTab = page.getByRole('button', { name: /关注我的/ }).first();
  if ((await fanTab.count()) > 0) {
    await fanTab.click();
    await page.waitForTimeout(900);
    body = await page.locator('body').innerText();
    check('★ 「粉丝」名单里出现乙（互关后两侧都在）', body.includes(peer.nickname), body.slice(0, 400));
  } else {
    check('关注页提供「关注我的」切换入口', false, '未找到「关注我的」按钮');
  }

  // 互关后：私信 canSend 恢复（服务端下发）
  const conv = await call('GET', `/api/socials/messages/conversations/${peerId}`, undefined, me.jar);
  check('★ 互关后 canSend 恢复为 true（限一条的约束解除）', conv.data?.canSend === true,
    { canSend: conv.data?.canSend, mutual: conv.data?.mutual });

  const noisy = httpErrors.filter((e) => !/favicon/i.test(e) && !/(^|\s)(401|403|404)\s/.test(e));
  check('关注页无非预期 4xx/5xx', noisy.length === 0, noisy.slice(0, 4));
  check('关注页无 console / pageerror',
    consoleErrors.filter((e) => !/favicon|status of (401|403|404)/i.test(e)).length === 0, consoleErrors.slice(0, 4));
}

// ---------------- ④ 未读角标（S7）：全站可见 + 合并口径一致 ----------------
step('④ 未读角标：顶栏全站可见，且数字与 unread-summary 合并口径一致');
{
  // ① 段已把通知全部标为已读、② 段读了私信，所以这里从「有未读」重新造一次。
  // ⚠️ 不写死期望数字：③ 关注段也会产生通知（「X 关注了你」），数量随实现变。
  //    改为**与接口的 total 对拍** —— 这才是「口径一致」这句话的可执行版本。
  const push2 = await call('POST', `${SOCIAL}/api/socials/internal/notices`, {
    items: [{
      userId: meId,
      category: 'SYSTEM',
      title: `${MARK} 未读角标用例`,
      content: '用于验证 S7：Web 端全站未读角标',
    }],
  }, null, internalHeaders('POST'));
  check('④ 夹具：再推 1 条未读通知', push2.status === 200, `${push2.status} ${push2.raw?.slice(0, 160)}`);

  const summary = await call('GET', '/api/socials/notices/unread-summary', undefined, me.jar);
  const total = Number(summary.data?.total ?? -1);
  check('④ 合并口径接口可用，且 total == notice + message（不变量）',
    summary.status === 200
      && total === Number(summary.data?.notice ?? -2) + Number(summary.data?.message ?? -2),
    summary.data);

  // ★ 核心断言：在**首页**（不是通知页）就能看到顶栏角标 —— S7 的诉求正是「任何页面都能看到」
  const { page, consoleErrors, httpErrors } = await newPage();
  await login(page, me.phone);
  await page.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);

  const badge = await page.evaluate(() => {
    // 顶栏头像按钮（aria-haspopup=menu）里的角标 —— 用该选择器把它与购物车角标区分开
    const btn = document.querySelector('button[aria-haspopup="menu"]');
    const el = btn?.querySelector('.badge-dot');
    return { text: el?.textContent?.trim() ?? null, hasBtn: Boolean(btn) };
  });
  check('④ 首页能看到顶栏头像按钮（角标的载体存在）', badge.hasBtn, badge);
  check('★ ④ 首页顶栏就出现未读角标（不只在通知页 —— 这是 S7 的核心承诺）',
    badge.text !== null, badge);
  check('★ ④ 角标数字 == unread-summary 的 total（两端同口径，没有各算一套）',
    badge.text === String(total > 99 ? '99+' : total), { badge: badge.text, total });
  await shot(page, 'web-unread-badge.png');

  // 未登录访客：没有头像菜单，自然也不该有任何未读角标（上一个账号的历史数字不能留着）
  const { page: guest } = await newPage();
  await guest.goto(`${UI}/`, { waitUntil: 'networkidle' });
  await guest.waitForTimeout(1200);
  const guestState = await guest.evaluate(() => ({
    hasMenu: Boolean(document.querySelector('button[aria-haspopup="menu"]')),
    dots: document.querySelectorAll('.badge-dot').length,
  }));
  check('④ 未登录访客没有头像菜单、也就没有未读角标', !guestState.hasMenu, guestState);

  const noisy = httpErrors.filter((e) => !/favicon/i.test(e) && !/(^|\s)(401|403|404)\s/.test(e));
  check('④ 未读角标流程无非预期 4xx/5xx', noisy.length === 0, noisy.slice(0, 4));
  check('④ 未读角标流程无 console / pageerror',
    consoleErrors.filter((e) => !/favicon|status of (401|403|404)/i.test(e)).length === 0, consoleErrors.slice(0, 4));
}

// ---------------- 收尾：自清理 ----------------
await browser.close();
if (!process.env.KEEP_FIXTURE) {
  await sql('duoduo_social', `DELETE FROM t_social_notice WHERE user_id = ${meId};`);
  await sql('duoduo_social', `DELETE FROM t_social_message WHERE sender_id IN (${meId}, ${peerId}) OR receiver_id IN (${meId}, ${peerId});`);
  await sql('duoduo_social', `DELETE FROM t_social_follow WHERE follower_id IN (${meId}, ${peerId}) OR followee_id IN (${meId}, ${peerId});`);
  const left = (await sql('duoduo_social',
    `SELECT (SELECT COUNT(*) FROM t_social_notice WHERE user_id=${meId}) AS n, (SELECT COUNT(*) FROM t_social_message WHERE sender_id IN (${meId},${peerId})) AS m, (SELECT COUNT(*) FROM t_social_follow WHERE follower_id=${meId}) AS f;`)).trim();
  check('★ 夹具已自清理（通知 / 私信 / 关注均归零）', /^0\s+0\s+0$/.test(left.replace(/\s+/g, ' ')), left);
  console.log(`  （夹具已复原）通知 / 私信 / 关注三类数据已删除；账号 ${me.phone} / ${peer.phone} 保留`);
}

console.log(`\n=== 通知·私信·关注 UI 自测结果：通过 ${pass} 项，失败 ${failures.length} 项 ===`);
if (failures.length) {
  console.log('失败项：');
  failures.forEach((f) => console.log(`  - ${f}`));
}
process.exit(failures.length ? 1 : 0);

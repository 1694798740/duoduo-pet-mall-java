#!/usr/bin/env node
/**
 * R55 小程序单测：未读角标口径 + 全局刷新点 + 卡片冷却与跳转。
 *
 * 为什么这些必须单独测（接口探针覆盖不到，人工看也很难发现）：
 *  1. **角标口径**是纯前端约定：0 要清除角标、>99 要显示 `99+`、请求失败**不能**清掉
 *     （清掉等于告诉用户「消息没了」）。这三种行为都只在真机上以「数字不对」的形式暴露。
 *  2. **刷新点**最容易被漏：第五十四轮的真实缺陷就是全仓只有通知页 onShow 会刷新角标，
 *     用户在首页/分享圈永远看不到数字。这条只能靠「每个 tab 页都调了刷新」的断言守住。
 *  3. **卡片冷却**是「同一个人连发三条消息只弹一次」的实现依据，写错了会变成骚扰。
 *  4. **跳转口径**：wx.switchTab 对非 tab 页静默失败（第五十轮的坑），必须区分。
 *
 * 用法：node scripts/r55-unread-badge-test.js     （退出码 0 = 全过）
 */
const fs = require('fs');
const path = require('path');

const MP = path.join(__dirname, '..', 'miniprogram');
const read = (rel) => fs.readFileSync(path.join(MP, rel), 'utf8');
const tick = () => new Promise((r) => setImmediate(r));

let pass = 0;
let fail = 0;
function ok(name, cond, detail) {
  if (cond) {
    pass++;
    console.log('  [PASS] ' + name);
  } else {
    fail++;
    console.log('  [FAIL] ' + name + (detail ? ' → ' + detail : ''));
  }
}
function check(name, actual, expected) {
  ok(name, JSON.stringify(actual) === JSON.stringify(expected), '实际=' + JSON.stringify(actual) + ' 期望=' + JSON.stringify(expected));
}

// ═══════════════════════════════════════════════════════════════════════
// 桩：wx 与定时器（真实加载 utils/unread.js / utils/notice.js）
// ═══════════════════════════════════════════════════════════════════════
const TOKEN_KEY = 'duoduo_access_token';

let loggedIn = true;
let nextApiData = null; // { data: ... } 由用例设置；null 表示「请求失败」
let requestCount = 0;
const badges = [];   // setTabBarBadge 调用
const cleared = [];  // removeTabBarBadge 调用
const switches = []; // switchTab 调用
const navigates = []; // navigateTo 调用
const storage = {};  // 真实可用的 storage 桩

global.wx = {
  getStorageSync: (k) => (k === TOKEN_KEY ? (loggedIn ? 'fake-token' : '') : (storage[k] || '')),
  setStorageSync: (k, v) => { storage[k] = v; },
  removeStorageSync: (k) => { delete storage[k]; },
  request(opts) {
    requestCount++;
    if (nextApiData === null) {
      if (opts.fail) opts.fail({ errMsg: 'request:fail mock' });
      return;
    }
    if (opts.success) {
      opts.success({ statusCode: 200, data: { code: 'SUCCESS', message: 'ok', data: nextApiData } });
    }
  },
  setTabBarBadge(opts) { badges.push(opts); },
  removeTabBarBadge(opts) { cleared.push(opts); },
  switchTab(opts) { switches.push(opts); },
  navigateTo(opts) { navigates.push(opts); },
  showToast() {},
};

const timers = new Map();
let timerSeq = 0;
global.setInterval = (fn, ms) => { const id = ++timerSeq; timers.set(id, { fn, ms }); return id; };
global.clearInterval = (id) => { timers.delete(id); };

const unread = require(path.join(MP, 'utils', 'unread.js'));
const notice = require(path.join(MP, 'utils', 'notice.js'));

async function main() {
  // ═════════════════════════════════════════════════════════════════════
  console.log('\n[A] 角标数字口径（真实加载 utils/unread.js）');
  // ═════════════════════════════════════════════════════════════════════
  check('A1 badgeText：0 → 空串（调用方据此清除角标）', unread.badgeText(0), '');
  check('A2 badgeText：1 → "1"', unread.badgeText(1), '1');
  check('A3 badgeText：99 → "99"', unread.badgeText(99), '99');
  check('A4 badgeText：100 → "99+"（超过两位会被裁成省略号）', unread.badgeText(100), '99+');
  check('A5 badgeText：null/undefined → 空串（不显示 "NaN"）', unread.badgeText(null), '');
  check('A6 角标画在 tabBar 第 3 项（通知）', unread.NOTICE_TAB_INDEX, 2);

  loggedIn = true;
  badges.length = 0;
  cleared.length = 0;
  nextApiData = { notice: 2, message: 3, total: 5 };
  const s1 = await unread.refresh();
  check('A7 刷新返回 {notice, message, total}', s1, { notice: 2, message: 3, total: 5 });
  ok('A8 有未读时画角标：index=2 text="5"',
    badges.length === 1 && badges[0].index === 2 && badges[0].text === '5',
    JSON.stringify(badges));
  ok('A9 有数字时不清角标', cleared.length === 0, JSON.stringify(cleared));

  badges.length = 0;
  cleared.length = 0;
  nextApiData = { notice: 0, message: 0, total: 0 };
  await unread.refresh();
  ok('A10 未读为 0 → 清除角标（removeTabBarBadge）',
    badges.length === 0 && cleared.length === 1 && cleared[0].index === 2,
    'badges=' + JSON.stringify(badges) + ' cleared=' + JSON.stringify(cleared));

  badges.length = 0;
  cleared.length = 0;
  nextApiData = { notice: 0, message: 0, total: 240 };
  await unread.refresh();
  ok('A11 超过 99 → 文案 "99+"', badges.length === 1 && badges[0].text === '99+', JSON.stringify(badges));

  // 请求失败：保留旧角标，不误清
  badges.length = 0;
  cleared.length = 0;
  nextApiData = null;
  const s2 = await unread.refresh();
  ok('A12 ★请求失败不清角标（清掉 = 告诉用户「消息没了」）', cleared.length === 0 && badges.length === 0,
    'cleared=' + JSON.stringify(cleared));
  check('A13 请求失败返回 -1（调用方据此区分「确实没有」与「没问到」）', s2, { notice: -1, message: -1, total: -1 });

  // 未登录：不发请求，直接清
  loggedIn = false;
  badges.length = 0;
  cleared.length = 0;
  requestCount = 0;
  const s3 = await unread.refresh();
  ok('A14 未登录：不发请求（避免无谓的 401 抖动）', requestCount === 0, 'requestCount=' + requestCount);
  ok('A15 未登录：清掉上一个账号的角标', cleared.length === 1, JSON.stringify(cleared));
  check('A16 未登录返回全 0', s3, { notice: 0, message: 0, total: 0 });
  loggedIn = true;

  // 单飞：并发刷新只发一次请求
  nextApiData = { notice: 1, message: 1, total: 2 };
  requestCount = 0;
  await Promise.all([unread.refresh(), unread.refresh(), unread.refresh()]);
  ok('A17 ★并发刷新只发一次请求（四个刷新点可能同时触发）', requestCount === 1, 'requestCount=' + requestCount);

  // ═════════════════════════════════════════════════════════════════════
  console.log('\n[B] 全局刷新点（第五十四轮缺陷：只在通知页刷新）');
  // ═════════════════════════════════════════════════════════════════════
  const appJs = read('app.js');
  ok('B1 app.js 在 onShow 里刷新角标', /onShow\s*\(\)\s*\{[\s\S]{0,200}?unread\.refresh\(\)/.test(appJs));
  ok('B2 app.js 在 onShow 里启动兜底轮询', /onShow\s*\(\)\s*\{[\s\S]{0,300}?unread\.startPolling\(\)/.test(appJs));
  ok('B3 app.js 在 onHide 里停止轮询（后台不轮询）', /onHide\s*\(\)\s*\{[\s\S]{0,120}?unread\.stopPolling\(\)/.test(appJs));
  ok('B4 app.js 登录成功后也刷新角标（换账号不能看到上一个人的数字）',
    /finishLogin\s*\(user\)\s*\{[\s\S]{0,260}?unread\.refresh\(\)/.test(appJs));

  const tabPages = ['pages/index/index', 'pages/share-circle/share-circle', 'pages/profile/profile'];
  for (const p of tabPages) {
    const js = read(p + '.js');
    const hasOnShow = /onShow\s*\(\)\s*\{[\s\S]{0,400}?unread\.refresh\(\)/.test(js);
    ok('B5 ' + p + ' 的 onShow 里调了 unread.refresh()', hasOnShow);
    ok('B6 ' + p + ' 引入了 utils/unread', /require\(['"][^'"]*utils\/unread['"]\)/.test(js));
  }

  const notifJs = read('pages/notification/notification.js');
  // 先剥注释再匹配（第五十四轮教训）：注释里说明「本页不再自己调 setTabBarBadge」
  // 是有价值的文档，不该被静态规则判成违规调用。
  const stripComments = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  ok('B7 通知页走 utils/unread 的统一口径（代码里不再直接 setTabBarBadge）',
    stripComments(notifJs).indexOf('setTabBarBadge') < 0 && notifJs.indexOf('unread.refresh()') >= 0);

  // 静态规则：全仓页面 / 组件不得直接调 setTabBarBadge（口径必须统一）
  const directUsers = [];
  (function walk(dir) {
    for (const name of fs.readdirSync(dir)) {
      const p = path.join(dir, name);
      const st = fs.statSync(p);
      if (st.isDirectory()) {
        walk(p);
      } else if (name.endsWith('.js')) {
        const src = fs.readFileSync(p, 'utf8');
        // 去掉注释再匹配：注释里提到 setTabBarBadge 是说明，不是调用
        const code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
        if (code.indexOf('wx.setTabBarBadge') >= 0) directUsers.push(path.relative(MP, p));
      }
    }
  })(MP);
  check('B8 ★全仓只有 utils/unread.js 直接调 setTabBarBadge（口径不许漂移）',
    directUsers.filter((f) => f.indexOf('utils' + path.sep + 'unread.js') < 0), []);

  // ═════════════════════════════════════════════════════════════════════
  console.log('\n[C] 兜底轮询的定时器生命周期（防泄漏）');
  // ═════════════════════════════════════════════════════════════════════
  unread.stopPolling();
  timers.clear();
  check('C1 初始无定时器', unread.isPolling(), false);
  unread.startPolling();
  check('C2 startPolling 后有一个定时器', timers.size, 1);
  check('C3 轮询间隔为 60 秒', timers.get(1).ms, 60000);
  unread.startPolling();
  check('C4 ★重复 startPolling 不叠加定时器（幂等）', timers.size, 1);
  unread.stopPolling();
  check('C5 stopPolling 后定时器被清空', unread.isPolling(), false);
  check('C6 stopPolling 后 timers 为空（clearInterval 真被调用）', timers.size, 0);

  const toastJs = read('components/notice-toast/notice-toast.js');
  const toastWxss = read('components/notice-toast/notice-toast.wxss');
  ok('C7 卡片组件在 detached 里清定时器（页面销毁不留悬挂定时器）',
    /detached\s*\(\)\s*\{[\s\S]{0,200}?clearTimer\(\)/.test(toastJs));
  ok('C8 卡片组件 4 秒自动收起', /AUTO_HIDE_MS\s*=\s*4000/.test(toastJs));
  ok('C9 收起动画时长与 js 的 LEAVE_MS 对齐（260ms）',
    /LEAVE_MS\s*=\s*260/.test(toastJs) && /transition:\s*transform\s*0\.26s/.test(toastWxss));

  // ═════════════════════════════════════════════════════════════════════
  console.log('\n[D] 首页卡片：冷却与选取（真实加载 utils/notice.js）');
  // ═════════════════════════════════════════════════════════════════════
  notice.resetPopupCooldown();
  nextApiData = {
    items: [
      { id: '1', category: 'FRIEND', bizType: 'MESSAGE', bizId: '100:200', title: '好友消息', content: '在吗', read: false },
      { id: '2', category: 'ORDER', bizType: 'ORDER_PAID', bizId: 'DD001', title: '支付成功', content: '已支付', read: false },
    ],
  };
  const first = await notice.pickForPopup();
  ok('D1 第一次挑出最新一条未读通知', !!first && String(first.id) === '1', JSON.stringify(first));

  const second = await notice.pickForPopup();
  ok('D2 ★同来源 5 分钟内不重复弹（冷却生效）', second === null || String(second.id) !== '1',
    '第二次=' + JSON.stringify(second));
  ok('D3 冷却按「来源」跳过已弹过的那条，依然可以弹另一条新的',
    !!second && String(second.id) === '2', JSON.stringify(second));
  const third = await notice.pickForPopup();
  ok('D4 两条都弹过之后不再弹', third === null, JSON.stringify(third));

  check('D5 冷却键按 bizType+bizId（同一会话/同一业务单）',
    notice.popupKeyOf({ id: '9', bizType: 'MESSAGE', bizId: '100:200' }), 'MESSAGE:100:200');
  check('D6 无 biz 信息时退化为按通知 id', notice.popupKeyOf({ id: '9' }), 'notice:9');

  // 冷却过期（把时间戳往前拨 6 分钟）后可以再弹
  const seenKey = notice.SEEN_STORAGE_KEY;
  const seenMap = storage[seenKey] || {};
  Object.keys(seenMap).forEach((k) => { seenMap[k] = Date.now() - (notice.POPUP_COOLDOWN_MS + 60000); });
  storage[seenKey] = seenMap;
  const again = await notice.pickForPopup();
  ok('D7 超过冷却时长后可以再弹', !!again, JSON.stringify(again));

  nextApiData = { items: [{ id: '3', category: 'SYSTEM', title: '已读的', read: true }] };
  notice.resetPopupCooldown();
  const none = await notice.pickForPopup();
  ok('D8 没有未读通知 → 不弹（不要弹一条用户已经看过的）', none === null, JSON.stringify(none));

  nextApiData = null;
  const onFail = await notice.pickForPopup();
  ok('D9 拉取失败 → 不弹（弹不出来也不该报错）', onFail === null, JSON.stringify(onFail));

  // ═════════════════════════════════════════════════════════════════════
  console.log('\n[E] 跳转口径：tab 页 switchTab、其余 navigateTo');
  // ═════════════════════════════════════════════════════════════════════
  nextApiData = null;
  switches.length = 0;
  navigates.length = 0;
  notice.openNotice({ jumpPage: '/pages/notification/notification' });
  ok('E1 tab 页落地页 → switchTab', switches.length === 1 && navigates.length === 0,
    'switch=' + JSON.stringify(switches) + ' nav=' + JSON.stringify(navigates));

  switches.length = 0;
  navigates.length = 0;
  notice.openNotice({ jumpPage: '/pages/order-detail/order-detail?orderNo=DD1' });
  ok('E2 非 tab 页落地页 → navigateTo（带 query 原样传）',
    navigates.length === 1 && navigates[0].url.indexOf('orderNo=DD1') > 0 && switches.length === 0,
    JSON.stringify(navigates));

  switches.length = 0;
  navigates.length = 0;
  notice.openNotice({ jumpPage: '' });
  ok('E3 无落地页 → 不跳转（不跳空白页）', switches.length === 0 && navigates.length === 0);

  check('E4 冷却表写入的是真实 storage key（便于排查）', notice.SEEN_STORAGE_KEY, 'duoduo_notice_popup_seen');

  // ═════════════════════════════════════════════════════════════════════
  console.log('\n[F] 组件与页面结构（静态）');
  // ═════════════════════════════════════════════════════════════════════
  const appJson = JSON.parse(read('app.json'));
  ok('F1 卡片组件已在 app.json 注册',
    !!((appJson.usingComponents || {})['notice-toast']), JSON.stringify(appJson.usingComponents));
  const indexWxml = read('pages/index/index.wxml');
  ok('F2 首页挂载了卡片组件', indexWxml.indexOf('<notice-toast') >= 0);
  ok('F3 首页绑定了关闭与点击事件',
    indexWxml.indexOf('bind:close="closeNoticeToast"') >= 0 &&
    indexWxml.indexOf('bind:tapnotice="tapNoticeToast"') >= 0);
  ok('F4 卡片组件不自己拉数据（数据与策略在 utils/notice）',
    toastJs.indexOf('require(') < 0, '组件里出现了 require');

  const notifWxml = read('pages/notification/notification.wxml');
  ok('F5 通知页有分类筛选 chip', notifWxml.indexOf('switchCategory') >= 0 && notifWxml.indexOf('chips') >= 0);
  ok('F6 通知页有分页状态提示', notifWxml.indexOf('list-tip') >= 0 && notifWxml.indexOf('没有更多了') >= 0);
  const notifWxss = read('pages/notification/notification.wxss');
  // 第五十四轮教训：scroll-view 内 flex 子项默认 stretch，胶囊四条缺一就会被拉变形
  ok('F7 胶囊四条齐备（height + line-height + radius=height/2 + 容器 align-items:center）',
    /\.chip\s*\{[^}]*height:\s*56rpx[^}]*line-height:\s*56rpx[^}]*border-radius:\s*28rpx/s.test(notifWxss) &&
    /\.chips\s*\{[^}]*align-items:\s*center/s.test(notifWxss));
  const notifJsSrc = read('pages/notification/notification.js');
  ok('F8 通知页支持触底分页', notifJsSrc.indexOf('onReachBottom') >= 0 && notifJsSrc.indexOf('noMore') >= 0);

  console.log('\n结果：' + pass + ' 通过 / ' + fail + ' 失败');
  process.exit(fail ? 1 : 0);
}

main().catch((e) => {
  console.error('测试自身异常：', e);
  process.exit(1);
});

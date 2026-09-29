// 未读角标（第五十五轮）：tabBar「通知」上的数字 = 通知未读 + 私信未读。
//
// ★ 为什么必须统一走这里，而不是各页面自己 wx.setTabBarBadge：
//   1. 口径只能有一处来源（/api/socials/notices/unread-summary，服务端合并）。
//      页面各写一遍，迟早出现「首页角标 3、切到通知页变 5」这类自相矛盾 ——
//      用户不会怀疑口径，只会认为未读数是错的；
//   2. 刷新点有四处（回前台 / 三个自绘导航 tab 页 onShow / 60 秒兜底轮询），
//      散在各处必然漏掉一处。第五十四轮的真实表现就是：全仓只有通知页 onShow
//      会刷新角标，于是用户在首页/分享圈/我的页面时**永远看不到未读数字**。
//
// 依赖方向：本模块只依赖 utils/api，不依赖任何页面 —— 页面调它，它不回调页面
// （回调会让「谁负责刷新」重新变得说不清）。
const api = require('./api');

/** tabBar 里「通知」的下标（app.json tabBar.list 第 3 项，第五十轮起为通知） */
const NOTICE_TAB_INDEX = 2;

/** 兜底轮询间隔：前台时每 60 秒刷一次，覆盖「用户停在同一页等消息」的场景 */
const POLL_INTERVAL_MS = 60 * 1000;

/** 模块级单例定时器：同一时刻最多一个，避免反复 onShow 叠出多个定时器 */
let pollTimer = null;

/** 未完成的请求（单飞）：四个刷新点可能在同一次页面切换里一起触发 */
let inflight = null;

/**
 * 角标文案：0 → 空串（调用方据此清除角标）、1..99 → 数字、>99 → `99+`。
 *
 * 99 封顶是微信 tabBar 角标的现实：超过两位数字会被裁成省略号，
 * 与其让用户看到半个数字，不如统一成「99+」表达「很多」。
 */
function badgeText(count) {
  const n = Number(count) || 0;
  if (n <= 0) {
    return '';
  }
  return n > 99 ? '99+' : String(n);
}

/** 把数字画到 tabBar 上。fail 静默：角标是提醒不是主功能，画不上不该报错 */
function paint(count) {
  const text = badgeText(count);
  if (!text) {
    wx.removeTabBarBadge({ index: NOTICE_TAB_INDEX, fail: () => {} });
    return;
  }
  wx.setTabBarBadge({ index: NOTICE_TAB_INDEX, text, fail: () => {} });
}

/**
 * 刷新角标（唯一入口）。
 *
 * @returns {Promise<{notice:number, message:number, total:number}>} 服务端算出的三个计数
 *          （同源同刻，页面可拿 message 显示「私信 3 条」而无需再发一次请求）；
 *          未登录返回全 0；请求失败返回全 -1
 *
 * 失败时为什么返回 -1 而不是 0：`0` 会被上层当成「确实没有未读」而清除角标。
 * 网络抖一下就把用户的未读提示抹掉，比「数字暂时停在旧值」更让人困惑 ——
 * 旧值至少还是真的，清掉则凭空制造了「消息不见了」的错觉。
 */
function refresh() {
  if (inflight) {
    return inflight;
  }
  if (!api.getToken()) {
    // 未登录：没有「我的未读」可言，清掉角标（上一个账号的历史数字不能留着）
    paint(0);
    return Promise.resolve({ notice: 0, message: 0, total: 0 });
  }
  inflight = api.social.notice
    .unreadSummary()
    .then((data) => {
      const summary = {
        notice: Number((data && data.notice) || 0),
        message: Number((data && data.message) || 0),
        total: Number((data && data.total) || 0),
      };
      paint(summary.total);
      return summary;
    })
    .catch(() => ({ notice: -1, message: -1, total: -1 }))
    .then((value) => {
      inflight = null;
      return value;
    });
  return inflight;
}

/** 开始兜底轮询（幂等：重复调用不会叠出第二个定时器） */
function startPolling() {
  if (pollTimer) {
    return;
  }
  pollTimer = setInterval(() => {
    refresh();
  }, POLL_INTERVAL_MS);
}

/** 停止轮询（幂等）。调用点是小程序进入后台（app.onHide）—— 后台轮询既拿不到数据也费电 */
function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

/** 是否有正在运行的定时器（自测用：断言「onHide 后确实清干净了」） */
function isPolling() {
  return !!pollTimer;
}

module.exports = {
  NOTICE_TAB_INDEX,
  POLL_INTERVAL_MS,
  badgeText,
  refresh,
  startPolling,
  stopPolling,
  isPolling,
};

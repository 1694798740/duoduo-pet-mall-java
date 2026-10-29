// 站内跳转统一入口（2026-10-29 · 阶段四）
//
// ★ 为什么不能让页面自己 wx.navigateTo：
//   1. TabBar 页面（首页/分享圈/通知/购物车/我的）只能 switchTab，
//      用 navigateTo 会直接 fail —— 用户点了「打开通知中心」没有任何反应；
//   2. 小程序页面栈上限 10 层，navigateTo 在栈满时同样 fail；
//      不处理的话，「点卡片 → 没反应」是用户唯一能看到的结果；
//   3. AI 卡片的 miniPath 来自服务端（已过白名单），但客户端仍是最后执行者：
//      一份被篡改的响应若带 pages/../admin 或外部协议，必须在这里拒掉。
//
// 口径：
//   · 只接受 `pages/...` 形状的站内页面，其余一律拒绝（不静默跳外链）；
//   · TabBar 页面走 switchTab。**switchTab 不支持查询串**，带参数时只丢参数、不丢页面
//     （页面自己会按默认筛选渲染；为了一个筛选参数把用户挡住更糟）；
//   · 普通页面：栈 < 10 走 navigateTo；栈满 / navigateTo 失败 → redirectTo（替换当前页，
//     用户仍能到达目标）；redirectTo 也失败 → reLaunch（回首页重新开始，最差但可达）。
//
// 返回值统一是 Promise，同时兼容 success / fail 回调：现有页面多用回调风格，
// 新代码可以 await；两种写法都不至于出现「跳转失败了没人知道」。

/** TabBar 页面清单：必须与 app.json tabBar.list 的 pagePath 一致（新增 tab 时同步这里） */
const TAB_PAGES = [
  'pages/index/index',
  'pages/share-circle/share-circle',
  'pages/notification/notification',
  'pages/cart/cart',
  'pages/profile/profile',
];

/** 小程序页面栈上限（微信官方限制） */
const MAX_STACK = 10;

/**
 * 安全路径：`pages/` + 1~N 段，允许查询串，不允许 `..`、反斜杠、协议、空白。
 * 与服务端 RouteWhitelist 的 miniPath 口径一致。
 */
const SAFE_PATH = /^pages\/(?:[A-Za-z0-9_-]+\/)*[A-Za-z0-9_-]+(?:\?[^#\s]*)?$/;

/**
 * 归一化路径：去掉前导 `/`，拒绝非法形状。
 *
 * @returns {string|null} 形如 `pages/order-detail/order-detail?orderNo=...`；非法返回 null
 */
function normalizePath(path) {
  if (typeof path !== 'string') return null;
  let value = path.trim();
  if (!value) return null;
  // 协议（http:、javascript:、data: …）一律拒绝：小程序页面只能是站内路径
  if (/^[a-z][a-z0-9+.-]*:/i.test(value)) return null;
  if (value.indexOf('//') === 0) return null;
  if (value.indexOf('..') >= 0 || value.indexOf('\\') >= 0) return null;
  if (value.charAt(0) === '/') value = value.slice(1);
  return SAFE_PATH.test(value) ? value : null;
}

/** 取 `?` 之前的页面路径 */
function pagePathOf(target) {
  const index = target.indexOf('?');
  return index >= 0 ? target.slice(0, index) : target;
}

/** 是否 TabBar 页面 */
function isTabPage(pagePath) {
  return TAB_PAGES.indexOf(pagePath) >= 0;
}

/** 当前页面栈深度（异常时按 1 处理，宁可先 navigateTo 也不要无谓替换当前页） */
function stackDepth() {
  try {
    return getCurrentPages().length || 1;
  } catch (e) {
    return 1;
  }
}

/**
 * 跳转到站内页面。
 *
 * @param {string} path 形如 `pages/orders/orders?status=PENDING_PAYMENT`（可带前导 `/`）
 * @param {object} [options]
 * @param {boolean} [options.replace] 强制用 redirectTo（不压栈）
 * @param {boolean} [options.silent]  失败时不弹 toast（批量跳转/埋点场景用）
 * @param {function} [options.success]
 * @param {function} [options.fail]
 * @returns {Promise}
 */
function navigate(path, options) {
  const opts = options || {};
  const target = normalizePath(path);
  if (!target) {
    const err = { errMsg: 'navigate: 非法页面路径 ' + String(path) };
    if (opts.fail) opts.fail(err);
    if (!opts.silent) {
      wx.showToast({ title: '这个页面暂时打不开', icon: 'none' });
    }
    return Promise.reject(err);
  }

  const pagePath = pagePathOf(target);
  const url = '/' + target;
  const tabUrl = '/' + pagePath;

  return new Promise((resolve, reject) => {
    const done = (res) => {
      if (opts.success) opts.success(res);
      resolve(res);
    };
    const failed = (err) => {
      if (opts.fail) opts.fail(err);
      if (!opts.silent) {
        wx.showToast({ title: '页面打开失败', icon: 'none' });
      }
      reject(err);
    };

    if (isTabPage(pagePath)) {
      if (target.indexOf('?') >= 0) {
        // switchTab 不接受参数：保留页面、丢掉筛选（页面按默认状态渲染）
        console.warn('navigate: TabBar 页面不支持查询串，已忽略', target);
      }
      wx.switchTab({
        url: tabUrl,
        success: done,
        // switchTab 失败通常意味着 app.json 里其实没有这个 tab（清单漂移），
        // 退回普通跳转仍可能成功，不至于把用户挡在原地
        fail: () => openNormal(url, done, failed, opts),
      });
      return;
    }

    openNormal(url, done, failed, opts);
  });
}

/** 普通页面：按页面栈深度选择 navigateTo / redirectTo，失败逐级降级 */
function openNormal(url, done, failed, opts) {
  if (opts.replace || stackDepth() >= MAX_STACK) {
    redirect(url, done, failed, opts);
    return;
  }
  wx.navigateTo({
    url,
    success: done,
    fail: () => redirect(url, done, failed, opts),
  });
}

/** 替换当前页：不解栈、不压栈，栈满时的第一选择 */
function redirect(url, done, failed, opts) {
  wx.redirectTo({
    url,
    success: done,
    fail: () => reLaunch(url, done, failed, opts),
  });
}

/** 最后兜底：关掉所有页面重新打开目标页（历史栈丢失，但用户能到达） */
function reLaunch(url, done, failed, opts) {
  wx.reLaunch({
    url,
    success: done,
    fail: failed,
  });
}

module.exports = {
  MAX_STACK,
  TAB_PAGES,
  normalizePath,
  isTabPage,
  stackDepth,
  navigate,
};

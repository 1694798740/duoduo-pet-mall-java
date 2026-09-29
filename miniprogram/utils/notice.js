// 通知公共逻辑（第五十五轮）：图标口径、跳转口径、首页卡片的弹出策略。
//
// 为什么这些放一处而不是各页面各写一份：
//  1. 图标映射两份 → 同一类通知在通知页是 📦、在首页卡片是别的，用户以为是两个功能；
//  2. 跳转口径两份 → tab 页必须 switchTab、非 tab 页必须 navigateTo，
//     写错的表现是「点了没反应」（switchTab 对非 tab 页静默失败，第五十轮的坑）；
//  3. 卡片冷却两份 → 同一会话连发消息会反复弹出。
//
// 依赖方向：本模块只依赖 utils/api（与 utils/unread 一样，不依赖任何页面）。
const api = require('./api');

/** tabBar 页面：必须用 switchTab 跳（用 navigateTo 会失败；反过来也一样） */
const TAB_PAGES = [
  '/pages/index/index',
  '/pages/share-circle/share-circle',
  '/pages/notification/notification',
  '/pages/cart/cart',
  '/pages/profile/profile',
];

/** 通知类别 → 图标（服务端 category 字段；未知类别兜底铃铛） */
const ICONS = {
  ANNOUNCEMENT: '📢',
  SYSTEM: '🔔',
  FRIEND: '👤',
  MERCHANT: '🏪',
  ORDER: '📦',
};

function iconOf(notice) {
  return ICONS[(notice && notice.category) || ''] || '🔔';
}

/**
 * 按落地页跳转：tab 页 switchTab、其余 navigateTo。
 *
 * 为什么必须区分：wx.switchTab 对**非 tab 页静默失败**（不报错、没反应），
 * 而 wx.navigateTo 对 tab 页直接失败。两者都不能「都试一遍」—— 那会出现双跳。
 */
function openNotice(notice) {
  const jump = notice && notice.jumpPage;
  if (!jump) {
    return;
  }
  if (TAB_PAGES.some((p) => jump.indexOf(p) === 0)) {
    wx.switchTab({ url: jump });
  } else {
    wx.navigateTo({ url: jump, fail: () => {} });
  }
}

/** 标已读（幂等，best-effort）：卡片点击与列表点击都要做，失败不阻断跳转 */
function markRead(noticeId) {
  if (!noticeId) {
    return Promise.resolve();
  }
  return api.social.notice.markRead(noticeId).catch(() => {});
}

// ---------------- 首页卡片的弹出策略 ----------------

const SEEN_STORAGE_KEY = 'duoduo_notice_popup_seen';

/** 同一来源（同一会话 / 同一业务单）的弹出冷却时长 */
const POPUP_COOLDOWN_MS = 5 * 60 * 1000;

/** 冷却表最多保留多少条（防止长期堆积；超出按时间淘汰最旧的） */
const MAX_SEEN = 50;

/** 挑「最新未读」时最多看几条（未读总在时间序前部，取一小段足够） */
const POPUP_SCAN_SIZE = 10;

/** 读冷却表：顺手丢掉过期项，避免它随使用无限增长 */
function loadSeen(now) {
  let raw = {};
  try {
    raw = wx.getStorageSync(SEEN_STORAGE_KEY) || {};
  } catch (e) {
    raw = {};
  }
  const fresh = Object.create(null);
  Object.keys(raw).forEach((key) => {
    if (now - raw[key] < POPUP_COOLDOWN_MS) {
      fresh[key] = raw[key];
    }
  });
  return fresh;
}

function saveSeen(seen) {
  const keys = Object.keys(seen);
  if (keys.length > MAX_SEEN) {
    keys.sort((a, b) => seen[a] - seen[b]);
    keys.slice(0, keys.length - MAX_SEEN).forEach((k) => delete seen[k]);
  }
  try {
    wx.setStorageSync(SEEN_STORAGE_KEY, seen);
  } catch (e) {}
}

/**
 * 冷却键：按「来源」而不是「通知 id」。
 *
 * 私信通知是**同会话合并**的（连发多条只更新同一行，id 不变）——
 * 按来源做键，「同一个人连发三条」只弹一次；订单类通知每次事件都是新行，
 * 按来源做键会让同一订单的「已支付」与「已发货」各弹一次，也都符合预期。
 */
function popupKeyOf(notice) {
  if (notice && notice.bizType && notice.bizId) {
    return notice.bizType + ':' + notice.bizId;
  }
  return 'notice:' + (notice && notice.id);
}

/**
 * 挑「现在该弹的那一条」未读通知。
 *
 * @returns {Promise<object|null>} 通知对象（原始形态）或 null（没有该弹的）
 */
function pickForPopup() {
  return api.social.notice
    .list(1, POPUP_SCAN_SIZE, '')
    .then((data) => {
      const items = (data && data.items) || [];
      const unread = items.filter((it) => !it.read);
      if (!unread.length) {
        return null;
      }
      const seen = loadSeen(Date.now());
      const target = unread.find((it) => !seen[popupKeyOf(it)]);
      if (!target) {
        return null;
      }
      seen[popupKeyOf(target)] = Date.now();
      saveSeen(seen);
      return target;
    })
    .catch(() => null);
}

/** 清空冷却表（切账号 / 自测用） */
function resetPopupCooldown() {
  try {
    wx.removeStorageSync(SEEN_STORAGE_KEY);
  } catch (e) {}
}

module.exports = {
  TAB_PAGES,
  ICONS,
  iconOf,
  openNotice,
  markRead,
  SEEN_STORAGE_KEY,
  POPUP_COOLDOWN_MS,
  POPUP_SCAN_SIZE,
  popupKeyOf,
  pickForPopup,
  resetPopupCooldown,
};

// 通知中心（第五十轮）
//
// 数据：GET /api/socials/notices —— 定向通知（user_id=我）+ 全站公告（user_id=0，
// 已读记在集合表）由服务端合并为同一个视图，前端只消费 read 布尔。
// 进入页面后：拉列表 + 私信未读合计；未读数同步到 tabBar 角标（wx.setTabBarBadge）。
// 点击通知：标已读（幂等）→ 有落地页则跳转（tab 页用 switchTab，其余 navigateTo）。
const api = require('../../utils/api');

const PAGE_SIZE = 50;

// tabBar 页面必须用 switchTab 跳转（第五十轮口径：首页/分享圈/通知/购物车/我的）
const TAB_PAGES = [
  '/pages/index/index',
  '/pages/share-circle/share-circle',
  '/pages/notification/notification',
  '/pages/cart/cart',
  '/pages/profile/profile',
];

/** 通知类别 → 图标与文案口径（服务端 category 字段） */
const CATEGORY_ICONS = {
  ANNOUNCEMENT: '📢',
  SYSTEM: '🔔',
  FRIEND: '👤',
  MERCHANT: '🏪',
  ORDER: '📦',
};

function decorate(it) {
  return Object.assign({}, it, {
    icon: CATEGORY_ICONS[it.category] || '🔔',
    timeText: api.shortTime(it.createdAt),
  });
}

Page({
  data: {
    logged: false,
    items: [],
    loading: false,
    error: '',
    hasUnread: false,
    msgUnread: 0,
  },

  onShow() {
    // onShow 而不是 onLoad：从落地页返回、从其他 tab 切回都刷新到最新
    const logged = !!api.getToken();
    this.setData({ logged });
    if (!logged) return;
    this.fetch(true);
    this.fetchMsgUnread();
  },

  onPullDownRefresh() {
    if (!this.data.logged) {
      wx.stopPullDownRefresh();
      return;
    }
    Promise.all([this.fetch(true), this.fetchMsgUnread()])
      .catch(() => {})
      .then(() => wx.stopPullDownRefresh());
  },

  fetch(reset) {
    this.setData({ loading: true, error: '' });
    return api.social.notice
      .list(1, PAGE_SIZE)
      .then((data) => {
        const rows = ((data && data.items) || []).map(decorate);
        const hasUnread = rows.some((it) => !it.read);
        this.setData({ items: rows, hasUnread, loading: false });
        // tabBar「通知」角标：未读 > 0 显示数字，否则清除（本页就是 tab 页，可直调）
        this.syncBadge();
      })
      .catch((err) => this.setData({ loading: false, error: (err && err.message) || '加载失败' }));
  },

  /** 私信未读合计：复用会话列表接口，前端求和（v1 量级小，够用） */
  fetchMsgUnread() {
    return api.social.message
      .conversations(1, 50)
      .then((data) => {
        const list = (data && data.items) || [];
        const sum = list.reduce((acc, it) => acc + (Number(it.unread) || 0), 0);
        this.setData({ msgUnread: sum });
      })
      .catch(() => {});
  },

  /** 通知未读数 → tabBar 角标（用服务端口径，避免前端对公告已读的两种存储做重复判断） */
  syncBadge() {
    api.social.notice
      .unreadCount()
      .then((data) => {
        const count = (data && data.count) || 0;
        if (count > 0) {
          wx.setTabBarBadge({ index: 2, text: count > 99 ? '99+' : String(count), fail: () => {} });
        } else {
          wx.removeTabBarBadge({ index: 2, fail: () => {} });
        }
      })
      .catch(() => {});
  },

  retry() {
    this.fetch(true);
  },

  /** 点击通知：标已读（幂等）→ 有落地页则跳（tab 页 switchTab / 其余 navigateTo） */
  tapNotice(e) {
    const { id, jump } = e.currentTarget.dataset;
    const row = this.data.items.find((it) => String(it.id) === String(id));
    if (row && !row.read) {
      api.social.notice
        .markRead(id)
        .then(() => {
          this.setData({
            items: this.data.items.map((it) => (String(it.id) === String(id) ? Object.assign({}, it, { read: true }) : it)),
            hasUnread: this.data.items.some((it) => !it.read && String(it.id) !== String(id)),
          });
          this.syncBadge();
        })
        .catch(() => {});
    }
    if (jump) {
      if (TAB_PAGES.some((p) => jump.indexOf(p) === 0)) {
        wx.switchTab({ url: jump });
      } else {
        wx.navigateTo({ url: jump });
      }
    }
  },

  /** 全部已读：服务端一次置齐（定向行 + 公告集合表），前端刷新视图 */
  readAll() {
    api.social.notice
      .readAll()
      .then(() => {
        this.setData({
          items: this.data.items.map((it) => Object.assign({}, it, { read: true })),
          hasUnread: false,
        });
        this.syncBadge();
      })
      .catch((err) => api.toastError(err));
  },

  goChatList() {
    wx.navigateTo({ url: '/pages/chat-list/chat-list' });
  },

  goLogin() {
    api.openLoginPage();
  },
});

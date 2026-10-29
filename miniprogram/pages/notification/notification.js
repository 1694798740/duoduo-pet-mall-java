// 通知中心（第五十轮启用；第五十五轮补分类筛选与分页）
//
// 数据：GET /api/socials/notices —— 定向通知（user_id=我）+ 全站公告（user_id=0，
// 已读记在集合表）由服务端合并为同一个视图，前端只消费 read 布尔。
//
// 未读角标（第五十五轮）：本页不再自己调 wx.setTabBarBadge —— 角标口径统一在
// utils/unread.js（通知 + 私信合并，服务端算）。本页只是「刷新得最勤的那个点」，
// 顺带把合并结果里的私信数拿来做页内显示（同一次请求，同源同刻）。
//
// 点击通知：标已读（幂等）→ 有落地页则跳转（tab 页用 switchTab，其余 navigateTo）。
const api = require('../../utils/api');
const unread = require('../../utils/unread');
const notice = require('../../utils/notice');

const PAGE_SIZE = 20;

/** 分类筛选项（key 为空 = 全部）。与 t_social_notice.category 的取值一一对应 */
const CATEGORIES = [
  { key: '', label: '全部' },
  { key: 'ORDER', label: '订单' },
  { key: 'FRIEND', label: '好友' },
  { key: 'MERCHANT', label: '商家' },
  { key: 'SYSTEM', label: '系统' },
  { key: 'ANNOUNCEMENT', label: '公告' },
];

function decorate(it) {
  return Object.assign({}, it, {
    icon: notice.iconOf(it),
    timeText: api.shortTime(it.createdAt),
  });
}

Page({
  data: {
    logged: false,
    items: [],
    page: 1,
    noMore: false,
    loading: false,
    error: '',
    hasUnread: false,
    msgUnread: 0,
    categories: CATEGORIES,
    activeCategory: '',
  },

  onShow() {
    // onShow 而不是 onLoad：从落地页返回、从其他 tab 切回都刷新到最新
    const logged = !!api.getToken();
    this.setData({ logged });
    if (!logged) return;
    this.fetch(true);
    this.fetchSummary();
  },

  onPullDownRefresh() {
    if (!this.data.logged) {
      wx.stopPullDownRefresh();
      return;
    }
    Promise.all([this.fetch(true), this.fetchSummary()])
      .catch(() => {})
      .then(() => wx.stopPullDownRefresh());
  },

  /** 触底加载下一页（第五十五轮：此前一次拉 50 条，超过就永远看不到） */
  onReachBottom() {
    if (this.data.loading || this.data.noMore || !this.data.logged) return;
    this.fetch(false);
  },

  /** 切换分类：清空列表重新从第 1 页拉（服务端按 category 过滤，前端不本地筛） */
  switchCategory(e) {
    const key = (e.currentTarget.dataset.key || '').trim();
    if (key === this.data.activeCategory) return;
    this.setData({ activeCategory: key, items: [], noMore: false });
    this.fetch(true);
  },

  /**
   * 拉通知列表。
   *
   * @param {boolean} reset true = 从第 1 页重拉（切换分类 / 下拉 / 首次进入）
   */
  fetch(reset) {
    const page = reset ? 1 : this.data.page;
    this.setData({ loading: true, error: '' });
    return api.social.notice
      .list(page, PAGE_SIZE, this.data.activeCategory)
      .then((data) => {
        const rows = ((data && data.items) || []).map(decorate);
        const merged = reset ? rows : this.data.items.concat(rows);
        const total = Number((data && data.total) || 0);
        this.setData({
          items: merged,
          page: page + 1,
          // 没有更多 = 本页空 / 已拉到 total 条（两个判据都要：total 在并发新增时可能偏小）
          noMore: rows.length === 0 || merged.length >= total,
          hasUnread: merged.some((it) => !it.read),
          loading: false,
        });
      })
      .catch((err) => this.setData({ loading: false, error: (err && err.message) || '加载失败' }));
  },

  /**
   * 合并未读（第五十五轮）：一次请求拿到「通知 / 私信 / 总数」，同时把总数画到 tabBar。
   *
   * 此前这里是两处独立计算：通知未读走服务端，私信未读靠拉一页会话自己求和 ——
   * 会话超过一页时私信数偏小，角标与页内数字也会因为两次请求的时序差对不上。
   */
  fetchSummary() {
    // 合并未读与分类分桶**并行**取：串行会让 chip 上的数字比 tabBar 晚一拍才出现。
    // 分类失败按空对象兜底 —— 角标是提醒，不能因为它报错让整页进不去。
    return Promise.all([
      unread.refresh(),
      api.social.notice.unreadByCategory().catch(() => ({})),
    ]).then(([summary, byCategory]) => {
      this.setData({
        msgUnread: summary.message > 0 ? summary.message : 0,
        // 分类 chip 的角标：数字只认服务端分桶（端上按「已加载列表里 !read 的条数」数，
        // 会把没加载的页整个漏掉 —— 用户看到 chip 没数字、点进去却有未读）
        categories: CATEGORIES.map((c) =>
          Object.assign({}, c, { count: c.key ? Number((byCategory || {})[c.key] || 0) : 0 })
        ),
      });
      return summary;
    });
  },

  retry() {
    this.fetch(true);
    this.fetchSummary();
  },

  /** 点击通知：标已读（幂等）→ 有落地页则跳（tab 页 switchTab / 其余 navigateTo） */
  tapNotice(e) {
    const { id, jump } = e.currentTarget.dataset;
    const row = this.data.items.find((it) => String(it.id) === String(id));
    if (row && !row.read) {
      notice
        .markRead(id)
        .then(() => {
          this.setData({
            items: this.data.items.map((it) => (String(it.id) === String(id) ? Object.assign({}, it, { read: true }) : it)),
            hasUnread: this.data.items.some((it) => !it.read && String(it.id) !== String(id)),
          });
          // 已读后角标要跟着减（第五十五轮）：口径仍由 utils/unread 统一算
          this.fetchSummary();
        })
        .catch(() => {});
    }
    if (jump) {
      notice.openNotice({ jumpPage: jump });
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
        this.fetchSummary();
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

// 关注与粉丝列表（第四十七轮）：两个 tab（我关注的 / 我的粉丝）
//
// 数据：GET /api/socials/follows/following | /followers（只能看自己的，
// 他人主页的计数点击会提示不支持）。mutual 角标来自服务端批量互关判定。
const api = require('../../utils/api');

const PAGE_SIZE = 20;

/** 行装饰：时间文本 + 头像兜底 */
function decorate(it) {
  return Object.assign({}, it, {
    timeText: api.shortTime(it.followedAt),
    avatarSrc: it.avatar || '/images/category/default.png',
  });
}

Page({
  data: {
    tab: 'following', // following=我关注的 | followers=我的粉丝
    items: [],
    total: 0,
    page: 1,
    loading: false,
    refreshing: false,
    noMore: false,
    error: '',
  },

  onLoad(query) {
    this.applyTab(query.tab === 'followers' ? 'followers' : 'following');
  },

  onPullDownRefresh() {
    this.setData({ refreshing: true });
    this.fetch(true, () => {
      this.setData({ refreshing: false });
      wx.stopPullDownRefresh();
    });
  },

  onReachBottom() {
    if (this.data.loading || this.data.noMore) return;
    this.fetch(false);
  },

  switchTab(e) {
    const tab = e.currentTarget.dataset.tab;
    if (tab === this.data.tab) return;
    this.applyTab(tab);
  },

  applyTab(tab) {
    this.setData({
      tab,
      items: [],
      page: 1,
      noMore: false,
      error: '',
    });
    wx.setNavigationBarTitle({ title: tab === 'followers' ? '我的粉丝' : '我关注的' });
    this.fetch(true);
  },

  fetch(reset, done) {
    const page = reset ? 1 : this.data.page + 1;
    this.setData({ loading: true, error: '' });
    api.social.follow[this.data.tab](page, PAGE_SIZE)
      .then((data) => {
        const rows = ((data && data.items) || []).map(decorate);
        this.setData({
          items: reset ? rows : this.data.items.concat(rows),
          total: (data && data.total) || 0,
          page,
          noMore: rows.length < PAGE_SIZE,
          loading: false,
          refreshing: false,
        });
        if (done) done();
      })
      .catch((err) => {
        this.setData({ loading: false, refreshing: false, error: (err && err.message) || '加载失败' });
        if (done) done();
      });
  },

  retry() {
    this.fetch(true);
  },

  /** 点行 → 他人主页 */
  goProfile(e) {
    wx.navigateTo({ url: '/pages/user-profile/user-profile?id=' + e.currentTarget.dataset.user });
  },

  /** 点「私信」→ 聊天页（不冒泡到行） */
  goChat(e) {
    wx.navigateTo({ url: '/pages/chat/chat?peerId=' + e.currentTarget.dataset.user });
  },
});

// 他人主页（第四十七轮）：社交域的「用户名片」
//
// 入口：分享圈/分享详情的作者区、关注与粉丝列表、聊天头部。
// 数据一次拿齐：GET /api/socials/users/{id}/profile（资料 + 计数 + 我与 TA 的关系）。
//
// 关注按钮文案随关系变化：未关注「＋ 关注」/ 已关注 / 互相关注；
// 关注与取关都是幂等接口，重复点击不会产生脏数据。
const api = require('../../utils/api');

Page({
  data: {
    id: '',
    user: null,
    isSelf: false,
    loading: true,
    error: '',
    toggling: false,
  },

  onLoad(query) {
    const me = getApp().globalData.user;
    this.setData({
      id: query.id || '',
      // 自己点进自己的主页（如分享圈里自己的帖子）：不显示关注/私信操作
      isSelf: !!(me && String(me.id) === String(query.id)),
    });
    this.fetch();
  },

  fetch() {
    this.setData({ loading: true, error: '' });
    api.social.follow
      .profile(this.data.id)
      .then((user) => this.setData({ loading: false, user }))
      .catch((err) => this.setData({ loading: false, error: (err && err.message) || '加载失败' }));
  },

  retry() {
    this.fetch();
  },

  /** 关注 / 取关：动作由当前状态决定，接口幂等；成功后重取 profile（计数变了） */
  toggleFollow() {
    const user = this.data.user;
    if (!user || this.data.toggling) return;
    this.setData({ toggling: true });
    const action = user.viewerFollowing
      ? api.social.follow.unfollow(user.userId)
      : api.social.follow.follow(user.userId);
    action
      .then(() => api.social.follow.profile(user.userId))
      .then((fresh) => {
        this.setData({ toggling: false, user: fresh });
        wx.showToast({ title: fresh.viewerFollowing ? '已关注' : '已取消关注', icon: 'none' });
      })
      .catch((err) => {
        this.setData({ toggling: false });
        if (err && err.needLogin) return; // 已引导登录页
        wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' });
      });
  },

  goChat() {
    if (this.data.isSelf) return;
    wx.navigateTo({ url: '/pages/chat/chat?peerId=' + this.data.id });
  },

  /**
   * 点粉丝 / 关注数 → 关注与粉丝列表。
   *
   * <p>后端 following/followers 只提供「查自己」的语义（未做看他人列表的端点），
   * 非本人主页时诚实提示，而不是发起一个注定 404/403 的请求。
   */
  goList(e) {
    if (!this.data.isSelf) {
      wx.showToast({ title: '暂不支持查看他人的关注列表', icon: 'none' });
      return;
    }
    wx.navigateTo({
      url: '/pages/follow-list/follow-list?tab=' + (e.currentTarget.dataset.tab || 'followers'),
    });
  },
});

// 我的：用户信息 + 功能入口（领券 / 券包 / AI / 秒杀 / 订单 / 购物车）
//
// A14 修复要点：把「未登录」与「接口失败」两类失败分开处理 ——
// 此前共用一个 catch，券包接口抖动会把已登录用户显示成「点击登录」。
const api = require('../../utils/api');
const unread = require('../../utils/unread');

Page({
  data: {
    user: null,
    logged: false,
    couponCount: 0,
  },

  onShow() {
    // 未读角标（第五十五轮）：tab 页每次露面都刷一次（原因见 utils/unread.js 头注释）
    unread.refresh();
    api
      .ensureSession()
      .then((user) => {
        getApp().globalData.user = user;
        this.setData({ user, logged: true });
        // 券包数量是「锦上添花」：失败只把数字归零，绝不影响登录态展示
        return api.coupon
          .mine('UNUSED')
          .then((list) => this.setData({ couponCount: (list || []).length }))
          .catch(() => this.setData({ couponCount: 0 }));
      })
      .catch(() => {
        // 只有「确实没有登录态」才回到未登录展示
        this.setData({ logged: false, user: null, couponCount: 0 });
      });
  },

  goLogin() {
    api.openLoginPage();
  },

  go(e) {
    const url = e.currentTarget.dataset.url;
    // 第五十轮：分类移出 tabBar、通知升为居中 tab，清单与 app.json tabBar 同步
    const tabPages = [
      '/pages/index/index',
      '/pages/share-circle/share-circle',
      '/pages/notification/notification',
      '/pages/cart/cart',
      '/pages/profile/profile',
    ];
    if (tabPages.some((p) => url.indexOf(p) === 0)) {
      wx.switchTab({ url });
    } else {
      wx.navigateTo({ url });
    }
  },

  /** 收货地址：跳小程序内地址簿（pages/address） */
  goAddress() {
    wx.navigateTo({ url: '/pages/address/address' });
  },

  // ── 问客服直连（A4，2026-10-20）──────────────────────────────
  /**
   * 先向服务端要「官方客服是谁」，再打开与 TA 的私信会话。
   *
   * <p>前端不硬编码客服 id：换客服账号只改服务端 `duoduo.social.official-cs-user-id`，
   * 端侧无需发版。会话本身复用整套 /api/socials/messages/** 规则。
   *
   * <p>★ 失败必须给下一步：服务端在客服账号不存在时回 503（SUPPORT_AGENT_NOT_READY）。
   * 若这里只是吞掉异常，用户点一下没反应，会以为按钮坏了。
   */
  goSupport() {
    wx.showLoading({ title: '正在接通…', mask: true });
    api.social.support
      .agent()
      .then((res) => {
        wx.hideLoading();
        const peerId = res && res.userId;
        if (!peerId) {
          this.supportFallback();
          return;
        }
        // 注意参数名是 peerId（不是 userId）；id 一律按字符串拼，禁止 Number() 强转
        wx.navigateTo({ url: '/pages/chat/chat?peerId=' + String(peerId) });
      })
      .catch(() => {
        wx.hideLoading();
        this.supportFallback();
      });
  },

  /** 客服没接通时的兜底：说清原因 + 给 AI 导购出口（7×24 在线，不是安慰话） */
  supportFallback() {
    wx.showModal({
      title: '客服暂时没接通',
      content:
        '可能是网络不稳定，或客服账号尚未配置。商品参数、规格、订单进度这类问题可以先问 AI 导购（7×24 小时在线）。',
      confirmText: '去问 AI',
      cancelText: '知道了',
      success: (r) => {
        if (r.confirm) wx.navigateTo({ url: '/pages/ai/ai' });
      },
    });
  },

  // ── 微信绑定管理（A14）────────────────────────────────────
  /**
   * 绑定 / 解绑微信。
   *
   * <p>都要重新取 code：解绑必须证明"操作者持有这个微信"（后端据此校验 code 的 openid
   * 是否等于当前账号绑定的那个），而 code 是一次性的，不能复用登录时那一个。
   */
  toggleWechat() {
    const bound = !!(this.data.user && this.data.user.wechatBound);
    const doIt = () => {
      wx.showLoading({ title: bound ? '解绑中…' : '绑定中…', mask: true });
      api
        .getWxCode()
        .then((code) => (bound ? api.wechatUnbind(code) : api.wechatBind(code)))
        .then((user) => {
          wx.hideLoading();
          this.setData({ user });
          wx.showToast({
            title: bound ? '已解绑微信' : '已绑定微信',
            icon: 'success',
          });
        })
        .catch((err) => {
          wx.hideLoading();
          if (err && err.needLogin) return; // 已引导登录
          wx.showModal({
            title: bound ? '解绑失败' : '绑定失败',
            content: (err && err.message) || '请稍后重试',
            showCancel: false,
          });
        });
    };

    if (!bound) {
      doIt();
      return;
    }
    wx.showModal({
      title: '解绑微信',
      content: '解绑后将无法用「微信一键登录」，需要重新绑定才能恢复。确定解绑吗？',
      confirmText: '解绑',
      confirmColor: '#e5484d',
      success: (res) => {
        if (res.confirm) doIt();
      },
    });
  },

  logout() {
    api.clearSession();
    this.setData({ logged: false, user: null, couponCount: 0 });
    wx.showToast({ title: '已退出登录', icon: 'none' });
  },

  /**
   * C7 · 账号注销入口。
   *
   * 用 navigateTo 而不是 switchTab：这是独立页面，且注销后要能返回。
   * ★ 注意：提交注销成功后后端会吊销所有 refresh 令牌，本页返回时登录态已失效，
   *   注销页内部会自行 clearSession()，这里不重复处理。
   */
  goDeleteAccount() {
    wx.navigateTo({ url: '/pages/account-delete/account-delete' });
  },
});

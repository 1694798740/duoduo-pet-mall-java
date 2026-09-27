// 我的：用户信息 + 功能入口（领券 / 券包 / AI / 秒杀 / 订单 / 购物车）
//
// A14 修复要点：把「未登录」与「接口失败」两类失败分开处理 ——
// 此前共用一个 catch，券包接口抖动会把已登录用户显示成「点击登录」。
const api = require('../../utils/api');

Page({
  data: {
    user: null,
    logged: false,
    couponCount: 0,
  },

  onShow() {
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
    const tabPages = [
      '/pages/index/index',
      '/pages/category/category',
      '/pages/share-circle/share-circle',
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
});

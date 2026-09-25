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
      '/pages/cart/cart',
      '/pages/orders/orders',
      '/pages/profile/profile',
    ];
    if (tabPages.some((p) => url.indexOf(p) === 0)) {
      wx.switchTab({ url });
    } else {
      wx.navigateTo({ url });
    }
  },

  /** 地址簿目前只在 Web 端维护：给小程序的明确指引，避免用户在「找不到入口」上卡住 */
  addressTip() {
    wx.showModal({
      title: '收货地址',
      content: '当前版本请在 Web 端「我的 → 收货地址」维护地址；小程序下单使用默认地址。',
      showCancel: false,
    });
  },

  logout() {
    api.clearSession();
    this.setData({ logged: false, user: null, couponCount: 0 });
    wx.showToast({ title: '已退出登录', icon: 'none' });
  },
});

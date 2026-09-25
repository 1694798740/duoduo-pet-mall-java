// 我的：用户信息 + 功能入口（领券 / 券包 / AI / 订单）
const api = require('../../utils/api');

Page({
  data: {
    user: null,
    logged: false,
    couponCount: 0,
  },

  onShow() {
    const app = getApp();
    api
      .ensureSession()
      .then((user) => {
        app.globalData.user = user;
        this.setData({ user, logged: true });
        return api.coupon.mine('UNUSED');
      })
      .then((list) => {
        this.setData({ couponCount: (list || []).length });
      })
      .catch(() => this.setData({ logged: false, user: null, couponCount: 0 }));
  },

  goLogin() {
    wx.navigateTo({ url: '/pages/login/login' });
  },

  go(e) {
    const url = e.currentTarget.dataset.url;
    if (url.indexOf('/pages/cart') === 0 || url.indexOf('/pages/orders') === 0) {
      wx.switchTab({ url });
    } else {
      wx.navigateTo({ url });
    }
  },

  logout() {
    api.clearToken();
    getApp().globalData.user = null;
    this.setData({ logged: false, user: null, couponCount: 0 });
    wx.showToast({ title: '已退出登录', icon: 'none' });
  },
});

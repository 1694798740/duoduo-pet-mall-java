// 登录页：测试号阶段用手机号 + 密码（复用既有账号体系）；
// AppID + 后端 /api/auth/wechat 就绪后（config.WECHAT_LOGIN_READY=true）优先微信登录。
const { WECHAT_LOGIN_READY } = require('../../config');

Page({
  data: {
    phone: '',
    password: '',
    submitting: false,
    wechatReady: WECHAT_LOGIN_READY,
  },

  onPhone(e) {
    this.setData({ phone: e.detail.value });
  },

  onPassword(e) {
    this.setData({ password: e.detail.value });
  },

  submit() {
    const { phone, password } = this.data;
    if (!phone || !password) {
      wx.showToast({ title: '请输入手机号和密码', icon: 'none' });
      return;
    }
    this.setData({ submitting: true });
    getApp()
      .loginBy(phone, password)
      .then(() => {
        this.setData({ submitting: false });
        wx.showToast({ title: '登录成功', icon: 'success' });
        // 登录成功回上一页（ensureLogin 的来源页）；tab 页用 switchTab 回首页
        const pages = getCurrentPages();
        if (pages.length > 1) {
          wx.navigateBack();
        } else {
          wx.switchTab({ url: '/pages/index/index' });
        }
      })
      .catch((err) => {
        this.setData({ submitting: false });
        wx.showToast({ title: (err && err.message) || '登录失败', icon: 'none' });
      });
  },
});

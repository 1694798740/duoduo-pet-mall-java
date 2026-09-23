// A13 · 小程序入口
const api = require('./utils/api');
const { WECHAT_LOGIN_READY } = require('./config');

App({
  globalData: {
    user: null,
  },

  onLaunch() {
    // 启动即探测登录态；未登录不阻塞浏览（商品列表是公开接口），
    // 加购/下单时由各页面调用 ensureLogin。
    api.ensureSession()
      .then((user) => {
        this.globalData.user = user;
      })
      .catch(() => {
        // 静默失败：不自动跳登录页，让「首页」先渲染
      });
  },

  // 页面按需调用：未登录则去登录页，登录成功回跳
  ensureLogin() {
    return api.ensureSession()
      .then((user) => {
        this.globalData.user = user;
        return user;
      })
      .catch(() => {
        return new Promise((resolve, reject) => {
          // 登录页成功后调 getApp().finishLogin(user) → 这里 resolve
          wx.navigateTo({ url: '/pages/login/login' });
          this._loginResolve = resolve;
          this._loginReject = reject;
        });
      });
  },

  // 登录页登录成功后调用
  finishLogin(user) {
    this.globalData.user = user;
    if (this._loginResolve) {
      this._loginResolve(user);
      this._loginResolve = null;
      this._loginReject = null;
    }
  },

  // 登录方式：WECHAT_LOGIN_READY=true 走 code2Session，否则手机号登录（测试号阶段）
  loginBy(phone, password) {
    const p = WECHAT_LOGIN_READY
      ? api.wxLogin()
      : api.phoneLogin(phone, password);
    return p.then((user) => {
      this.finishLogin(user);
      return user;
    });
  },
});

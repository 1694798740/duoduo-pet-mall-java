// 登录页：微信一键登录 + 手机号登录/注册（A14 修复：两条路都必须可见可用）
//
// 为什么两条路都要有：
//  * 微信一键登录走 /api/auth/wechat/token（code 换令牌，令牌随响应体下发）。
//    但后端对**未绑定**的微信身份返回 422（不做自动注册，避免造出无手机号的孤儿账号），
//    所以新用户必须能走手机号注册 —— 把手机号入口藏起来等于把新用户挡在门外。
//  * 手机号登录/注册走 /api/auth/login/token、/api/auth/register（注册后自动登录换令牌）。
//
// 令牌写入与等待方结算都交给 app：app.finishLogin(user) 会唤醒
// ensureLogin() 里等待的页面 Promise，登录页只管界面与提示。
const { WECHAT_LOGIN_READY } = require('../../config');

const PHONE_REGEX = /^1[3-9]\d{9}$/;

Page({
  data: {
    // phone：手机号登录 / register：手机号注册（小程序内自助开户）
    mode: 'phone',
    wechatReady: WECHAT_LOGIN_READY,
    phone: '',
    password: '',
    nickname: '',
    submitting: false,
  },

  // 是否已登录成功：用于 onUnload 判断要不要唤醒等待方（失败退出）
  _logged: false,

  onPhone(e) {
    this.setData({ phone: e.detail.value });
  },

  onPassword(e) {
    this.setData({ password: e.detail.value });
  },

  onNickname(e) {
    this.setData({ nickname: e.detail.value });
  },

  switchMode(e) {
    const mode = e.currentTarget.dataset.mode;
    if (mode && mode !== this.data.mode) this.setData({ mode });
  },

  /** 微信一键登录 */
  wechatLogin() {
    if (this.data.submitting) return;
    this.setData({ submitting: true });
    const app = getApp();
    app
      .silentRelogin() // wx.login → /api/auth/wechat/token，并写入 globalData
      .then((user) => {
        app.finishLogin(user); // 唤醒等待中的页面 Promise（若有）
        this.afterLogin('登录成功');
      })
      .catch((err) => {
        this.setData({ submitting: false });
        this.handleWechatError(err);
      });
  },

  /** 微信登录失败的分类提示：未绑定要引导去手机号，别让用户以为是「登录坏了」 */
  handleWechatError(err) {
    const status = err && err.status;
    if (status === 422) {
      wx.showModal({
        title: '微信未绑定账号',
        content: '该微信还没有绑定多多账号。请用手机号登录/注册，再在「我的」页面绑定微信。',
        confirmText: '去手机号登录',
        showCancel: false,
        success: () => this.setData({ mode: 'phone' }),
      });
      return;
    }
    if (status === 409) {
      wx.showToast({ title: '该微信已绑定其他账号', icon: 'none' });
      return;
    }
    if (status === 503) {
      wx.showToast({ title: '微信登录暂不可用，请用手机号登录', icon: 'none' });
      return;
    }
    wx.showToast({ title: (err && err.message) || '登录失败，请重试', icon: 'none' });
  },

  /** 手机号登录 / 注册（同一个按钮，按 mode 分流） */
  submitPhone() {
    if (this.data.submitting) return;
    const { phone, password, nickname, mode } = this.data;
    if (!PHONE_REGEX.test(phone)) {
      wx.showToast({ title: '请输入正确的 11 位手机号', icon: 'none' });
      return;
    }
    if (!password) {
      wx.showToast({ title: '请输入密码', icon: 'none' });
      return;
    }
    // 与后端 RegisterRequest 的约束保持一致（8-64 位），避免「本地过了、后端 400」
    if (mode === 'register' && (password.length < 8 || password.length > 64)) {
      wx.showToast({ title: '密码长度需 8-64 位', icon: 'none' });
      return;
    }
    const app = getApp();
    this.setData({ submitting: true });
    const action =
      mode === 'register' ? app.registerBy(phone, password, nickname) : app.loginByPhone(phone, password);
    action
      .then(() => this.afterLogin(mode === 'register' ? '注册成功' : '登录成功'))
      .catch((err) => {
        this.setData({ submitting: false });
        wx.showToast({ title: (err && err.message) || '操作失败，请重试', icon: 'none' });
      });
  },

  afterLogin(title) {
    this._logged = true;
    this.setData({ submitting: false });
    wx.showToast({ title: title, icon: 'success' });
    // 稍等提示可见再回跳；tab 页用 switchTab，非 tab 页 navigateBack 回到来源页
    setTimeout(() => {
      const pages = getCurrentPages();
      if (pages.length > 1) {
        wx.navigateBack();
      } else {
        wx.switchTab({ url: '/pages/index/index' });
      }
    }, 500);
  },

  /** 用户返回/登录页被销毁且仍未登录：拒绝等待中的 Promise，避免调用方静默悬挂 */
  onUnload() {
    if (!this._logged) {
      const app = getApp();
      if (app && app.cancelLogin) app.cancelLogin();
    }
  },
});

// 登录页：微信一键登录 + 手机号登录/注册（含「未绑定微信」闭环）
//
// 四种情形都要走得通（A14 修复：此前只有第 1/2 种，后两种是死路）：
//   ① 微信已绑定        → 一键登录拿到令牌
//   ② 微信未绑定 + 未登录 → 弹窗引导手机号；**登录成功后自动用新 code 绑定当前微信**，
//                          下次即可一键登录（code 一次性，必须重新 wx.login）
//   ③ 微信未绑定 + 已登录 → 直接绑定（一次点击完成，不用再走一遍登录）
//   ④ 该微信已属他人     → 409，明确提示换账号
//
// 后端的刻意设计（不要在页面上绕开）：/api/auth/wechat 对未绑定微信返回 422，
// 不做自动注册 —— 微信授权拿不到手机号，自动注册会造出无手机号的孤儿账号。
const api = require('../../utils/api');
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
    // 是否处于「手机号登录后将自动绑定微信」流程（用于界面文案）
    pendingWechatBind: false,
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

  // ── 微信一键登录 ─────────────────────────────────────────
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

  /** 微信登录失败的分类处理：未绑定要给出**可走通的下一步**，别让用户反复点 */
  handleWechatError(err) {
    const status = err && err.status;

    if (status === 422) {
      // 情形③：已经登录了 → 一次点击直接绑定，不必再走登录
      if (api.getToken()) {
        this.bindCurrentWechat();
        return;
      }
      // 情形②：未登录 → 引导手机号，登录后自动绑定
      wx.showModal({
        title: '微信还没绑定账号',
        content: '用手机号登录或注册后，会自动把当前微信绑定到该账号；以后就能一键登录了。',
        confirmText: '去手机号登录',
        cancelText: '取消',
        success: (res) => {
          if (res.confirm) this.setData({ mode: 'phone', pendingWechatBind: true });
        },
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

  /** 把当前微信绑定到「已登录账号」：重新 wx.login 取新 code（旧 code 已用过） */
  bindCurrentWechat() {
    if (this.data.submitting) return;
    this.setData({ submitting: true });
    return new Promise((resolve, reject) => {
      wx.login({
        success: (res) => (res.code ? resolve(res.code) : reject({ message: '微信授权未返回 code' })),
        fail: () => reject({ message: '微信授权失败，请重试' }),
      });
    })
      .then((code) => api.wechatBind(code))
      .then(() => {
        this.setData({ submitting: false, pendingWechatBind: false });
        wx.showToast({ title: '已绑定微信，下次可一键登录', icon: 'success' });
      })
      .catch((err) => {
        this.setData({ submitting: false });
        wx.showToast({ title: (err && err.message) || '绑定失败，请重试', icon: 'none' });
      });
  },

  // ── 手机号登录 / 注册 ───────────────────────────────────
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
    const bindAfter = this.data.pendingWechatBind;
    this.setData({ submitting: true });
    const action =
      mode === 'register' ? app.registerBy(phone, password, nickname) : app.loginByPhone(phone, password);
    action
      .then(() => {
        // 登录/注册成功：若本次是从「微信未绑定」转过来的，顺手完成绑定
        if (bindAfter && this.data.wechatReady) {
          return this.bindCurrentWechat().then(() => this.afterLogin('已登录并绑定微信'));
        }
        return this.afterLogin(mode === 'register' ? '注册成功' : '登录成功');
      })
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

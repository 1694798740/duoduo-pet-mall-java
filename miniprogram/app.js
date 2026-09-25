// A13/A14 · 小程序入口
//
// 登录态设计（A14 修复要点）：
//  * 令牌（accessToken + refreshToken）由 /api/auth/*/token 随响应体下发，存 storage，以 Bearer 回传；
//  * 启动只做**静默探测**，未登录不阻塞浏览（商品/分类/评价都是公开接口）；
//  * 需要身份的动作由页面调 ensureLogin()：先探测 → 能静默重登就静默重登（wx.login 无需用户点击）
//    → 都不行才引导登录页；
//  * 登录页被用户返回、或打开登录页失败时，等待中的 Promise 必须**被拒绝**而不是永久悬挂，
//    否则调用方（加购/领券/秒杀）会静默无响应。
const api = require('./utils/api');
const { WECHAT_LOGIN_READY } = require('./config');

App({
  globalData: {
    user: null,
  },

  // 单飞标记：避免并发请求各触发一次静默重登 / 连续弹两个登录页
  _silentLoggingIn: false,
  _loginResolve: null,
  _loginReject: null,

  onLaunch() {
    // 令牌过期（401）时先尝试静默重登，失败再引导登录页
    api.onUnauthorized(() => this.handleUnauthorized());

    // 启动即探测登录态；未登录不阻塞浏览，也不自动跳登录页
    api
      .ensureSession()
      .then((user) => {
        this.globalData.user = user;
      })
      .catch(() => {
        // 静默失败：让「首页」先渲染
      });
  },

  /** 401 统一出口：能静默重登就静默，否则打开登录页 */
  handleUnauthorized() {
    if (!WECHAT_LOGIN_READY) {
      api.openLoginPage();
      return;
    }
    this.silentRelogin().catch(() => api.openLoginPage());
  },

  /**
   * 静默重新登录：微信环境下 wx.login 不需要用户点击，因此令牌过期可以无感恢复。
   * 并发调用只会有一次真实请求（_silentLoggingIn 单飞）。
   */
  silentRelogin() {
    if (this._silentLoggingIn) {
      return Promise.reject({ needLogin: true, message: '正在重新登录' });
    }
    this._silentLoggingIn = true;
    // 先试 refreshToken 换新令牌（比再走一次微信登录更轻）；没有 refreshToken 或它已失效时，
    // 微信环境下 wx.login 无需用户点击，可以无感重登。
    const attempt = api.refreshSession().catch(() => api.wxLogin());
    return attempt.then(
      (user) => {
        this._silentLoggingIn = false;
        this.globalData.user = user;
        return user;
      },
      (err) => {
        this._silentLoggingIn = false;
        throw err;
      }
    );
  },

  /** 页面按需调用：未登录则（静默重登或）去登录页，登录成功回跳 */
  ensureLogin() {
    return api
      .ensureSession()
      .then((user) => {
        this.globalData.user = user;
        return user;
      })
      .catch(() => {
        if (WECHAT_LOGIN_READY) {
          // 未绑定微信（422）等场景会走到这里：静默重登失败即引导登录页
          return this.silentRelogin().catch(() => this.openLogin());
        }
        return this.openLogin();
      });
  },

  /** 打开登录页并把等待中的 Promise 交给登录页结算 */
  openLogin() {
    return new Promise((resolve, reject) => {
      this._loginResolve = resolve;
      this._loginReject = reject;
      wx.navigateTo({
        url: '/pages/login/login',
        fail: () => {
          // 页面栈已满等：立即失败，避免调用方永久悬挂
          this._loginResolve = null;
          this._loginReject = null;
          reject({ needLogin: true, message: '打开登录页失败，请重试' });
        },
      });
    });
  },

  /** 登录页登录成功后调用 */
  finishLogin(user) {
    this.globalData.user = user;
    const resolve = this._loginResolve;
    this._loginResolve = null;
    this._loginReject = null;
    if (resolve) resolve(user);
  },

  /** 登录页被返回/销毁且仍未登录时调用：让等待方失败退出，不留悬挂 Promise */
  cancelLogin() {
    const reject = this._loginReject;
    this._loginResolve = null;
    this._loginReject = null;
    if (reject) reject({ needLogin: true, message: '未登录，请先登录' });
  },

  /** 登录方式：WECHAT_LOGIN_READY=true 走 code2Session，否则手机号登录 */
  loginBy(phone, password) {
    const p = WECHAT_LOGIN_READY ? api.wxLogin() : api.phoneLogin(phone, password);
    return p.then((user) => {
      this.finishLogin(user);
      return user;
    });
  },

  /** 手机号登录（含注册后自动登录），供登录页的手机号入口调用 */
  loginByPhone(phone, password) {
    return api.phoneLogin(phone, password).then((user) => {
      this.finishLogin(user);
      return user;
    });
  },

  /** 注册并登录（小程序内自助开户） */
  registerBy(phone, password, nickname) {
    return api.register(phone, password, nickname).then((user) => {
      this.finishLogin(user);
      return user;
    });
  },
});

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
    // 经服务端校验过的登录态（不是"本地存了令牌字符串"就算登录 —— 令牌可能已过期）
    loggedIn: false,
  },

  // 是否已登录成功：用于 onUnload 判断要不要唤醒等待方（失败退出）
  _logged: false,

  /**
   * 进页面先校验一次本地会话。
   *
   * <p>必要性：本地 storage 里的令牌可能已过期/失效，若不清掉，"微信未绑定"(422) 的后续处理
   * 会误判成"已登录"→ 直接去 bind → 401 → 静默重登 → 又 422，把用户卡在一个什么都不提示的死循环里。
   */
  onLoad() {
    this.verifySession();
  },

  verifySession() {
    return api
      .ensureSession()
      .then((user) => {
        getApp().globalData.user = user;
        this.setData({ loggedIn: true });
        return user;
      })
      .catch(() => {
        api.clearSession(); // 清掉无效令牌，让本地状态与真实登录态一致
        this.setData({ loggedIn: false });
        return null;
      });
  },

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
      // 情形③：确实已登录（服务端校验过）→ 一次点击直接绑定，不必再走登录
      if (this.data.loggedIn) {
        wx.showModal({
          title: '微信还没绑定账号',
          content: '当前账号已登录。要把这个微信绑定到当前账号吗？绑定后就能一键登录了。',
          confirmText: '绑定',
          cancelText: '换个账号',
          success: (res) => {
            if (res.confirm) {
              this.bindCurrentWechat();
            } else {
              // 给退路：换账号登录，登录后同样自动绑定
              this.setData({ mode: 'phone', pendingWechatBind: true, loggedIn: false });
            }
          },
        });
        return;
      }
      // 情形②：未登录 → 引导手机号，登录后自动绑定
      wx.showModal({
        title: '微信还没绑定账号',
        content: '用手机号登录或注册后，会自动把这个微信绑定到该账号；以后就能一键登录了。',
        confirmText: '用手机号登录并绑定',
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

  /**
   * 把当前微信绑定到「已登录账号」：重新 wx.login 取新 code（旧 code 已用过）。
   *
   * @param {{afterLogin?: boolean}} opts afterLogin=true 表示处在「登录成功后的绑定」场景：
   *   此时令牌刚刚签发、不该失效，因此失败只提示、不把用户踢回表单。
   */
  bindCurrentWechat(opts) {
    const afterLogin = !!(opts && opts.afterLogin);
    if (this.data.submitting && !afterLogin) return Promise.reject({ message: '正在处理中' });
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
        const tokenGone = !!(err && (err.needLogin || err.status === 401 || err.code === 'UNAUTHORIZED'));
        if (tokenGone && afterLogin) {
          // 刚登录成功却令牌失效：属于异常情况，提示但不打断（登录本身已成立）
          wx.showToast({ title: '绑定失败：登录态异常，请重进小程序再试', icon: 'none' });
          return Promise.reject(err);
        }
        if (tokenGone) {
          // 令牌已失效：不能只在原地报一句错，直接切到可执行的路径
          api.clearSession();
          this.setData({ loggedIn: false, mode: 'phone', pendingWechatBind: true });
          wx.showModal({
            title: '登录已过期',
            content: '请用手机号登录/注册，登录成功后会自动把当前微信绑定到该账号。',
            confirmText: '好的',
            showCancel: false,
          });
          return;
        }
        wx.showToast({ title: (err && err.message) || '绑定失败，请重试', icon: 'none' });
        throw err; // 让调用方（maybeBindWechat）知道没绑上，但不影响登录结果
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
    const doneTitle = mode === 'register' ? '注册成功' : '登录成功';
    this.setData({ submitting: true });
    const action =
      mode === 'register' ? app.registerBy(phone, password, nickname) : app.loginByPhone(phone, password);
    action
      .then(() => this.maybeBindWechat())   // 登录成功后统一处理「要不要绑微信」
      .then((bound) => this.afterLogin(bound ? '已登录并绑定微信' : doneTitle))
      .catch((err) => {
        this.setData({ submitting: false });
        wx.showToast({ title: (err && err.message) || '操作失败，请重试', icon: 'none' });
      });
  },

  /**
   * 登录成功后的「绑定微信」步骤。
   *
   * <p>为什么不能只在「先点微信一键登录被 422 拦下」时才绑：用户更自然的做法是**直接点手机号登录**
   * （或手动切到「手机号登录」tab）—— 那条路不会设置 pendingWechatBind，于是登录成功却从未绑定，
   * 之后他再点微信一键登录依旧 422，体感就是"登录了也没用"。
   *
   * <p>所以这里分两种触发：
   * <ul>
   *   <li>用户此前点过「微信一键登录」并被 422 拦下（pendingWechatBind=true）→ 意图明确，直接绑定；</li>
   *   <li>其余情况 → 弹一次询问（「去绑定」/「暂不」），把选择权交给用户。</li>
   * </ul>
   * 绑定失败**不影响登录成功**：409（已绑他人）/微信不可用，都只是少一种便捷登录方式。
   *
   * @return {Promise<boolean>} 是否绑定成功（用于决定提示文案）
   */
  maybeBindWechat() {
    if (!this.data.wechatReady) return Promise.resolve(false);
    const needAsk = !this.data.pendingWechatBind;
    const ask = () =>
      new Promise((resolve) => {
        wx.showModal({
          title: '绑定当前微信？',
          content: '绑定后下次可以直接用「微信一键登录」，不必再输手机号密码。',
          confirmText: '去绑定',
          cancelText: '暂不',
          success: (res) => resolve(!!res.confirm),
          fail: () => resolve(false),
        });
      });
    const go = needAsk ? ask() : Promise.resolve(true);
    return go.then((confirmed) => {
      if (!confirmed) return false;
      return this.bindCurrentWechat({ afterLogin: true }).then(
        () => true,
        () => false
      );
    });
  },

  afterLogin(title) {
    this._logged = true;
    this.setData({ submitting: false, loggedIn: true });
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

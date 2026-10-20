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
    /**
     * **页面内引导**（通用）：微信未绑定 / 手机号未注册 / 手机号已注册 都往这里写。
     *
     * action 取值：'bind'（绑定到当前账号）/ 'phone'（去手机号登录并绑定）
     *            / 'register'（去注册）/ 'login'（去登录）
     *
     * <p>为什么不只依赖 `wx.showModal`：实测出现过"诊断日志已打印 422、但弹窗没出现"——
     * 关键引导必须有**不依赖任何 wx API** 的载体，所以同时渲染成页面内卡片（一定看得见），
     * modal 保留作为第二重保险。
     */
    bindTip: null,
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
    // 不静默忽略：以前这里直接 return，一旦 submitting 被悬挂的 Promise 锁死，
    // 用户会连点多次都"毫无反应"，看起来就像按钮坏了。
    if (this.data.submitting) {
      wx.showToast({ title: '正在处理中，请稍候', icon: 'none' });
      return;
    }
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
    // 诊断线索：手机端没法看堆栈，把关键字段打出来，下次定位不用猜
    console.warn('[login] 微信登录失败', { status: status, code: err && err.code, message: err && err.message });

    // 一次性 code 过期/已被使用：让用户重试即可，不能误导成「未绑定」
    if (err && err.code === 'WECHAT_CODE_INVALID') {
      wx.showToast({ title: '微信授权已过期，请重试', icon: 'none' });
      return;
    }

    if (status === 422) {
      // 先落"页面内引导"（不依赖 wx API，必然可见），再补 modal 作第二重保险
      const bound = !!this.data.loggedIn;
      this.setData({
        bindTip: {
          title: '微信还没绑定账号',
          content: bound
            ? '当前账号已登录，把这个微信绑上去就能一键登录了。'
            : '用手机号登录或注册后，会自动把这个微信绑定到该账号；以后就能一键登录了。',
          action: bound ? 'bind' : 'phone',
          actionText: bound ? '绑定到当前账号' : '用手机号登录并绑定',
        },
      });
      // 情形③：确实已登录（服务端校验过）→ 一次点击直接绑定，不必再走登录
      if (this.data.loggedIn) {
        wx.showModal({
          title: '微信还没绑定账号',
          content: '当前账号已登录。要把这个微信绑定到当前账号吗？绑定后就能一键登录了。',
          confirmText: '绑定',
          cancelText: '换个账号',
          success: (res) => {
            if (!res.confirm) {
              // 给退路：换账号登录，登录后同样自动绑定
              this.setData({ mode: 'phone', pendingWechatBind: true, loggedIn: false });
              return;
            }
            this.setData({ submitting: true });
            this.bindCurrentWechat().then(
              () => {
                this.setData({ submitting: false });
                wx.showToast({ title: '已绑定微信，下次可一键登录', icon: 'success' });
              },
              (bindErr) => {
                this.setData({ submitting: false });
                this.notifyBindFailure(bindErr, null); // null = 不跳转（本页场景）
              }
            );
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
      wx.showModal({
        title: '微信登录暂不可用',
        content: '请改用手机号登录（微信侧配置就绪后即可恢复一键登录）。',
        showCancel: false,
      });
      return;
    }
    // 兜底：任何没被上面分类的失败（含单飞冲突的 {needLogin:true}、网络异常、超时）
    // 都必须给**可见**提示 —— 用 modal 而不是 toast，保证不会被后续操作顶掉。
    const hint = (err && err.message) || '登录失败，请重试';
    wx.showModal({
      title: '一键登录未完成',
      content: hint + '\n\n可以稍后重试，或直接用手机号登录。',
      confirmText: '用手机号登录',
      cancelText: '知道了',
      success: (res) => {
        if (res.confirm) this.setData({ mode: 'phone' });
      },
    });
  },

  /**
   * 把当前微信绑定到「已登录账号」：重新 wx.login 取新 code（旧 code 已用过）。
   *
   * @param {{afterLogin?: boolean}} opts afterLogin=true 表示处在「登录成功后的绑定」场景：
   *   此时令牌刚刚签发、不该失效，因此失败只提示、不把用户踢回表单。
   */
  /**
   * 页面内引导卡片上的按钮，按 action 分流：
   * bind（绑定当前微信）/ phone（去手机号登录并绑定）/ register（去注册）/ login（去登录）。
   *
   * 切换登录/注册页签时**保留用户已填的手机号**：他已经输入过一次，不该让他重打。
   */
  onBindTipAction() {
    const tip = this.data.bindTip;
    if (!tip) return;
    if (tip.action === 'register' || tip.action === 'login') {
      this.setData({ mode: tip.action === 'register' ? 'register' : 'phone', bindTip: null });
      return;
    }
    if (tip.action === 'phone') {
      this.setData({ mode: 'phone', pendingWechatBind: true, bindTip: null });
      return;
    }
    if (tip.action === 'bind') {
      this.setData({ submitting: true });
      this.bindCurrentWechat().then(
        () => {
          this.setData({ submitting: false, bindTip: null });
          wx.showToast({ title: '已绑定微信，下次可一键登录', icon: 'success' });
        },
        (err) => {
          this.setData({ submitting: false });
          this.notifyBindFailure(err, null);
        }
      );
      return;
    }
  },

  /** 关掉页面内引导 */
  dismissBindTip() {
    this.setData({ bindTip: null });
  },

  bindCurrentWechat(opts) {
    const afterLogin = !!(opts && opts.afterLogin);
    if (this.data.submitting && !afterLogin) return Promise.reject({ message: '正在处理中' });
    this.setData({ submitting: true });
    return api.getWxCode()
      .then((code) => api.wechatBind(code))
      .then(() => {
        // 成功不在这里 toast：调用方紧接着也会给提示，两个 toast 会互相覆盖
        this.setData({ submitting: false, pendingWechatBind: false });
      })
      .catch((err) => {
        this.setData({ submitting: false });
        // ★ 只有「身份未认证 / 登录已过期」才算登录态失效。
        //   微信侧的失败（code 过期、AppSecret 不匹配）由后端用 400/503 + 独立 code 表达，
        //   绝不能再当成登录态失效 —— 否则绑定一失败就把用户踢下线（2026-10-20 事故根因）。
        const tokenGone = !!(err && (err.needLogin || err.status === 401));
        if (tokenGone) {
          // 令牌失效：同类动作是「清会话 + 回到可执行的路径」，展示交给调用方统一处理
          api.clearSession();
          this.setData({ loggedIn: false });
          if (!afterLogin) this.setData({ mode: 'phone', pendingWechatBind: true });
          throw Object.assign({}, err, {
            friendly: afterLogin
              ? '登录态异常，本次没有绑定微信（你已登录成功，可稍后重试）。'
              : '登录已过期，请重新用手机号登录后再绑定微信。',
          });
        }
        // 其余失败（409 该微信已绑定其他账号 / 503 微信服务不可用 / 网络…）统一向上抛，
        // 由调用方用 modal 明确展示原因 —— 绝不静默。
        throw err;
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
      .then((res) => {
        if (res.bound) {
          this.afterLogin('已登录并绑定微信');
          return;
        }
        if (res.error) {
          // 绑定失败不影响登录成功，但**必须把原因告诉用户**（模态提示，不被 toast 覆盖）
          this.notifyBindFailure(res.error, doneTitle);
          return;
        }
        this.afterLogin(doneTitle);
      })
      .catch((err) => {
        this.setData({ submitting: false });
        this.handlePhoneAuthError(err, mode);
      });
  },

  /**
   * 手机号登录 / 注册失败：**给可执行的下一步**，而不是一句含糊提示。
   *
   * <p>后端对「手机号不存在」与「密码错误」统一返回 401 +「手机号或密码不正确」
   * ——这是防用户枚举的**刻意设计**，前端不能去猜是哪种，但可以把两种可能都摆出来，
   * 并把对应的入口直接放到提示里（未注册 → 去注册；已注册请检查密码）。
   */
  handlePhoneAuthError(err, mode) {
    const status = err && err.status;
    const message = (err && err.message) || '操作失败，请重试';

    if (status === 401) {
      const tip = {
        title: '手机号登录失败',
        content: message + '。如果是第一次使用，先去注册；已注册过请检查密码是否输错。',
        action: 'register',
        actionText: '去注册新账号',
      };
      this.setData({ bindTip: tip });
      wx.showModal({
        title: '手机号或密码不正确',
        content: '如果这个手机号还没注册过，点「去注册」用同一手机号创建账号即可。',
        confirmText: '去注册',
        cancelText: '重试',
        success: (res) => {
          if (res.confirm) this.setData({ mode: 'register' });
        },
      });
      return;
    }

    if (status === 409 && mode === 'register') {
      // 注册撞号：直接把人送到登录，并保留手机号
      this.setData({
        bindTip: {
          title: '该手机号已注册',
          content: '这个手机号已经注册过了，直接登录即可；忘记密码可在 Web 端「我的」重置。',
          action: 'login',
          actionText: '去登录',
        },
      });
      wx.showModal({
        title: '该手机号已注册',
        content: '直接登录即可，手机号已经帮你填好了。',
        confirmText: '去登录',
        cancelText: '知道了',
        success: (res) => {
          if (res.confirm) this.setData({ mode: 'phone' });
        },
      });
      return;
    }

    // 其余（网络、校验、限流…）：页面内提示 + toast 双通道
    this.setData({
      bindTip: { title: '操作未完成', content: message, action: null, actionText: '' },
    });
    wx.showToast({ title: message, icon: 'none' });
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
      if (!confirmed) return { bound: false, error: null };
      return this.bindCurrentWechat({ afterLogin: true }).then(
        () => ({ bound: true, error: null }),
        // **必须保留错误对象**：此前失败被归一成 false，409（该微信已绑定其他账号）
        // 就这样被丢掉了 —— 用户只看到「登录成功」，以为系统没检测到占用。
        (err) => ({ bound: false, error: err })
      );
    });
  },

  /**
   * 展示绑定失败的**真实原因**。
   *
   * <p>用 modal 而不是 toast：绑定之后紧接着就是「登录成功」的 toast，
   * 两个 toast 会互相覆盖，导致失败原因一闪而过（这正是线上看到「直接显示登录成功」的原因）。
   * modal 必须用户确认，不会被顶掉。
   *
   * @param {object} err 绑定失败的错误对象（含 status / message / friendly）
   * @param {string|null} doneTitle 关闭弹窗后要跳转的登录成功文案；null 表示不做跳转（页面内绑定场景）
   */
  notifyBindFailure(err, doneTitle) {
    const status = err && err.status;
    let content = (err && (err.friendly || err.message)) || '绑定失败，请稍后重试。';
    if (status === 409) {
      content = '这个微信已经绑定过其他账号，本次没有绑定。\n\n你的登录仍然成功，可以继续使用；如需换绑，请先在原账号解除绑定后再试。';
    } else if (err && err.code === 'WECHAT_CODE_INVALID') {
      // 一次性 code 只能用一次 / 已过期：属于「再点一次」就能解决的临时问题
      content = '微信授权已过期（一次性凭据只能用一次），本次没有绑定。\n\n请再点一次绑定；你的登录状态不受影响。';
    } else if (status === 503) {
      content = '微信登录服务暂时不可用（服务端配置未就绪），本次没有绑定。\n\n你的登录状态正常，可继续用手机号使用；服务恢复后再尝试绑定。';
    }
    wx.showModal({
      title: '微信未绑定成功',
      content: content,
      showCancel: false,
      confirmText: '知道了',
      complete: () => {
        if (doneTitle) this.afterLogin(doneTitle);
      },
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

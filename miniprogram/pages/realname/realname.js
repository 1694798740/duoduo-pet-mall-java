// 实名认证（第五十二轮热修新增）
//
// 为什么必须有这个页：发布闲置（POST /api/listings）后端硬性要求实名（C2C 是真实供给，
// 出问题要能追到人），此前小程序没有任何实名入口 —— 用户发布必然 403 且无处可去。
//
// 两条关键设计：
//  1. **实名成功后立刻静默换新令牌**（refreshSession）：realNameVerified 写在访问令牌
//     的 claim 里，旧令牌不会自动变；但后端 refresh 接口会重新读库签发新令牌
//     （AuthService.refresh → issueTokens(entity)），所以换完令牌即可直接发布，
//     不必强制重新登录。刷新失败（refreshToken 过期等）才退回「重新登录」。
//  2. **沙箱口径**：本阶段只做格式校验（2-20 位中文姓名 / 18 位身份证号），
//     不接公安实名服务；提交的身份证号后端只留脱敏掩码（id_card_masked）。
const api = require('../../utils/api');

// 与后端 RealNameRequest 的 @Pattern 完全一致（客户端先拦一道，省一次往返）
const NAME_RE = /^[\u4e00-\u9fa5·]{2,20}$/;
const ID_RE = /^\d{17}[\dXx]$/;

Page({
  data: {
    state: 'loading', // loading | needLogin | verified | unverified
    realName: '',
    idCard: '',
    verifiedName: '',
    verifiedIdMasked: '',

    submitting: false,
    error: '',
  },

  onShow() {
    // 用 onShow 而不是 onLoad：从登录页回来时也要重新拉状态
    this.loadStatus();
  },

  loadStatus() {
    if (!api.getToken()) {
      this.setData({ state: 'needLogin' });
      return;
    }
    api
      .ensureSession()
      .then((user) => {
        if (user && user.realNameVerified) {
          this.setData({
            state: 'verified',
            verifiedName: user.realName || '',
            verifiedIdMasked: user.idCardMasked || '',
          });
        } else {
          this.setData({ state: 'unverified' });
        }
      })
      .catch((e) => {
        if (e && e.needLogin) {
          this.setData({ state: 'needLogin' });
        } else {
          this.setData({ state: 'unverified', error: (e && e.message) || '状态获取失败，请重试' });
        }
      });
  },

  onInput(e) {
    this.setData({ [e.currentTarget.dataset.field]: e.detail.value, error: '' });
  },

  validate() {
    const name = this.data.realName.trim();
    if (!name) return '请填写真实姓名';
    if (!NAME_RE.test(name)) return '姓名需为 2-20 位中文';
    const id = this.data.idCard.trim();
    if (!id) return '请填写身份证号';
    if (!ID_RE.test(id)) return '身份证号需为 18 位（末位可为 X）';
    return null;
  },

  onSubmit() {
    if (this.data.submitting) return;
    const err = this.validate();
    if (err) {
      this.setData({ error: err });
      return;
    }

    this.setData({ submitting: true, error: '' });
    api.submitRealName(this.data.realName.trim(), this.data.idCard.trim())
      .then(() => {
        // 实名只改了数据库；旧访问令牌的 claim 还是「未实名」。
        // 立即用 refreshToken 换新令牌（后端会重新读库签发），换完即可直接去发布。
        return api.refreshSession().then(
          () => ({ refreshed: true }),
          () => ({ refreshed: false })
        );
      })
      .then((r) => {
        this.setData({ submitting: false });
        if (r.refreshed) {
          this.setData({ state: 'verified', verifiedName: this.data.realName.trim() });
          wx.showToast({ title: '实名完成，可以去发布了', icon: 'none', duration: 2000 });
          setTimeout(() => wx.navigateBack(), 1200);
        } else {
          // refreshToken 失效：实名已生效，但必须重新登录才能拿到带新标记的令牌
          wx.showModal({
            title: '实名已生效',
            content: '登录凭证已过期，请重新登录后继续操作',
            showCancel: false,
            confirmText: '去登录',
            success: () => {
              api.clearSession();
              this.setData({ state: 'needLogin' });
              api.openLoginPage();
            },
          });
        }
      })
      .catch((e) => {
        this.setData({
          submitting: false,
          error: (e && e.message) || '实名认证失败，请稍后重试',
        });
      });
  },

  goLogin() {
    api.openLoginPage();
  },
});

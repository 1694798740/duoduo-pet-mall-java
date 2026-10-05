// 领券中心 + 我的券包（A5：GET /api/coupons/templates、POST claim、GET /api/coupons/mine）
//
// A14 修复要点：
//  * 补上 wxml 引用的 goLogin（此前按钮点了没反应）；
//  * 不可领时展示服务端给的 claimableReason（「已领过」「未开始」都不该被说成「已领完」）；
//  * 时间统一走 shortTime 格式化（后端下发 ISO 串，直接展示是 2026-09-30T15:59:59Z 这种）；
//  * 领券链路补 catch（登录引导失败时不再静默无响应）。
const api = require('../../utils/api');

function decorate(coupon) {
  return Object.assign({}, coupon, {
    discountText: api.fen2yuan(coupon.discountAmount),
    thresholdText: api.fen2yuan(coupon.thresholdAmount),
    endText: api.shortTime(coupon.endAt || coupon.expireAt),
  });
}

Page({
  data: {
    tab: 'center', // center | mine
    templates: [],
    mine: [],
    loading: false,
    error: '',
    claimingId: '',
  },

  onLoad(query) {
    if (query && query.tab === 'mine') this.setData({ tab: 'mine' });
    this.fetch();
  },

  /**
   * onShow 补一次刷新。
   *
   * <p>此前只有 onLoad 会拉列表，而 onLoad 一个页面实例只跑一次 ——
   * 去登录、去商品详情再返回，看到的还是进页面那一刻的旧数据，
   * 领券中心便「长期只有固定的那几张」。
   *
   * <p>30 秒节流：券列表变化很慢，没必要每次切回来都打一次接口。
   * 时间戳在 {@link fetch} **发起时**就写下（不是等响应回来），因此首次进入
   * onLoad 发起的请求已经把时间戳落上，紧随其后的 onShow 会自然跳过 —— 不会打两次。
   */
  onShow() {
    if (!this._lastFetchAt || Date.now() - this._lastFetchAt > 30000) this.fetch();
  },

  /** 下拉刷新：与 onShow 共用同一个 fetch，避免两套取数逻辑各写一份 */
  onPullDownRefresh() {
    this.fetch().then(() => wx.stopPullDownRefresh());
  },

  switchTab(e) {
    const tab = e.currentTarget.dataset.tab;
    if (tab === this.data.tab) return;
    this.setData({ tab });
    this.fetch();
  },

  fetch() {
    // 节流时间戳在**发起时**写下：语义是「上一次发起请求的时间」。
    // 放在响应回来之后写会有两个问题 —— onLoad 与 onShow 相差不到一毫秒，
    // 首次进入就会并发打两次接口；接口失败时又变成每次 onShow 都重试，把失败放大成请求风暴。
    this._lastFetchAt = Date.now();
    this.setData({ loading: true, error: '' });
    const isCenter = this.data.tab === 'center';
    const p = isCenter ? api.coupon.templates() : api.ensureSession().then(() => api.coupon.mine());
    return p
      .then((data) => {
        const list = (data || []).map(decorate);
        if (isCenter) this.setData({ templates: list, loading: false });
        else this.setData({ mine: list, loading: false });
      })
      .catch((err) => {
        if (err && err.needLogin) {
          this.setData({ loading: false, error: '登录后查看我的券包' });
          return;
        }
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
      });
  },

  goLogin() {
    api.openLoginPage();
  },

  claim(e) {
    const tpl = e.currentTarget.dataset.tpl;
    if (!tpl || !tpl.claimable || this.data.claimingId) return;
    getApp()
      .ensureLogin()
      .then(() => {
        this.setData({ claimingId: tpl.id });
        return api.coupon.claim(tpl.id);
      })
      .then((result) => {
        this.setData({ claimingId: '' });
        wx.showToast({
          title: result && result.replay ? '已经领过啦' : '领取成功',
          icon: 'success',
        });
        this.fetch();
      })
      .catch((err) => {
        this.setData({ claimingId: '' });
        // needLogin：已引导登录页，无需重复提示
        if (err && err.needLogin) return;
        wx.showToast({ title: (err && err.message) || '领取失败', icon: 'none' });
      });
  },
});

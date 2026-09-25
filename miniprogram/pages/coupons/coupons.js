// 领券中心 + 我的券包（A5：GET /api/coupons/templates、POST claim、GET /api/coupons/mine）
const api = require('../../utils/api');

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

  switchTab(e) {
    this.setData({ tab: e.currentTarget.dataset.tab });
    this.fetch();
  },

  fetch() {
    this.setData({ loading: true, error: '' });
    const p =
      this.data.tab === 'center'
        ? api.coupon.templates()
        : api.ensureSession().then(() => api.coupon.mine());
    p.then((data) => {
      if (this.data.tab === 'center') {
        this.setData({ templates: data || [], loading: false });
      } else {
        this.setData({ mine: data || [], loading: false });
      }
    }).catch((err) => {
      if (err && err.needLogin) {
        this.setData({ loading: false, error: '登录后查看我的券包' });
        return;
      }
      this.setData({ loading: false, error: err.message || '加载失败' });
    });
  },

  claim(e) {
    const tpl = e.currentTarget.dataset.tpl;
    if (!tpl || !tpl.claimable) return;
    const app = getApp();
    app.ensureLogin().then(() => {
      this.setData({ claimingId: tpl.id });
      api.coupon
        .claim(tpl.id)
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
          wx.showToast({ title: err.message || '领取失败', icon: 'none' });
        });
    });
  },
});

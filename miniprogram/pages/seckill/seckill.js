// 限时秒杀（A14）：活动列表 + 一键秒杀（Redis 预扣，服务端限流/防超卖）
const api = require('../../utils/api');

const STATUS_LABEL = { ONGOING: '抢购中', UPCOMING: '即将开始', ENDED: '已结束' };

Page({
  data: {
    activities: [],
    loading: true,
    error: '',
    placingSkuId: '',
  },

  onShow() {
    this.fetch();
  },

  fetch() {
    this.setData({ loading: true, error: '' });
    api.seckill
      .activities()
      .then((list) => {
        const activities = (list || []).map((a) =>
          Object.assign({}, a, { statusLabel: STATUS_LABEL[a.status] || a.status })
        );
        this.setData({ activities, loading: false });
      })
      .catch((err) => this.setData({ loading: false, error: err.message || '加载失败' }));
  },

  buy(e) {
    const { activity, sku } = e.currentTarget.dataset;
    if (activity.status !== 'ONGOING') {
      wx.showToast({ title: '不在抢购时间内', icon: 'none' });
      return;
    }
    const app = getApp();
    app.ensureLogin().then(() => {
      this.setData({ placingSkuId: sku.skuId });
      api
        .address.default()
        .then((addr) => {
          if (!addr) {
            wx.showModal({
              title: '还没有收货地址',
              content: '请先在 Web 端「我的 → 地址簿」添加收货地址',
              showCancel: false,
            });
            return null;
          }
          return api.seckill.place(activity.id, sku.skuId, addr.id).then(() => {
            wx.showToast({ title: '秒杀成功！', icon: 'success' });
            wx.switchTab({ url: '/pages/orders/orders' });
            return null;
          });
        })
        .catch((err) => {
          wx.showToast({ title: (err && err.message) || '秒杀失败', icon: 'none' });
          this.fetch(); // 失败后刷新余量
        })
        .then(() => this.setData({ placingSkuId: '' }));
    });
  },
});

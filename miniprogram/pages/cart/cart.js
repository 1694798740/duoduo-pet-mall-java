// 购物车 + 下单（登录前置）。下单走 A8 语义：取默认地址的 addressId，服务端写快照。
const api = require('../../utils/api');

Page({
  data: {
    items: [],
    loading: false,
    error: '',
    ordering: false,
  },

  onShow() {
    // tab 页：每次进入都刷新（加购后回到本页要看到最新数据）
    this.fetchCart();
  },

  fetchCart() {
    api
      .ensureSession()
      .then(() => {
        this.setData({ loading: true, error: '' });
        return api.get('/api/cart');
      })
      .then((items) => {
        this.setData({ items: items || [], loading: false });
      })
      .catch((err) => {
        if (err && err.needLogin) {
          this.setData({ items: [], loading: false, error: '' });
          return;
        }
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
      });
  },

  removeItem(e) {
    const skuId = e.currentTarget.dataset.sku; // 字符串，直接拼 URL
    api.del('/api/cart/items/' + skuId).then(() => this.fetchCart());
  },

  placeOrder() {
    if (!this.data.items.length) return;
    this.setData({ ordering: true });
    // 下单 = 取默认地址的 addressId（没有则提示去 Web 端维护，小程序地址管理页后续轮次补）
    api
      .get('/api/addresses/default')
      .then((addr) => {
        if (!addr) {
          wx.showModal({
            title: '还没有收货地址',
            content: '请先到「我的 → 地址簿」添加收货地址',
            showCancel: false,
          });
          this.setData({ ordering: false });
          return null;
        }
        return api
          .post('/api/orders', {
            addressId: addr.id,
            idempotentKey: 'mp-' + Date.now() + '-' + Math.floor(Math.random() * 1e6),
          })
          .then(() => {
            wx.showToast({ title: '下单成功', icon: 'success' });
            wx.switchTab({ url: '/pages/orders/orders' });
            return null;
          });
      })
      .catch((err) => {
        wx.showToast({ title: (err && err.message) || '下单失败', icon: 'none' });
      })
      .then(() => {
        this.setData({ ordering: false });
      });
  },
});

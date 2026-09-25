// 购物车：列表 / 数量步进 / 删除 / 结算（A8 语义：默认地址 addressId，服务端写快照）
const api = require('../../utils/api');

Page({
  data: {
    items: [],
    loading: false,
    error: '',
    ordering: false,
    totalAmount: 0,
    totalCount: 0,
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
        return api.cart.view();
      })
      .then((view) => {
        const items = (view && view.items) || [];
        this.applyTotals(items, false);
      })
      .catch((err) => {
        if (err && err.needLogin) {
          this.setData({ items: [], loading: false, error: '' });
          return;
        }
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
      });
  },

  applyTotals(items, loading) {
    let totalAmount = 0;
    let totalCount = 0;
    (items || []).forEach((it) => {
      totalAmount += Number(it.price) * Number(it.quantity);
      totalCount += Number(it.quantity);
    });
    this.setData({ items: items || [], loading: !!loading, totalAmount, totalCount });
  },

  inc(e) {
    this.changeQty(e.currentTarget.dataset.sku, 1);
  },

  dec(e) {
    this.changeQty(e.currentTarget.dataset.sku, -1);
  },

  changeQty(skuId, delta) {
    const item = this.data.items.find((it) => String(it.skuId) === String(skuId));
    if (!item) return;
    const next = Number(item.quantity) + delta;
    if (next < 1) {
      wx.showModal({
        title: '移除商品',
        content: '数量已是最少，要删除该商品吗？',
        success: (res) => {
          if (res.confirm) {
            api.cart.remove(skuId).then(() => this.fetchCart()).catch(api.toastError);
          }
        },
      });
      return;
    }
    api.cart
      .updateQuantity(skuId, next)
      .then(() => this.fetchCart())
      .catch(api.toastError);
  },

  removeItem(e) {
    const skuId = e.currentTarget.dataset.sku; // 字符串，直接拼 URL
    api.cart.remove(skuId).then(() => this.fetchCart()).catch(api.toastError);
  },

  placeOrder() {
    if (!this.data.items.length) return;
    this.setData({ ordering: true });
    api
      .address.default()
      .then((addr) => {
        if (!addr) {
          wx.showModal({
            title: '还没有收货地址',
            content: '请先在 Web 端「我的 → 地址簿」添加收货地址',
            showCancel: false,
          });
          this.setData({ ordering: false });
          return null;
        }
        return api
          .order.place(addr.id)
          .then(() => {
            wx.showToast({ title: '下单成功', icon: 'success' });
            wx.switchTab({ url: '/pages/orders/orders' });
            return null;
          });
      })
      .catch((err) => {
        wx.showToast({ title: (err && err.message) || '下单失败', icon: 'none' });
      })
      .then(() => this.setData({ ordering: false }));
  },
});

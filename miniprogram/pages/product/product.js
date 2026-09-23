// 商品详情 + 加购（登录前置：ensureLogin）
const api = require('../../utils/api');

Page({
  data: {
    id: '',
    detail: null,
    loading: false,
    error: '',
    selectedSkuIndex: 0,
    adding: false,
  },

  onLoad(query) {
    this.setData({ id: query.id || '' });
    this.fetchDetail();
  },

  fetchDetail() {
    this.setData({ loading: true, error: '' });
    api
      .get('/api/products/' + this.data.id)
      .then((detail) => {
        this.setData({ detail, loading: false });
      })
      .catch((err) => {
        this.setData({ loading: false, error: err.message || '加载失败' });
      });
  },

  selectSku(e) {
    this.setData({ selectedSkuIndex: Number(e.currentTarget.dataset.index) });
  },

  addCart() {
    const app = getApp();
    app.ensureLogin().then(() => {
      const sku = this.data.detail.skus[this.data.selectedSkuIndex];
      if (!sku || sku.availableStock <= 0) {
        wx.showToast({ title: '该规格暂无库存', icon: 'none' });
        return;
      }
      this.setData({ adding: true });
      api
        .post('/api/cart/items', { skuId: sku.skuId, quantity: 1 })
        .then(() => {
          this.setData({ adding: false });
          wx.showToast({ title: '已加入购物车', icon: 'success' });
        })
        .catch((err) => {
          this.setData({ adding: false });
          wx.showToast({ title: err.message || '加购失败', icon: 'none' });
        });
    });
  },
});

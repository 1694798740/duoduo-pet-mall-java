// 首页 · 商品检索（公开接口，无需登录）
const api = require('../../utils/api');

Page({
  data: {
    keyword: '',
    items: [],
    loading: false,
    error: '',
    page: 1,
    total: 0,
  },

  onLoad() {
    this.fetchProducts();
  },

  onKeywordInput(e) {
    this.setData({ keyword: e.detail.value });
  },

  onSearch() {
    this.setData({ page: 1, items: [] });
    this.fetchProducts();
  },

  fetchProducts() {
    this.setData({ loading: true, error: '' });
    const kw = this.data.keyword ? '&keyword=' + encodeURIComponent(this.data.keyword) : '';
    api
      .get('/api/products?page=' + this.data.page + '&pageSize=10' + kw)
      .then((data) => {
        this.setData({
          items: this.data.page === 1 ? data.items : this.data.items.concat(data.items),
          total: data.total,
          loading: false,
        });
      })
      .catch((err) => {
        this.setData({ loading: false, error: err.message || '加载失败' });
      });
  },

  onReachBottom() {
    if (this.data.items.length < this.data.total) {
      this.setData({ page: this.data.page + 1 });
      this.fetchProducts();
    }
  },

  goDetail(e) {
    // spuId 是字符串（T-39 后全局字符串化），直接拼 URL，不做数值运算
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.id });
  },
});

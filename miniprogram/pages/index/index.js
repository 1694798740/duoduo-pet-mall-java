// 首页：搜索 + 快捷入口 + 秒杀预告 + 商品流（公开接口，无需登录）
const api = require('../../utils/api');

Page({
  data: {
    keyword: '',
    entries: [
      { icon: '⚡', label: '限时秒杀', url: '/pages/seckill/seckill', color: '#fff1ec' },
      { icon: '🎫', label: '领券中心', url: '/pages/coupons/coupons', color: '#eef1fe' },
      { icon: '🤖', label: 'AI 助手', url: '/pages/ai/ai', color: '#e8f7ee' },
    ],
    seckillSkus: [],
    items: [],
    loading: false,
    firstLoading: true,
    error: '',
    page: 1,
    total: 0,
  },

  onLoad() {
    this.fetchProducts();
    this.fetchSeckill();
  },

  onKeywordInput(e) {
    this.setData({ keyword: e.detail.value });
  },

  onSearch() {
    this.setData({ page: 1, items: [], firstLoading: true });
    this.fetchProducts();
  },

  fetchProducts() {
    this.setData({ loading: true, error: '' });
    api.product
      .list({ page: this.data.page, pageSize: 10, keyword: this.data.keyword })
      .then((data) => {
        this.setData({
          items: this.data.page === 1 ? data.items : this.data.items.concat(data.items),
          total: data.total,
          loading: false,
          firstLoading: false,
        });
      })
      .catch((err) => {
        this.setData({ loading: false, firstLoading: false, error: err.message || '加载失败' });
      });
  },

  // 秒杀预告：取进行中/即将开始的活动的头两个 SKU（静默失败，不打扰首页）
  fetchSeckill() {
    api.seckill
      .activities()
      .then((list) => {
        const skus = [];
        (list || []).forEach((act) => {
          (act.skus || []).slice(0, 2).forEach((s) => {
            skus.push(Object.assign({ activityId: act.id, activityStatus: act.status }, s));
          });
        });
        this.setData({ seckillSkus: skus.slice(0, 4) });
      })
      .catch(() => {});
  },

  onReachBottom() {
    if (this.data.items.length < this.data.total) {
      this.setData({ page: this.data.page + 1 });
      this.fetchProducts();
    }
  },

  goDetail(e) {
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.id });
  },

  goEntry(e) {
    const url = e.currentTarget.dataset.url;
    if (url.indexOf('/pages/index') === 0 || url.indexOf('/pages/cart') === 0) {
      wx.switchTab({ url });
    } else {
      wx.navigateTo({ url });
    }
  },
});

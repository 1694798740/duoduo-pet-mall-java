// 分享圈（第四十三轮 · 参考拼多多的「拼小圈」）
//
// 信息流展示用户的商品分享：每条 = 分享者（昵称/头像）+ 自撰评价 + 商品卡（快照）。
// 数据是公开接口（GET /api/shares/feed），未登录也能逛 —— 分享是口碑内容，
// 强制登录等于把传播链掐断（与商品评价同一公开读口径）。
const api = require('../../utils/api');
const images = require('../../utils/images');

const PAGE_SIZE = 10;

Page({
  data: {
    items: [],
    total: 0,
    page: 1,
    loading: false,
    refreshing: false,
    noMore: false,
    error: '',
  },

  onLoad() {
    this.fetch(true);
  },

  onPullDownRefresh() {
    this.fetch(true, () => wx.stopPullDownRefresh());
  },

  onReachBottom() {
    if (this.data.loading || this.data.noMore) return;
    this.fetch(false);
  },

  /** append=false 时重置回第一页 */
  fetch(reset, done) {
    const page = reset ? 1 : this.data.page + 1;
    this.setData({ loading: true, error: '' });
    api.share
      .feed(page, PAGE_SIZE)
      .then((data) => {
        const rows = ((data && data.items) || []).map((it) =>
          Object.assign({}, it, {
            image: images.productImage(it.spuId, it.productImage),
            priceText: api.fen2yuan(it.minPrice),
            timeText: api.shortTime(it.createdAt),
          })
        );
        this.setData({
          items: reset ? rows : this.data.items.concat(rows),
          total: (data && data.total) || 0,
          page: page,
          noMore: rows.length < PAGE_SIZE,
          loading: false,
          refreshing: false,
        });
        if (done) done();
      })
      .catch((err) => {
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
        if (done) done();
      });
  },

  retry() {
    this.fetch(true);
  },

  /** 点商品卡 → 商品详情 */
  goProduct(e) {
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.spu });
  },

  /** 点整条卡片 → 分享落地页（含评价语境） */
  goDetail(e) {
    wx.navigateTo({ url: '/pages/share-detail/share-detail?id=' + e.currentTarget.dataset.id });
  },
});

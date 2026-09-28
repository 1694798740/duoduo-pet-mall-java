// 闲置详情（第五十二轮）
//
// 数据源 GET /api/listings/{id}（免登）。服务端的可见性硬规则是
// 「只暴露 status=ON_SALE 且 auditStatus=APPROVED」，其余一律 404
// （不是 403 —— 避免用状态码探测「这条 id 存在但未过审」）。
// 因此本页对 404 的处理是「该闲置已下架或不存在」，不做任何存在性推断。
const api = require('../../utils/api');
const images = require('../../utils/images');

Page({
  data: {
    id: null,
    card: null,
    description: '',
    gallery: [],
    sameCategoryNew: [],
    loading: true,
    error: '',
  },

  onLoad(options) {
    const id = options && options.id;
    if (!id) {
      this.setData({ loading: false, error: '缺少闲置 id' });
      return;
    }
    this.setData({ id });
    this.load(id);
  },

  load(id) {
    this.setData({ loading: true, error: '' });
    api.listing
      .detail(id)
      .then((res) => {
        // 请求层成功时已返回包络里的 data（{card, description, images, sameCategoryNew}），
        // 勿再剥一层 res.data（R52 热修实录）
        const data = res || {};
        const card = data.card || {};
        const gallery = images.listingGallery(card.id, (data.images || [])[0]);
        this.setData({
          card: Object.assign({}, card, {
            priceText: api.fen2yuan(card.price),
            originalText: card.originalPrice ? api.fen2yuan(card.originalPrice) : '',
          }),
          description: data.description || '',
          gallery,
          sameCategoryNew: (data.sameCategoryNew || []).map((p) =>
            Object.assign({}, p, {
              image: images.productImage(p.id, p.mainImage || p.cover),
              priceText: api.fen2yuan(p.minPrice || p.price),
            })
          ),
          loading: false,
        });
      })
      .catch((err) => {
        this.setData({
          loading: false,
          error: (err && err.message) || '该闲置已下架或不存在',
        });
      });
  },

  onPreview(e) {
    const idx = e.currentTarget.dataset.index || 0;
    wx.previewImage({ current: this.data.gallery[idx], urls: this.data.gallery });
  },

  onTapNew(e) {
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.id });
  },

  onBackMarket() {
    wx.navigateBack();
  },
});

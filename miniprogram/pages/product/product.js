// 商品详情：规格选择 + 加购 + 用户评价 + AI 问问入口 + 分享
//
// 后端 GET /api/products/{id} 返回 { card, detail, images, skus, ... }，
// 这里在 fetchDetail 里做一次字段归一化，页面只消费归一化后的 shape。
// A14 修复要点：评价昵称用后端字段 nickname（此前用 userNickname，永远显示「匿名用户」）；
// 金额在 JS 侧转展示串；无规格/无库存时给明确反馈而不是静默失败。
const api = require('../../utils/api');

function specTextOf(sku) {
  if (sku.specText) return sku.specText;
  if (sku.spec && typeof sku.spec === 'object') {
    const text = Object.values(sku.spec).join(' / ');
    if (text) return text;
  }
  return sku.skuCode || '默认规格';
}

Page({
  data: {
    id: '',
    detail: null,
    loading: false,
    error: '',
    selectedSkuIndex: 0,
    adding: false,
    reviews: [],
    reviewSummary: null,
  },

  onLoad(query) {
    this.setData({ id: query.id || '' });
    this.fetchDetail();
  },

  fetchDetail() {
    this.setData({ loading: true, error: '', selectedSkuIndex: 0 });
    api.product
      .detail(this.data.id)
      .then((raw) => {
        const card = raw.card || {};
        const skus = (raw.skus || []).map((s) => ({
          id: s.id,
          specText: specTextOf(s),
          price: s.price,
          priceText: api.fen2yuan(s.price),
          marketPrice: s.marketPrice,
          image: s.image,
          stock: s.stock,
          sellable: s.sellable !== false,
        }));
        const detail = {
          title: card.title || '商品',
          subtitle: card.subtitle || '',
          mainImage: (skus[0] && skus[0].image) || card.mainImage || '',
          minPriceText: api.fen2yuan(card.minPrice || (skus.length ? skus[0].price : 0)),
          salesCount: card.salesCount || 0,
          rating: card.rating,
          ratingCount: card.ratingCount,
          inStock: card.inStock !== false,
          // 后端的 detail 是介绍长文本；images 是轮播图数组
          description: raw.detail || '',
          images: raw.images || [],
          skus,
        };
        this.setData({ detail, loading: false });
        // 评价静默加载（失败不打扰主流程）
        api.product
          .reviewSummary(this.data.id)
          .then((s) => this.setData({ reviewSummary: s }))
          .catch(() => {});
        api.product
          .reviews(this.data.id, 1, 5)
          .then((data) => this.setData({ reviews: (data && data.items) || [] }))
          .catch(() => {});
      })
      .catch((err) => {
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
      });
  },

  selectSku(e) {
    this.setData({ selectedSkuIndex: Number(e.currentTarget.dataset.index) });
  },

  addCart() {
    const skus = (this.data.detail && this.data.detail.skus) || [];
    if (!skus.length) {
      wx.showToast({ title: '该商品暂无可售规格', icon: 'none' });
      return;
    }
    if (this.data.adding) return;
    getApp()
      .ensureLogin()
      .then(() => {
        const sku = skus[this.data.selectedSkuIndex];
        if (!sku || !sku.sellable || sku.stock <= 0) {
          wx.showToast({ title: '该规格暂无库存', icon: 'none' });
          return;
        }
        this.setData({ adding: true });
        return api.cart
          .add(sku.id, 1)
          .then(() => {
            this.setData({ adding: false });
            wx.showToast({ title: '已加入购物车', icon: 'success' });
          })
          .catch((err) => {
            this.setData({ adding: false });
            wx.showToast({ title: (err && err.message) || '加购失败', icon: 'none' });
          });
      })
      .catch((err) => {
        if (err && err.needLogin) return; // 已引导登录页
        wx.showToast({ title: (err && err.message) || '请先登录', icon: 'none' });
      });
  },

  askAi() {
    const title = this.data.detail ? this.data.detail.title : '';
    wx.navigateTo({
      url: '/pages/ai/ai?prefill=' + encodeURIComponent('帮我看看「' + title + '」值不值得买？'),
    });
  },

  /** 分享商品卡（社交裂变入口） */
  onShareAppMessage() {
    const title = (this.data.detail && this.data.detail.title) || '多多宠物商店';
    return {
      title: title,
      path: '/pages/product/product?id=' + this.data.id,
    };
  },
});

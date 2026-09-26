// 分享落地页（第四十三轮）
//
// 两种入口，query 二选一：
//  * ?id=<分享单ID>   —— 来自分享圈信息流 / 带评价的分享卡片：展示分享者 + 评价 + 商品卡；
//  * ?spuId=<商品ID>  —— 来自好友转发的商品卡片（转发走微信原生、没有分享单）：
//                        展示「好友向你分享了这个商品」+ 商品卡。
//
// 旧版「查看分享页无响应」的根因：分享 path 指向商品页自身，点开卡片等于刷新原页。
// 现在落点固定为本页 —— 点开即见分享内容，再经「查看商品」进入详情。
const api = require('../../utils/api');
const images = require('../../utils/images');

function decorate(view) {
  // 快照里的商品图是后端相对路径（/img/...svg），统一映射随包位图
  const image = images.productImage(view.spuId, view.productImage);
  return Object.assign({}, view, {
    image: image,
    priceText: api.fen2yuan(view.minPrice),
    timeText: api.shortTime(view.createdAt),
  });
}

Page({
  data: {
    // friendShare=true 表示好友直转（无分享单，没有分享者信息）
    friendShare: false,
    view: null,
    loading: false,
    error: '',
  },

  onLoad(query) {
    if (query.id) {
      this.fetchShare(query.id);
    } else if (query.spuId) {
      this.fetchProduct(query.spuId);
    } else {
      this.setData({ error: '分享内容不存在或已失效' });
    }
  },

  /** 带分享单：取分享详情（公开接口） */
  fetchShare(id) {
    this.setData({ loading: true, error: '' });
    api.share
      .detail(id)
      .then((view) => this.setData({ loading: false, view: decorate(view) }))
      .catch((err) => this.setData({ loading: false, error: (err && err.message) || '加载失败' }));
  },

  /** 好友直转：分享单不存在，用公开商品详情拼一张商品卡 */
  fetchProduct(spuId) {
    this.setData({ loading: true, error: '' });
    api.product
      .detail(spuId)
      .then((raw) => {
        const card = raw.card || {};
        this.setData({
          loading: false,
          friendShare: true,
          view: decorate({
            spuId: Number(spuId),
            productTitle: card.title || '商品',
            productImage: card.mainImage || '',
            minPrice: card.minPrice || 0,
            content: '',
            nickname: '好友',
          }),
        });
      })
      .catch((err) => this.setData({ loading: false, error: (err && err.message) || '加载失败' }));
  },

  goProduct() {
    const view = this.data.view;
    if (!view || !view.spuId) return;
    wx.navigateTo({ url: '/pages/product/product?id=' + view.spuId });
  },

  goShareCircle() {
    // 第四十四轮：分享圈升为一级 tab 页，只能 switchTab
    wx.switchTab({ url: '/pages/share-circle/share-circle' });
  },

  /** 落地页也可再转发：带分享单转卡片（含评价语境），直转则继续转商品 */
  onShareAppMessage() {
    const view = this.data.view || {};
    const hasShareId = !this.data.friendShare && view.id;
    return {
      title: (view.productTitle || '多多宠物商店') + '｜好友觉得不错，来看看',
      path: hasShareId
        ? '/pages/share-detail/share-detail?id=' + view.id
        : '/pages/share-detail/share-detail?spuId=' + (view.spuId || ''),
      imageUrl: images.productImage(view.spuId, view.productImage) || '/images/share/cover.jpg',
    };
  },
});

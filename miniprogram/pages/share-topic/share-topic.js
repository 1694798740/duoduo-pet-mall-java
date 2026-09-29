// 话题聚合页 / 话题广场（第五十六轮）
//
// 两种入口共用本页：
//  * ?name=养宠日常  —— 从卡片上的 #话题 点进来：列出该话题下的分享；
//  * 不带 name        —— 话题广场：列出热门话题榜（发布页也会用到同一份数据）。
//
// 为什么合成一页：两者都是「话题」这一维度的浏览，拆两页要多维护一份列表样式，
// 而它们的空态、分页、跳转逻辑几乎相同。
const api = require('../../utils/api');
const images = require('../../utils/images');

const PAGE_SIZE = 10;

function decorate(it) {
  return Object.assign({}, it, {
    image: images.productImage(it.spuId, it.productImage),
    priceText: api.fen2yuan(it.minPrice),
    timeText: api.shortTime(it.createdAt),
    topics: it.topics || [],
    isRepost: !!it.parentId,
  });
}

Page({
  data: {
    /** 话题名；为空则本页是「话题广场」形态 */
    name: '',
    topicShareCount: 0,
    items: [],
    total: 0,
    page: 1,
    loading: false,
    noMore: false,
    error: '',
    /** 热门话题榜（广场形态） */
    hot: [],
    hotLoading: false,
  },

  onLoad(query) {
    if (query && query.name) {
      const name = decodeURIComponent(query.name);
      this.setData({ name: name });
      wx.setNavigationBarTitle && wx.setNavigationBarTitle({ title: '#' + name });
      this.fetch(true);
    } else {
      this.fetchHot();
    }
  },

  onPullDownRefresh() {
    if (this.data.name) {
      this.fetch(true, () => wx.stopPullDownRefresh());
    } else {
      this.fetchHot(() => wx.stopPullDownRefresh());
    }
  },

  onReachBottom() {
    if (!this.data.name) return;
    if (this.data.loading || this.data.noMore) return;
    this.fetch(false);
  },

  fetch(reset, done) {
    const page = reset ? 1 : this.data.page + 1;
    this.setData({ loading: true, error: '' });
    api.share
      .topicFeed(this.data.name, page, PAGE_SIZE)
      .then((data) => {
        const rows = ((data && data.items) || []).map(decorate);
        this.setData({
          items: reset ? rows : this.data.items.concat(rows),
          total: (data && data.total) || 0,
          page: page,
          noMore: rows.length < PAGE_SIZE,
          loading: false,
        });
        // 该话题的参与数从列表里拿不到（列表只给条目），单独取一次热门榜里对应项；
        // 取不到就不显示，不为此加一个后端端点
        if (reset) this.fillTopicCount();
        if (done) done();
      })
      .catch((err) => {
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
        if (done) done();
      });
  },

  /** 用热门榜里的计数回填头部（热门榜本来就按使用次数排，名额有限，取不到就留空） */
  fillTopicCount() {
    api.share
      .hotTopics(50)
      .then((list) => {
        const hit = (list || []).find((t) => t.name === this.data.name);
        if (hit) this.setData({ topicShareCount: hit.shareCount });
      })
      .catch(() => {});
  },

  fetchHot(done) {
    this.setData({ hotLoading: true });
    api.share
      .hotTopics(30)
      .then((list) => {
        this.setData({ hotLoading: false, hot: list || [] });
        if (done) done();
      })
      .catch((err) => {
        this.setData({ hotLoading: false, error: (err && err.message) || '加载失败' });
        if (done) done();
      });
  },

  retry() {
    if (this.data.name) {
      this.fetch(true);
    } else {
      this.fetchHot();
    }
  },

  goDetail(e) {
    wx.navigateTo({ url: '/pages/share-detail/share-detail?id=' + e.currentTarget.dataset.id });
  },

  goProduct(e) {
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.spu });
  },

  goTopic(e) {
    const name = e.currentTarget.dataset.name;
    if (!name) return;
    // 从广场进话题：redirectTo 而不是 navigateTo —— 否则「广场 → 话题 → 广场」会越堆越深
    wx.redirectTo({ url: '/pages/share-topic/share-topic?name=' + encodeURIComponent(name) });
  },

  goShareCircle() {
    wx.switchTab({ url: '/pages/share-circle/share-circle' });
  },
});

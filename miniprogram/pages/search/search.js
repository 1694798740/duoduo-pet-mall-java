// 搜索结果页（S6-D3 补缺）
//
// 小程序原先**没有**独立搜索结果页：首页的搜索框是「原地刷新首页列表」，
// 于是「搜索结果」和「首页推荐」混在同一个滚动列表里 ——
// ① 用户点返回会丢搜索态；② 分类页内完全搜不了（金刚区进「全部分类」后再也没有搜索入口）。
//
// 本页把这两件事收拢到一处：首页与分类页都 navigateTo 到本页并带 keyword / categoryId。
// 列表逻辑与分类页**同构**（同一套「请求序号 + 触底分页」），
// 这样两处的分页行为、过期响应丢弃语义完全一致，不会出现「分类页正常、搜索页重复加载」。
//
// ★ 刻意**不占 tabBar**：tabBar 已满 5 项（再多直接校验失败），所以用 navigateTo 打开。
// ★ 入参必须 encodeURIComponent：wx.navigateTo 的 url 是查询串，中文不编码会被截断。
const api = require('../../utils/api');
const images = require('../../utils/images');
const { getNavMetrics } = require('../../utils/nav.js');

const PAGE_SIZE = 10;

function decorate(list) {
  return (list || []).map((it) =>
    Object.assign({}, it, {
      priceText: api.fen2yuan(it.minPrice),
      imageSrc: images.productImage(it.id, it.mainImage),
    })
  );
}

Page({
  data: {
    keyword: '',
    categoryId: '',
    categoryName: '',
    items: [],
    loading: false,
    error: '',
    page: 1,
    total: 0,
    hasMore: false,
    navTop: 0,
    scrollTop: 0,
  },

  // 请求序号：只认最后一次请求的响应（连续改关键词时旧响应作废）
  _seq: 0,

  onLoad(options) {
    this.setData({
      navTop: getNavMetrics().navTotalHeight,
      keyword: decodeURIComponent(options.keyword || ''),
      categoryId: options.categoryId || '',
      categoryName: decodeURIComponent(options.name || ''),
    });
    // 深链进来时可能只有 categoryId 没有 name（例如从扫码或分享进入）→ 自己解析一次
    if (this.data.categoryId && !this.data.categoryName) this.resolveCategoryName();
    this.fetchProducts(true);
  },

  resolveCategoryName() {
    api.product
      .categories()
      .then((list) => {
        const all = [];
        (list || []).forEach((c) => {
          all.push(c);
          (c.children || []).forEach((s) => all.push(s));
        });
        const hit = all.find((c) => String(c.id) === String(this.data.categoryId));
        if (hit) this.setData({ categoryName: hit.name || '' });
      })
      .catch(() => {}); // 解析不到名字不影响搜索，只是标题回落到「搜索」
  },

  onKeywordInput(e) {
    this.setData({ keyword: e.detail.value });
  },

  onSearch() {
    this.fetchProducts(true);
  },

  /** 清空关键词：只清输入框，不动结果 —— 「清了但结果还在」比「清了结果也消失」更可预期 */
  onClearKeyword() {
    this.setData({ keyword: '' });
  },

  /**
   * @param {boolean} reset 是否从第一页重来
   */
  fetchProducts(reset) {
    const seq = ++this._seq;
    const page = reset ? 1 : this.data.page;
    this.setData(
      reset
        ? { loading: true, error: '', page: 1, scrollTop: 0 }
        : { loading: true, error: '' }
    );

    const params = { page, pageSize: PAGE_SIZE };
    if (this.data.keyword) params.keyword = this.data.keyword;
    if (this.data.categoryId) params.categoryId = this.data.categoryId;

    api.product
      .list(params)
      .then((data) => {
        if (seq !== this._seq) return; // 过期响应：已发起新搜索，丢弃
        const items = decorate(data && data.items);
        const merged = page === 1 ? items : this.data.items.concat(items);
        const total = Number((data && data.total) || merged.length);
        this.setData({
          items: merged,
          total,
          loading: false,
          hasMore: merged.length < total,
        });
      })
      .catch((err) => {
        if (seq !== this._seq) return;
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
      });
  },

  /** scroll-view 触底（页面级 onReachBottom 在 scroll-view 布局下不生效，与分类页同因） */
  onScrollToLower() {
    if (!this.data.hasMore || this.data.loading) return;
    this.setData({ page: this.data.page + 1 });
    this.fetchProducts(false);
  },

  goDetail(e) {
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.id });
  },
});

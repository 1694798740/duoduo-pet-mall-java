// 分类页：左侧分类树 + 右侧商品列表（/api/categories + /api/products?categoryId=）
//
// A14 修复要点：
//  * 商品列表在 scroll-view 内滚动，**页面级 onReachBottom 不会触发** →
//    改用 scroll-view 的 bindscrolltolower 驱动分页（此前每类只能看到前 10 件）；
//  * 字段按 ProductCard 规范：mainImage / id，金额 JS 侧转展示串；
//  * 底部高度扣掉 tabBar 与安全区（100vh 会把最后一行压在 tabBar 下面）。
const api = require('../../utils/api');

const PAGE_SIZE = 10;

function decorate(list) {
  return (list || []).map((it) =>
    Object.assign({}, it, { priceText: api.fen2yuan(it.minPrice) })
  );
}

Page({
  data: {
    categories: [],
    activeIndex: 0,
    activeChildren: [],
    activeChildId: '',
    items: [],
    loading: false,
    error: '',
    page: 1,
    total: 0,
    hasMore: false,
  },

  onLoad() {
    this.fetchCategories();
  },

  fetchCategories() {
    api.product
      .categories()
      .then((list) => {
        const cats = list || [];
        this.setData({ categories: cats, error: '' });
        if (cats.length > 0) this.applyCategory(cats[0], 0);
      })
      .catch((err) => this.setData({ error: (err && err.message) || '分类加载失败' }));
  },

  selectMain(e) {
    const idx = Number(e.currentTarget.dataset.index);
    const cat = this.data.categories[idx];
    if (!cat) return;
    this.applyCategory(cat, idx);
  },

  selectChild(e) {
    const id = e.currentTarget.dataset.id;
    const cat = this.data.categories[this.data.activeIndex];
    this.setData({ activeChildId: id });
    this.fetchProducts(cat ? cat.id : id, true, id);
  },

  /** 切换主分类：重置子分类选择与列表 */
  applyCategory(cat, idx) {
    this.setData({
      activeIndex: idx,
      activeChildren: cat.children || [],
      activeChildId: '',
      items: [],
      page: 1,
      total: 0,
      hasMore: false,
    });
    this.fetchProducts(cat.id, true, '');
  },

  /**
   * @param {string} categoryId 生效的分类 id（子分类优先）
   * @param {boolean} reset
   * @param {string} childId 当前选中的子分类（用于翻页时保持筛选一致）
   */
  fetchProducts(categoryId, reset, childId) {
    if (this.data.loading) return;
    const page = reset ? 1 : this.data.page;
    const cid = categoryId || '';
    this.setData(reset ? { loading: true, error: '', page: 1 } : { loading: true, error: '' });
    api.product
      .list({ page, pageSize: PAGE_SIZE, categoryId: cid })
      .then((data) => {
        const items = decorate(data && data.items);
        const merged = page === 1 ? items : this.data.items.concat(items);
        const total = Number((data && data.total) || merged.length);
        const patch = {
          items: merged,
          total,
          loading: false,
          hasMore: merged.length < total,
        };
        if (typeof childId === 'string') patch.activeChildId = childId;
        this.setData(patch);
      })
      .catch((err) =>
        this.setData({ loading: false, error: (err && err.message) || '加载失败' })
      );
  },

  /** scroll-view 触底（页面级 onReachBottom 在 scroll-view 布局下不生效） */
  onScrollToLower() {
    if (!this.data.hasMore || this.data.loading) return;
    const cat = this.data.categories[this.data.activeIndex];
    const cid = this.data.activeChildId || (cat && cat.id) || '';
    this.setData({ page: this.data.page + 1 });
    this.fetchProducts(cid, false, this.data.activeChildId);
  },

  goDetail(e) {
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.id });
  },
});

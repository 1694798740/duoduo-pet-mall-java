// 分类页：左侧分类树（带图标）+ 右侧商品列表
//
// A14 修复（样式不一致）根因与对策：
//  ① 左栏被压缩 → 分类名逐字竖排：`.cat-side` 缺 `flex: none`，右侧内容（固定宽卡片）
//     把它挤窄；同时 `.cat-item` 没有 `white-space: nowrap`。→ 左栏固定 200rpx 不收缩，
//     文案 ellipsis 单行。
//  ② 商品卡有时单列、有时双列：卡片写死 320rpx，与左栏宽度相加超出可用宽度，
//     flex 换行结果依赖内容宽度（分类不同、商品不同 → 表现不同）。
//     → 卡片宽度改**容器百分比**（48.6%），与内容无关，永远两列。
//  ③ 「点了没反应/停在旧分类」：`fetchProducts` 用 `if (loading) return` 直接丢弃新请求，
//     快速切换时新分类永远不发请求。→ 改为**请求序号**：新请求发出去，过期响应丢弃。
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
    // 自绘导航栏总高：整页是 fixed 布局，需要据此把内容顶到导航栏下方
    navTop: 0,
  },

  // 请求序号：只认最后一次请求的响应（快速切分类时旧响应作废）
  _seq: 0,

  onLoad() {
    this.setData({ navTop: getNavMetrics().navTotalHeight });
    this.fetchCategories();
  },

  fetchCategories() {
    api.product
      .categories()
      .then((list) => {
        const cats = (list || []).map((c) =>
          Object.assign({}, c, {
            iconSrc: images.categoryIcon(c.icon),
            children: (c.children || []).map((s) =>
              Object.assign({}, s, { iconSrc: images.categoryIcon(s.icon) })
            ),
          })
        );
        this.setData({ categories: cats, error: '' });
        if (cats.length > 0) this.applyCategory(cats[0], 0);
      })
      .catch((err) => this.setData({ error: (err && err.message) || '分类加载失败' }));
  },

  selectMain(e) {
    const idx = Number(e.currentTarget.dataset.index);
    const cat = this.data.categories[idx];
    if (!cat || idx === this.data.activeIndex) return;
    this.applyCategory(cat, idx);
  },

  selectChild(e) {
    const id = e.currentTarget.dataset.id;
    const cat = this.data.categories[this.data.activeIndex];
    this.setData({ activeChildId: id });
    this.fetchProducts(id || (cat && cat.id), true);
  },

  /** 切换主分类：重置子分类与列表 */
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
    this.fetchProducts(cat.id, true);
  },

  /**
   * @param {string} categoryId 生效分类 id（子分类优先，空则当前主分类）
   * @param {boolean} reset     是否从第一页重来
   */
  fetchProducts(categoryId, reset) {
    const target = categoryId || (this.data.categories[this.data.activeIndex] || {}).id;
    if (!target) return;
    const seq = ++this._seq;
    const page = reset ? 1 : this.data.page;
    this.setData(reset ? { loading: true, error: '', page: 1 } : { loading: true, error: '' });

    api.product
      .list({ page, pageSize: PAGE_SIZE, categoryId: target })
      .then((data) => {
        if (seq !== this._seq) return; // 过期响应：已切到别的分类，丢弃
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

  /** scroll-view 触底（页面级 onReachBottom 在 scroll-view 布局下不生效） */
  onScrollToLower() {
    if (!this.data.hasMore || this.data.loading) return;
    this.setData({ page: this.data.page + 1 });
    this.fetchProducts('', false);
  },

  goDetail(e) {
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.id });
  },
});

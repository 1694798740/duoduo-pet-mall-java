// 分类页：左侧分类树 + 右侧商品列表（/api/categories + /api/products?categoryId=）
const api = require('../../utils/api');

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
  },

  onLoad() {
    this.fetchCategories();
  },

  fetchCategories() {
    api.product
      .categories()
      .then((list) => {
        const cats = list || [];
        this.setData({ categories: cats });
        if (cats.length > 0) this.selectMain(0);
      })
      .catch((err) => this.setData({ error: err.message || '分类加载失败' }));
  },

  selectMain(e) {
    const idx = typeof e === 'number' ? e : Number(e.currentTarget.dataset.index);
    const cat = this.data.categories[idx];
    if (!cat) return;
    this.setData({
      activeIndex: idx,
      activeChildren: cat.children || [],
      activeChildId: '',
      page: 1,
      items: [],
    });
    this.fetchProducts(cat.id);
  },

  selectChild(e) {
    const id = e.currentTarget.dataset.id;
    const cat = this.data.categories[this.data.activeIndex];
    this.setData({ activeChildId: id, page: 1, items: [] });
    this.fetchProducts(id || (cat && cat.id));
  },

  fetchProducts(categoryId) {
    this.setData({ loading: true, error: '' });
    api.product
      .list({ page: this.data.page, pageSize: 10, categoryId: categoryId || '' })
      .then((data) => {
        this.setData({
          items: this.data.page === 1 ? data.items : this.data.items.concat(data.items),
          total: data.total,
          loading: false,
        });
      })
      .catch((err) => this.setData({ loading: false, error: err.message || '加载失败' }));
  },

  onReachBottom() {
    if (this.data.items.length < this.data.total) {
      this.setData({ page: this.data.page + 1 });
      const cat = this.data.categories[this.data.activeIndex];
      const cid = this.data.activeChildId || (cat && cat.id);
      this.fetchProducts(cid);
    }
  },

  goDetail(e) {
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.id });
  },
});

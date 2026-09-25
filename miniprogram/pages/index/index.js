// 首页：搜索 + 快捷入口 + 秒杀预告 + 商品流（公开接口，无需登录）
//
// A14 修复要点：
//  * 商品卡字段按后端 ProductCard 规范：mainImage / salesCount / id（此前用的
//    image / sales / spuId 都不存在，导致图片不显示、已售恒为 0、wx:key 失效）；
//  * 秒杀预告只展示**未结束**的活动，状态读 phase（后端按当前时间计算，没有 status 字段）；
//  * 金额在 JS 侧一次性转成展示串（fen2yuan），避免 WXML 里做除法；
//  * 分页有 hasMore 与 loading 双重护栏：不会越界请求，也不会重复请求同一页。
const api = require('../../utils/api');

const PAGE_SIZE = 10;
// tabBar 页面必须用 switchTab 跳转，其余用 navigateTo
const TAB_PAGES = ['/pages/index/index', '/pages/category/category', '/pages/cart/cart', '/pages/orders/orders', '/pages/profile/profile'];

function decorate(list) {
  return (list || []).map((it) =>
    Object.assign({}, it, { priceText: api.fen2yuan(it.minPrice) })
  );
}

Page({
  data: {
    keyword: '',
    entries: [
      { icon: '⚡', label: '限时秒杀', url: '/pages/seckill/seckill', color: '#fff1ec' },
      { icon: '🎫', label: '领券中心', url: '/pages/coupons/coupons', color: '#eef1fe' },
      { icon: '🤖', label: 'AI 助手', url: '/pages/ai/ai', color: '#e8f7ee' },
    ],
    skeletons: [1, 2, 3, 4],
    seckillSkus: [],
    items: [],
    loading: false,
    firstLoading: true,
    error: '',
    page: 1,
    total: 0,
    hasMore: false,
  },

  onLoad() {
    this.fetchProducts(true);
    this.fetchSeckill();
  },

  // tab 页返回时只轻量刷新秒杀预告（商品流较稳定，全量重拉会造成闪动与多余请求）
  onShow() {
    this.fetchSeckill();
  },

  onPullDownRefresh() {
    Promise.all([this.fetchProducts(true), this.fetchSeckill()])
      .catch(() => {})
      .then(() => wx.stopPullDownRefresh());
  },

  onKeywordInput(e) {
    this.setData({ keyword: e.detail.value });
  },

  onSearch() {
    this.fetchProducts(true);
  },

  /** @param {boolean} reset true=从第一页重来 */
  fetchProducts(reset) {
    if (this.data.loading) return Promise.resolve();
    const page = reset ? 1 : this.data.page;
    this.setData(reset ? { loading: true, error: '', page: 1, firstLoading: true } : { loading: true, error: '' });
    return api.product
      .list({ page, pageSize: PAGE_SIZE, keyword: this.data.keyword })
      .then((data) => {
        const items = decorate(data && data.items);
        const merged = page === 1 ? items : this.data.items.concat(items);
        const total = Number((data && data.total) || merged.length);
        this.setData({
          items: merged,
          total,
          loading: false,
          firstLoading: false,
          hasMore: merged.length < total,
        });
      })
      .catch((err) => {
        this.setData({
          loading: false,
          firstLoading: false,
          error: (err && err.message) || '加载失败',
        });
      });
  },

  // 秒杀预告：只取未结束活动的头两个 SKU（静默失败，不打扰首页）
  fetchSeckill() {
    return api.seckill
      .activities()
      .then((list) => {
        const skus = [];
        (list || []).forEach((act) => {
          if (act.phase === 'ENDED') return; // 已结束的活动不进预告条
          (act.skus || []).slice(0, 2).forEach((s) => {
            skus.push(
              Object.assign({}, s, {
                activityId: act.id,
                phaseLabel: act.phase === 'ONGOING' ? '抢购中' : '即将开始',
                priceText: api.fen2yuan(s.seckillPrice),
                originText: api.fen2yuan(s.originalPrice),
              })
            );
          });
        });
        this.setData({ seckillSkus: skus.slice(0, 4) });
      })
      .catch(() => {});
  },

  onReachBottom() {
    if (!this.data.hasMore || this.data.loading) return;
    this.setData({ page: this.data.page + 1 });
    this.fetchProducts(false);
  },

  goDetail(e) {
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.id });
  },

  goEntry(e) {
    const url = e.currentTarget.dataset.url;
    const isTab = TAB_PAGES.some((p) => url.indexOf(p) === 0);
    if (isTab) wx.switchTab({ url });
    else wx.navigateTo({ url });
  },
});

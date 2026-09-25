// 首页：搜索 + 快捷入口 + 秒杀预告 + 商品流（公开接口，无需登录）
//
// 图片说明（A14 修复轮）：后端 mainImage 是 `/img/products/1001.svg` 这类相对路径 SVG，
// 小程序 <image> 既解析不了相对路径、也不支持 SVG，因此统一经 utils/images 映射为本地位图。
const api = require('../../utils/api');
const images = require('../../utils/images');
const hl = require('../../utils/highlight');
const bannerConfig = require('../../config/banners');

const PAGE_SIZE = 10;
// tabBar 页面必须用 switchTab 跳转，其余用 navigateTo
const TAB_PAGES = ['/pages/index/index', '/pages/category/category', '/pages/cart/cart', '/pages/orders/orders', '/pages/profile/profile'];

function decorate(list, keyword) {
  return (list || []).map((it) =>
    Object.assign({}, it, {
      priceText: api.fen2yuan(it.minPrice),
      imageSrc: images.productImage(it.id, it.mainImage),
      // 关键词高亮：拆成片段交给 WXML 渲染（不用 rich-text，零注入面）
      titleSegs: hl.segments(it.title, keyword),
    })
  );
}

Page({
  data: {
    keyword: '',
    // 轮播图：内容与节奏全部来自 config/banners.js（改配置即换图，页面不动）
    banners: bannerConfig.list || [],
    bannerCurrent: 0,
    bannerAutoplay: bannerConfig.autoplay !== false,
    bannerInterval: bannerConfig.interval || 4000,
    bannerDuration: bannerConfig.duration || 400,
    entries: [
      { iconSrc: '/images/entry/seckill.png', label: '限时秒杀', url: '/pages/seckill/seckill' },
      { iconSrc: '/images/entry/coupon.png', label: '领券中心', url: '/pages/coupons/coupons' },
      { iconSrc: '/images/entry/ai.png', label: 'AI 助手', url: '/pages/ai/ai' },
      { iconSrc: '/images/entry/category.png', label: '全部分类', url: '/pages/category/category' },
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
    hasSeckill: false,
    // 零结果时的纠错建议（"你是不是想搜 X"）
    suggestion: '',
  },

  // 请求序号：关键词/刷新切换时丢弃过期响应，避免「列表短暂混排」
  _seq: 0,

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
    const seq = ++this._seq;
    const page = reset ? 1 : this.data.page;
    this.setData(reset ? { loading: true, error: '', page: 1, firstLoading: true } : { loading: true, error: '' });
    return api.product
      .list({ page, pageSize: PAGE_SIZE, keyword: this.data.keyword })
      .then((data) => {
        if (seq !== this._seq) return;
        const items = decorate(data && data.items, this.data.keyword);
        const merged = page === 1 ? items : this.data.items.concat(items);
        const total = Number((data && data.total) || merged.length);
        this.setData({
          items: merged,
          total,
          loading: false,
          firstLoading: false,
          hasMore: merged.length < total,
          suggestion: merged.length === 0 ? this.data.suggestion : '',
        });
        // 完全搜不到时问后端要一个纠错建议（给不出建议则保持空，不硬凑）
        if (page === 1 && merged.length === 0 && this.data.keyword) {
          this.fetchSuggestion(this.data.keyword);
        }
      })
      .catch((err) => {
        if (seq !== this._seq) return;
        this.setData({
          loading: false,
          firstLoading: false,
          error: (err && err.message) || '加载失败',
        });
      });
  },

  /** 纠错建议：完全搜不到时调一次；失败静默（绝不因为"建议"打扰主流程） */
  fetchSuggestion(keyword) {
    return api.product
      .suggest(keyword)
      .then((data) => {
        const s = (data && data.suggestion) || '';
        // 只在用户还没重新搜索（关键词未变）时才应用，避免慢响应覆盖新结果
        if (s && s !== keyword && this.data.keyword === keyword) {
          this.setData({ suggestion: s });
        }
      })
      .catch(() => {});
  },

  /** 采纳建议：用建议词重新搜索 */
  useSuggestion() {
    if (!this.data.suggestion) return;
    this.setData({ keyword: this.data.suggestion, suggestion: '' });
    this.fetchProducts(true);
  },

  // 秒杀预告：只取未结束活动的头两个 SKU（静默失败，不打扰首页）
  fetchSeckill() {
    return api.seckill
      .activities()
      .then((list) => {
        const skus = [];
        (list || []).forEach((act) => {
          if (act.phase === 'ENDED') return;
          (act.skus || []).slice(0, 2).forEach((s) => {
            skus.push(
              Object.assign({}, s, {
                activityId: act.id,
                phaseLabel: act.phase === 'ONGOING' ? '抢购中' : '即将开始',
                priceText: api.fen2yuan(s.seckillPrice),
                originText: api.fen2yuan(s.originalPrice),
                imageSrc: images.skuImage(s.skuId, null, s.image),
              })
            );
          });
        });
        const picked = skus.slice(0, 4);
        this.setData({ seckillSkus: picked, hasSeckill: picked.length > 0 });
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

  // ── 首页轮播 ─────────────────────────────────────────────
  /** 手势滑动 / 自动播放都会触发：把真实下标同步回来，箭头与指示器才不会错位 */
  onBannerChange(e) {
    this.setData({ bannerCurrent: e.detail.current });
  },

  bannerPrev() {
    this.stepBanner(-1);
  },

  bannerNext() {
    this.stepBanner(1);
  },

  /** 左右箭头：环形步进（与 swiper 的 circular 行为保持一致） */
  stepBanner(delta) {
    const total = this.data.banners.length;
    if (total <= 1) return;
    const next = (this.data.bannerCurrent + delta + total) % total;
    this.setData({ bannerCurrent: next });
  },

  onBannerTap(e) {
    const item = this.data.banners[Number(e.currentTarget.dataset.index)];
    if (!item || !item.url) return; // 未配置 url：纯展示图，点了不跳
    const isTab = TAB_PAGES.some((p) => item.url.indexOf(p) === 0);
    if (isTab) wx.switchTab({ url: item.url });
    else wx.navigateTo({ url: item.url });
  },

  goEntry(e) {
    const url = e.currentTarget.dataset.url;
    const isTab = TAB_PAGES.some((p) => url.indexOf(p) === 0);
    if (isTab) wx.switchTab({ url });
    else wx.navigateTo({ url });
  },

  onShareAppMessage() {
    return { title: '多多宠物商店 · 好物推荐', path: '/pages/index/index' };
  },
});

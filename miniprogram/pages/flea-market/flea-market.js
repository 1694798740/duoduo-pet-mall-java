// 跳蚤市场（第五十二轮 · 补全 C2C 发布链路的浏览侧）
//
// 为什么不是 tab 页：tabBar 已满 5 项（首页/分享圈/通知/购物车/我的），
// 微信小程序 tabBar 上限硬性为 5，第 6 个会直接校验失败、模拟器拒绝启动。
// 因此跳蚤市场是**普通页**：首页有显眼入口，从我的页也能「我的闲置」进来。
//
// 非 tab 页必须保留原生导航栏（R41 教训的推论）：自绘导航栏没有返回键，
// 推进式打开的页面会被困住。这里用原生导航 + 页面内筛选条。
//
// 数据源 GET /api/listings/search（免登）：闲置浏览与商品浏览同性质，
// 未登录先逛才谈得上注册。发布才是需登录的动作（POST /api/listings）。
const api = require('../../utils/api');
const images = require('../../utils/images');

const PAGE_SIZE = 10;

const CONDITIONS = [
  { code: '', label: '全部成色' },
  { code: 'NEW', label: '全新' },
  { code: 'LIKE_NEW', label: '九成新' },
  { code: 'GOOD', label: '良好' },
  { code: 'FAIR', label: '可用' },
];

const SORTS = [
  { code: 'default', label: '综合' },
  { code: 'newest', label: '最新' },
  { code: 'price_asc', label: '价格↑' },
  { code: 'price_desc', label: '价格↓' },
];

/** 列表项装饰：图片映射 + 金额 + 成色文案 */
function decorate(it) {
  return Object.assign({}, it, {
    image: images.listingImage(it.id, it.cover),
    priceText: api.fen2yuan(it.price),
    originalText: it.originalPrice ? api.fen2yuan(it.originalPrice) : '',
    conditionText: conditionLabel(it.conditionLevel),
  });
}

function conditionLabel(code) {
  const hit = CONDITIONS.find((c) => c.code === code);
  return hit && hit.code ? hit.label : '成色未标注';
}

Page({
  data: {
    items: [],
    total: 0,
    page: 1,
    loading: false,
    refreshing: false,
    noMore: false,
    error: '',

    keyword: '',
    city: '',
    conditionLevel: '',
    sort: 'default',

    conditions: CONDITIONS,
    sorts: SORTS,
    showFilter: false,
  },

  onLoad() {
    this.load(true);
  },

  onShow() {
    // 从发布页返回时刷新：刚发布的条目（虽仍在待审核、公开列表看不到）
    // 在「我的闲置」里可见；这里刷新是为了让下架/售出等状态变化及时反映
    if (this._needRefresh) {
      this._needRefresh = false;
      this.load(true);
    }
  },

  onPullDownRefresh() {
    this.setData({ refreshing: true });
    this.load(true, () => {
      this.setData({ refreshing: false });
      wx.stopPullDownRefresh();
    });
  },

  onReachBottom() {
    if (!this.data.noMore && !this.data.loading) {
      this.load(false);
    }
  },

  /** 拉列表。reset=true 表示回到第一页（下拉刷新 / 改筛选项） */
  load(reset, done) {
    if (this.data.loading) {
      if (done) done();
      return;
    }
    const page = reset ? 1 : this.data.page + 1;
    const params = {
      page,
      pageSize: PAGE_SIZE,
      sort: this.data.sort,
    };
    if (this.data.keyword) params.keyword = this.data.keyword;
    if (this.data.city) params.city = this.data.city;
    if (this.data.conditionLevel) params.conditionLevel = this.data.conditionLevel;

    this.setData({ loading: true, error: reset ? '' : this.data.error });
    api.listing
      .list(params)
      .then((res) => {
        // 请求层成功时已返回包络里的 data（分页结果本身），勿再剥一层（R52 热修实录）
        const data = res || {};
        const rows = (data.items || data.records || []).map(decorate);
        const items = reset ? rows : this.data.items.concat(rows);
        this.setData({
          items,
          total: data.total || items.length,
          page,
          // 服务端已无可翻页内容：返回条数不足一页即到底
          noMore: rows.length < PAGE_SIZE,
          loading: false,
        });
        if (done) done();
      })
      .catch((err) => {
        this.setData({
          loading: false,
          error: (err && err.message) || '加载失败，请下拉重试',
        });
        if (done) done();
      });
  },

  onKeywordInput(e) {
    this.setData({ keyword: e.detail.value });
  },

  onCityInput(e) {
    this.setData({ city: e.detail.value });
  },

  onSearch() {
    this.load(true);
  },

  onClearFilters() {
    this.setData({ keyword: '', city: '', conditionLevel: '', sort: 'default' });
    this.load(true);
  },

  toggleFilter() {
    this.setData({ showFilter: !this.data.showFilter });
  },

  onPickCondition(e) {
    this.setData({ conditionLevel: e.currentTarget.dataset.code });
    this.load(true);
  },

  onPickSort(e) {
    this.setData({ sort: e.currentTarget.dataset.code });
    this.load(true);
  },

  onTapItem(e) {
    // 详情页每次访问都会累加浏览量（第五十三轮热修二）→ 标记返回后重拉，
    // 否则用户看完回来看到的还是旧计数（0 人看过），会以为统计没生效
    this._needRefresh = true;
    wx.navigateTo({ url: '/pages/flea-detail/flea-detail?id=' + e.currentTarget.dataset.id });
  },

  /** 发布入口：未登录时先引导登录（发布是需登录动作，提前拦比填完表单再报错友好） */
  onTapPublish() {
    if (!api.getToken()) {
      api.openLoginPage();
      return;
    }
    wx.navigateTo({ url: '/pages/flea-publish/flea-publish' });
  },

  /** 我的闲置 */
  onTapMine() {
    if (!api.getToken()) {
      api.openLoginPage();
      return;
    }
    wx.navigateTo({ url: '/pages/flea-mine/flea-mine' });
  },
});

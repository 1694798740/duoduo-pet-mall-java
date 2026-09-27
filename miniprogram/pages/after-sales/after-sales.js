// 我的售后列表（第三十七轮新增）：「我的」页入口进这里
//
// 后端契约（履约域 A3）：GET /api/aftersales/mine?status=&page=&pageSize= → PageResult<AfterSaleView>
//   · 售后单 id 是 18 位雪花值，前端只展示不拼数值运算；
//   · 点卡片跳订单详情 —— 售后进度、卖家回复、物流都在订单详情页里看（不重复造详情页）；
//   · 申请售后入口仍在订单详情页（按订单状态出现），本页不做申请入口，避免脱离订单上下文。
const api = require('../../utils/api');

const PAGE_SIZE = 10;

Page({
  data: {
    items: [],
    loading: false,
    error: '',
    page: 1,
    hasMore: false,
  },

  onShow() {
    this.fetch(true);
  },

  onPullDownRefresh() {
    this.fetch(true).then(() => wx.stopPullDownRefresh());
  },

  onReachBottom() {
    if (!this.data.hasMore || this.data.loading) return;
    this.setData({ page: this.data.page + 1 });
    this.fetch(false);
  },

  fetch(reset) {
    if (this.data.loading) return Promise.resolve();
    const page = reset ? 1 : this.data.page;
    this.setData(reset ? { loading: true, error: '', page: 1, items: [] } : { loading: true, error: '' });
    return api
      .ensureSession()
      .then(() => api.aftersale.mine(page, PAGE_SIZE))
      .then((data) => {
        const items = ((data && data.items) || []).map((a) =>
          Object.assign({}, a, {
            statusText: a.statusLabel || a.status,
            typeText: a.typeLabel || a.type,
            timeText: api.shortTime(a.updatedAt || a.createdAt),
          })
        );
        const merged = page === 1 ? items : this.data.items.concat(items);
        const total = Number((data && data.total) || merged.length);
        this.setData({ items: merged, loading: false, hasMore: merged.length < total });
      })
      .catch((err) => {
        if (err && err.needLogin) {
          this.setData({ items: [], loading: false, error: '登录后查看售后' });
          return;
        }
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
      });
  },

  /** 点卡片进对应订单详情（售后进度在详情页展示） */
  goOrder(e) {
    const orderNo = e.currentTarget.dataset.no;
    if (!orderNo) return;
    wx.navigateTo({ url: '/pages/order-detail/order-detail?orderNo=' + orderNo });
  },

  /** 想申请售后？入口在订单详情（按订单状态出现），列表页给个指路 */
  goOrders() {
    // 第四十五轮：orders 回归普通页面，switchTab 对非 tab 页无效
    wx.navigateTo({ url: '/pages/orders/orders' });
  },

  goLogin() {
    api.openLoginPage();
  },
});

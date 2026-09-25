// 订单列表：状态筛选 + 沙箱支付（预下单 + 轮询，与 Web 端同一套后端契约）+ 取消/确认收货
//
// A14 修复要点：
//  * 支付请求补 idempotentKey（后端 @NotBlank，缺了必 400）；
//  * 支付响应字段按 PayResult 规范：收银台地址是 codeUrl（不是 payUrl）、
//    支付状态是 payStatus（不是 status）—— 此前轮询永远判定不了成功；
//  * 轮询句柄落到实例上，onHide/onUnload 清理，避免离开页面后定时器泄漏与叠加；
//  * 状态文案直接用服务端 statusLabel（本地映射只作兜底，缺 REFUNDED 等状态时不再显示英文）；
//  * 加入分页：列表是页面级滚动，onReachBottom 可正常触发。
const api = require('../../utils/api');
const pay = require('../../utils/pay');

const FALLBACK_LABEL = {
  PENDING_PAYMENT: '待付款',
  PAID: '待发货',
  SHIPPED: '待收货',
  COMPLETED: '已完成',
  CLOSED: '已关闭',
  REFUNDED: '已退款',
};

const TABS = [
  { key: 'ALL', label: '全部' },
  { key: 'PENDING_PAYMENT', label: '待付款' },
  { key: 'PAID', label: '待发货' },
  { key: 'SHIPPED', label: '待收货' },
  { key: 'COMPLETED', label: '已完成' },
];

const PAGE_SIZE = 10;

Page({
  data: {
    tabs: TABS,
    activeTab: 'ALL',
    orders: [],
    loading: false,
    error: '',
    payingOrderNo: '',
    page: 1,
    hasMore: false,
  },

  onShow() {
    this.fetchOrders(true);
  },

  onHide() {
    pay.stopPolling(this);
  },

  onUnload() {
    pay.stopPolling(this);
  },

  switchTab(e) {
    const key = e.currentTarget.dataset.key;
    if (key === this.data.activeTab) return;
    this.setData({ activeTab: key });
    this.fetchOrders(true);
  },

  fetchOrders(reset) {
    if (this.data.loading) return Promise.resolve();
    const page = reset ? 1 : this.data.page;
    // 重新拉第一页时先清空列表，避免「旧筛选的结果 + 新筛选的结果」短暂混排
    this.setData(reset ? { loading: true, error: '', page: 1, orders: [] } : { loading: true, error: '' });
    return api
      .ensureSession()
      .then(() => api.order.list(page, this.data.activeTab, PAGE_SIZE))
      .then((data) => {
        const items = ((data && data.items) || []).map((o) => this.decorate(o));
        const merged = page === 1 ? items : this.data.orders.concat(items);
        const total = Number((data && data.total) || merged.length);
        this.setData({
          orders: merged,
          loading: false,
          hasMore: merged.length < total,
        });
      })
      .catch((err) => {
        if (err && err.needLogin) {
          this.setData({ orders: [], loading: false, error: '' });
          return;
        }
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
      });
  },

  /** 补齐展示需要的派生字段：状态中文（优先服务端）、件数、金额串 */
  decorate(order) {
    const items = order.items || [];
    const quantity = items.reduce((sum, it) => sum + (Number(it.quantity) || 0), 0);
    return Object.assign({}, order, {
      statusText: order.statusLabel || FALLBACK_LABEL[order.status] || order.status,
      quantityText: quantity || items.length,
      payAmountText: api.fen2yuan(order.payAmount),
    });
  },

  onReachBottom() {
    if (!this.data.hasMore || this.data.loading) return;
    this.setData({ page: this.data.page + 1 });
    this.fetchOrders(false);
  },

  /** 支付：发起 + 沙箱收银台 + 轮询（实现抽到 utils/pay.js，与订单详情页共用） */
  pay(e) {
    const orderNo = e.currentTarget.dataset.no; // 业务键 orderNo，不暴露内部 id
    if (this.data.payingOrderNo) return;
    this.setData({ payingOrderNo: orderNo });
    pay
      .checkout(orderNo, this, {
        onSuccess: () => {
          wx.showToast({ title: '支付成功', icon: 'success' });
          this.fetchOrders(true);
        },
        onTimeout: () => wx.showToast({ title: '还未检测到支付成功', icon: 'none' }),
      })
      .then((state) => {
        this.setData({ payingOrderNo: '' });
        if (state === 'SUCCESS') this.fetchOrders(true);
      })
      .catch((err) => {
        this.setData({ payingOrderNo: '' });
        wx.showToast({ title: (err && err.message) || '发起支付失败', icon: 'none' });
      });
  },

  /** 点卡片进订单详情 */
  goDetail(e) {
    const orderNo = e.currentTarget.dataset.no;
    wx.navigateTo({ url: '/pages/order-detail/order-detail?orderNo=' + orderNo });
  },

  cancelOrder(e) {
    const orderNo = e.currentTarget.dataset.no;
    wx.showModal({
      title: '取消订单',
      content: '确定取消这笔待付款订单吗？取消后库存会释放。',
      confirmColor: '#e5484d',
      success: (res) => {
        if (!res.confirm) return;
        api.order
          .cancel(orderNo, '小程序用户取消')
          .then(() => {
            wx.showToast({ title: '已取消', icon: 'success' });
            this.fetchOrders(true);
          })
          .catch(api.toastError);
      },
    });
  },

  confirmOrder(e) {
    const orderNo = e.currentTarget.dataset.no;
    wx.showModal({
      title: '确认收货',
      content: '确认已收到商品？确认后货款将结算给卖家。',
      success: (res) => {
        if (!res.confirm) return;
        api.order
          .confirm(orderNo)
          .then(() => {
            wx.showToast({ title: '已确认收货', icon: 'success' });
            this.fetchOrders(true);
          })
          .catch(api.toastError);
      },
    });
  },
});

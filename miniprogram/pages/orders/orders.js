// 订单列表：状态筛选 + 沙箱支付（预下单 + 轮询，与 Web 端同一套后端契约）
const api = require('../../utils/api');

const STATUS_LABEL = {
  PENDING_PAYMENT: '待付款',
  PAID: '待发货',
  SHIPPED: '待收货',
  COMPLETED: '已完成',
  CLOSED: '已关闭',
};

const TABS = [
  { key: 'ALL', label: '全部' },
  { key: 'PENDING_PAYMENT', label: '待付款' },
  { key: 'PAID', label: '待发货' },
  { key: 'SHIPPED', label: '待收货' },
  { key: 'COMPLETED', label: '已完成' },
];

Page({
  data: {
    tabs: TABS,
    activeTab: 'ALL',
    orders: [],
    loading: false,
    error: '',
    payingOrderNo: '',
  },

  onShow() {
    this.fetchOrders();
  },

  switchTab(e) {
    this.setData({ activeTab: e.currentTarget.dataset.key, orders: [] });
    this.fetchOrders();
  },

  fetchOrders() {
    api
      .ensureSession()
      .then(() => {
        this.setData({ loading: true, error: '' });
        return api.order.list(1, this.data.activeTab);
      })
      .then((data) => {
        const orders = ((data && data.items) || []).map((o) =>
          Object.assign({}, o, { statusLabel: STATUS_LABEL[o.status] || o.status })
        );
        this.setData({ orders, loading: false });
      })
      .catch((err) => {
        if (err && err.needLogin) {
          this.setData({ orders: [], loading: false, error: '' });
          return;
        }
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
      });
  },

  pay(e) {
    const orderNo = e.currentTarget.dataset.no; // 业务键 orderNo，不暴露内部 id
    this.setData({ payingOrderNo: orderNo });
    api
      .order.pay(orderNo)
      .then((payResult) => {
        this.setData({ payingOrderNo: '' });
        // 沙箱支付：预下单返回 payNo 与收银台地址；复制链接到浏览器完成支付，状态靠轮询。
        wx.showModal({
          title: '沙箱支付',
          content:
            '收银台链接（可复制）：' + (payResult.payUrl || '/sandbox/pay/' + payResult.payNo),
          confirmText: '复制',
          cancelText: '关闭',
          success: (res) => {
            if (res.confirm) {
              wx.setClipboardData({ data: payResult.payUrl || '' });
            }
            this.pollPayment(payResult.payNo);
          },
        });
      })
      .catch((err) => {
        this.setData({ payingOrderNo: '' });
        wx.showToast({ title: (err && err.message) || '发起支付失败', icon: 'none' });
      });
  },

  // 轮询支付状态（3 秒一次，最多 20 次 = 1 分钟；与 Web 端轮询口径一致）
  pollPayment(payNo) {
    let times = 0;
    const timer = setInterval(() => {
      times += 1;
      api.order
        .payStatus(payNo)
        .then((p) => {
          if (p && p.status === 'SUCCESS') {
            clearInterval(timer);
            wx.showToast({ title: '支付成功', icon: 'success' });
            this.fetchOrders();
          } else if (times >= 20) {
            clearInterval(timer);
          }
        })
        .catch(() => {
          if (times >= 20) clearInterval(timer);
        });
    }, 3000);
  },

  confirmOrder(e) {
    const orderNo = e.currentTarget.dataset.no;
    api.order
      .confirm(orderNo)
      .then(() => {
        wx.showToast({ title: '已确认收货', icon: 'success' });
        this.fetchOrders();
      })
      .catch(api.toastError);
  },
});

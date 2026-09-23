// 订单列表 + 沙箱支付（预下单 + 轮询，与 Web 端同一套后端契约）
const api = require('../../utils/api');

Page({
  data: {
    orders: [],
    loading: false,
    error: '',
    payingOrderNo: '',
  },

  onShow() {
    this.fetchOrders();
  },

  fetchOrders() {
    api
      .ensureSession()
      .then(() => {
        this.setData({ loading: true, error: '' });
        return api.get('/api/orders?page=1&pageSize=20');
      })
      .then((data) => {
        this.setData({ orders: (data && data.items) || [], loading: false });
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
    const order = this.data.orders.find((o) => o.orderNo === orderNo);
    if (!order) return;
    this.setData({ payingOrderNo: orderNo });
    api
      .post('/api/orders/' + orderNo + '/pay', { channel: 'sandbox' })
      .then((payResult) => {
        // 沙箱支付：预下单返回 payNo 与收银台地址。小程序内不能直接打开外部 H2 页面，
        // 测试号阶段把收银台链接展示出来（可复制到浏览器完成支付），支付状态靠轮询。
        this.setData({ payingOrderNo: '' });
        wx.showModal({
          title: '沙箱支付',
          content: '收银台链接（可复制）：' + (payResult.payUrl || '/sandbox/pay/' + payResult.payNo),
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
      api
        .get('/api/payments/' + payNo, { silent: true })
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
    api
      .post('/api/orders/' + orderNo + '/confirm')
      .then(() => {
        wx.showToast({ title: '已确认收货', icon: 'success' });
        this.fetchOrders();
      })
      .catch((err) => {
        wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' });
      });
  },
});

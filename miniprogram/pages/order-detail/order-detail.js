// 订单详情：商品明细 / 金额 / 收货信息 / 物流轨迹 / 按状态的可用操作
//
// 后端契约：GET /api/orders/{orderNo}（OrderView，含 items）
//           GET /api/logistics/orders/{orderNo}/tracks（TrackListView，履约域）
// 支付/取消/确认收货复用既有接口，支付轮询抽到 utils/pay.js（与订单列表页共用）。
const api = require('../../utils/api');
const images = require('../../utils/images');
const pay = require('../../utils/pay');

const STATUS_HINT = {
  PENDING_PAYMENT: '请在有效期内完成支付，超时未付订单会自动关闭并释放库存',
  PAID: '已付款，卖家正在备货',
  SHIPPED: '商品已发出，收到货后请及时确认',
  COMPLETED: '交易已完成，感谢你的支持',
  CLOSED: '订单已关闭',
  REFUNDED: '订单已退款',
};

function decorate(order) {
  const items = (order.items || []).map((it) =>
    Object.assign({}, it, {
      imageSrc: images.skuImage(it.skuId, it.spuId, it.mainImage),
      priceText: api.fen2yuan(it.price),
      amountText: api.fen2yuan(it.amount),
    })
  );
  const quantity = items.reduce((sum, it) => sum + (Number(it.quantity) || 0), 0);
  return Object.assign({}, order, {
    items,
    quantityText: quantity || items.length,
    totalAmountText: api.fen2yuan(order.totalAmount),
    freightAmountText: api.fen2yuan(order.freightAmount),
    discountAmountText: api.fen2yuan(order.discountAmount),
    payAmountText: api.fen2yuan(order.payAmount),
    statusText: order.statusLabel || order.status,
    statusHint: STATUS_HINT[order.status] || '',
    createdText: api.shortTime(order.createdAt),
    expireText: api.shortTime(order.expireAt),
    paidText: api.shortTime(order.paidAt),
    shippedText: api.shortTime(order.shippedAt),
    finishedText: api.shortTime(order.finishedAt),
    closedText: api.shortTime(order.closedAt),
  });
}

Page({
  data: {
    orderNo: '',
    order: null,
    loading: true,
    error: '',
    tracks: [],
    tracksError: '',
    paying: false,
    // 该订单的售后单列表（同订单最多两种类型各一份，全部提交后才收起申请入口）
    aftersales: [],
  },

  onLoad(query) {
    this.setData({ orderNo: (query && query.orderNo) || '' });
    this.fetch();
  },

  onHide() {
    pay.stopPolling(this);
  },

  onUnload() {
    pay.stopPolling(this);
  },

  onPullDownRefresh() {
    Promise.all([this.fetch(), this.fetchTracks()])
      .catch(() => {})
      .then(() => wx.stopPullDownRefresh());
  },

  fetch() {
    if (!this.data.orderNo) {
      this.setData({ loading: false, error: '缺少订单号' });
      return Promise.resolve();
    }
    this.setData({ loading: true, error: '' });
    return api
      .ensureSession()
      .then(() => api.order.detail(this.data.orderNo))
      .then((order) => {
        this.setData({ order: decorate(order), loading: false });
        // 只有已发货/已完成的订单才有物流可看（静默失败，不影响主信息）
        if (order && (order.status === 'SHIPPED' || order.status === 'COMPLETED')) {
          this.fetchTracks();
        }
        // 售后状态：只有发货后才可能申请（后端规则），静默失败（不影响订单主体展示）
        if (order && ['SHIPPED', 'COMPLETED'].indexOf(order.status) >= 0) {
          this.fetchAfterSale();
        }
        return order;
      })
      .catch((err) => {
        if (err && err.needLogin) {
          this.setData({ loading: false, error: '登录后查看订单' });
          return null;
        }
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
        return null;
      });
  },

  fetchTracks() {
    return api.order
      .logistics(this.data.orderNo)
      .then((data) =>
        this.setData({
          tracks: (data && data.tracks) || [],
          tracksError: '',
        })
      )
      .catch(() => this.setData({ tracks: [], tracksError: '物流信息暂时取不到' }));
  },

  /** 拉该订单的售后单（接口是"我的售后"分页，这里本地筛 orderNo） */
  fetchAfterSale() {
    return api.aftersale
      .mine(1, 50)
      .then((data) => {
        const list = (data && data.items) || [];
        const hits = list.filter((a) => a.orderNo === this.data.orderNo);
        this.setData({ aftersales: hits });
      })
      .catch(() => this.setData({ aftersales: [] }));
  },

  /** 申请售后 */
  goAfterSale() {
    wx.navigateTo({ url: '/pages/after-sale/after-sale?orderNo=' + this.data.orderNo });
  },

  /** 复制订单号 */
  copyOrderNo() {
    wx.setClipboardData({ data: this.data.orderNo });
  },

  // ── 按状态的操作 ─────────────────────────────────────────
  doPay() {
    if (this.data.paying) return;
    this.setData({ paying: true });
    pay
      .checkout(this.data.orderNo, this, {
        onSuccess: () => {
          wx.showToast({ title: '支付成功', icon: 'success' });
          this.fetch();
        },
        onTimeout: () => wx.showToast({ title: '还未检测到支付成功', icon: 'none' }),
      })
      .then((state) => {
        this.setData({ paying: false });
        if (state === 'SUCCESS') this.fetch();
      })
      .catch((err) => {
        this.setData({ paying: false });
        wx.showToast({ title: (err && err.message) || '发起支付失败', icon: 'none' });
      });
  },

  doCancel() {
    wx.showModal({
      title: '取消订单',
      content: '确定取消这笔待付款订单吗？取消后库存会释放。',
      confirmColor: '#e5484d',
      success: (res) => {
        if (!res.confirm) return;
        api.order
          .cancel(this.data.orderNo, '小程序用户取消')
          .then(() => {
            wx.showToast({ title: '已取消', icon: 'success' });
            this.fetch();
          })
          .catch(api.toastError);
      },
    });
  },

  doConfirm() {
    wx.showModal({
      title: '确认收货',
      content: '确认已收到商品？确认后货款将结算给卖家。',
      success: (res) => {
        if (!res.confirm) return;
        api.order
          .confirm(this.data.orderNo)
          .then(() => {
            wx.showToast({ title: '已确认收货', icon: 'success' });
            this.fetch();
          })
          .catch(api.toastError);
      },
    });
  },
});

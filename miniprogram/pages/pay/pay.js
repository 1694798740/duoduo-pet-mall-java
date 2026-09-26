// 页内沙箱收银台（第三十九轮）：替代「复制 codeUrl 到浏览器」
//
// 为什么做成页内而不是 web-view：web-view 要求目标域名是 HTTPS + 小程序后台配置过的业务域名，
// 沙箱环境的 localhost/内网地址永远配不了；wx.requestPayment 需要真实微信渠道预下单参数
// （B-07 边界，未接真实渠道）。而 POST /api/payments/sandbox/{payNo} 是公开端点、
// 金额取库内值、与服务端渲染收银台同一个入账路径 —— 直接调它就是「同一动作的页内版」。
//
// 真实渠道接入时（B-07）：把 doConfirm 换成 wx.requestPayment 的预下单参数拉起，
// 轮询（payStatus）与回调链路完全复用，调用方（订单列表/详情）零改动。
const api = require('../../utils/api');
const pay = require('../../utils/pay');

Page({
  data: {
    payNo: '',
    orderNo: '',
    amountText: '',
    statusText: '待支付',
    status: '',
    paying: false,
    error: '',
  },

  onLoad(query) {
    const payNo = (query && query.payNo) || '';
    const orderNo = (query && query.orderNo) || '';
    this.handlers = pay.takeHandlers(payNo);
    this.setData({ payNo, orderNo });
    this.fetchStatus();
  },

  onUnload() {
    pay.stopPolling(this);
  },

  onHide() {
    pay.stopPolling(this);
  },

  /** 展示支付单金额与状态（失败不阻塞：金额后端核验，页面金额仅展示） */
  fetchStatus() {
    if (!this.data.payNo) {
      this.setData({ error: '缺少支付单号' });
      return Promise.resolve();
    }
    return api.order
      .payStatus(this.data.payNo)
      .then((p) => {
        if (p && p.payStatus === 'SUCCESS') {
          this.setData({ status: 'SUCCESS', statusText: '支付成功', amountText: api.fen2yuan(p.amount) });
          this.notifySuccess();
          return p;
        }
        this.setData({
          status: (p && p.payStatus) || '',
          statusText: (p && p.statusLabel) || '待支付',
          amountText: api.fen2yuan(p ? p.amount : 0),
        });
        return p;
      })
      .catch((err) => {
        this.setData({ error: (err && err.message) || '支付单信息获取失败' });
        return null;
      });
  },

  /** 确认支付：调沙箱支付动作（公开端点，金额取库内值，走同一验签入账路径） */
  doConfirm() {
    if (this.data.paying || this.data.status === 'SUCCESS') return;
    this.setData({ paying: true, error: '' });
    api.order
      .sandboxConfirm(this.data.payNo)
      .then((result) => {
        if (result && result.payStatus === 'SUCCESS') {
          this.setData({ status: 'SUCCESS', statusText: '支付成功', paying: false });
          this.notifySuccess();
          return result;
        }
        // 理论上沙箱同步返回 SUCCESS；若真给了中间态，退回轮询兜底
        pay.startPolling(this.data.payNo, {
          onSuccess: () => {
            this.setData({ status: 'SUCCESS', statusText: '支付成功', paying: false });
            this.notifySuccess();
          },
          onTimeout: () => {
            this.setData({ paying: false, error: '还没检测到支付成功，可稍后在订单页查看' });
          },
        }, this);
        return result;
      })
      .catch((err) => {
        this.setData({ paying: false, error: (err && err.message) || '支付失败，请重试' });
      });
  },

  /** 成功后触发调用方回调（toast/刷新），稍候返回来源页 */
  notifySuccess() {
    wx.showToast({ title: '支付成功', icon: 'success' });
    if (this.handlers && this.handlers.onSuccess) this.handlers.onSuccess();
    setTimeout(() => wx.navigateBack(), 800);
  },
});

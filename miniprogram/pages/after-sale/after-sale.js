// 售后申请：选择类型 + 填写诉求 → 提交
//
// 后端契约（履约域 A3）：POST /api/aftersales {orderNo, type, reason, images[]}
//   · type：REFUND_ONLY（仅退款）/ RETURN_REFUND（退货退款）
//   · reason：≤ 500 字（本地校验与后端一致，避免"本地过了、后端 400"）
//   · images：可空 —— 小程序端本轮不做图片上传（需要额外的上传链路），
//     页面里明确告知用户"凭证图片可在 Web 端补充"，不做一个半截的入口。
const api = require('../../utils/api');

const TYPES = [
  { key: 'REFUND_ONLY', label: '仅退款', desc: '没收到货 / 不想要了' },
  { key: 'RETURN_REFUND', label: '退货退款', desc: '已收到货，需要退回' },
];

const REASON_MAX = 500;

Page({
  data: {
    orderNo: '',
    types: TYPES,
    type: 'REFUND_ONLY',
    reason: '',
    reasonMax: REASON_MAX,
    submitting: false,
    loading: true,
    error: '',
    // 该订单已有的售后单（有则不该重复申请）
    existing: null,
  },

  onLoad(query) {
    this.setData({ orderNo: (query && query.orderNo) || '' });
    this.checkExisting();
  },

  /** 查一下这个订单是否已经提过售后（避免重复申请） */
  checkExisting() {
    if (!this.data.orderNo) {
      this.setData({ loading: false, error: '缺少订单号' });
      return Promise.resolve();
    }
    this.setData({ loading: true, error: '' });
    return api
      .ensureSession()
      .then(() => api.aftersale.mine(1, 50))
      .then((data) => {
        const list = (data && data.items) || [];
        const hit = list.find((a) => a.orderNo === this.data.orderNo);
        this.setData({ existing: hit || null, loading: false });
      })
      .catch((err) => {
        // 查不到已有售后不影响提交，只是少一层提醒
        this.setData({ loading: false, error: (err && err.message) || '' });
      });
  },

  pickType(e) {
    this.setData({ type: e.currentTarget.dataset.key });
  },

  onReason(e) {
    this.setData({ reason: e.detail.value });
  },

  submit() {
    if (this.data.submitting || this.data.existing) return;
    const reason = (this.data.reason || '').trim();
    if (!reason) {
      wx.showToast({ title: '请填写售后诉求', icon: 'none' });
      return;
    }
    if (reason.length > REASON_MAX) {
      wx.showToast({ title: '诉求不能超过 ' + REASON_MAX + ' 字', icon: 'none' });
      return;
    }
    this.setData({ submitting: true });
    api.aftersale
      .create(this.data.orderNo, this.data.type, reason)
      .then(() => {
        this.setData({ submitting: false });
        wx.showModal({
          title: '已提交售后',
          content: '卖家会尽快处理，处理进度可在订单详情查看。',
          showCancel: false,
          confirmText: '知道了',
          complete: () => wx.navigateBack(),
        });
      })
      .catch((err) => {
        this.setData({ submitting: false });
        wx.showToast({ title: (err && err.message) || '提交失败', icon: 'none' });
      });
  },
});

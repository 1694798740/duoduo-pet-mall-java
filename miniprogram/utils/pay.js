// 沙箱支付：发起 + 收银台跳转 + 轮询（订单列表页与订单详情页共用）
//
// 沙箱渠道的支付链路（与 Web 端同一套契约）：
//   POST /api/orders/{no}/pay {channel:'sandbox', idempotentKey}  → PayResult（含 payNo）
//   跳转 pages/pay/pay 沙箱收银台 → 确认支付 POST /api/payments/sandbox/{payNo}（公开端点）
//   GET  /api/payments/{payNo}                                    → 轮询支付状态
//
// 关键约定（都是踩过的坑）：
//  * 发起支付必须带 idempotentKey：后端 @NotBlank，缺了直接 400；
//  * 支付状态字段是 payStatus（不是 status）；
//  * 旧版「复制 codeUrl 到浏览器」已废弃：小程序打不开未配置业务域名的 H5，
//    沙箱收银台直接做成本地页面（第三十九轮）；
//  * 轮询句柄必须挂到页面实例上并在 onHide/onUnload 清理，否则离开页面仍在跑、连点还会叠加；
//  * 调用方的 onSuccess/onTimeout 回调经 pendingHandlers 透传给收银台页 ——
//    调用方代码（toast + 刷新）一行不用改。
const api = require('./api');

const POLL_INTERVAL = 3000;
const POLL_MAX = 20; // 3s × 20 = 1 分钟，与 Web 端口径一致

/** 发起支付（含幂等键）。返回 PayResult（payNo / payStatus / codeUrl） */
function payOrder(orderNo) {
  return api.order.pay(orderNo);
}

// 收银台页按 payNo 取走调用方注册的回调（取即删，防止重复触发）
const pendingHandlers = {};

function stashHandlers(payNo, opts) {
  if (payNo && opts && (opts.onSuccess || opts.onTimeout)) {
    pendingHandlers[payNo] = opts;
  }
}

function takeHandlers(payNo) {
  const h = pendingHandlers[payNo] || {};
  delete pendingHandlers[payNo];
  return h;
}

/**
 * 轮询支付状态。
 *
 * @param {string} payNo 支付单号
 * @param {object} handlers { onSuccess, onTimeout } 均为可选回调
 * @param {object} page   页面实例（句柄挂在 page._payTimer 上，便于统一清理）
 * @returns {number} 定时器 id
 */
function startPolling(payNo, handlers, page) {
  stopPolling(page);
  const opts = handlers || {};
  let times = 0;
  const timer = setInterval(() => {
    times += 1;
    api.order
      .payStatus(payNo)
      .then((p) => {
        if (p && p.payStatus === 'SUCCESS') {
          stopPolling(page);
          if (opts.onSuccess) opts.onSuccess();
        } else if (times >= POLL_MAX) {
          stopPolling(page);
          if (opts.onTimeout) opts.onTimeout();
        }
      })
      .catch(() => {
        if (times >= POLL_MAX) {
          stopPolling(page);
          if (opts.onTimeout) opts.onTimeout();
        }
      });
  }, POLL_INTERVAL);
  if (page) page._payTimer = timer;
  return timer;
}

/** 停止轮询（onHide / onUnload / 重新支付前都要调用） */
function stopPolling(page) {
  if (page && page._payTimer) {
    clearInterval(page._payTimer);
    page._payTimer = null;
  }
}

/**
 * 一站式：发起支付并跳转**页内沙箱收银台**。
 *
 * @returns {Promise<'SUCCESS'|'PENDING'>} SUCCESS=发起即已支付成功；PENDING=已进入收银台页
 */
function checkout(orderNo, page, handlers) {
  const opts = handlers || {};
  return payOrder(orderNo).then((result) => {
    if (result && result.payStatus === 'SUCCESS') {
      if (opts.onSuccess) opts.onSuccess();
      return 'SUCCESS';
    }
    const payNo = result && result.payNo;
    if (!payNo) {
      wx.showToast({ title: '未取得支付单号，请稍后重试', icon: 'none' });
      return 'PENDING';
    }
    stashHandlers(payNo, opts);
    wx.navigateTo({ url: '/pages/pay/pay?payNo=' + payNo + '&orderNo=' + orderNo });
    return 'PENDING';
  });
}

module.exports = {
  payOrder: payOrder,
  startPolling: startPolling,
  stopPolling: stopPolling,
  checkout: checkout,
  takeHandlers: takeHandlers,
};

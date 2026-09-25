// 沙箱支付：发起 + 轮询（订单列表页与订单详情页共用，避免两处各写一份）
//
// 沙箱渠道的支付链路（与 Web 端同一套契约）：
//   POST /api/orders/{no}/pay {channel:'sandbox', idempotentKey}  → PayResult
//   GET  /api/payments/{payNo}                                    → 轮询支付状态
//
// 关键约定（都是踩过的坑）：
//  * 必须带 idempotentKey：后端 @NotBlank，缺了直接 400；
//  * 收银台地址字段是 PayResult.codeUrl（不是 payUrl）；
//  * 支付状态字段是 payStatus（不是 status）；
//  * 轮询句柄必须挂到页面实例上并在 onHide/onUnload 清理，否则离开页面仍在跑、连点还会叠加。
const api = require('./api');

const POLL_INTERVAL = 3000;
const POLL_MAX = 20; // 3s × 20 = 1 分钟，与 Web 端口径一致

/** 发起支付（含幂等键）。调用方负责把返回的 codeUrl/payNo 展示与轮询 */
function payOrder(orderNo) {
  return api.order.pay(orderNo);
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
 * 一站式：发起支付并展示沙箱收银台。
 *
 * @returns {Promise<'SUCCESS'|'PENDING'>} SUCCESS=已立刻支付成功；PENDING=已拉起收银台、进入轮询
 */
function checkout(orderNo, page, handlers) {
  const opts = handlers || {};
  return payOrder(orderNo).then((result) => {
    if (result && result.payStatus === 'SUCCESS') {
      if (opts.onSuccess) opts.onSuccess();
      return 'SUCCESS';
    }
    const cashierUrl = (result && result.codeUrl) || '';
    wx.showModal({
      title: '沙箱支付',
      content: cashierUrl
        ? '收银台链接（复制后在浏览器打开完成支付）：\n' + cashierUrl
        : '未取得收银台链接，请稍后重试',
      confirmText: cashierUrl ? '复制' : '知道了',
      cancelText: '关闭',
      success: (res) => {
        if (res.confirm && cashierUrl) {
          wx.setClipboardData({ data: cashierUrl });
        }
        if (result && result.payNo) {
          startPolling(result.payNo, opts, page);
        }
      },
    });
    return 'PENDING';
  });
}

module.exports = {
  payOrder: payOrder,
  startPolling: startPolling,
  stopPolling: stopPolling,
  checkout: checkout,
};

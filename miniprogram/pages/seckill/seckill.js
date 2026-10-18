// 限时秒杀（A14）：活动列表 + 一键秒杀（服务端 Redis 预扣 + DB 兜底防超卖）
//
// A14 修复要点：
//  * 活动状态读 **phase**（后端按当前时间计算 ONGOING/UPCOMING/ENDED，没有 status 字段）——
//    此前读 status 恒为 undefined，导致按钮恒禁用、点击恒提示「不在抢购时间内」；
//  * dataset 只传 id/phase 等标量，不再把整个活动对象（含 skus 数组）塞进 dataset；
//  * 登录引导与失败分支都有 catch：不会静默无响应。
const api = require('../../utils/api');
const images = require('../../utils/images');

const PHASE_LABEL = { ONGOING: '抢购中', UPCOMING: '即将开始', ENDED: '已结束' };

function decorate(act) {
  return Object.assign({}, act, {
    phaseLabel: PHASE_LABEL[act.phase] || act.phase,
    startText: api.shortTime(act.startAt),
    endText: api.shortTime(act.endAt),
    skus: (act.skus || []).map((s) =>
      Object.assign({}, s, {
        imageSrc: images.skuImage(s.skuId, null, s.image),
        seckillPriceText: api.fen2yuan(s.seckillPrice),
        originalPriceText: api.fen2yuan(s.originalPrice),
        soldOut: Number(s.availableStock) <= 0,
      })
    ),
  });
}

Page({
  data: {
    activities: [],
    loading: true,
    error: '',
    placingSkuId: '',
  },

  onShow() {
    this.fetch();
  },

  fetch() {
    this.setData({ loading: true, error: '' });
    return api.seckill
      .activities()
      .then((list) => this.setData({ activities: (list || []).map(decorate), loading: false }))
      .catch((err) =>
        this.setData({ loading: false, error: (err && err.message) || '加载失败' })
      );
  },

  /** D4：「我的抢购记录」复用订单页，服务端按 orderType=SECKILL 筛选（不在此页自己拉一遍订单） */
  goMyOrders() {
    getApp()
      .ensureLogin()
      .then(() => wx.navigateTo({ url: '/pages/orders/orders?type=SECKILL' }))
      .catch((err) => {
        if (err && err.needLogin) return;
        wx.showToast({ title: (err && err.message) || '请先登录', icon: 'none' });
      });
  },

  buy(e) {
    const activityId = e.currentTarget.dataset.activityId;
    const skuId = e.currentTarget.dataset.skuId;
    const phase = e.currentTarget.dataset.phase;
    if (phase !== 'ONGOING') {
      wx.showToast({ title: '不在抢购时间内', icon: 'none' });
      return;
    }
    if (this.data.placingSkuId) return; // 防连点
    getApp()
      .ensureLogin()
      .then(() => this.place(activityId, skuId))
      .catch((err) => {
        // needLogin：已引导登录页或用户放弃登录，无需再弹错误
        if (err && err.needLogin) return;
        wx.showToast({ title: (err && err.message) || '请先登录', icon: 'none' });
      });
  },

  place(activityId, skuId) {
    this.setData({ placingSkuId: skuId });
    return api.address
      .default()
      .then((addr) => {
        if (!addr) {
          wx.showModal({
            title: '还没有收货地址',
            content: '先添加一个收货地址，就可以直接抢购了。',
            confirmText: '去添加',
            cancelText: '取消',
            success: (res) => {
              if (res.confirm) wx.navigateTo({ url: '/pages/address/address' });
            },
          });
          return null;
        }
        // 秒杀讲「快」，所以不在流程里插"选地址"这一步；但下单后要把收货信息摆给用户看，
        // 避免手快抢到却寄错地方（要换地址可去「购物车 → 地址」或「我的 → 收货地址」）
        return api.seckill.place(activityId, skuId, addr.id).then((result) => {
          wx.showToast({
            title: result && result.replay ? '你已抢到过该商品' : '秒杀成功！',
            icon: 'success',
          });
          wx.showModal({
            title: '收货信息',
            content: (addr.receiverName || '') + ' ' + (addr.receiverPhone || '') + '\n' + (addr.fullAddress || ''),
            showCancel: false,
            confirmText: '查看订单',
            complete: () => {
              this.fetch();
              // 第四十五轮：orders 回归普通页面，switchTab 对非 tab 页无效
              wx.navigateTo({ url: '/pages/orders/orders' });
            },
          });
        });
      })
      .catch((err) => {
        wx.showToast({ title: (err && err.message) || '秒杀失败', icon: 'none' });
        this.fetch(); // 失败后刷新余量（可能已抢完或触发限购）
      })
      .then(() => this.setData({ placingSkuId: '' }));
  },
});

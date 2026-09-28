// 私信会话列表（第四十七轮）
//
// 数据：GET /api/socials/messages/conversations —— 每会话最新一条 + 未读数 +
// canSend（「还能不能发」由服务端算好：互关 / 咨询会话恒可发，非互关发过一条即不可）。
// 第四十九轮：商品咨询会话按商品隔离（会话键 lo:hi:p{spuId}），同一卖家多商品互不串线。
// onShow 刷新：从聊天页返回时能看到最新未读与最后一条。
const api = require('../../utils/api');

const PAGE_SIZE = 20;

/** 行装饰：时间 + 头像 + 摘要前缀（卡片类消息带类型图标）+ 咨询会话标识 */
function decorate(it) {
  const prefix =
    it.lastBizType === 'ASK'
      ? '❓ '
      : it.lastBizType === 'PRODUCT'
        ? '🛍️ '
        : it.lastBizType === 'LISTING'
          ? '🏷️ '
          : '';
  // 第四十九轮：同一 peer 可有「主会话 + 每商品/闲置一个咨询会话」，wx:key 必须会话级唯一
  return Object.assign({}, it, {
    key: it.peerId + '-' + (it.productId || 'main') + '-' + (it.listingId || 'n'),
    timeText: api.shortTime(it.lastAt),
    avatarSrc: it.avatar || '/images/category/default.png',
    preview: prefix + (it.lastContent || ''),
  });
}

Page({
  data: {
    items: [],
    total: 0,
    page: 1,
    loading: false,
    noMore: false,
    error: '',
  },

  onShow() {
    // onShow 而不是 onLoad：从聊天页返回、从「我的」进入都要看到最新状态
    this.fetch(true);
  },

  onReachBottom() {
    if (this.data.loading || this.data.noMore) return;
    this.fetch(false);
  },

  fetch(reset) {
    const page = reset ? 1 : this.data.page + 1;
    this.setData({ loading: true, error: '' });
    api.social.message
      .conversations(page, PAGE_SIZE)
      .then((data) => {
        const rows = ((data && data.items) || []).map(decorate);
        this.setData({
          items: reset ? rows : this.data.items.concat(rows),
          total: (data && data.total) || 0,
          page,
          noMore: rows.length < PAGE_SIZE,
          loading: false,
        });
      })
      .catch((err) => this.setData({ loading: false, error: (err && err.message) || '加载失败' }));
  },

  retry() {
    this.fetch(true);
  },

  goChat(e) {
    // 咨询会话带 productId / listingId（第四十九轮商品 / 第五十三轮闲置）：进对会话，不串线
    const d = e.currentTarget.dataset;
    wx.navigateTo({
      url:
        '/pages/chat/chat?peerId=' +
        d.user +
        (d.product ? '&productId=' + d.product : '') +
        (d.listing ? '&listingId=' + d.listing : ''),
    });
  },
});

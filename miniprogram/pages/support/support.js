// 客服会话页（第七十八轮 · 从私信独立）
//
// 与 pages/chat 的关系：**视觉同源、规则不同**。这里没有「互相关注」，
// 也没有「每人只能发一条」—— 客服是消费场景里的正当诉求，社交防骚扰规则不适用。
//
// 入口 query：
//  * 无参数                      —— 自营客服（服务端按配置定位账号）
//  * ?sellerId=<卖家ID>          —— 某商家的客服
//  * ?productId=<SPU>            —— 首条消息附带商品卡（服务端校验商品归属该店铺）
//
// 闲置（跳蚤市场）**不走这里**：那是买卖双方的个人交易，只走私信。
const api = require('../../utils/api');
const media = require('../../utils/media');
const images = require('../../utils/images');
const { getNavMetrics } = require('../../utils/nav');

const PAGE_SIZE = 20;
const POLL_MS = 4000;

Page({
  data: {
    navTotal: 0,
    sellerId: '',
    productId: '',
    sessionId: '',
    sellerName: '',
    sellerAvatar: '',
    status: 'OPEN',
    items: [],
    input: '',
    sending: false,
    loading: true,
    error: '',
    scrollInto: '',
  },

  onLoad(query) {
    this.setData({
      navTotal: getNavMetrics().navTotalHeight,
      sellerId: query.sellerId || '',
      productId: query.productId || '',
    });
    this.fetchThread(true);
  },

  onShow() {
    this.startPolling();
  },

  onHide() {
    this.stopPolling();
  },

  onUnload() {
    this.stopPolling();
  },

  startPolling() {
    this.stopPolling();
    this._timer = setInterval(() => this.fetchThread(false), POLL_MS);
  },

  stopPolling() {
    if (this._timer) {
      clearInterval(this._timer);
      this._timer = null;
    }
  },

  decorate(msg) {
    return Object.assign({}, msg, {
      timeText: api.shortTime(msg.createdAt),
      cardImage: msg.card ? images.productImage(msg.card.productId, msg.card.image) : '',
      cardPriceText: msg.card && msg.card.price ? api.fen2yuan(msg.card.price) : '',
    });
  },

  fetchThread(reset) {
    if (this._fetching) return;
    this._fetching = true;
    api.support
      .messages(this.data.sellerId || null, 1, PAGE_SIZE)
      .then((data) => {
        this._fetching = false;
        const items = ((data && data.items) || []).map((m) => this.decorate(m));
        const lastIncoming = items.filter((m) => !m.mine).pop();
        const lastIncomingId = lastIncoming ? String(lastIncoming.id) : '';
        const hasNew = lastIncomingId && lastIncomingId !== this._lastIncomingId;
        this._lastIncomingId = lastIncomingId;

        const prevCount = this.data.items.length;
        this.setData(
          {
            loading: false,
            error: '',
            sessionId: (data && data.sessionId) || this.data.sessionId,
            sellerName: (data && data.sellerName) || '客服',
            sellerAvatar: (data && data.sellerAvatar) || '',
            status: (data && data.status) || 'OPEN',
            items,
          },
          () => {
            if (reset || items.length !== prevCount) this.scrollToBottom();
          }
        );

        const sid = (data && data.sessionId) || this.data.sessionId;
        if (hasNew && sid) {
          api.support.markRead(sid).catch(() => {});
        }
      })
      .catch((err) => {
        this._fetching = false;
        if (reset) {
          this.setData({ loading: false, error: (err && err.message) || '客服暂时没接通' });
        }
      });
  },

  scrollToBottom() {
    const items = this.data.items;
    if (!items.length) return;
    this.setData({ scrollInto: 'msg-' + items[items.length - 1].id });
  },

  retry() {
    this.setData({ loading: true });
    this.fetchThread(true);
  },

  onInput(e) {
    this.setData({ input: e.detail.value });
  },

  send() {
    const content = (this.data.input || '').trim();
    if (!content || this.data.sending) return;
    if (this.data.status === 'CLOSED') {
      wx.showToast({ title: '会话已关闭，请让商家重新开启', icon: 'none' });
      return;
    }
    this.setData({ sending: true });
    api.support
      .send(content, this.data.sellerId || null, this.data.productId || null)
      .then(() => {
        // 商品卡只在首条附带，发完即清，避免后面每句话都挂一张卡
        this.setData({ sending: false, input: '', productId: '' });
        this.fetchThread(false);
      })
      .catch((err) => {
        this.setData({ sending: false });
        wx.showToast({ title: (err && err.message) || '发送失败', icon: 'none' });
      });
  },

  /** B 批：拍摄或从相册选图/视频（不写 requiredPrivateInfos，避免模拟器起不来） */
  pickMedia() {
    if (this.data.status === 'CLOSED') {
      wx.showToast({ title: '会话已关闭', icon: 'none' });
      return;
    }
    wx.chooseMedia({
      count: 1,
      mediaType: ['image', 'video'],
      sourceType: ['album', 'camera'],
      maxDuration: 60,
      success: (res) => {
        const file = (res.tempFiles || [])[0];
        if (!file) return;
        const isVideo = res.type === 'video';
        // 时长是唯一硬约束；超过 6MB 先压缩，压缩失败自动回退原视频（media.js 内处理）
        const prepared = isVideo ? media.prepareVideo(file) : Promise.resolve(null);
        prepared
          .then((p) => {
            wx.showLoading({ title: isVideo && p && p.compressed ? '上传压缩视频…' : '上传中…', mask: true });
            return api.upload(isVideo ? p.path : file.tempFilePath).then((data) => ({ data, p }));
          })
          .then(({ data, p }) => {
            const url = data && data.url;
            if (!url) throw { message: '上传失败' };
            const extra = {};
            if (isVideo && p) {
              if (p.width) extra.width = p.width;
              if (p.height) extra.height = p.height;
              if (p.durationMs) extra.durationMs = p.durationMs;
            } else {
              if (file.width) extra.width = file.width;
              if (file.height) extra.height = file.height;
              if (file.duration) extra.durationMs = Math.round(file.duration * 1000);
            }
            return api.support.sendMedia(
              this.data.sellerId || null,
              isVideo ? 'VIDEO' : 'IMAGE',
              url,
              extra
            );
          })
          .then(() => {
            wx.hideLoading();
            this.fetchThread(false);
          })
          .catch((err) => {
            wx.hideLoading();
            wx.showToast({ title: (err && err.message) || '发送失败', icon: 'none' });
          });
      },
    });
  },

  previewImage(e) {
    const url = e.currentTarget.dataset.url;
    if (url) wx.previewImage({ urls: [url], current: url });
  },

  goProduct(e) {
    const id = e.currentTarget.dataset.spu;
    wx.navigateTo({ url: '/pages/product/product?id=' + id });
  },

  noop() {},
});

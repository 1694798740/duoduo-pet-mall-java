// 私信聊天页（第四十七轮）：气泡流 + HTTP 轮询 + 卡片消息 + 进入即标已读
//
// 入口（query 二选一组合）：
//  * ?peerId=<用户ID>                       —— 普通私信；
//  * ?peerId=<卖家ID>&productId=<SPU>&ask=1 —— 商品页「问一问」：首条消息按 ASK 发送，
//    服务端把收件人定为商品归属卖家（不信前端），会话从此带上咨询豁免 —— 之后可自由聊
//    （后端 countAskInConversation 判定，无需互关）。
//  * ?peerId=<卖家ID>&listingId=<闲置ID>&ask=1 —— 闲置详情页「问一问」（第五十三轮）：
//    首条按 LISTING 发送，服务端按闲置归属定收件人，会话键带闲置段（lo:hi:l{listingId}），
//    与商品咨询会话同款豁免 —— 整段对话留在该闲置的独立会话里。
//
// 规则展示（一律消费服务端结果，前端不自算）：
//  * canSend=false 时输入框禁用 + 提示「互相关注后才能继续聊天」（已发过一条且未互关）；
//  * 轮询 4s（wx.connectSocket 带不上 Authorization 头，破坏 JWT 鉴权模型，故不用 WS）；
//  * 每次拉到新消息后调 markRead（幂等），红点在下一次会话列表刷新时消失。
const api = require('../../utils/api');
const media = require('../../utils/media');
const images = require('../../utils/images');
const rt = require('../../utils/realtime');
const { EMOJIS, STICKERS } = require('../../utils/emoji');
const { getNavMetrics } = require('../../utils/nav');

const PAGE_SIZE = 20;
const POLL_MS = 4000;

Page({
  data: {
    navTotal: 0, // 自绘导航栏总高（第五十轮）：整页 flex 高度按它扣减
    peerId: '',
    productId: '',
    listingId: '',
    ask: false, // 首条按 ASK / LISTING 发（问一问）
    page: null, // MessagePage：对方资料 + 互关/可发状态
    items: [], // 装饰后的消息（时间正序）
    input: '',
    sending: false,
    loading: true,
    error: '',
    scrollInto: '',
    canSend: true,
    hint: '',
    // 表情 / 贴纸面板（2026-10-23 · 与 Web、客服页同一清单）
    emojiOpen: false,
    emojiTab: 'emoji',
    emojis: EMOJIS,
    stickers: STICKERS,
    /** 会话键（2026-10-23）：实时事件按它匹配，来自服务端下发 */
    conversationId: '',
    /** 对方正在输入 */
    typingVisible: false,
  },

  onLoad(query) {
    this.setData({ navTotal: getNavMetrics().navTotalHeight });
    if (!query.peerId) {
      this.setData({ loading: false, error: '缺少对方用户，无法打开会话' });
      return;
    }
    this.setData({
      peerId: query.peerId,
      productId: query.productId || '',
      listingId: query.listingId || '',
      ask: query.ask === '1',
    });
    this.fetchThread(true);
  },

  onShow() {
    this.startPolling();
    rt.open((e) => this.onRealtime(e));
  },

  onHide() {
    this.stopPolling();
    rt.close();
  },

  onUnload() {
    this.stopPolling();
    rt.close();
    if (this._typingTimer) clearTimeout(this._typingTimer);
  },

  /** 实时事件：私信的新消息 / 已读回执 / 正在输入（2026-10-23） */
  onRealtime(event) {
    if (!event || event.biz !== 'DIRECT') return;
    const same = this.data.conversationId && event.conversationId === this.data.conversationId;
    if (event.type === 'MESSAGE' && same) {
      this.setData({ typingVisible: false });
      this.fetchThread(false);
      return;
    }
    if (event.type === 'READ' && same) {
      this.fetchThread(false);
      return;
    }
    if (event.type === 'TYPING' && same) {
      this.setData({ typingVisible: true });
      if (this._typingTimer) clearTimeout(this._typingTimer);
      this._typingTimer = setTimeout(() => this.setData({ typingVisible: false }), 3000);
    }
  },

  // ---------------- 轮询 ----------------

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

  // ---------------- 数据 ----------------

  /** 消息装饰：时间文本 + 卡片图映射（卡片 image 是后端相对路径；闲置卡走闲置图通道） */
  decorate(msg) {
    const isListing = msg.card && msg.card.type === 'LISTING';
    return Object.assign({}, msg, {
      timeText: api.shortTime(msg.createdAt),
      cardImage: msg.card
        ? isListing
          ? images.listingImage(msg.card.id, msg.card.image)
          : images.productImage(msg.card.id, msg.card.image)
        : '',
      cardPriceText: msg.card && msg.card.minPrice ? api.fen2yuan(msg.card.minPrice) : '',
    });
  },

  /**
   * 拉取最新一页消息（时间正序展示）。轮询与首次加载共用；
   * reset=true 时滚到底部；检测到新消息时顺手标已读。
   */
  fetchThread(reset) {
    if (this._fetching) return; // 上一轮还没回来，跳过本次（防请求堆积）
    this._fetching = true;
    api.social.message
      .thread(
        this.data.peerId,
        1,
        PAGE_SIZE,
        this.data.productId || null,
        this.data.listingId || null
      )
      .then((data) => {
        this._fetching = false;
        const items = ((data && data.items) || []).map((m) => this.decorate(m));
        const lastIncoming = items.filter((m) => !m.mine).pop();
        const lastIncomingId = lastIncoming ? String(lastIncoming.id) : '';
        const hasNew = lastIncomingId && lastIncomingId !== this._lastIncomingId;
        this._lastIncomingId = lastIncomingId;

        const patch = {
          loading: false,
          page: data,
          items,
          conversationId: (data && data.conversationId) || this.data.conversationId,
          canSend: !!data.canSend,
          // canSend=false 只可能是「非互关 + 非咨询 + 我已发过一条」的组合
          hint: data.canSend ? '' : '已发送过 1 条消息，等 TA 回复或互相关注后继续聊',
        };
        // ★ 用最后一条 id 判「有没有新消息」：分页窗口固定，长会话里条数不变（2026-10-23）
        const prevLastId = this.data.items.length ? String(this.data.items[this.data.items.length - 1].id) : '';
        const lastId = items.length ? String(items[items.length - 1].id) : '';
        this.setData(patch, () => {
          // 打开会话定位到最新未读；轮询新增消息时才滚到底（2026-10-23 与客服页对齐）
          if (reset) this.scrollToLatest(items);
          else if (lastId !== prevLastId) this.scrollToBottom();
        });
        if (hasNew) {
          api.social.message
            .markRead(this.data.peerId, this.data.productId || null, this.data.listingId || null)
            .catch(() => {});
        }
      })
      .catch((err) => {
        this._fetching = false;
        // 轮询失败静默（网络抖动很常见），只有首次加载才显示错误
        if (reset) this.setData({ loading: false, error: (err && err.message) || '加载失败' });
      });
  },

  scrollToBottom() {
    const items = this.data.items;
    if (!items.length) return;
    this.setData({ scrollInto: 'msg-' + items[items.length - 1].id });
  },

  /** 打开会话的定位点：最新一条「对方发来、我还没读」的消息；没有未读则到底 */
  scrollToLatest(items) {
    if (!items || !items.length) return;
    const firstUnread = items.find((m) => !m.mine && !m.read);
    const target = firstUnread || items[items.length - 1];
    if (target) this.setData({ scrollInto: 'msg-' + target.id });
  },

  retry() {
    this.setData({ loading: true });
    this.fetchThread(true);
  },

  // ---------------- 发送 ----------------

  onInput(e) {
    this.setData({ input: e.detail.value });
    if (this.data.conversationId) {
      rt.typing('DIRECT', { conversationId: this.data.conversationId });
    }
  },

  /** 表情面板：与 Web/客服页同一清单；点表情拼进输入框，点贴纸直接发 */
  toggleEmoji() {
    this.setData({ emojiOpen: !this.data.emojiOpen });
  },

  switchEmojiTab(e) {
    this.setData({ emojiTab: e.currentTarget.dataset.tab || 'emoji' });
  },

  pickEmoji(e) {
    const emoji = e.currentTarget.dataset.e || '';
    this.setData({ input: (this.data.input || '') + emoji });
  },

  pickSticker(e) {
    const url = e.currentTarget.dataset.url;
    if (!url || this.data.sending || !this.data.canSend) return;
    this.setData({ sending: true });
    api.social.message
      .sendMedia(
        this.data.peerId,
        'EMOJI',
        url,
        this.data.productId || null,
        this.data.listingId || null,
        {}
      )
      .then(() => {
        this.setData({ sending: false, emojiOpen: false });
        this.fetchThread(false);
      })
      .catch((err) => {
        this.setData({ sending: false });
        wx.showToast({ title: (err && err.message) || '发送失败', icon: 'none' });
      });
  },

  send() {
    const content = (this.data.input || '').trim();
    if (!content || this.data.sending || !this.data.canSend) return;
    this.setData({ sending: true });
    // 问一问首条：会话为空且带上下文时按咨询类型发送（ASK=商品 / LISTING=闲置，服务端定收件人）；
    // 后续 TEXT 仍带上下文（第四十九轮商品 / 第五十三轮闲置：整段对话留在独立咨询会话里）
    const askNow = this.data.ask && (this.data.productId || this.data.listingId) && this.data.items.length === 0;
    const bizType = askNow ? (this.data.listingId ? 'LISTING' : 'ASK') : 'TEXT';
    const askBizId = askNow ? (this.data.listingId || this.data.productId) : null;
    api.social.message
      .send(
        this.data.peerId,
        content,
        bizType,
        askBizId,
        this.data.productId || null,
        this.data.listingId || null
      )
      .then(() => {
        this.setData({ sending: false, input: '', ask: askNow ? false : this.data.ask });
        this.fetchThread(false); // 拉最新一页，含自己刚发的这条
      })
      .catch((err) => {
        this.setData({ sending: false });
        wx.showToast({ title: (err && err.message) || '发送失败', icon: 'none' });
      });
  },

  /** B 批：拍摄/相册选图或视频并发送（私信同样支持） */
  pickMedia() {
    if (!this.data.canSend) {
      wx.showToast({ title: this.data.hint || '暂时不能发送', icon: 'none' });
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
        const prepared = isVideo ? media.prepareVideo(file) : Promise.resolve(null);
        // 封面：chooseMedia 给的首帧缩略图，与视频上传并行
        const poster = isVideo ? media.uploadPoster(file) : Promise.resolve('');
        Promise.all([prepared, poster])
          .then(([p, thumb]) => {
            wx.showLoading({ title: isVideo && p && p.compressed ? '上传压缩视频…' : '上传中…', mask: true });
            return api.upload(isVideo ? p.path : file.tempFilePath).then((data) => ({ data, p, thumb }));
          })
          .then(({ data, p, thumb }) => {
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
            if (thumb) extra.thumbUrl = thumb;
            return api.social.message.sendMedia(
              this.data.peerId,
              isVideo ? 'VIDEO' : 'IMAGE',
              url,
              this.data.productId || null,
              this.data.listingId || null,
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

  /** 点卡片 → 按类型分流：闲置卡去闲置详情，商品卡去商城详情（不冒泡到气泡） */
  goCard(e) {
    const card = this.data.items.find(
      (m) => String(m.id) === String(e.currentTarget.dataset.msgid)
    );
    const id = e.currentTarget.dataset.spu;
    if (card && card.card && card.card.type === 'LISTING') {
      wx.navigateTo({ url: '/pages/flea-detail/flea-detail?id=' + id });
    } else {
      wx.navigateTo({ url: '/pages/product/product?id=' + id });
    }
  },

  noop() {},
});

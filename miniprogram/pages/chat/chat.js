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
const images = require('../../utils/images');
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
  },

  onHide() {
    this.stopPolling();
  },

  onUnload() {
    this.stopPolling();
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
          canSend: !!data.canSend,
          // canSend=false 只可能是「非互关 + 非咨询 + 我已发过一条」的组合
          hint: data.canSend ? '' : '已发送过 1 条消息，等 TA 回复或互相关注后继续聊',
        };
        const prevCount = this.data.items.length;
        this.setData(patch, () => {
          if (reset || items.length !== prevCount) {
            this.scrollToBottom();
          }
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

  retry() {
    this.setData({ loading: true });
    this.fetchThread(true);
  },

  // ---------------- 发送 ----------------

  onInput(e) {
    this.setData({ input: e.detail.value });
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

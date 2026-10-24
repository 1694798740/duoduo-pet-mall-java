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
    // 撤回 / 引用 / 语音（2026-10-24/25）
    replyTo: null,
    replyText: '',
    /** 点击式录音（2026-10-25）：'' = 空闲；recording / paused */
    recState: '',
    recSecs: 0,
    /** 多选删除模式（2026-10-25） */
    selectMode: false,
    selectedIds: [],
    /** 引用跳转定位（2026-10-25） */
    aroundId: '',
    pendingScrollId: '',
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
    // 撤回信号（2026-10-24）：回源刷新，灰字替换原气泡
    if (event.type === 'RECALL' && same) {
      this.fetchThread(false);
      return;
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
      recalled: Boolean(msg.recalledAt),
      voiceSecs: msg.bizType === 'VOICE' && msg.durationMs ? Math.max(1, Math.round(msg.durationMs / 1000)) : 0,
      quotedExcerpt: msg.quoted ? msg.quoted.excerpt : '',
      quotedId: msg.replyToId ? String(msg.replyToId) : '',
      // 自己 2 分钟内发出的消息可撤回
      canRecall: Boolean(msg.mine && !msg.recalledAt
        && Date.now() - new Date(msg.createdAt).getTime() < 120000),
    });
  },

  // ---------------- 撤回 / 引用 / 语音（2026-10-24） ----------------

  /** 长按消息 → 引用 / 删除 / 撤回 / 多选（2026-10-25） */
  onMsgLongPress(e) {
    const msg = (e.currentTarget.dataset.msg) || {};
    if (msg.recalled || this.data.selectMode) return;
    const options = ['引用', '删除'];
    if (msg.canRecall) options.push('撤回');
    options.push('多选删除');
    wx.showActionSheet({
      itemList: options,
      success: (res) => {
        const choice = options[res.tapIndex];
        if (choice === '引用') {
          this.setData({
            replyTo: { id: String(msg.id) },
            replyText: '引用：' + (msg.quotedExcerpt || msg.content || '[媒体消息]'),
          });
        } else if (choice === '删除') {
          this.deleteMessages([String(msg.id)]);
        } else if (choice === '撤回') {
          this.recallMessage(msg);
        } else if (choice === '多选删除') {
          this.setData({ selectMode: true, selectedIds: [] });
        }
      },
    });
  },

  /** 撤回（双向）：确认后撤回，双方都变成居中灰字 */
  recallMessage(msg) {
    wx.showModal({
      title: '撤回消息',
      content: '撤回后双方都看不到这条消息，确定撤回吗？',
      success: (res) => {
        if (!res.confirm) return;
        api.social.message
          .recall(msg.id)
          .then(() => this.fetchThread(false))
          .catch((err) => wx.showToast({ title: (err && err.message) || '撤回失败', icon: 'none' }));
      },
    });
  },

  /** 单向删除（2026-10-25）：只对本人隐藏，对方仍可见 */
  deleteMessages(ids) {
    if (!ids || !ids.length) return;
    wx.showModal({
      title: '删除消息',
      content: ids.length === 1
        ? '删除后仅自己看不到这条消息，对方不受影响。确定删除吗？'
        : '已选 ' + ids.length + ' 条，删除后仅自己看不到，对方不受影响。确定删除吗？',
      success: (res) => {
        if (!res.confirm) return;
        api.social.message
          .deleteMessages(ids)
          .then(() => this.setData({ selectMode: false, selectedIds: [] }))
          .then(() => this.fetchThread(false))
          .catch((err) => wx.showToast({ title: (err && err.message) || '删除失败', icon: 'none' }));
      },
    });
  },

  toggleSelect(e) {
    const id = String(e.currentTarget.dataset.id);
    const cur = this.data.selectedIds.slice();
    const idx = cur.indexOf(id);
    if (idx >= 0) cur.splice(idx, 1); else cur.push(id);
    this.setData({ selectedIds: cur });
    this.syncChecked();
  },

  toggleAllSelect() {
    const all = this.data.items.map((m) => String(m.id));
    this.setData({
      selectedIds: this.data.selectedIds.length === all.length ? [] : all,
    });
    this.syncChecked();
  },

  /** 把 selectedIds 同步进 items[].checked（WXML 绑定不支持 indexOf） */
  syncChecked() {
    const sel = this.data.selectedIds;
    this.setData({
      items: this.data.items.map((m) => Object.assign({}, m, { checked: sel.indexOf(String(m.id)) >= 0 })),
    });
  },

  deleteSelected() {
    this.deleteMessages(this.data.selectedIds.slice());
  },

  cancelSelect() {
    this.setData({ selectMode: false, selectedIds: [] });
  },

  /** 引用跳转定位（2026-10-25）：点击引用条滚到原消息；不在窗口内时用 aroundId 拉一页 */
  jumpQuoted(e) {
    const id = e.currentTarget.dataset.id;
    if (!id) return;
    const inWindow = this.data.items.some((m) => String(m.id) === String(id));
    if (inWindow) {
      this.setData({ scrollInto: 'msg-' + id });
    } else {
      this.setData({ pendingScrollId: String(id) });
      this.fetchThread(false, String(id));
    }
  },

  cancelQuote() {
    this.setData({ replyTo: null, replyText: '' });
  },

  /** 语音播放：点击气泡播放/暂停（同一时刻只播一条） */
  playVoice(e) {
    const url = e.currentTarget.dataset.url;
    if (!url) return;
    if (!this._audio) this._audio = wx.createInnerAudioContext();
    if (this._audio.src === url && !this._audio.paused) {
      this._audio.pause();
      return;
    }
    this._audio.src = url;
    this._audio.play();
  },

  /** 点击式录音（2026-10-25）：点麦克风开始 → 可暂停/继续 → 点结束发送 */
  voiceTap() {
    if (this.data.recState === 'recording') { this.voiceStop(); return; }
    if (this.data.recState === 'paused') { this.voiceStop(); return; }
    if (!this.data.canSend) {
      wx.showToast({ title: this.data.hint || '暂时不能发送', icon: 'none' });
      return;
    }
    if (!this._recorder) {
      this._recorder = media.createVoiceRecorder({
        onStart: () => this.setData({ recState: 'recording', recSecs: 0 }),
        onState: (state, seconds) => this.setData({ recState: state, recSecs: seconds }),
        onTick: (seconds) => this.setData({ recSecs: seconds }),
        onReady: (path, durationMs) => {
          this.setData({ recState: '', recSecs: 0 });
          wx.showLoading({ title: '发送语音…', mask: true });
          api.upload(path)
            .then((data) => {
              const url = data && data.url;
              if (!url) throw { message: '上传失败' };
              return api.social.message.sendMedia(
                this.data.peerId, 'VOICE', url,
                this.data.productId || null, this.data.listingId || null,
                { durationMs: Math.round(durationMs),
                  replyToId: this.data.replyTo ? this.data.replyTo.id : undefined }
              );
            })
            .then(() => {
              wx.hideLoading();
              this.setData({ replyTo: null, replyText: '' });
              this.fetchThread(false);
            })
            .catch((err) => {
              wx.hideLoading();
              wx.showToast({ title: (err && err.message) || '语音发送失败', icon: 'none' });
            });
        },
        onError: (message) => {
          this.setData({ recState: '', recSecs: 0 });
          wx.showToast({ title: message, icon: 'none' });
        },
      });
    }
    this._recorder.start();
  },

  voicePause() { if (this._recorder) this._recorder.pause(); },
  voiceResume() { if (this._recorder) this._recorder.resume(); },
  voiceStop() { if (this._recorder) this._recorder.stop(); },
  voiceCancel() {
    if (this._recorder) this._recorder.cancel();
    this.setData({ recState: '', recSecs: 0 });
  },

  /**
   * 拉取最新一页消息（时间正序展示）。轮询与首次加载共用；
   * reset=true 时滚到底部；检测到新消息时顺手标已读。
   */
  fetchThread(reset, aroundId) {
    if (this._fetching) return; // 上一轮还没回来，跳过本次（防请求堆积）
    this._fetching = true;
    api.social.message
      .thread(
        this.data.peerId,
        1,
        PAGE_SIZE,
        this.data.productId || null,
        this.data.listingId || null,
        aroundId || null
      )
      .then((data) => {
        this._fetching = false;
        const items = ((data && data.items) || []).map((m) => this.decorate(m));
        // 时间分隔条（2026-10-25）：与上一条间隔 ≥10 分钟才显示，格式按当天/昨天/前天/更早分档
        let prev = null;
        items.forEach((m) => {
          m.timeSep = api.chatTimeLabel(m.createdAt, prev ? prev.createdAt : null);
          prev = m;
        });
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
          // 引用跳转：aroundId 拉回的目标消息到位后定位并清掉窗口参数（2026-10-25）
          const pid = this.data.pendingScrollId;
          if (pid && items.some((m) => String(m.id) === pid)) {
            this.setData({ scrollInto: 'msg-' + pid, pendingScrollId: '', aroundId: '' });
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
        this.data.listingId || null,
        this.data.replyTo ? { replyToId: this.data.replyTo.id } : null
      )
      .then(() => {
        this.setData({ sending: false, input: '', ask: askNow ? false : this.data.ask,
          replyTo: null, replyText: '' });
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

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
const rt = require('../../utils/realtime');
const { EMOJIS, STICKERS } = require('../../utils/emoji');
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
    // 表情 / 贴纸面板（2026-10-23 · 与 Web 对齐）
    emojiOpen: false,
    emojiTab: 'emoji',
    emojis: EMOJIS,
    stickers: STICKERS,
    /** 本次会话已结束：按钮隐藏，仅在流里留一行小字；再次发送后恢复 */
    ended: false,
    /** 对方正在输入（2026-10-23） */
    typingVisible: false,
    // 撤回 / 引用 / 语音（2026-10-24/25）
    replyTo: null,
    replyText: '',
    /** 点击式录音（2026-10-25）：'' 空闲；recording / paused */
    recState: '',
    recSecs: 0,
    /** 多选删除模式（2026-10-25） */
    selectMode: false,
    selectedIds: [],
    /** 引用跳转定位（2026-10-25） */
    pendingScrollId: '',
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
    // 实时通道：进前台建连（退后台会被微信静默掐断，必须显式重连）
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

  /**
   * 实时事件（2026-10-23）：新消息 / 已读回执 / 正在输入。
   * 轮询保留为断线兜底；收到信号后仍走 HTTP 重拉，避免两套渲染口径。
   */
  onRealtime(event) {
    if (!event || event.biz !== 'SUPPORT') return;
    const same = this.data.sessionId && String(event.sessionId) === String(this.data.sessionId);
    if (event.type === 'MESSAGE') {
      if (same) {
        this.setData({ typingVisible: false });
        this.fetchThread(false);
      }
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
    if (event.type === 'RECALL') {
      if (same) this.fetchThread(false);
      return;
    }
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
    const voiceSecs = msg.bizType === 'VOICE' && msg.durationMs ? Math.max(1, Math.round(msg.durationMs / 1000)) : 0;
    return Object.assign({}, msg, {
      timeText: api.shortTime(msg.createdAt),
      cardImage: msg.card ? images.productImage(msg.card.productId, msg.card.image) : '',
      cardPriceText: msg.card && msg.card.price ? api.fen2yuan(msg.card.price) : '',
      recalled: Boolean(msg.recalledAt),
      voiceSecs: voiceSecs,
      quotedExcerpt: msg.quoted ? msg.quoted.excerpt : '',
      quotedId: msg.replyToId ? String(msg.replyToId) : '',
      // 自己 2 分钟内发出的消息可撤回
      canRecall: Boolean(msg.mine && !msg.recalledAt
        && Date.now() - new Date(msg.createdAt).getTime() < 120000),
    });
  },

  // ---------------- 撤回 / 删除 / 引用 / 语音（2026-10-24/25） ----------------

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
        api.support
          .recall(msg.id)
          .then(() => this.fetchThread(false))
          .catch((err) => wx.showToast({ title: (err && err.message) || '撤回失败', icon: 'none' }));
      },
    });
  },

  /** 单向删除（2026-10-25）：只对本人隐藏，客服仍可见 */
  deleteMessages(ids) {
    if (!ids || !ids.length) return;
    wx.showModal({
      title: '删除消息',
      content: ids.length === 1
        ? '删除后仅自己看不到这条消息，对方不受影响。确定删除吗？'
        : '已选 ' + ids.length + ' 条，删除后仅自己看不到，对方不受影响。确定删除吗？',
      success: (res) => {
        if (!res.confirm) return;
        api.support
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
    this.setData({ selectedIds: this.data.selectedIds.length === all.length ? [] : all });
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
    if (this.data.recState) { this.voiceStop(); return; }
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
              return api.support.sendMedia(this.data.sellerId || null, 'VOICE', url, {
                durationMs: Math.round(durationMs),
                replyToId: this.data.replyTo ? this.data.replyTo.id : undefined,
              });
            })
            .then(() => {
              wx.hideLoading();
              this.setData({ ended: false, replyTo: null, replyText: '' });
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

  fetchThread(reset, aroundId) {
    if (this._fetching) return;
    this._fetching = true;
    api.support
      .messages(this.data.sellerId || null, 1, PAGE_SIZE, aroundId || null)
      .then((data) => {
        this._fetching = false;
        const items = ((data && data.items) || []).map((m) => this.decorate(m));
        // 时间分隔条（2026-10-25）：与上一条间隔 ≥10 分钟才显示
        let prev = null;
        items.forEach((m) => {
          m.timeSep = api.chatTimeLabel(m.createdAt, prev ? prev.createdAt : null);
          prev = m;
        });
        const lastIncoming = items.filter((m) => !m.mine).pop();
        const lastIncomingId = lastIncoming ? String(lastIncoming.id) : '';
        const hasNew = lastIncomingId && lastIncomingId !== this._lastIncomingId;
        this._lastIncomingId = lastIncomingId;

        // ★ 用最后一条 id 判「有没有新消息」：分页窗口固定，长会话里条数不变（2026-10-23）
        const prevLastId = this.data.items.length ? String(this.data.items[this.data.items.length - 1].id) : '';
        const lastId = items.length ? String(items[items.length - 1].id) : '';
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
            // 打开会话：定位到最新未读（没有未读就到底）；轮询新增消息时才滚到底
            if (reset) this.scrollToLatest(items);
            else if (lastId !== prevLastId) this.scrollToBottom();
            // 引用跳转：aroundId 拉回的目标消息到位后定位（2026-10-25）
            const pid = this.data.pendingScrollId;
            if (pid && items.some((m) => String(m.id) === pid)) {
              this.setData({ scrollInto: 'msg-' + pid, pendingScrollId: '' });
            }
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

  /**
   * 打开会话的定位点（2026-10-23）：最新一条「对方发来、我还没读」的消息。
   *
   * <p>之前一律滚到底：卖家连发几条时，用户第一眼看到的是最后一句，
   * 前面的上下文要自己往上翻。有未读时停在第一条未读，符合微信的阅读顺序。
   */
  scrollToLatest(items) {
    if (!items || !items.length) return;
    const firstUnread = items.find((m) => !m.mine && !m.read);
    const target = firstUnread || items[items.length - 1];
    if (target) this.setData({ scrollInto: 'msg-' + target.id });
  },

  /** 结束本次咨询：按钮立即隐藏 + 会话流里落一行系统小字；再次发消息后按钮恢复 */
  endConsult() {
    const sid = this.data.sessionId;
    if (!sid || this.data.ended) return;
    wx.showModal({
      title: '结束咨询',
      content: '结束后仍可继续发送消息，客服会重新收到。',
      success: (res) => {
        if (!res.confirm) return;
        api.support
          .close(sid)
          .then(() => this.setData({ ended: true }))
          .catch((err) => wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' }));
      },
    });
  },

  retry() {
    this.setData({ loading: true });
    this.fetchThread(true);
  },

  onInput(e) {
    this.setData({ input: e.detail.value });
    // 输入即上报「正在输入」（客户端 2 秒节流，服务端再限一次）
    if (this.data.sessionId) rt.typing('SUPPORT', { sessionId: this.data.sessionId });
  },

  /** 表情面板：与 Web 同一个清单（56 表情 + 12 贴纸），点表情拼进输入框，点贴纸直接发 */
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
    if (!url || this.data.sending) return;
    this.setData({ sending: true });
    api.support
      .sendMedia(this.data.sellerId || null, 'EMOJI', url, {})
      .then(() => {
        this.setData({ sending: false, emojiOpen: false, ended: false });
        this.fetchThread(false);
      })
      .catch((err) => {
        this.setData({ sending: false });
        wx.showToast({ title: (err && err.message) || '发送失败', icon: 'none' });
      });
  },

  send() {
    const content = (this.data.input || '').trim();
    if (!content || this.data.sending) return;
    // 2026-10-23：不再因会话「关闭」而拦截 —— 关闭只是客服侧标记，发送会自动重开
    this.setData({ sending: true });
    api.support
      .send(content, this.data.sellerId || null, this.data.productId || null,
        this.data.replyTo ? { replyToId: this.data.replyTo.id } : null)
      .then(() => {
        // 商品卡只在首条附带，发完即清，避免后面每句话都挂一张卡
        // 再发消息 = 会话重开：「结束咨询」按钮恢复
        this.setData({ sending: false, input: '', productId: '', ended: false, replyTo: null, replyText: '' });
        this.fetchThread(false);
      })
      .catch((err) => {
        this.setData({ sending: false });
        wx.showToast({ title: (err && err.message) || '发送失败', icon: 'none' });
      });
  },

  /** B 批：拍摄或从相册选图/视频（不写 requiredPrivateInfos，避免模拟器起不来） */
  pickMedia() {
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
        // 封面与压缩并行：封面来自 chooseMedia 的 thumbTempFilePath，不占用户等待时间
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
            return api.support.sendMedia(
              this.data.sellerId || null,
              isVideo ? 'VIDEO' : 'IMAGE',
              url,
              extra
            );
          })
          .then(() => {
            wx.hideLoading();
            // 发消息即重开：结束提示撤下、按钮恢复
            this.setData({ ended: false });
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

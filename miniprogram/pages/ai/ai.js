// AI 导购「多多」：对话 + 商品推荐卡片（A9：POST /api/duoduo/chat，免登录，429/503 友好降级）
//
// A14 修复要点：
//  * 消息加稳定 id（wx:key="index" 不是合法 key）；
//  * 本地只保留最近 N 条消息，避免长会话反复 setData 整段历史（性能与 256KB 上限风险）；
//  * 键盘高度自适配：输入条随键盘上移，且 iOS/Android 表现一致（adjust-position 关掉、自己定位）。
const api = require('../../utils/api');

const MAX_KEEP = 60; // 本地最多保留的消息条数（超出丢最旧的）
const HISTORY_LIMIT = 6; // 每次请求带的历史条数（后端 @Size(max=6)）

let seq = 0;
function nextId() {
  seq += 1;
  return 'm' + Date.now().toString(36) + '-' + seq;
}

Page({
  data: {
    messages: [], // { id, role, content, recs?, error? }
    input: '',
    sending: false,
    scrollInto: '',
    keyboardHeight: 0,
  },

  _keyboardHandler: null,

  onLoad(query) {
    const initial = [this.greeting()];
    if (query && query.prefill) {
      // 从商品详情「问问 AI」进来：预填问题，同时保留问候语（否则首屏是空的）
      this.setData({ input: decodeURIComponent(query.prefill) });
    }
    this.setData({ messages: initial });
    this.bindKeyboard();
  },

  /** 监听键盘高度：输入条跟着上移，避免被键盘遮挡（两端行为统一） */
  bindKeyboard() {
    if (!wx.onKeyboardHeightChange) return;
    this._keyboardHandler = (res) => this.setData({ keyboardHeight: res.height || 0 });
    wx.onKeyboardHeightChange(this._keyboardHandler);
  },

  onUnload() {
    if (this._keyboardHandler && wx.offKeyboardHeightChange) {
      wx.offKeyboardHeightChange(this._keyboardHandler);
    }
    this._keyboardHandler = null;
  },

  greeting() {
    return {
      id: nextId(),
      role: 'assistant',
      content: '你好呀，我是 AI 导购「多多」🐱 告诉我你想给谁买什么，我来帮你挑！',
    };
  },

  onInput(e) {
    this.setData({ input: e.detail.value });
  },

  send() {
    const text = (this.data.input || '').trim();
    if (!text || this.data.sending) return;

    this.push({ id: nextId(), role: 'user', content: text });
    this.setData({ input: '', sending: true });

    // history 只带最近 6 条**非错误**消息的纯文本（后端 @Size(max=6)）
    const history = this.data.messages
      .filter((m) => !m.error)
      .slice(-HISTORY_LIMIT)
      .map((m) => ({ role: m.role, content: m.content }));

    return api.ai
      .chat(text, history)
      .then((res) => {
        this.push({
          id: nextId(),
          role: 'assistant',
          content: res.reply,
          recs: (res.recommendations || []).map((r) =>
            Object.assign({}, r, { priceText: api.fen2yuan(r.minPrice) })
          ),
        });
      })
      .catch((err) => {
        this.push({
          id: nextId(),
          role: 'assistant',
          content: (err && err.message) || '多多走神了，再试一次？',
          error: true,
        });
      })
      .then(() => this.setData({ sending: false }));
  },

  push(msg) {
    const all = this.data.messages.concat([msg]);
    const messages = all.length > MAX_KEEP ? all.slice(all.length - MAX_KEEP) : all;
    this.setData({
      messages,
      // 滚动锚点用最后一条的 id（下标在裁剪后会漂移）
      scrollInto: 'msg-' + messages[messages.length - 1].id,
    });
  },

  goDetail(e) {
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.id });
  },
});

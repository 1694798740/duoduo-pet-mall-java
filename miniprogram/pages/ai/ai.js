// AI 导购「多多」：对话 + 商品推荐卡片（A9：POST /api/duoduo/chat，免登录，429/503 友好降级）
const api = require('../../utils/api');

Page({
  data: {
    messages: [], // { role: 'user' | 'assistant', content, recs? }
    input: '',
    sending: false,
    scrollInto: '',
  },

  onLoad(query) {
    if (query && query.prefill) {
      this.setData({ input: decodeURIComponent(query.prefill) });
    } else {
      this.setData({
        messages: [
          {
            role: 'assistant',
            content: '你好呀，我是 AI 导购「多多」🐱 告诉我你想给谁买什么，我来帮你挑！',
          },
        ],
      });
    }
  },

  onInput(e) {
    this.setData({ input: e.detail.value });
  },

  send() {
    const text = (this.data.input || '').trim();
    if (!text || this.data.sending) return;
    this.push({ role: 'user', content: text });
    this.setData({ input: '', sending: true });

    // history 只带最近 6 条纯文本（后端 @Size(max=6)）
    const history = this.data.messages
      .filter((m) => !m.error)
      .slice(-6)
      .map((m) => ({ role: m.role, content: m.content }));

    api.ai
      .chat(text, history)
      .then((res) => {
        this.push({
          role: 'assistant',
          content: res.reply,
          recs: (res.recommendations || []).map((r) =>
            Object.assign({}, r, { priceText: (r.minPrice / 100).toFixed(0) })
          ),
        });
      })
      .catch((err) => {
        this.push({ role: 'assistant', content: err.message || '多多走神了，再试一次？', error: true });
      })
      .then(() => this.setData({ sending: false }));
  },

  push(msg) {
    const messages = this.data.messages.concat([msg]);
    this.setData({ messages, scrollInto: 'msg-' + (messages.length - 1) });
  },

  goDetail(e) {
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.id });
  },
});

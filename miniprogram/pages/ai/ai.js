// AI 导购「多多」：对话 + 商品推荐卡片（A9：POST /api/duoduo/chat，免登录，429/503 友好降级）
//
// A14 修复要点：
//  * 消息加稳定 id（wx:key="index" 不是合法 key）；
//  * 本地只保留最近 N 条消息，避免长会话反复 setData 整段历史（性能与 256KB 上限风险）；
//  * 键盘高度自适配：输入条随键盘上移，且 iOS/Android 表现一致（adjust-position 关掉、自己定位）。
const api = require('../../utils/api');
const { getNavMetrics } = require('../../utils/nav');

const MAX_KEEP = 60; // 本地最多保留的消息条数（超出丢最旧的）
const HISTORY_LIMIT = 6; // 每次请求带的历史条数（后端 @Size(max=6)）

let seq = 0;
function nextId() {
  seq += 1;
  return 'm' + Date.now().toString(36) + '-' + seq;
}

Page({
  data: {
    navTotal: 0, // 自绘导航栏总高（第五十轮）：消息区高度按它扣减
    messages: [], // { id, role, content, recs?, error?, options? }
    input: '',
    sending: false,
    scrollInto: '',
    keyboardHeight: 0,
    // 能力探测（第五十五轮）：服务端未配置大模型时 chatAvailable=false
    aiReady: true,
    aiNotice: '',
  },

  _keyboardHandler: null,
  /**
   * 本次会话是否已经澄清过一轮（第五十七轮）。
   *
   * <p>放在实例上而不是 data 里：它不参与渲染，只是要随请求带上；
   * 放进 data 会让每次 setData 都白搬一个布尔值，也容易被误读成「界面状态」。
   */
  _clarifiedSent: false,

  onLoad(query) {
    this.setData({ navTotal: getNavMetrics().navTotalHeight });
    const initial = [this.greeting()];
    if (query && query.prefill) {
      // 从商品详情「问问 AI」进来：预填问题，同时保留问候语（否则首屏是空的）
      this.setData({ input: decodeURIComponent(query.prefill) });
    }
    this.setData({ messages: initial });
    this.bindKeyboard();
    this.checkCapability();
  },

  /**
   * 能力探测（第五十五轮）。
   *
   * <p>此前 {@code api.ai.capabilities} 是**死代码** —— 接口封装好了，页面从来没调过。
   * 后果：服务端没配大模型 Key 时，用户输入问题只会看到一句「多多走神了」，
   * 于是他会反复重试、换问题、以为是自己的网络问题 —— 而真正的原因（AI 未开放）
   * 只有服务端日志里才有。现在页面在进入时就问一次，不可用就直接说清楚并禁用输入。
   *
   * <p>探测失败**不当作不可用**：探测接口本身抖动不代表对话不可用，
   * 因此失败时保持可用，让用户直接试 —— 真失败了发送时会给出明确提示。
   */
  checkCapability() {
    return api.ai
      .capabilities()
      .then((cap) => {
        const ready = !cap || cap.chatAvailable !== false;
        this.setData({
          aiReady: ready,
          aiNotice: ready ? '' : 'AI 导购暂时不可用：服务端尚未配置大模型，请稍后再来～',
        });
        if (!ready) {
          // 提示直接进消息流：用户一进页面就看见，且不会被后续滚动丢掉。
          // 不做浮层条是因为消息区高度是按导航栏与输入条 calc 出来的，
          // 插一个流内块会把底部输入条挤出屏幕。
          this.push({
            id: nextId(),
            role: 'assistant',
            content:
              '😿 我现在连不上「大脑」（服务端还没配置大模型），暂时帮不了你。' +
              '你可以先去逛逛商品，等配置好了再来找我～',
          });
        }
      })
      .catch(() => {
        this.setData({ aiReady: true, aiNotice: '' });
      });
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

  /**
   * 发一轮对话。
   *
   * @param textOverride 点了澄清选项时直接传选项文本，不必经过输入框
   *                     （选项是「点一下就发」的交互，走输入框会多一步、还容易被用户改坏）
   */
  send(textOverride) {
    const text =
      typeof textOverride === 'string' ? textOverride.trim() : (this.data.input || '').trim();
    if (!text || this.data.sending) return;
    if (!this.data.aiReady) {
      // 已经知道不可用就先拦住：让用户看到明确原因，而不是发出去再等一句「走神了」
      wx.showToast({ title: this.data.aiNotice || 'AI 导购暂不可用', icon: 'none' });
      return;
    }

    this.push({ id: nextId(), role: 'user', content: text });
    // 用户已经作答，历史里的选项不该再能点（点了会重复发同一条）——发送时统一清掉
    const cleared = this.data.messages.map((m) =>
      m.options ? Object.assign({}, m, { options: null }) : m
    );
    this.setData({ messages: cleared, input: '', sending: true });

    // history 只带最近 6 条**非错误**消息的纯文本（后端 @Size(max=6)）
    const history = cleared
      .filter((m) => !m.error)
      .slice(-HISTORY_LIMIT)
      .map((m) => ({ role: m.role, content: m.content }));

    return api.ai
      .chat(text, history, this._clarifiedSent)
      .then((res) => {
        // 服务端这一轮是「反问」而不是「回答」：把选项渲染成可点 chips，
        // 并记住「已澄清过」——避免它下一轮又问一次（一次对话最多问一次）
        const clarify = res.clarify;
        if (clarify && (clarify.options || []).length) {
          this._clarifiedSent = true;
        }
        this.push({
          id: nextId(),
          role: 'assistant',
          content: res.reply,
          recs: (res.recommendations || []).map((r) =>
            Object.assign({}, r, { priceText: api.fen2yuan(r.minPrice) })
          ),
          options: clarify && clarify.options ? clarify.options : null,
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

  /** 点澄清选项：直接把选项要发送的文本发出去（`send` 与 `label` 分离，见后端 DTO 注释） */
  tapOption(e) {
    const send = e.currentTarget.dataset.send;
    if (!send) return;
    this.send(String(send));
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

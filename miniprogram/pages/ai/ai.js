// AI 导购「多多」：对话 + 商品推荐卡片（A9：POST /api/duoduo/chat，免登录，429/503 友好降级）
//
// A14 修复要点：
//  * 消息加稳定 id（wx:key="index" 不是合法 key）；
//  * 本地只保留最近 N 条消息，避免长会话反复 setData 整段历史（性能与 256KB 上限风险）；
//  * 键盘高度自适配：输入条随键盘上移，且 iOS/Android 表现一致（adjust-position 关掉、自己定位）。
const api = require('../../utils/api');
const { getNavMetrics } = require('../../utils/nav');
const nav = require('../../utils/navigate');
const unread = require('../../utils/unread');

const MAX_KEEP = 60; // 本地最多保留的消息条数（超出丢最旧的）
const HISTORY_LIMIT = 6; // 每次请求带的历史条数（后端 @Size(max=6)）

/**
 * 会话持久化（2026-10-29 修复「切页后会话丢失」）。
 *
 * <p>小程序切页会销毁页面实例：`messages` 与 `_sessionId` 一起丢失，
 * 用户从「我的」再回到 AI 页就成了新会话。这里把最近 30 条消息与会话 ID
 * 存进本地缓存，2 小时过期（与服务端 Redis 会话 TTL 对齐）。
 *
 * <p>不用全局变量：小程序没有稳定的 module 级单例生命周期，
 * 页面实例销毁后变量不一定还在；本地缓存是唯一可靠的跨页存储。
 */
const STORAGE_KEY = 'duoduo.ai.session.v1';
const STORAGE_TTL_MS = 2 * 60 * 60 * 1000;
const STORAGE_KEEP = 30;

function loadStoredSession() {
  try {
    const raw = wx.getStorageSync(STORAGE_KEY);
    if (!raw || typeof raw !== 'object') return null;
    if (typeof raw.savedAt !== 'number' || Date.now() - raw.savedAt > STORAGE_TTL_MS) {
      wx.removeStorageSync(STORAGE_KEY);
      return null;
    }
    const messages = Array.isArray(raw.messages)
      ? raw.messages.map((m) => Object.assign({}, m, { streaming: false, confirm: null }))
      : [];
    return { sessionId: raw.sessionId || '', messages };
  } catch (e) {
    return null;
  }
}

function persistSession(page) {
  try {
    wx.setStorageSync(STORAGE_KEY, {
      sessionId: page._sessionId || '',
      messages: (page.data.messages || []).slice(-STORAGE_KEEP),
      savedAt: Date.now(),
    });
  } catch (e) {
    // 存储不可用：会话只留在内存，不影响当次对话
  }
}

let seq = 0;
function nextId() {
  seq += 1;
  return 'm' + Date.now().toString(36) + '-' + seq;
}

const NOTICE_CATEGORY = {
  ORDER: '订单',
  FRIEND: '好友',
  MERCHANT: '商家',
  SYSTEM: '系统',
  ANNOUNCEMENT: '公告',
};

/** 卡片/条目的稳定 key：WXML 的 wx:key 必须是条目自身属性，不能直接写循环下标 */
function keyOf(prefix, index, value) {
  return prefix + '-' + (value === undefined || value === null ? index : String(value));
}

function formatActions(actions) {
  if (!Array.isArray(actions)) return [];
  return actions.map((action, index) => ({
    _key: keyOf('a', index, action && action.label),
    label: (action && action.label) || '',
    miniPath: (action && action.miniPath) || '',
  }));
}

/**
 * 服务端卡片 → 小程序可渲染结构（2026-10-29 · 阶段四）。
 *
 * <p>格式化只做「展示文案」：金额分转元、时间 UTC 转本地、分类转中文。
 * 不改业务值、不补默认金额 —— 卡片是服务端装配的事实，前端只是换个写法。
 * WXML 里不能调函数，所以所有派生文案都在这里算好。
 */
function formatCards(cards) {
  if (!Array.isArray(cards)) return [];
  return cards
    .map((card, index) => {
      if (!card || typeof card !== 'object') return null;

      if (card.type === 'notification_list') {
        return {
          _key: keyOf('notice', index),
          type: 'notification_list',
          title: card.title || '通知',
          unreadCount: Number(card.unreadCount) || 0,
          items: (card.items || []).map((it, i) => ({
            _key: keyOf('n', i, it && it.id),
            title: (it && it.title) || '',
            summary: (it && it.summary) || '',
            meta: [
              NOTICE_CATEGORY[(it && it.category) || ''] || (it && it.category) || '',
              api.shortTime(it && it.createdAt),
            ]
              .filter(Boolean)
              .join(' · '),
            read: !!(it && it.read),
            miniPath: (it && it.miniPath) || '',
          })),
          actions: formatActions(card.actions),
        };
      }

      if (card.type === 'coupon_list') {
        return {
          _key: keyOf('coupon', index),
          type: 'coupon_list',
          title: card.title || '我的优惠券',
          items: (card.items || []).map((it, i) => {
            const percent = Number(it && it.discountPercent) || 0;
            const threshold = Number(it && it.thresholdAmount) || 0;
            const maxDiscount = Number(it && it.maxDiscountAmount) || 0;
            return {
              _key: keyOf('c', i, it && (it.templateId || it.code)),
              name: (it && it.name) || '',
              amountText:
                percent > 0
                  ? (percent / 10).toFixed(percent % 10 === 0 ? 0 : 1) + '折'
                  : '¥' + api.fen2yuan(it && it.discountAmount),
              thresholdText:
                threshold > 0 ? '满 ¥' + api.fen2yuan(threshold) + ' 可用' : '无门槛',
              meta: [
                it && it.typeLabel,
                maxDiscount > 0 ? '封顶 ¥' + api.fen2yuan(maxDiscount) : '',
                it && it.expireAt ? '有效期至 ' + api.shortTime(it.expireAt) : '',
              ]
                .filter(Boolean)
                .join(' · '),
              statusLabel: (it && it.statusLabel) || '',
              miniPath: (it && it.miniPath) || '',
            };
          }),
          actions: formatActions(card.actions),
        };
      }

      if (card.type === 'order_list') {
        return {
          _key: keyOf('order', index),
          type: 'order_list',
          title: card.title || '我的订单',
          items: (card.items || []).map((it, i) => ({
            _key: keyOf('o', i, it && it.orderNo),
            // 只露尾号：完整订单号对用户没有意义，还容易被截图带出去
            tail:
              it && it.orderNo && it.orderNo.length > 6
                ? it.orderNo.slice(-6)
                : (it && it.orderNo) || '',
            statusText: (it && it.statusText) || (it && it.status) || '',
            summary: (it && it.itemsSummary) || '（无商品明细）',
            amountText: '¥' + api.fen2yuan(it && it.amountCents),
            timeText: api.shortTime(it && it.createdAt),
            miniPath: (it && it.miniPath) || '',
          })),
          actions: formatActions(card.actions),
        };
      }

      if (card.type === 'action_grid') {
        return {
          _key: keyOf('grid', index),
          type: 'action_grid',
          title: card.title || '快捷入口',
          items: (card.items || []).map((it, i) => ({
            _key: keyOf('g', i, it && it.label),
            label: (it && it.label) || '',
            miniPath: (it && it.miniPath) || '',
          })),
        };
      }

      // 未知卡片类型：整条丢掉（新卡片由前端发版支持，不要渲染半张空壳）
      return null;
    })
    .filter(Boolean);
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
    // 快捷问题（第五十八轮 S3）：取自商品域**真实类目**，保证点下去有结果；
    // 类目取不到时服务端给空数组，前端就不展示这一块（不拿固定文案凑数 —— 点了是空结果更像敷衍）
    quickQuestions: [],
  },

  _keyboardHandler: null,
  /**
   * 本次会话是否已经澄清过一轮（第五十七轮）。
   *
   * <p>放在实例上而不是 data 里：它不参与渲染，只是要随请求带上；
   * 放进 data 会让每次 setData 都白搬一个布尔值，也容易被误读成「界面状态」。
   */
  _clarifiedSent: false,

  /**
   * 服务端会话 ID（2026-10-29 · 阶段四）。
   *
   * <p>小程序没有 SSE，拿不到 meta 事件里的 sessionId，只能从非流式响应取。
   * 存在实例上而不是 data 里：它不参与渲染，只随请求带回；放进 data 会让
   * 每次 setData 白搬一个字符串。
   */
  _sessionId: '',

  onLoad(query) {
    this.setData({ navTotal: getNavMetrics().navTotalHeight });
    // 先恢复上一次的会话（切页/重新进入不应该丢上下文）
    const stored = loadStoredSession();
    this._sessionId = (stored && stored.sessionId) || '';
    const initial = stored && stored.messages.length ? stored.messages : [this.greeting()];
    if (query && query.prefill) {
      // 从商品详情「问问 AI」进来：预填问题，同时保留问候语（否则首屏是空的）
      this.setData({ input: decodeURIComponent(query.prefill) });
    }
    this.setData({
      messages: initial,
      scrollInto: initial.length ? 'msg-' + initial[initial.length - 1].id : '',
    });
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
          // 探测失败时保持「可用」且不给快捷问题：接口抖动不该让首屏少一块内容又多一行解释
          quickQuestions: ready && cap && cap.quickQuestions ? cap.quickQuestions : [],
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
    // 用户已经作答，历史里的选项不该再能点（点了会重复发同一条）——发送时统一清掉。
    // 追问（followUps）同理：它们是**针对上一轮候选**推导出来的，话题一换就答非所问。
    const cleared = this.data.messages.map((m) =>
      m.options || m.followUps ? Object.assign({}, m, { options: null, followUps: null }) : m
    );
    this.setData({ messages: cleared, input: '', sending: true });

    // history 只带最近 6 条**非错误**消息的纯文本（后端 @Size(max=6)）
    const history = cleared
      .filter((m) => !m.error)
      .slice(-HISTORY_LIMIT)
      .map((m) => ({ role: m.role, content: m.content }));

    // 当前页面路径（服务端据此知道「用户刚从哪来」，并按白名单校验）
    let pagePath = '';
    try {
      const pages = getCurrentPages();
      const current = pages[pages.length - 1];
      pagePath = (current && current.route) || '';
      // 把页面参数也带上（如商品详情的 id）：服务端只保留路径部分，
      // 但「从哪个商品页来」这类上下文对意图判断有价值
      const options = (current && current.options) || {};
      const query = Object.keys(options)
        .filter((key) => options[key] !== undefined && options[key] !== null)
        .map((key) => encodeURIComponent(key) + '=' + encodeURIComponent(String(options[key])))
        .join('&');
      if (query) {
        pagePath += '?' + query;
      }
    } catch (e) {
      pagePath = '';
    }

    return api.ai
      .chat(text, history, this._clarifiedSent, pagePath, this._sessionId || undefined)
      .then((res) => {
        // 记下服务端会话 ID：下一轮带回，槽位/摘要/记忆才连得上
        if (res.sessionId) {
          this._sessionId = res.sessionId;
          // 立即落盘：用户可能在收到回复后马上切页
          persistSession(this);
        }

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
          // 阶段四：服务端装配的卡片（通知/券/订单/宫格），点击走 utils/navigate 统一跳转
          cards: formatCards(res.cards),
          // 游客轮次用尽 / 功能需登录：卡片下方给一个「去登录」入口
          needLogin: !!res.loginUrl,
          options: clarify && clarify.options ? clarify.options : null,
          // 相关追问：由本轮候选推导，候选为空时服务端不给（此时任何追问点下去都是空结果）
          followUps: res.followUps && res.followUps.length ? res.followUps : null,
        });

        // 红点联动（阶段四）：AI 展示并标记了已读 → 用服务端下发的补丁直接刷新
        // tabBar 角标。补丁是那次操作算出的权威值，再发一次 unread-summary 反而可能对不上。
        if (res.badgePatch) {
          unread.refresh();
        }
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

  /**
   * 点澄清选项 / 相关追问：直接把要发送的文本发出去（`send` 与 `label` 分离，见后端 DTO 注释）。
   *
   * <p>两类 chips 走同一个处理：语义完全一致 —— 都是「点一下就发这一句」。
   * 分两个方法只会多一处需要同步修改的地方，而它们的差别只在样式。
   */
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
    persistSession(this);
  },

  goDetail(e) {
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.id });
  },

  /**
   * 卡片 / 通知条目 / 宫格按钮点击（阶段四）。
   *
   * <p>miniPath 全部来自服务端装配（已过 RouteWhitelist）。这里统一交给
   * utils/navigate 处理「TabBar 页面走 switchTab、栈满降级」两件事 ——
   * 页面自己调 wx.navigateTo 会在通知中心（tabBar 页）上直接失败。
   *
   * <p>没有 miniPath 的条目（例如纯公告在网页端没有落地页）只展示不跳转，
   * 静默忽略，不弹「页面打不开」。
   */
  tapCard(e) {
    const path = e.currentTarget.dataset.path;
    if (!path) return;
    nav.navigate(path).catch(() => {});
  },

  /**
   * 「去登录」：小程序的登录页是 pages/login/login。
   * 服务端下发的 loginUrl 是 **Web 路径**（/login?next=…），不能直接给小程序用。
   */
  goLogin() {
    nav.navigate('pages/login/login').catch(() => {});
  },
});

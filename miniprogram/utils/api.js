// A14 · API 分域封装（页面只 import 本文件，按域调用）
// 形状约定：分页一律 { items, total }；ID 全字符串；金额单位为「分」（展示层用 fen2yuan 或 /100）。
//
// 令牌通道（A14 修复）：小程序没有 Cookie 语义，登录/刷新必须用**令牌版端点**
// （/api/auth/*/token，响应体含 accessToken/refreshToken）；浏览器版的
// /api/auth/login、/api/auth/wechat 只回扁平 UserView、令牌走 httpOnly Cookie，小程序用不了。
const req = require('./request');

// ── 登录态 ───────────────────────────────────────────────────────────────────

/** 把令牌对与用户一次性落盘（登录/刷新共用） */
function acceptSession(data) {
  if (!data || !data.accessToken) {
    // 端点用错或后端未部署令牌版端点时会走到这里：报明确原因，而不是静默变成「未登录」
    throw { code: 'NO_TOKEN', message: '登录响应缺少令牌，请确认后端已部署 /api/auth/*/token' };
  }
  req.setTokens(data.accessToken, data.refreshToken);
  return data.user;
}

/**
 * 取微信登录 code（一次性，5 分钟有效）。
 *
 * <p>绑定/解绑/换绑都要重新取 code —— 不能复用上一次的（微信侧用过即失效）。
 */
function getWxCode(timeoutMs) {
  const limit = timeoutMs || 10000;
  return new Promise((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      // wx.login 在个别环境（工具权限异常/网络切换）会出现「既不 success 也不 fail」，
      // 没有超时的话调用方的 loading/submitting 会被永久锁死 —— 表现就是"再点也没反应"。
      reject({ message: '微信授权超时，请重试' });
    }, limit);
    const done = (fn) => (arg) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      fn(arg);
    };
    wx.login({
      success: done((res) => {
        if (!res.code) {
          reject({ message: '微信授权未返回 code，请重试' });
          return;
        }
        resolve(res.code);
      }),
      fail: done(() => reject({ message: '微信授权失败，请重试' })),
    });
  });
}

function wxLogin() {
  return new Promise((resolve, reject) => {
    wx.login({
      success(res) {
        if (!res.code) {
          reject({ message: 'wx.login 未返回 code，请重试' });
          return;
        }
        req
          .post('/api/auth/wechat/token', { code: res.code })
          .then((data) => resolve(acceptSession(data)))
          .catch(reject);
      },
      fail() {
        reject({ message: 'wx.login 调用失败，请重试' });
      },
    });
  });
}

/**
 * 绑定当前微信到已登录账号（A13 端点 /api/auth/wechat/bind，需登录态）。
 *
 * 场景：微信一键登录返回 422「未绑定」时，引导用户用手机号登录，登录成功后立刻
 * 用**新拿的 code**绑定当前微信 —— code 是一次性的，不能复用失败那次。
 */
function wechatBind(code) {
  return req.post('/api/auth/wechat/bind', { code });
}

/** 解绑当前账号的微信（需登录；code 用于证明"操作者持有该微信"） */
function wechatUnbind(code) {
  return req.post('/api/auth/wechat/unbind', { code });
}

/** 换绑：把当前账号的微信换成 code 对应的新微信 */
function wechatRebind(code) {
  return req.post('/api/auth/wechat/rebind', { code });
}

function phoneLogin(phone, password) {
  return req.post('/api/auth/login/token', { phone, password }).then((data) => acceptSession(data));
}

/**
 * 注册并直接登录（小程序内自助开户）。
 *
 * <p>后端 /api/auth/register 的响应体是扁平 UserView（浏览器契约，令牌走 Cookie），
 * 因此这里「注册 + 换令牌」两步走：先建号，再用密码登录拿令牌对 —— 不新增后端端点，
 * 也不改动既有契约。
 */
function register(phone, password, nickname) {
  return req
    .post('/api/auth/register', { phone, password, nickname })
    .then(() => phoneLogin(phone, password));
}

/** 静默探测登录态：无令牌直接判定未登录（不发请求） */
function ensureSession() {
  if (!req.getToken()) return Promise.reject({ needLogin: true });
  return req.get('/api/users/me', { silent: true });
}

/** 用 refreshToken 换新的令牌对（访问令牌过期时调用，失败即需要重新登录） */
function refreshSession() {
  const refreshToken = req.getRefreshToken();
  if (!refreshToken) return Promise.reject({ needLogin: true });
  return req.post('/api/auth/refresh/token', { refreshToken }, { silent: true }).then((data) => acceptSession(data));
}

/**
 * 实名认证（第五十二轮热修新增）：提交真实姓名 + 身份证号。
 *
 * <p>发布闲置（POST /api/listings）硬性要求实名，此前小程序没有任何实名入口，
 * 用户发布必然 403 且无处可去。成功后**必须 refreshSession() 换新令牌**：
 * realNameVerified 写在访问令牌的 claim 里，旧令牌不会自动变。
 */
function submitRealName(realName, idCard) {
  return req.post('/api/users/me/realname', { realName, idCard });
}

// ── 商品域（公开） ──────────────────────────────────────────────────────────
const product = {
  list(params) {
    // params: { page, pageSize, keyword, categoryId }
    const qs = Object.keys(params || {})
      .filter((k) => params[k] !== undefined && params[k] !== '')
      .map((k) => k + '=' + encodeURIComponent(params[k]))
      .join('&');
    return req.get('/api/products' + (qs ? '?' + qs : ''));
  },
  detail(id) {
    return req.get('/api/products/' + id);
  },
  categories() {
    return req.get('/api/categories');
  },
  reviews(spuId, page, pageSize) {
    return req.get(
      '/api/reviews/product/' + spuId + '?page=' + (page || 1) + '&pageSize=' + (pageSize || 5)
    );
  },
  reviewSummary(spuId) {
    return req.get('/api/reviews/product/' + spuId + '/summary');
  },
  home() {
    return req.get('/api/home');
  },
  /** 搜索纠错建议：给不出建议时后端返回空对象（前端不展示提示） */
  suggest(keyword) {
    return req.get('/api/products/search/suggestion?keyword=' + encodeURIComponent(keyword), {
      silent: true,
      timeout: 8000,
    });
  },
};

// ── 交易域 ───────────────────────────────────────────────────────────────────
const cart = {
  /** 购物车视图：含 items / selectedAmount / selectedCount / totalQuantity / invalidReasons */
  view() {
    return req.get('/api/cart');
  },
  count() {
    return req.get('/api/cart/count');
  },
  add(skuId, quantity) {
    return req.post('/api/cart/items', { skuId, quantity: quantity || 1 });
  },
  updateQuantity(skuId, quantity) {
    return req.patch('/api/cart/items/' + skuId, { quantity });
  },
  /** 勾选 / 取消勾选（下单只结已勾选条目，因此这是结算口径的一部分） */
  setSelection(skuId, selected) {
    return req.patch('/api/cart/items/' + skuId + '/selection', { selected: !!selected });
  },
  remove(skuId) {
    return req.del('/api/cart/items/' + skuId);
  },
};

const order = {
  /**
   * 我的订单列表。
   * @param orderType 订单类型（NORMAL / SECKILL / C2C）—— **由服务端筛**。
   *   分页下前端自己过滤只会得到「当前页里的那几条」，记录落在下一页时表现为「没有」，最难排查。
   */
  list(page, status, pageSize, orderType) {
    const s = status && status !== 'ALL' ? '&status=' + status : '';
    const t = orderType ? '&orderType=' + encodeURIComponent(orderType) : '';
    return req.get('/api/orders?page=' + (page || 1) + '&pageSize=' + (pageSize || 20) + s + t);
  },
  detail(orderNo) {
    return req.get('/api/orders/' + orderNo);
  },
  /**
   * 下单（A8 地址簿语义 + A5 优惠券 + 第四十一轮直购）。
   *
   * <p>购物车结算：只传 addressId（+ 可选 couponCode），结算范围是购物车中「已勾选」的条目。
   * <p>立即购买（direct）：额外传 { skuId, quantity }，服务端按该 SKU 直购——
   * 不读购物车、下单成功后也不清购物车；其余语义（库存冻结 / 券 / 幂等）两条路径完全一致。
   * <p>折扣金额一律由服务端锁券时计算，前端只传券码不传金额。
   */
  place(addressId, couponCode, direct) {
    const payload = {
      addressId,
      idempotentKey: 'mp-' + Date.now() + '-' + Math.floor(Math.random() * 1e6),
    };
    if (couponCode) payload.couponCode = couponCode;
    if (direct && direct.skuId) {
      payload.buySkuId = direct.skuId;
      payload.buyQuantity = direct.quantity || 1;
    }
    return req.post('/api/orders', payload);
  },
  /**
   * 发起支付（沙箱渠道）。
   *
   * <p>payNo/idempotentKey 都是后端 @NotBlank 的必填项：幂等键保证「重复点支付只入账一次」，
   * 缺了会直接 400 —— 这是 A14 排查发现的缺陷之一。
   */
  pay(orderNo) {
    return req.post('/api/orders/' + orderNo + '/pay', {
      channel: 'sandbox',
      idempotentKey: 'pay-' + orderNo + '-' + Date.now(),
    });
  },
  /** 物流轨迹（履约域，需登录） */
  logistics(orderNo) {
    return req.get('/api/logistics/orders/' + orderNo + '/tracks', { silent: true });
  },
  payStatus(payNo) {
    return req.get('/api/payments/' + payNo, { silent: true });
  },
  /**
   * 沙箱收银台「确认支付」（A15 第三十九轮）。
   *
   * <p>公开端点（白名单）：现实中扫码设备没有买家登录态，金额一律取库内值，
   * 与服务端渲染收银台页（GET /sandbox/pay/{payNo}）的支付按钮走同一入账路径。
   * 小程序内直接调它，替代「复制 codeUrl 到浏览器」的旧体验。
   */
  sandboxConfirm(payNo) {
    return req.post('/api/payments/sandbox/' + payNo);
  },
  /** 取消订单（仅待付款可取消；请求体可省略） */
  cancel(orderNo, reason) {
    return req.post('/api/orders/' + orderNo + '/cancel', { reason: reason || '用户取消' });
  },
  confirm(orderNo) {
    return req.post('/api/orders/' + orderNo + '/confirm');
  },

  /**
   * 结算预览（只读）。
   *
   * <p>为什么不复用 place()：preview 是**纯展示**动作，不该带 idempotentKey
   * （会让人误以为它落库），也不该因为「没填地址」就算不出价格 ——
   * 地址校验属于提交阶段。服务端为此单开了 POST /api/orders/preview。
   */
  preview(couponCode, direct) {
    const payload = {};
    if (couponCode) payload.couponCode = couponCode;
    if (direct && direct.skuId) {
      payload.buySkuId = direct.skuId;
      payload.buyQuantity = direct.quantity || 1;
    }
    return req.post('/api/orders/preview', payload);
  },
};

/**
 * C2C 闲置（跳蚤市场）。
 *
 * <p>路径划分是服务端的安全设计，前端照契约调即可 ——
 * 列表是 `/search`（两段，免登），发布是裸 `/api/listings`（单段，需登录），
 * 我的发布是 `/mine/list`（三段，需登录）。三段与单段路径都不是「写错了」，
 * 而是为了把写端点挡在网关免登白名单之外（白名单按路径匹配、不区分方法）。
 */
const listing = {
  /** 闲置列表（免登）。params: { keyword, city, species, conditionLevel, minPrice, maxPrice, sort, page, pageSize } */
  list(params) {
    const qs = Object.keys(params || {})
      .filter((k) => params[k] !== undefined && params[k] !== null && params[k] !== '')
      .map((k) => k + '=' + encodeURIComponent(params[k]))
      .join('&');
    return req.get('/api/listings/search' + (qs ? '?' + qs : ''));
  },
  /** 闲置详情（免登）。未过审的内容服务端返回 404（不暴露存在性） */
  detail(id) {
    return req.get('/api/listings/' + id);
  },
  /**
   * 发布闲置（需登录 + 需实名）。
   *
   * <p>请求体刻意不含 sellerId / status / auditStatus：归属与审核态一律由服务端决定，
   * 前端传了也会被忽略 —— 否则发布者可以自己把审核状态写成「已通过」。
   */
  publish(data) {
    return req.post('/api/listings', {
      title: data.title,
      description: data.description,
      categoryId: data.categoryId,
      price: data.price,
      originalPrice: data.originalPrice,
      conditionLevel: data.conditionLevel,
      city: data.city,
      images: data.images || [],
      species: data.species,
      breed: data.breed,
    });
  },
  /**
   * 我的发布（需登录）。含未过审 / 被驳回的条目，带 auditReason 供用户改稿。
   * status（在售/下架/已售）与 auditStatus（待审/通过/驳回）是两个正交的过滤维度：
   * 「审核中」「审核失败」分类标签走 auditStatus —— 被驳回的条目不是「在售」。
   */
  mine(params) {
    const p = params || {};
    let s = '';
    if (p.status && p.status !== 'ALL') s += '&status=' + p.status;
    if (p.auditStatus) s += '&auditStatus=' + p.auditStatus;
    return req.get('/api/listings/mine/list?page=' + (p.page || 1) + '&pageSize=' + (p.pageSize || 20) + s);
  },
  /**
   * 卖家视角详情（需登录，第五十三轮）：编辑重提的回填数据源。
   * 公开详情对未过审内容 404，被驳回的条目只能从这里拿回自己的内容。
   */
  ownerDetail(id) {
    return req.get('/api/listings/mine/' + id);
  },
  /**
   * 重新提审（需登录 + 需实名，第五十三轮）：仅被驳回（REJECTED）的闲置可调。
   * 请求体与发布一致；服务端把内容更新后审核态重置回 PENDING。
   */
  resubmit(id, data) {
    return req.post('/api/listings/' + id + '/resubmit', {
      title: data.title,
      description: data.description,
      categoryId: data.categoryId,
      price: data.price,
      originalPrice: data.originalPrice,
      conditionLevel: data.conditionLevel,
      city: data.city,
      images: data.images || [],
      species: data.species,
      breed: data.breed,
    });
  },
  /** 上架 / 下架（需登录，仅本人）。action: 'ON_SALE' | 'OFF_SHELF' */
  changeStatus(id, action) {
    return req.post('/api/listings/' + id + '/status', { action });
  },
};

const address = {
  list() {
    return req.get('/api/addresses');
  },
  default() {
    return req.get('/api/addresses/default');
  },
  create(payload) {
    return req.post('/api/addresses', payload);
  },
  update(id, payload) {
    return req.put('/api/addresses/' + id, payload);
  },
  setDefault(id) {
    return req.post('/api/addresses/' + id + '/default');
  },
  remove(id) {
    return req.del('/api/addresses/' + id);
  },
};

// ── 售后（A3，履约域） ──────────────────────────────────────────────────────
const aftersale = {
  /** 申请售后：type 为 REFUND_ONLY（仅退款）或 RETURN_REFUND（退货退款）；images 为 http(s) 直链数组（可空） */
  create(orderNo, type, reason, images) {
    const payload = { orderNo, type, reason };
    if (images && images.length > 0) payload.images = images;
    return req.post('/api/aftersales', payload);
  },
  /** 我的售后单（分页） */
  mine(page, pageSize) {
    return req.get('/api/aftersales/mine?page=' + (page || 1) + '&pageSize=' + (pageSize || 20));
  },
  detail(id) {
    return req.get('/api/aftersales/' + id);
  },
};

// ── 营销域（A5） ─────────────────────────────────────────────────────────────
const coupon = {
  templates() {
    return req.get('/api/coupons/templates');
  },
  claim(templateId) {
    return req.post('/api/coupons/templates/' + templateId + '/claim');
  },
  mine(status) {
    return req.get('/api/coupons/mine' + (status ? '?status=' + status : ''));
  },
  /**
   * 结算可用券（A5，第三十九轮）：amount=已勾选商品金额（分）。
   * 门槛/有效期/锁定状态的判定全在服务端 —— 前端自算迟早与锁券时不一致。
   * 返回 { amount, coupons[{code,name,thresholdAmount,discountAmount,usable,reason}],
   *        bestCouponCode, bestDiscountAmount }，bestCouponCode 是服务端推荐的最优券。
   */
  usable(amount) {
    return req.get('/api/coupons/mine/usable?amount=' + (amount || 0));
  },
};

// ── AI 导购（A9，免登录可用，限流 429 / 降级 503 友好提示） ──────────────────
const ai = {
  /**
   * 一轮对话。
   *
   * clarified（第五十七轮）：**本次之前是否已经澄清过一轮**。
   * 服务端没有会话状态，只能由前端把这个事实带上去 —— 它的唯一用途是保证
   * 「一次对话最多反问一次」，避免 AI 一直追问把导购变成填问卷。
   * 响应里的 `clarify` 非空时，说明这一轮是反问而不是回答，前端要把 options 渲染成可点选项。
   */
  chat(message, history, clarified) {
    return req.post(
      '/api/duoduo/chat',
      { message, history, clarified: !!clarified },
      { timeout: 20000 }
    );
  },
  capabilities() {
    return req.get('/api/duoduo/capabilities', { silent: true });
  },
};

// ── 秒杀（A14 新增，C 端） ───────────────────────────────────────────────────
const seckill = {
  /** 活动列表：phase 由服务端按当前时间计算（ONGOING / UPCOMING / ENDED） */
  activities() {
    return req.get('/api/seckill/activities');
  },
  detail(id) {
    return req.get('/api/seckill/activities/' + id);
  },
  /** 秒杀下单；couponCode 可选（与秒杀价叠加，门槛按秒杀价判定） */
  place(activityId, skuId, addressId, couponCode) {
    const payload = {
      activityId,
      skuId,
      addressId,
      idempotentKey: 'sk-' + Date.now() + '-' + Math.floor(Math.random() * 1e6),
    };
    if (couponCode) payload.couponCode = couponCode;
    return req.post('/api/seckill/orders', payload);
  },
};

// ── 分享圈（第四十三轮，履约域）：发布需登录，信息流 / 详情 / 话题 / 评论列表公开 ──
const share = {
  /** 发布分享：spuId + 用户自撰评价内容（必填，≤500 字）+ 话题（可选，最多 2 个） */
  publish(spuId, content, topics) {
    return req.post('/api/shares', { spuId: String(spuId), content, topics: topics || [] });
  },
  /**
   * 信息流（公开）：按发布时间倒序，{ items, total }；登录时带 liked/favorited/mine 标记。
   *
   * scope（第五十六轮）：'RECOMMEND'（默认，全站）或 'FOLLOWING'（只看我关注的人）。
   * FOLLOWING 需要登录 —— 服务端会拒（401），所以调用方只在已登录时传它。
   */
  feed(page, pageSize, scope) {
    let url = '/api/shares/feed?page=' + (page || 1) + '&pageSize=' + (pageSize || 10);
    // RECOMMEND 是**服务端的默认值**，不显式传：URL 只有一种规范形式，
    // 免得同一份内容出现两个等价 URL（浏览器/网关/CDN 缓存会当成两份）。
    if (scope && scope !== 'RECOMMEND') url += '&scope=' + scope;
    return req.get(url);
  },
  /** 分享详情（公开）：分享落地页数据源 */
  detail(id) {
    return req.get('/api/shares/' + id, { silent: true });
  },
  /** 点赞 / 取消点赞（toggle，需登录）→ { active, count } */
  like(id) {
    return req.post('/api/shares/' + id + '/like');
  },
  /** 收藏 / 取消收藏（toggle，需登录）→ { active, count } */
  favorite(id) {
    return req.post('/api/shares/' + id + '/favorite');
  },
  /**
   * 转发分享到分享圈（第四十六轮，需登录）→ 转发单视图。
   *
   * <p>转发是**写操作**：服务端真的插一条新分享行（userId = 我，parentId = 原帖）。
   * 返回体里 `id` 是转发单 ID —— 后续「分享给好友」的落点必须用它，
   * 否则好友点开看到的是原发帖人的页面。content 为转发附言，可空。
   */
  repost(id, content) {
    return req.post('/api/shares/' + id + '/repost', { content: content || '' });
  },

  // ---------------- 第五十六轮 ----------------

  /**
   * 编辑自己的分享：只改文字与话题。
   *
   * topics 的三态必须区分（服务端按此判断，混了就永远删不掉话题）：
   *   undefined → 不改话题；null → 不改话题；[] → 清空话题；[...] → 替换
   * 故这里显式把 undefined 归一成 null 再上行。
   */
  edit(id, content, topics) {
    return req.post('/api/shares/' + id + '/edit', {
      content: content,
      topics: topics === undefined ? null : topics,
    });
  },
  /** 删除自己的分享（软删；别人转发过它的转发单仍在，引用块显示「原内容已被作者删除」） */
  remove(id) {
    return req.post('/api/shares/' + id + '/delete');
  },
  /** 我的分享（需登录）：管理页数据源，编辑 / 删除入口挂在这里 */
  mine(page, pageSize) {
    return req.get(
      '/api/shares/mine/list?page=' + (page || 1) + '&pageSize=' + (pageSize || 10)
    );
  },
  /** 举报分享（需登录）：理由必填，重复举报 409 */
  report(id, reason) {
    return req.post('/api/shares/' + id + '/report', { reason: reason });
  },

  /**
   * 评论列表（公开）。
   *
   * parentId 省略 = 一级评论分页（每条带 replyCount）；传值 = 该评论下的回复分页。
   * 走查询参数而不是 /{id}/comments：后者会让白名单不得不放行
   * `/*​/comments`，连带把「发表评论」也放成匿名可调（详见后端控制器注释）。
   */
  comments(shareId, page, pageSize, parentId) {
    let url =
      '/api/shares/comments?shareId=' +
      shareId +
      '&page=' +
      (page || 1) +
      '&pageSize=' +
      (pageSize || 20);
    if (parentId) url += '&parentId=' + parentId;
    return req.get(url);
  },
  /** 发表评论 / 回复（需登录）：parentCommentId 省略 = 一级评论 */
  comment(shareId, content, parentCommentId) {
    const body = { content: content };
    if (parentCommentId) body.parentCommentId = parentCommentId;
    return req.post('/api/shares/' + shareId + '/comments', body);
  },
  /** 评论点赞 / 取消（toggle，需登录）→ { active, count } */
  likeComment(commentId) {
    return req.post('/api/shares/comments/' + commentId + '/like');
  },
  /** 举报评论（需登录） */
  reportComment(commentId, reason) {
    return req.post('/api/shares/comments/' + commentId + '/report', { reason: reason });
  },

  /** 热门话题榜（公开）：发布页的「大家都在聊」 */
  hotTopics(limit) {
    return req.get('/api/shares/topics?limit=' + (limit || 10));
  },
  /** 话题聚合页（公开）：按话题名查分享 */
  topicFeed(name, page, pageSize) {
    return req.get(
      '/api/shares/topics/' +
        encodeURIComponent(name) +
        '?page=' +
        (page || 1) +
        '&pageSize=' +
        (pageSize || 10)
    );
  },
};

// ── 社交域（第四十七轮）：关注 / 私信（duoduo-social，需登录） ────────────────
const social = {
  follow: {
    /** 关注（重复关注幂等）→ { following, followedBy, mutual } */
    follow(userId) {
      return req.post('/api/socials/follows/' + userId);
    },
    /** 取关（幂等）→ 同上 */
    unfollow(userId) {
      return req.del('/api/socials/follows/' + userId);
    },
    /** 我与 TA 的关注关系（关注按钮状态源） */
    state(userId) {
      return req.get('/api/socials/follows/' + userId + '/state');
    },
    /** 他人主页聚合：资料 + 计数 + 关系，一次拿齐 */
    profile(userId) {
      return req.get('/api/socials/users/' + userId + '/profile');
    },
    /** 我关注的人 */
    following(page, pageSize) {
      return req.get(
        '/api/socials/follows/following?page=' + (page || 1) + '&pageSize=' + (pageSize || 20)
      );
    },
    /** 我的粉丝 */
    followers(page, pageSize) {
      return req.get(
        '/api/socials/follows/followers?page=' + (page || 1) + '&pageSize=' + (pageSize || 20)
      );
    },
  },
  message: {
    /**
     * 发消息。bizType 缺省 TEXT；bizType=ASK（商品咨询）时服务端把收件人改成
     * 商品归属卖家 —— 前端传的 receiverId 只是契约占位，真正收件人以后端事实为准。
     * bizType=LISTING（闲置咨询，第五十三轮）同款纪律：服务端按闲置归属定人。
     *
     * ★ 所有 id 一律**按字符串原样上行，禁止 Number() 强转**（第五十三轮热修）：
     *   雪花 id 是 19 位，JS Number 只有 53 位有效精度 ——
     *   `Number('710000000000000001') === 710000000000000000`，
     *   服务端按这个值查库必然查无此人 → 404「用户不存在」→ 前端文案化成
     *   「内容不存在或已删除」，表现为「消息怎么都发不出去」（官方客服 id 18 位、
     *   闲置 id 18 位，全部踩中）。后端字段是 Long，Jackson 对数字串自动转换，无需前端转数值。
     */
    send(receiverId, content, bizType, bizId, contextProductId, contextListingId) {
      const payload = { content: content || '' };
      if (receiverId) payload.receiverId = String(receiverId);
      if (bizType) payload.bizType = bizType;
      if (bizId) payload.bizId = String(bizId);
      // 商品咨询会话上下文（第四十九轮）：TEXT 带 productId 落回该商品的独立会话
      if (contextProductId) payload.productId = String(contextProductId);
      // 闲置咨询会话上下文（第五十三轮）：TEXT 带 listingId 落回该闲置的独立会话
      if (contextListingId) payload.listingId = String(contextListingId);
      return req.post('/api/socials/messages', payload);
    },
    /** 会话列表：每会话最新一条 + 未读数 + canSend（服务端算好，前端只消费） */
    conversations(page, pageSize) {
      return req.get(
        '/api/socials/messages/conversations?page=' + (page || 1) + '&pageSize=' + (pageSize || 20)
      );
    },
    /**
     * 与某人的双向消息（时间正序，取最新一页）+ 对方资料与可发状态。
     * productId 非空读商品咨询会话；listingId 非空读闲置咨询会话（第五十三轮）。
     */
    thread(peerId, page, pageSize, productId, listingId) {
      return req.get(
        '/api/socials/messages/conversations/' +
          peerId +
          '?page=' +
          (page || 1) +
          '&pageSize=' +
          (pageSize || 20) +
          (productId ? '&productId=' + productId : '') +
          (listingId ? '&listingId=' + listingId : '')
      );
    },
    /** 打开会话后标已读（幂等）；productId / listingId 语义同上 */
    markRead(peerId, productId, listingId) {
      return req.post(
        '/api/socials/messages/conversations/' + peerId + '/read' +
          (productId ? '?productId=' + productId : '') +
          (listingId ? (productId ? '&' : '?') + 'listingId=' + listingId : '')
      );
    },
  },
  /** 通知中心（第五十轮）：定向通知 + 全站公告合并视图，前端无感两种存储 */
  notice: {
    /** 通知列表；category 可选（SYSTEM / ANNOUNCEMENT / FRIEND / MERCHANT / ORDER，第五十五轮加） */
    list(page, pageSize, category) {
      return req.get(
        '/api/socials/notices?page=' +
          (page || 1) +
          '&pageSize=' +
          (pageSize || 20) +
          (category ? '&category=' + category : '')
      );
    },
    unreadCount() {
      return req.get('/api/socials/notices/unread-count');
    },
    /**
     * 合并未读（第五十五轮）：{ notice, message, total } —— tabBar 角标的唯一数据源。
     * 三个值同源同刻，前端不必先拉通知未读、再拉一页会话自己求和（那种算法在会话
     * 超过一页时会偏小，也会因为两次请求的时序差出现「总数 ≠ 明细」）。
     */
    unreadSummary() {
      return req.get('/api/socials/notices/unread-summary');
    },
    markRead(noticeId) {
      return req.post('/api/socials/notices/' + noticeId + '/read');
    },
    readAll() {
      return req.post('/api/socials/notices/read-all');
    },
  },
};

// ── 金额工具（分 → 元字符串，避免浮点误差） ─────────────────────────────────
function fen2yuan(fen) {
  const n = Number(fen) || 0;
  return (n / 100).toFixed(n % 100 === 0 ? 0 : 2);
}

// ── 时间工具 ────────────────────────────────────────────────────────────────
/**
 * ISO-8601 串 → MM-DD HH:mm（**设备本地时区**）。
 *
 * 第五十三轮热修：旧实现是 `replace('T',' ').replace('Z','')` 后裸切片 —— 把后端
 * 明确声明的时区标记（`Z`）主动删掉再按字面展示，于是 UTC 挂钟时间被当成北京时间显示，
 * **全站时间统一少 8 小时**（订单/优惠券/秒杀/分享圈/通知/聊天共 13 个页面同源）。
 *
 * 后端契约（服务端写入与下发一致，均为 UTC）：
 *   LocalDateTime.now(ZoneOffset.UTC) → time.toInstant(ZoneOffset.UTC).toString() → 「…Z」
 * 故本函数按「UTC 解析 → 转设备本地展示」处理；无时区标记的串按同一契约视为 UTC。
 * 纯日期（yyyy-mm-dd）无时区语义，原样返回；解析失败也原样返回，绝不显示 undefined。
 *
 * 注：手写解析而非 `new Date(str)` —— iOS 的 JavaScriptCore 对超过 3 位小数秒的
 * ISO 串（后端下发纳秒精度）会解析成 Invalid Date，逐段取值可绕开该兼容性差异。
 */
function shortTime(iso) {
  if (!iso) return '';
  const raw = String(iso).trim();
  const m = raw.match(
    /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?$/
  );
  if (!m) return raw; // 纯日期 / 非时间串：无时区语义，原样返回

  const [y, mo, d, h, mi, s] = [+m[1], +m[2], +m[3], +m[4], +m[5], +(m[6] || 0)];
  const frac = m[7] ? +((m[7] + '000').slice(0, 3)) : 0; // 纳秒 → 毫秒
  const zone = m[8];

  let offsetMin = 0; // 串自带偏移（分钟，东八区为 +480）
  if (zone && zone !== 'Z') {
    const sign = zone[0] === '-' ? -1 : 1;
    const z = zone.slice(1).replace(':', '');
    offsetMin = sign * (+z.slice(0, 2) * 60 + +z.slice(2));
  }

  // 先按 UTC 造出绝对时刻，再减掉串自带偏移；无标记时 offsetMin=0（按合约即 UTC）
  const epoch = Date.UTC(y, mo - 1, d, h, mi, s, frac) - offsetMin * 60000;
  const dt = new Date(epoch);
  if (isNaN(dt.getTime())) return raw;

  const p2 = (n) => ('0' + n).slice(-2);
  // getMonth/getDate/getHours 取的是设备本地时区 → 北京时间自动 +8
  return p2(dt.getMonth() + 1) + '-' + p2(dt.getDate()) + ' ' + p2(dt.getHours()) + ':' + p2(dt.getMinutes());
}

module.exports = {
  get: req.get,
  post: req.post,
  put: req.put,
  patch: req.patch,
  del: req.del,
  upload: req.upload,
  getToken: req.getToken,
  setToken: req.setToken,
  setTokens: req.setTokens,
  clearToken: req.clearToken,
  clearSession: req.clearSession,
  openLoginPage: req.openLoginPage,
  onUnauthorized: req.onUnauthorized,
  toastError: req.toastError,
  wxLogin,
  getWxCode,
  wechatBind,
  wechatUnbind,
  wechatRebind,
  phoneLogin,
  register,
  ensureSession,
  refreshSession,
  submitRealName,
  product,
  cart,
  order,
  listing,
  aftersale,
  address,
  coupon,
  ai,
  seckill,
  share,
  social,
  fen2yuan,
  shortTime,
};

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
  list(page, status, pageSize) {
    const s = status && status !== 'ALL' ? '&status=' + status : '';
    return req.get('/api/orders?page=' + (page || 1) + '&pageSize=' + (pageSize || 20) + s);
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
  chat(message, history) {
    return req.post('/api/duoduo/chat', { message, history }, { timeout: 20000 });
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
  place(activityId, skuId, addressId) {
    return req.post('/api/seckill/orders', {
      activityId,
      skuId,
      addressId,
      idempotentKey: 'sk-' + Date.now() + '-' + Math.floor(Math.random() * 1e6),
    });
  },
};

// ── 金额工具（分 → 元字符串，避免浮点误差） ─────────────────────────────────
function fen2yuan(fen) {
  const n = Number(fen) || 0;
  return (n / 100).toFixed(n % 100 === 0 ? 0 : 2);
}

// ── 时间工具（后端下发 ISO-8601 UTC 串，直接展示不友好） ─────────────────────
/** ISO 串 → MM-DD HH:mm（本地时区）；无法解析时原样返回，绝不显示 undefined */
function shortTime(iso) {
  if (!iso) return '';
  const text = String(iso).replace('T', ' ').replace('Z', '');
  const m = text.match(/^(\d{4})-(\d{2})-(\d{2})[ ]?(\d{2}):(\d{2})/);
  if (!m) return String(iso);
  return m[2] + '-' + m[3] + ' ' + m[4] + ':' + m[5];
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
  product,
  cart,
  order,
  aftersale,
  address,
  coupon,
  ai,
  seckill,
  fen2yuan,
  shortTime,
};

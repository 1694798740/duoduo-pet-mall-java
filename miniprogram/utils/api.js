// A14 · API 分域封装（页面只 import 本文件，按域调用）
// 形状约定：分页一律 { items, total }；ID 全字符串；金额单位为「分」（展示层用 fen2yuan 转换）。
const req = require('./request');

// ── 登录态 ───────────────────────────────────────────────────────────────────
function wxLogin() {
  return new Promise((resolve, reject) => {
    wx.login({
      success(res) {
        if (!res.code) {
          reject({ message: 'wx.login 未返回 code' });
          return;
        }
        req
          .post('/api/auth/wechat', { code: res.code })
          .then((data) => {
            req.setToken(data.accessToken);
            resolve(data.user);
          })
          .catch(reject);
      },
      fail() {
        reject({ message: 'wx.login 调用失败' });
      },
    });
  });
}

function phoneLogin(phone, password) {
  return req.post('/api/auth/login', { phone, password }).then((data) => {
    req.setToken(data.accessToken);
    return data.user;
  });
}

function ensureSession() {
  if (!req.getToken()) return Promise.reject({ needLogin: true });
  return req.get('/api/users/me', { silent: true });
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
  reviews(spuId, page) {
    return req.get('/api/reviews/product/' + spuId + '?page=' + (page || 1) + '&pageSize=5');
  },
  reviewSummary(spuId) {
    return req.get('/api/reviews/product/' + spuId + '/summary');
  },
  home() {
    return req.get('/api/home');
  },
};

// ── 交易域 ───────────────────────────────────────────────────────────────────
const cart = {
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
  remove(skuId) {
    return req.del('/api/cart/items/' + skuId);
  },
};

const order = {
  list(page, status) {
    const s = status && status !== 'ALL' ? '&status=' + status : '';
    return req.get('/api/orders?page=' + (page || 1) + '&pageSize=20' + s);
  },
  detail(orderNo) {
    return req.get('/api/orders/' + orderNo);
  },
  place(addressId) {
    return req.post('/api/orders', {
      addressId,
      idempotentKey: 'mp-' + Date.now() + '-' + Math.floor(Math.random() * 1e6),
    });
  },
  pay(orderNo) {
    return req.post('/api/orders/' + orderNo + '/pay', { channel: 'sandbox' });
  },
  payStatus(payNo) {
    return req.get('/api/payments/' + payNo, { silent: true });
  },
  confirm(orderNo) {
    return req.post('/api/orders/' + orderNo + '/confirm');
  },
};

const address = {
  default() {
    return req.get('/api/addresses/default');
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

module.exports = {
  get: req.get,
  post: req.post,
  patch: req.patch,
  del: req.del,
  getToken: req.getToken,
  setToken: req.setToken,
  clearToken: req.clearToken,
  toastError: req.toastError,
  wxLogin,
  phoneLogin,
  ensureSession,
  product,
  cart,
  order,
  address,
  coupon,
  ai,
  seckill,
  fen2yuan,
};

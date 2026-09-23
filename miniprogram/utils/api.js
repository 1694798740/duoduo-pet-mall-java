// A13 · 统一请求封装
// 约定与 Web 端 api/client.ts 保持一致：
//  * 响应包络 { code, message, data, requestId }，code === 'SUCCESS' 才算成功；
//  * 令牌放 Authorization: Bearer（存 storage，**不进 URL**）；
//  * 401 → 清登录态并跳登录页；4xx 不重试（错误文案透出 message）；5xx 提示「服务暂时不可用」。
//  * ID 一律字符串（T-39 后端已全局字符串化，前端不做数值运算，只拼 URL / 相等比较）。
const { BASE_URL } = require('../config');

const TOKEN_KEY = 'duoduo_access_token';

function getToken() {
  return wx.getStorageSync(TOKEN_KEY) || '';
}

function setToken(token) {
  wx.setStorageSync(TOKEN_KEY, token || '');
}

function clearToken() {
  wx.removeStorageSync(TOKEN_KEY);
}

function friendlyMessage(code, message) {
  if (code === 'UNAUTHORIZED') return '请先登录';
  if (code === 'FORBIDDEN') return '没有权限执行该操作';
  if (code === 'NOT_FOUND') return '内容不存在或已删除';
  if (code === 'BUSINESS_RULE') return message || '当前状态不允许该操作';
  return message || '请求失败，请稍后重试';
}

function request(method, path, data, opts) {
  const options = opts || {};
  return new Promise((resolve, reject) => {
    wx.request({
      url: BASE_URL + path,
      method,
      data: data || {},
      timeout: 10000,
      header: Object.assign(
        { 'Content-Type': 'application/json' },
        getToken() ? { Authorization: 'Bearer ' + getToken() } : {}
      ),
      success(res) {
        const body = res.data || {};
        if (res.statusCode >= 200 && res.statusCode < 300 && body.code === 'SUCCESS') {
          resolve(body.data);
          return;
        }
        if (res.statusCode === 401 || body.code === 'UNAUTHORIZED') {
          clearToken();
          if (!options.silent) {
            wx.navigateTo({ url: '/pages/login/login' });
          }
        }
        reject({
          status: res.statusCode,
          code: body.code,
          message: friendlyMessage(body.code, body.message),
        });
      },
      fail() {
        reject({ status: 0, code: 'NETWORK', message: '网络不可用，请检查网络后重试' });
      },
    });
  });
}

const get = (path, opts) => request('GET', path, null, opts);
const post = (path, data, opts) => request('POST', path, data, opts);
const patch = (path, data, opts) => request('PATCH', path, data, opts);
const del = (path, opts) => request('DELETE', path, null, opts);

// ── 登录 ─────────────────────────────────────────────────────────────────────
// 微信登录（A13 正式形态）：wx.login 拿 js_code → 后端 code2Session → 换 JWT。
// 测试号阶段 WECHAT_LOGIN_READY=false，直接走手机号登录（复用既有账号体系）。
function wxLogin() {
  return new Promise((resolve, reject) => {
    wx.login({
      success(res) {
        if (!res.code) {
          reject({ message: 'wx.login 未返回 code' });
          return;
        }
        post('/api/auth/wechat', { code: res.code })
          .then((data) => {
            setToken(data.accessToken);
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

// 手机号登录（测试号阶段的开发回退）
function phoneLogin(phone, password) {
  return post('/api/auth/login', { phone, password }).then((data) => {
    setToken(data.accessToken);
    return data.user;
  });
}

// 登录态探测：有 token 且 /api/users/me 可用才算已登录（与 Web 端同一判据）
function ensureSession() {
  if (!getToken()) return Promise.reject({ needLogin: true });
  return get('/api/users/me', { silent: true });
}

module.exports = {
  get,
  post,
  patch,
  del,
  getToken,
  setToken,
  clearToken,
  wxLogin,
  phoneLogin,
  ensureSession,
};

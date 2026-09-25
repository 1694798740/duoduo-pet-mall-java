// A14 · 统一请求核心（从 api.js 拆出，语义与 Web 端 api/client.ts 一致）
//  * 响应包络 { code, message, data, requestId }，code === 'SUCCESS' 才算成功；
//  * 令牌放 Authorization: Bearer（存 storage，不进 URL）；
//  * 401 → 清登录态并交给上层处理（默认跳登录页，可由 app 注册静默重登）；
//  * 4xx 不重试，超时与断网分别给不同文案；
//  * ID 一律字符串（T-39），前端只拼 URL / 相等比较，不做数值运算。
const { BASE_URL } = require('../config');

const TOKEN_KEY = 'duoduo_access_token';
const REFRESH_KEY = 'duoduo_refresh_token';

// 401 去重：并发多个请求同时 401 时只跳一次登录页（否则页面栈会被登录页塞满）
let redirectingToLogin = false;

function getToken() {
  return wx.getStorageSync(TOKEN_KEY) || '';
}

function getRefreshToken() {
  return wx.getStorageSync(REFRESH_KEY) || '';
}

/** 仅写访问令牌（保留给只拿到单令牌的调用方） */
function setToken(token) {
  wx.setStorageSync(TOKEN_KEY, token || '');
}

/** 一次写入令牌对（移动端登录/刷新都返回一对） */
function setTokens(accessToken, refreshToken) {
  if (accessToken) wx.setStorageSync(TOKEN_KEY, accessToken);
  if (refreshToken) wx.setStorageSync(REFRESH_KEY, refreshToken);
}

function clearToken() {
  wx.removeStorageSync(TOKEN_KEY);
}

function clearRefreshToken() {
  wx.removeStorageSync(REFRESH_KEY);
}

/** 清干净登录态（令牌对 + 全局用户），避免「令牌已废但界面仍显示已登录」 */
function clearSession() {
  clearToken();
  clearRefreshToken();
  const app = getApp();
  if (app && app.globalData) app.globalData.user = null;
}

function friendlyMessage(code, message) {
  // 401 的语义取决于接口，别一刀切：
  //   · 业务接口 401 = 令牌过期/未登录 → 后端文案是「身份未认证或登录已过期」；
  //   · 登录接口 401 = 手机号或密码不正确 → 后端文案是「手机号或密码不正确」。
  // 一律透出后端文案，否则会出现「用未注册手机号登录却提示『请先登录』」这种误导。
  if (code === 'UNAUTHORIZED') return message || '登录已过期，请重新登录';
  if (code === 'FORBIDDEN') return '没有权限执行该操作';
  if (code === 'NOT_FOUND') return '内容不存在或已删除';
  if (code === 'RATE_LIMITED') return message || '操作太频繁啦，请稍等再试';
  if (code === 'DEGRADED') return message || '服务暂时不可用，请稍后重试';
  if (code === 'BUSINESS_RULE') return message || '当前状态不允许该操作';
  if (code === 'STOCK_INSUFFICIENT') return message || '手慢了，库存不足';
  if (code === 'VALIDATION') return message || '提交的内容不符合要求';
  return message || '请求失败，请稍后重试';
}

/** 当前页面路由（形如 /pages/index/index），用于「已在登录页就不再叠加」判断 */
function currentRoute() {
  const pages = getCurrentPages();
  const top = pages.length ? pages[pages.length - 1] : null;
  return top && top.route ? '/' + top.route : '';
}

/** 打开登录页（带去重；已在登录页时不重复打开） */
function openLoginPage() {
  if (redirectingToLogin) return;
  if (currentRoute().indexOf('/pages/login/login') === 0) return;
  redirectingToLogin = true;
  wx.navigateTo({
    url: '/pages/login/login',
    // 无论成败都解锁（失败可能是页面栈已满，此时由调用方提示重试）
    complete() {
      setTimeout(() => {
        redirectingToLogin = false;
      }, 800);
    },
  });
}

// 401 处理器：由 app.js 注册「先静默重登、失败再跳登录页」，未注册时退回直接跳登录页。
let unauthorizedHandler = null;

function onUnauthorized(handler) {
  unauthorizedHandler = handler;
}

function request(method, path, data, opts) {
  const options = opts || {};
  return new Promise((resolve, reject) => {
    const token = getToken(); // 只读一次，避免同一请求里两次 storage 访问
    const header = { 'Content-Type': 'application/json' };
    if (token) header.Authorization = 'Bearer ' + token;

    wx.request({
      url: BASE_URL + path,
      method,
      data: data || {},
      header,
      timeout: options.timeout || 10000,
      dataType: 'json', // 显式声明，避免个别基础库按字符串返回
      success(res) {
        const body = res.data || {};
        if (res.statusCode >= 200 && res.statusCode < 300 && body.code === 'SUCCESS') {
          resolve(body.data);
          return;
        }
        if (res.statusCode === 401 || body.code === 'UNAUTHORIZED') {
          clearSession();
          if (!options.silent) {
            if (unauthorizedHandler) unauthorizedHandler();
            else openLoginPage();
          }
        }
        reject({
          status: res.statusCode,
          code: body.code,
          message: friendlyMessage(body.code, body.message),
        });
      },
      fail(err) {
        // 超时与断网分开提示：用户能据此决定「重试」还是「检查网络」
        const errMsg = (err && err.errMsg) || '';
        const timeout = errMsg.indexOf('timeout') >= 0;
        reject({
          status: 0,
          code: timeout ? 'TIMEOUT' : 'NETWORK',
          message: timeout ? '请求超时，请稍后重试' : '网络不可用，请检查网络后重试',
        });
      },
    });
  });
}

const get = (path, opts) => request('GET', path, null, opts);
const post = (path, data, opts) => request('POST', path, data, opts);
const put = (path, data, opts) => request('PUT', path, data, opts);
const patch = (path, data, opts) => request('PATCH', path, data, opts);
const del = (path, opts) => request('DELETE', path, null, opts);

/** 统一错误提示（页面 catch 里一行搞定） */
function toastError(err) {
  wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' });
}

module.exports = {
  request,
  get,
  post,
  put,
  patch,
  del,
  getToken,
  getRefreshToken,
  setToken,
  setTokens,
  clearToken,
  clearRefreshToken,
  clearSession,
  openLoginPage,
  onUnauthorized,
  toastError,
};

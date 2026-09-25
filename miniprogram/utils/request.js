// A14 · 统一请求核心（从 api.js 拆出，语义与 Web 端 api/client.ts 一致）
//  * 响应包络 { code, message, data, requestId }，code === 'SUCCESS' 才算成功；
//  * 令牌放 Authorization: Bearer（存 storage，不进 URL）；
//  * 401 → 清登录态并跳登录页（silent 模式除外）；
//  * 4xx 不重试，5xx 提示「服务暂时不可用」；网络失败提示离线；
//  * ID 一律字符串（T-39），前端只拼 URL / 相等比较，不做数值运算。
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
  if (code === 'RATE_LIMITED') return message || '操作太频繁啦，请稍等再试';
  if (code === 'DEGRADED') return message || '服务暂时不可用，请稍后重试';
  if (code === 'BUSINESS_RULE') return message || '当前状态不允许该操作';
  if (code === 'STOCK_INSUFFICIENT') return message || '手慢了，库存不足';
  return message || '请求失败，请稍后重试';
}

function request(method, path, data, opts) {
  const options = opts || {};
  return new Promise((resolve, reject) => {
    wx.request({
      url: BASE_URL + path,
      method,
      data: data || {},
      timeout: options.timeout || 10000,
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
          const app = getApp();
          if (app) app.globalData.user = null;
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

/** 统一错误提示（页面 catch 里一行搞定） */
function toastError(err) {
  wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' });
}

module.exports = { request, get, post, patch, del, getToken, setToken, clearToken, toastError };

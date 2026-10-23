// 实时通道（2026-10-23 · 与 Web 端 packages/shared/src/realtime.ts 同一协议）
//
// 为什么小程序也要一条独立的实现：`wx.connectSocket` 与浏览器 WebSocket 的 API 不同，
// 但协议完全一致（同一 /ws/chat 端点、同一 JSON 事件），因此两端行为不会漂移。
//
// 关键约束：
//  · 浏览器建 WS 带不了 Authorization 头 —— 小程序同样只能把票据放 query，
//    所以先用登录态换一次性票据（60 秒、用后即焚），绝不把长期令牌写进 URL；
//  · 小程序没有 document.visibilitychange：用页面的 onShow/onHide 显式 connect/close，
//    避免"退到后台还挂着连接"（微信会在切后台一段时间后静默掐断，表现为假在线）；
//  · 心跳 25 秒一次，超时判死重连；重连指数退避，页面 onUnload 时彻底关闭。
const req = require('./request');
const { BASE_URL } = require('../config');

const RETRY_DELAYS = [1000, 2000, 5000, 10000, 20000, 30000];
const HEARTBEAT_MS = 25000;
const PONG_TIMEOUT_MS = 10000;
const TYPING_THROTTLE_MS = 2000;

let socket = null;
let handler = null;
let retry = 0;
let manualClose = false;
let connecting = false;
let reconnectTimer = null;
let heartbeatTimer = null;
let lastPongAt = 0;
let lastTypingAt = 0;

function wsUrl(ticket) {
  const base = BASE_URL.replace(/^http/, 'ws');
  return base + '/ws/chat?ticket=' + encodeURIComponent(ticket);
}

function emit(event) {
  if (typeof handler === 'function') {
    try {
      handler(event);
    } catch (e) {
      // 页面回调异常不能影响连接本身
    }
  }
}

function startHeartbeat() {
  stopHeartbeat();
  heartbeatTimer = setInterval(() => {
    if (Date.now() - lastPongAt > HEARTBEAT_MS + PONG_TIMEOUT_MS) {
      // 半开连接：主动关掉走重连，否则会一直"在线但收不到消息"
      try {
        socket && socket.close({});
      } catch (e) {
        // ignore
      }
      return;
    }
    send({ type: 'PING' });
  }, HEARTBEAT_MS);
}

function stopHeartbeat() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer);
    heartbeatTimer = null;
  }
}

function send(payload) {
  if (!socket) return;
  try {
    socket.send({ data: JSON.stringify(payload) });
  } catch (e) {
    // 连接已断，交给 onClose 处理
  }
}

function scheduleReconnect() {
  if (manualClose || reconnectTimer) return;
  const delay = RETRY_DELAYS[Math.min(retry, RETRY_DELAYS.length - 1)];
  retry += 1;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    if (!manualClose) connect();
  }, delay);
}

function connect() {
  if (socket || connecting || manualClose) return;
  connecting = true;
  req
    .post('/api/socials/realtime/ticket')
    .then((data) => {
      if (!data || !data.ticket) throw new Error('未取得实时票据');
      const task = wx.connectSocket({ url: wsUrl(data.ticket) });
      socket = task;
      task.onOpen(() => {
        connecting = false;
        retry = 0;
        lastPongAt = Date.now();
        startHeartbeat();
      });
      task.onMessage((res) => {
        let event = null;
        try {
          event = JSON.parse(res.data);
        } catch (e) {
          return;
        }
        if (event.type === 'PING') {
          send({ type: 'PONG' });
          return;
        }
        if (event.type === 'PONG' || event.type === 'READY') {
          lastPongAt = Date.now();
        }
        if (event.type !== 'PONG') emit(event);
      });
      task.onClose(() => {
        socket = null;
        connecting = false;
        stopHeartbeat();
        scheduleReconnect();
      });
      task.onError(() => {
        socket = null;
        connecting = false;
        stopHeartbeat();
        scheduleReconnect();
      });
    })
    .catch(() => {
      connecting = false;
      scheduleReconnect();
    });
}

/** 页面进入：绑定回调并建连 */
function open(fn) {
  handler = fn;
  manualClose = false;
  if (!socket && !connecting) connect();
}

/** 页面离开：解绑并断开（不自动重连） */
function close() {
  manualClose = true;
  handler = null;
  stopHeartbeat();
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  try {
    socket && socket.close({});
  } catch (e) {
    // ignore
  }
  socket = null;
  connecting = false;
}

/** 「正在输入」：客户端 2 秒节流（服务端还有 700ms 限流） */
function typing(biz, target) {
  const now = Date.now();
  if (now - lastTypingAt < TYPING_THROTTLE_MS) return;
  lastTypingAt = now;
  send(Object.assign({ type: 'TYPING', biz: biz }, target));
}

module.exports = { open, close, typing };

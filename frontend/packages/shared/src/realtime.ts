/**
 * 实时通道客户端（2026-10-23）。
 *
 * <h2>协议</h2>
 * 服务端只推**信号**（有新消息 / 对方读到哪 / 对方正在输入），不带消息正文；
 * 页面收到后调用已有的 HTTP 接口渲染。好处是：
 *  · 消息渲染只有一套实现，不会出现"推送来的格式"与"拉取来的格式"不一致；
 *  · 断线期间漏掉的信号不会丢消息 —— 重连后照常拉取即可，实时通道本身无状态。
 *
 * <h2>连接生命周期</h2>
 * 用**引用计数**管理：页面 `useRealtime()` 进入 +1、卸载 -1，最后一个页面离开才断开。
 * 聊天页在路由间来回切换时连接不会反复重建（每次重连都要换票、握手，成本不低）。
 *
 * <h2>可靠性</h2>
 *  · 票据：每次建连前用登录态换一张一次性票（60 秒有效）；
 *  · 心跳：25 秒发 PING，10 秒内没收到 PONG 判死并重连（防 NAT 超时后的"假在线"）；
 *  · 重连：指数退避 1s→2s→5s→10s→20s→30s 封顶，页面重新可见时立即重连一次；
 *  · 手动关闭（登出）不再自动重连。
 */
import { useEffect, useRef } from 'react';
import { realtimeApi } from '@duoduo/api-sdk/endpoints';

export type RealtimeBiz = 'SUPPORT' | 'DIRECT';

export interface RealtimeEvent {
  type: 'MESSAGE' | 'READ' | 'TYPING' | 'RECALL' | 'READY' | 'PING' | 'PONG';
  biz?: RealtimeBiz;
  sessionId?: string;
  conversationId?: string;
  fromUserId?: string;
  messageId?: string;
  lastReadId?: string;
  at?: number;
}

type Handler = (event: RealtimeEvent) => void;

const RETRY_DELAYS = [1000, 2000, 5000, 10000, 20000, 30000];
const HEARTBEAT_MS = 25_000;
const PONG_TIMEOUT_MS = 10_000;
const TYPING_THROTTLE_MS = 2000;

class RealtimeClient {
  private ws: WebSocket | null = null;
  private handlers = new Set<Handler>();
  private refs = 0;
  private retry = 0;
  private reconnectTimer: number | null = null;
  private heartbeatTimer: number | null = null;
  private lastPongAt = 0;
  private manuallyClosed = false;
  private connecting = false;
  private lastTypingAt = 0;
  /** 最近一次连接状态（供调试与 UI 提示） */
  state: 'idle' | 'connecting' | 'open' | 'closed' = 'idle';

  on(handler: Handler): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  /** 页面进入：引用计数 +1 并确保连接（与 release 成对，由 useRealtime 调用） */
  retain(): void {
    this.refs += 1;
    this.manuallyClosed = false;
    this.wake();
  }

  /**
   * 唤醒：连接不存在就建连，**不改变引用计数**。
   * 页面重新可见时用它（不能复用 retain，否则每次切标签页都会把计数加一、永不归零）。
   */
  wake(): void {
    if (this.refs > 0 && !this.ws && !this.connecting) {
      void this.connect();
    }
  }

  /** 页面离开：-1，最后一个离开才断开 */
  release(): void {
    this.refs = Math.max(0, this.refs - 1);
    if (this.refs === 0) {
      this.disconnect();
    }
  }

  /** 主动断开（登出 / 账号切换） */
  disconnect(): void {
    this.manuallyClosed = true;
    this.clearTimers();
    try {
      this.ws?.close();
    } catch {
      // 已断开
    }
    this.ws = null;
    this.state = 'closed';
  }

  /**
   * 发送「正在输入」。服务端会 700ms 限流一次，客户端这里再按 2 秒节流：
   * 每敲一个字都发会把提示变成持续的广播，对 500+ 并发是明显浪费。
   */
  typing(biz: RealtimeBiz, target: { sessionId?: string; conversationId?: string }): void {
    const now = Date.now();
    if (now - this.lastTypingAt < TYPING_THROTTLE_MS) return;
    this.lastTypingAt = now;
    this.send({ type: 'TYPING', biz, ...target });
  }

  private send(payload: Record<string, unknown>): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(payload));
    }
  }

  private async connect(): Promise<void> {
    this.connecting = true;
    this.state = 'connecting';
    try {
      const ticket = await realtimeApi.ticket();
      if (!ticket?.ticket) throw new Error('未取得实时通道票据');
      const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
      const url = `${proto}://${window.location.host}/ws/chat?ticket=${encodeURIComponent(ticket.ticket)}`;
      const ws = new WebSocket(url);
      this.ws = ws;
      ws.onopen = () => {
        this.retry = 0;
        this.connecting = false;
        this.state = 'open';
        this.lastPongAt = Date.now();
        this.startHeartbeat();
      };
      ws.onmessage = (ev) => this.handle(ev.data);
      ws.onerror = () => {
        // 具体原因由 onclose 处理，这里只保证状态归位
      };
      ws.onclose = () => {
        this.connecting = false;
        this.ws = null;
        this.state = 'closed';
        this.clearTimers();
        this.scheduleReconnect();
      };
    } catch {
      this.connecting = false;
      this.state = 'closed';
      this.scheduleReconnect();
    }
  }

  private handle(raw: unknown): void {
    if (typeof raw !== 'string') return;
    let event: RealtimeEvent;
    try {
      event = JSON.parse(raw) as RealtimeEvent;
    } catch {
      return;
    }
    if (event.type === 'PING') {
      this.send({ type: 'PONG' });
      return;
    }
    if (event.type === 'PONG') {
      this.lastPongAt = Date.now();
      return;
    }
    if (event.type === 'READY') {
      this.lastPongAt = Date.now();
    }
    this.handlers.forEach((h) => {
      try {
        h(event);
      } catch {
        // 单个页面的处理异常不能影响其它订阅者
      }
    });
  }

  private startHeartbeat(): void {
    this.clearHeartbeat();
    this.heartbeatTimer = window.setInterval(() => {
      if (Date.now() - this.lastPongAt > HEARTBEAT_MS + PONG_TIMEOUT_MS) {
        // 半开连接：直接关掉走重连，否则用户会一直"在线但收不到消息"
        try {
          this.ws?.close();
        } catch {
          // ignore
        }
        return;
      }
      this.send({ type: 'PING' });
    }, HEARTBEAT_MS);
  }

  private scheduleReconnect(): void {
    if (this.manuallyClosed || this.refs === 0 || this.reconnectTimer !== null) {
      return;
    }
    const delay = RETRY_DELAYS[Math.min(this.retry, RETRY_DELAYS.length - 1)];
    this.retry += 1;
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      if (this.refs > 0 && !this.manuallyClosed) void this.connect();
    }, delay);
  }

  private clearHeartbeat(): void {
    if (this.heartbeatTimer !== null) {
      window.clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private clearTimers(): void {
    this.clearHeartbeat();
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
}

export const realtime = new RealtimeClient();

/**
 * 页面订阅实时事件。回调用 ref 持有最新闭包 —— 组件每次渲染新建的箭头函数
 * 不会导致反复订阅，也不会读到过期的 state。
 */
export function useRealtime(handler: Handler): void {
  const ref = useRef(handler);
  ref.current = handler;
  useEffect(() => {
    const off = realtime.on((e) => ref.current(e));
    realtime.retain();
    const onVisible = () => {
      if (document.visibilityState === 'visible') realtime.wake();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      off();
      document.removeEventListener('visibilitychange', onVisible);
      realtime.release();
    };
  }, []);
}

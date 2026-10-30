/**
 * AI 导购「多多」（2026-10-28 重构）。
 *
 * 对话走 SSE：服务端按 token 推送，页面实时追加，解决首字延迟。
 * 加购必须二次确认：服务端返回 confirm 事件（一次性 token），
 * 用户点「确认加购」后才调用 /api/duoduo/confirm，模型不能直接下单。
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, formatPrice, toFriendlyMessage } from '@duoduo/api-sdk/client';
import { duoduoApi } from '@duoduo/api-sdk/endpoints';
import type { AiCard, DuoduoCapability, DuoduoClarifyView, DuoduoRecommendation } from '@duoduo/api-sdk/types';
import { Alert, LoadingBlock, useToast } from '@duoduo/shared/components/ui';
import { SeckillPrice, SeckillTag } from '@duoduo/shared/components/seckill';
import { useSpuSeckill } from '@duoduo/shared/store/seckill';
import { useAuth } from '@duoduo/shared/store/auth';
import { useApp } from '@duoduo/shared/store/app';
import { AiCardRenderer, safeWebPath } from '../components/ai/AiCardRenderer';

const AiRecommendCard: React.FC<{ rec: DuoduoRecommendation }> = ({ rec }) => {
  const seckill = useSpuSeckill(String(rec.id));
  return (
    <Link to={`/product/${rec.id}`} className="card" style={{ padding: 10, display: 'block' }}>
      {seckill ? <SeckillTag phase={seckill.phase} /> : null}
      <div style={{ fontWeight: 600, marginTop: seckill ? 6 : 0 }}>{rec.title}</div>
      {rec.subtitle ? <div className="small muted">{rec.subtitle}</div> : null}
      <div style={{ marginTop: 4, display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
        {seckill ? (
          <SeckillPrice price={seckill.sku.seckillPrice} original={seckill.sku.originalPrice} size="sm" />
        ) : (
          <span style={{ color: '#e5484d', fontWeight: 600 }}>
            {formatPrice(rec.minPrice)}
            {rec.maxPrice > rec.minPrice ? ' 起' : ''}
          </span>
        )}
        <span className="small muted">{rec.inStock ? '有货' : '暂时缺货'}</span>
      </div>
    </Link>
  );
};

interface ConfirmPreview {
  spuId?: string;
  skuId?: string;
  quantity?: number;
}

interface Bubble {
  id: number;
  role: 'user' | 'assistant';
  text: string;
  recommendations?: DuoduoRecommendation[];
  /** 服务端装配的卡片（通知 / 券 / 订单 / 宫格） */
  cards?: AiCard[];
  streaming?: boolean;
  confirm?: { token: string; preview: ConfirmPreview };
  handoff?: { summary: string };
  /** 游客轮次用尽 / 功能需登录时，服务端下发的登录地址（已带站内 next） */
  loginUrl?: string;
  /** 结构化反问（DEF-014）：渲染成可点选项，点一下即发送 */
  clarify?: DuoduoClarifyView;
}

const DEFAULT_QUICK = [
  '推荐一款适合幼犬吃的狗粮',
  '猫咪爱吃的零食罐头',
  '有没有性价比高的猫砂',
  '狗狗关节不好的话吃什么',
];

/**
 * 会话持久化（2026-10-29 修复「切页后会话丢失」）。
 *
 * <p>页面组件在路由切换时会被卸载，`messages` 与 `sessionId` 原本只存在
 * React state 里 —— 切到「我的」再回来，界面空空、sessionId 也变成 undefined，
 * 服务端虽然有 Redis 会话，但前端已经找不到它了。
 *
 * <p>三个约束：
 * <ol>
 *   <li><b>用 sessionStorage 而不是 localStorage</b>：会话只在当前标签页有效，
 *       关标签即清；共用电脑时不会把上一个人的聊天记录留给下一个人；</li>
 *   <li><b>TTL 与服务端 Redis 会话对齐（2 小时）</b>：超过 TTL 恢复出来也接不上，
 *       不如直接当作新会话；</li>
 *   <li><b>归属校验</b>：记录里带上 owner，登录用户切换账号后不会读到别人的对话。</li>
 * </ol>
 */
const SESSION_STORAGE_KEY = 'duoduo.ai.session.v1';
const SESSION_TTL_MS = 2 * 60 * 60 * 1000;
const MAX_STORED_MESSAGES = 30;

interface StoredAiSession {
  owner: string;
  sessionId?: string;
  messages: Bubble[];
  savedAt: number;
}

function sessionOwner(userId?: number | string | null): string {
  return userId == null ? 'guest' : `u${userId}`;
}

function loadStoredSession(expectedOwner: string): { sessionId?: string; messages: Bubble[] } | null {
  try {
    const raw = sessionStorage.getItem(SESSION_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as StoredAiSession;
    if (!parsed || typeof parsed.savedAt !== 'number' || !Array.isArray(parsed.messages)) {
      return null;
    }
    if (Date.now() - parsed.savedAt > SESSION_TTL_MS) {
      sessionStorage.removeItem(SESSION_STORAGE_KEY);
      return null;
    }
    // 上一个登录用户的对话不能被另一个账号或游客看到；游客对话登录后可继续
    if (parsed.owner !== expectedOwner && parsed.owner !== 'guest') {
      sessionStorage.removeItem(SESSION_STORAGE_KEY);
      return null;
    }
    return {
      sessionId: parsed.sessionId,
      // 恢复时清掉传输态：streaming 会永远转圈，confirm 令牌 5 分钟就过期
      messages: parsed.messages.map((m) => ({ ...m, streaming: false, confirm: undefined })),
    };
  } catch {
    return null;
  }
}

function persistSession(owner: string, sessionId: string | undefined, messages: Bubble[]): void {
  try {
    const payload: StoredAiSession = {
      owner,
      sessionId,
      savedAt: Date.now(),
      messages: messages
        .slice(-MAX_STORED_MESSAGES)
        .map(({ confirm, ...rest }) => ({ ...rest, streaming: false })),
    };
    sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(payload));
  } catch {
    // 存储不可用或超限：会话只留在内存，不影响当次对话
  }
}

export const AiGuruPage: React.FC = () => {
  // 注意：登录态是**异步**恢复的（AuthProvider 先调 /api/auth/me）。
  // 挂载瞬间 user=null，如果这时读/写会话缓存，会把「登录用户的会话」误判成他人会话并删掉。
  const { user, loading: authLoading } = useAuth();
  const { push } = useToast();
  const { applyBadgePatch } = useApp();
  const owner = sessionOwner(user?.id);
  const [capability, setCapability] = useState<DuoduoCapability | null>(null);
  const [capLoading, setCapLoading] = useState(true);
  const [messages, setMessages] = useState<Bubble[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [sessionId, setSessionId] = useState<string | undefined>(undefined);
  const listRef = useRef<HTMLDivElement | null>(null);
  /** 消息 id 从恢复的最大值继续，否则 React key 会撞 */
  const seq = useRef(0);
  /**
   * 本轮会话是否已经澄清过：服务端无状态，只认前端带上来的这个事实；
   * 不清空 —— 一次对话最多只反问一次（DEF-014）。
   */
  const clarifiedRef = useRef(false);
  /** 是否已经完成「鉴权就绪后的首次恢复」；未完成前绝不写缓存，避免空状态覆盖旧会话 */
  const [restoredReady, setRestoredReady] = useState(false);
  const lastOwner = useRef(owner);

  // ① 等鉴权就绪 → 恢复一次会话
  useEffect(() => {
    if (authLoading || restoredReady) return;
    const stored = loadStoredSession(sessionOwner(user?.id));
    if (stored) {
      setMessages(stored.messages);
      setSessionId(stored.sessionId);
      seq.current = Math.max(
        seq.current,
        stored.messages.reduce((max, m) => Math.max(max, m.id), 0),
      );
    }
    setRestoredReady(true);
  }, [authLoading, restoredReady, user]);

  // ② 恢复完成后才允许落盘：切页卸载时不会丢
  useEffect(() => {
    if (!restoredReady) return;
    persistSession(owner, sessionId, messages);
  }, [restoredReady, owner, sessionId, messages]);

  // ③ 退出登录：不能把上一个账号的对话留给下一个人
  useEffect(() => {
    const previous = lastOwner.current;
    if (previous === owner) return;
    lastOwner.current = owner;
    if (previous.startsWith('u') && owner === 'guest') {
      setMessages([]);
      setSessionId(undefined);
      try {
        sessionStorage.removeItem(SESSION_STORAGE_KEY);
      } catch {
        // 忽略存储不可用
      }
    }
  }, [owner]);

  useEffect(() => {
    const controller = new AbortController();
    duoduoApi
      .capabilities({ signal: controller.signal })
      .then(setCapability)
      .catch(() => {
        if (!controller.signal.aborted) {
          setCapability({ chatAvailable: false, mode: 'unconfigured', hint: 'AI 导购暂时不可用，请稍后再试。' });
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setCapLoading(false);
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
  }, [messages, sending]);

  const nextId = () => {
    seq.current += 1;
    return seq.current;
  };

  const patchAssistant = useCallback((id: number, patch: Partial<Bubble>) => {
    setMessages((prev) => prev.map((m) => (m.id === id ? { ...m, ...patch } : m)));
  }, []);

  const handleEvent = useCallback(
    (assistantId: number, event: string, data: string) => {
      let payload: any = null;
      try {
        payload = data ? JSON.parse(data) : null;
      } catch {
        payload = null;
      }
      if (event === 'meta') {
        if (payload?.sessionId) setSessionId(payload.sessionId);
        return;
      }
      if (event === 'token') {
        const text = payload?.text ?? '';
        if (text) {
          setMessages((prev) =>
            prev.map((m) => (m.id === assistantId ? { ...m, text: m.text + text } : m)),
          );
        }
        return;
      }
      if (event === 'recommendations') {
        if (Array.isArray(payload)) patchAssistant(assistantId, { recommendations: payload });
        return;
      }
      /** 结构化反问：选项来自真实类目，点一下即发送；同时记住「已澄清」 */
      if (event === 'clarify') {
        clarifiedRef.current = true;
        if (payload?.question) {
          patchAssistant(assistantId, { clarify: payload as DuoduoClarifyView });
        }
        return;
      }
      /**
       * 结构化卡片（阶段三新增）：正文与卡片是两个独立通道 ——
       * 正文已经由服务端护栏清洗过，卡片内容是工具结果，模型没参与。
       *
       * <p>badgePatch 只在这一轮服务端**确实改了角标**时才非空：
       * 这里直接把它合并进全局 store，不发第二次 unread-summary
       * （补丁就是那次操作算出的权威值，再查一次反而可能拿到更新的数字）。
       */
      if (event === 'cards') {
        patchAssistant(assistantId, {
          cards: Array.isArray(payload?.cards) ? (payload.cards as AiCard[]) : [],
        });
        if (payload?.badgePatch) applyBadgePatch(payload.badgePatch);
        return;
      }
      /** 游客轮次用尽 / 功能需要登录：给一句说明 + 登录按钮（地址由服务端生成） */
      if (event === 'login_required') {
        patchAssistant(assistantId, {
          text: payload?.reply ?? '登录后可以继续使用哦',
          streaming: false,
          loginUrl: typeof payload?.loginUrl === 'string' ? payload.loginUrl : '/login',
        });
        return;
      }
      if (event === 'confirm') {
        if (payload?.token) {
          patchAssistant(assistantId, { confirm: { token: payload.token, preview: payload.preview ?? {} } });
        }
        return;
      }
      if (event === 'handoff') {
        patchAssistant(assistantId, { handoff: { summary: payload?.summary ?? '' } });
        return;
      }
      if (event === 'done') {
        patchAssistant(assistantId, {
          text: payload?.reply ?? '',
          streaming: false,
        });
        return;
      }
      if (event === 'error') {
        patchAssistant(assistantId, {
          text: payload?.message ?? 'AI 暂时不可用，请稍后再试',
          streaming: false,
        });
      }
    },
    [patchAssistant, applyBadgePatch],
  );

  const send = useCallback(
    async (preset?: string) => {
      const text = (preset ?? input).trim();
      if (!text || sending) return;
      if (text.length > 500) {
        push('一句话最多 500 个字', 'error');
        return;
      }
      const userId = nextId();
      const assistantId = nextId();
      setMessages((prev) => [
        ...prev,
        { id: userId, role: 'user', text },
        { id: assistantId, role: 'assistant', text: '', streaming: true },
      ]);
      setInput('');
      setSending(true);
      try {
        const res = await fetch('/api/duoduo/chat/stream', {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            message: text,
            sessionId,
            clarified: clarifiedRef.current,
            // 当前页面（含查询串）交给服务端做上下文；服务端会按白名单校验路径并丢掉查询串
            pageUrl: `${window.location.pathname}${window.location.search}`,
          }),
        });
        if (!res.ok || !res.body) {
          let message = 'AI 暂时不可用，请稍后再试';
          try {
            const body = await res.json();
            message = body?.message ?? message;
          } catch {
            // ignore
          }
          throw new ApiError(res.status, 'HTTP_ERROR', message);
        }
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let pos = buffer.indexOf('\n\n');
          while (pos >= 0) {
            const block = buffer.slice(0, pos);
            buffer = buffer.slice(pos + 2);
            let event = 'message';
            const dataLines: string[] = [];
            for (const line of block.split('\n')) {
              if (line.startsWith('event:')) event = line.slice(6).trim();
              if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
            }
            handleEvent(assistantId, event, dataLines.join('\n'));
            pos = buffer.indexOf('\n\n');
          }
        }
      } catch (error) {
        const text429 = error instanceof ApiError && error.status === 429;
        const message = text429 ? '问得太快啦，歇一分钟再来吧' : toFriendlyMessage(error);
        push(message, 'error');
        patchAssistant(assistantId, { text: message, streaming: false });
      } finally {
        setSending(false);
        patchAssistant(assistantId, { streaming: false });
      }
    },
    [input, sending, sessionId, push, handleEvent, patchAssistant],
  );

  const confirmAdd = async (bubble: Bubble) => {
    if (!bubble.confirm) return;
    const { token, preview } = bubble.confirm;
    if (!user) {
      push('请先登录后再加购', 'error');
      return;
    }
    try {
      const result = await duoduoApi.confirm({
        token,
        spuId: String(preview.spuId ?? ''),
        skuId: String(preview.skuId ?? ''),
        quantity: Number(preview.quantity ?? 1),
        sessionId,
      });
      if (result.success) {
        push('已加入购物车', 'success');
        patchAssistant(bubble.id, { confirm: undefined });
      } else {
        push(result.message || '加购失败，请重新选择规格', 'error');
      }
    } catch (error) {
      push(toFriendlyMessage(error), 'error');
    }
  };

  if (capLoading) return <LoadingBlock text="正在连接 AI 导购…" />;

  const available = capability?.chatAvailable === true;
  const quick = capability?.quickQuestions?.length ? capability.quickQuestions : DEFAULT_QUICK;

  return (
    <div className="container section">
      <div className="section-head">
        <h1 className="section-title">AI 导购「多多」</h1>
        <span className="small muted">描述你的需求，多多从真实商品库里帮你挑</span>
      </div>

      {capability ? <Alert kind={available ? 'info' : 'warn'}>{capability.hint}</Alert> : null}

      <div className="card" style={{ marginTop: 14, display: 'flex', flexDirection: 'column', height: 500, padding: 0 }}>
        <div ref={listRef} style={{ flex: 1, overflowY: 'auto', padding: 16 }}>
          {messages.length === 0 ? (
            <div className="muted small">
              你好，我是多多 🐾 想找什么？比如「幼犬狗粮」「猫咪零食」，我帮你从在售商品里挑几款。
            </div>
          ) : null}

          {messages.map((m) => (
            <div
              key={m.id}
              style={{ display: 'flex', justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start', marginBottom: 12 }}
            >
              <div style={{ maxWidth: '78%' }}>
                <div
                  className={m.role === 'user' ? 'bubble bubble-user' : 'bubble'}
                  style={{
                    background: m.role === 'user' ? 'var(--brand, #2f6fed)' : 'var(--surface-2, #f4f5f7)',
                    color: m.role === 'user' ? '#fff' : 'inherit',
                    padding: '9px 12px',
                    borderRadius: 10,
                    whiteSpace: 'pre-wrap',
                    lineHeight: 1.6,
                  }}
                >
                  {m.text || (m.streaming ? '…' : '')}
                </div>

                {m.confirm ? (
                  <div className="card" style={{ marginTop: 8, padding: 10 }}>
                    <div className="small">确认把商品 {m.confirm.preview.spuId ?? ''} 加入购物车？</div>
                    <div className="row" style={{ gap: 8, marginTop: 8 }}>
                      <button className="btn btn-primary btn-sm" type="button" onClick={() => void confirmAdd(m)}>
                        确认加购
                      </button>
                      <button
                        className="btn btn-ghost btn-sm"
                        type="button"
                        onClick={() => patchAssistant(m.id, { confirm: undefined })}
                      >
                        先不要
                      </button>
                    </div>
                  </div>
                ) : null}

                {m.handoff ? (
                  <div className="card" style={{ marginTop: 8, padding: 10 }}>
                    <div className="small">需要人工客服继续处理？</div>
                    <Link className="btn btn-ghost btn-sm" to="/support" style={{ marginTop: 8 }}>
                      去找人工客服
                    </Link>
                  </div>
                ) : null}

                {m.recommendations && m.recommendations.length > 0 ? (
                  <div style={{ marginTop: 8, display: 'grid', gap: 8 }}>
                    {m.recommendations.map((rec) => (
                      <AiRecommendCard key={rec.id} rec={rec} />
                    ))}
                  </div>
                ) : null}

                {/* 结构化反问（DEF-014）：选项来自真实类目，点一下即发送，不必打字 */}
                {m.clarify && m.clarify.options?.length ? (
                  <div style={{ marginTop: 8, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    {m.clarify.options.map((opt) => (
                      <button
                        key={opt.send}
                        type="button"
                        className="btn btn-ghost btn-sm"
                        disabled={!available || sending}
                        onClick={() => void send(opt.send)}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </div>
                ) : null}

                {/* 服务端装配的卡片：通知 / 券 / 订单 / 宫格，点击跳转全部由服务端给出的地址 */}
                {m.cards && m.cards.length > 0 ? <AiCardRenderer cards={m.cards} /> : null}

                {/* 登录拦截（游客轮次用尽 / 功能需登录）：地址同样先过客户端白名单 */}
                {m.loginUrl ? (
                  <div className="card" style={{ marginTop: 8, padding: 10 }}>
                    <div className="small">登录后可以继续：查订单、通知、优惠券，也能记住宠物档案。</div>
                    <Link
                      className="btn btn-primary btn-sm"
                      to={safeWebPath(m.loginUrl) ?? '/login'}
                      style={{ marginTop: 8 }}
                    >
                      去登录
                    </Link>
                  </div>
                ) : null}
              </div>
            </div>
          ))}

          {sending ? <div className="small muted">多多正在思考…</div> : null}
        </div>

        <div style={{ borderTop: '1px solid var(--border, #e6e8eb)', padding: 12 }}>
          {messages.length === 0 ? (
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 10 }}>
              {quick.map((q) => (
                <button
                  key={q}
                  type="button"
                  className="btn btn-ghost btn-sm"
                  disabled={!available || sending}
                  onClick={() => void send(q)}
                >
                  {q}
                </button>
              ))}
            </div>
          ) : null}

          <div style={{ display: 'flex', gap: 8 }}>
            <input
              value={input}
              maxLength={500}
              disabled={!available || sending}
              placeholder={available ? '说说你想找什么…' : 'AI 导购暂不可用'}
              aria-label="对多多说"
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  void send();
                }
              }}
              style={{ flex: 1, padding: '9px 12px' }}
            />
            <button
              type="button"
              className="btn btn-primary"
              disabled={!available || sending || input.trim().length === 0}
              onClick={() => void send()}
            >
              发送
            </button>
          </div>
        </div>
      </div>

      {!user ? (
        <div style={{ marginTop: 12 }}>
          <Alert kind="info">
            想加购或下单？<Link to="/login?next=/ai">登录</Link> 后到商品页操作即可，多多不代下单。
          </Alert>
        </div>
      ) : null}
    </div>
  );
};

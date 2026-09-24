/**
 * AI 导购「多多」（A9，duoduo-ai 服务）。
 *
 * 三条与后端契约对齐的行为约定：
 *  1. **先问能力再谈对话**：`capabilities` 未回来之前不渲染输入框。
 *     `chatAvailable=false`（未配置百炼 Key）时直接说明并禁用输入，
 *     而不是让人对着输入框问半天、每次都是 503。
 *  2. **未登录也能问**：`/api/duoduo/chat` 在网关白名单里，对话本身不承载身份。
 *     需要「加购 / 下单」时由服务端回复文案引导登录，页面在这里再加一条登录入口 ——
 *     把「能不能问」和「能不能买」分开，转化路径更短。
 *  3. **限流不当故障**：服务端每 IP 每分钟限流，429 只提示「太频繁」且**不自动重试** ——
 *     重试只会让下一次更早撞上限。
 *
 * 推荐卡片直接渲染服务端返回的 `recommendations`：那已经是真实商品库里召回的结果，
 * 每一条都能点进详情页，**不做任何前端二次加工**。
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, formatPrice, toFriendlyMessage } from '../api/client';
import { duoduoApi } from '../api/endpoints';
import type { DuoduoCapability, DuoduoChatTurn, DuoduoRecommendation } from '../api/types';
import { Alert, LoadingBlock, useToast } from '../components/ui';
import { useAuth } from '../store/auth';

interface Bubble {
  id: number;
  role: 'user' | 'assistant';
  text: string;
  recommendations?: DuoduoRecommendation[];
}

const QUICK_QUESTIONS = [
  '推荐一款适合幼犬吃的狗粮',
  '猫咪爱吃的零食罐头',
  '有没有性价比高的猫砂',
  '狗狗关节不好的话吃什么',
];

/** 历史最多回传 6 轮 —— 与 ChatRequest 的 @Size(max = 6) 对齐 */
const HISTORY_LIMIT = 6;

export const AiGuruPage: React.FC = () => {
  const { user } = useAuth();
  const { push } = useToast();
  const [capability, setCapability] = useState<DuoduoCapability | null>(null);
  const [capLoading, setCapLoading] = useState(true);
  const [messages, setMessages] = useState<Bubble[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const listRef = useRef<HTMLDivElement | null>(null);
  const seq = useRef(0);

  useEffect(() => {
    const controller = new AbortController();
    duoduoApi
      .capabilities({ signal: controller.signal })
      .then(setCapability)
      .catch(() => {
        // 能力接口都不通，说明 AI 服务不可用 —— 按「不可用」渲染，不抛给用户
        if (!controller.signal.aborted) {
          setCapability({ chatAvailable: false, mode: 'unconfigured', hint: 'AI 导购暂时不可用，请稍后再试。' });
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setCapLoading(false);
      });
    return () => controller.abort();
  }, []);

  // 新消息进来滚到底部，免去用户手动下拉
  useEffect(() => {
    if (listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
  }, [messages, sending]);

  const nextId = () => {
    seq.current += 1;
    return seq.current;
  };

  const send = useCallback(
    async (preset?: string) => {
      const text = (preset ?? input).trim();
      if (!text || sending) return;
      if (text.length > 500) {
        push('一句话最多 500 个字', 'error');
        return;
      }
      setMessages((prev) => [...prev, { id: nextId(), role: 'user', text }]);
      setInput('');
      setSending(true);
      try {
        // 带上角色回传最近几轮：服务端据此还原上下文（纯文本数组会让它分不清谁说的）
        const history: DuoduoChatTurn[] = messages
          .slice(-HISTORY_LIMIT)
          .map((m) => ({ role: m.role, content: m.text }));
        const res = await duoduoApi.chat({ message: text, history });
        setMessages((prev) => [
          ...prev,
          { id: nextId(), role: 'assistant', text: res.reply, recommendations: res.recommendations },
        ]);
      } catch (error) {
        const text429 = error instanceof ApiError && error.status === 429;
        const msg = text429 ? '问得太快啦，歇一分钟再来吧' : toFriendlyMessage(error);
        push(msg, 'error');
        // 失败也补一条气泡：只弹 toast 的话，用户会觉得「我问的这句话没了」
        setMessages((prev) => [...prev, { id: nextId(), role: 'assistant', text: msg }]);
      } finally {
        setSending(false);
      }
    },
    [input, sending, messages, push],
  );

  if (capLoading) {
    return <LoadingBlock text="正在连接 AI 导购…" />;
  }

  const available = capability?.chatAvailable === true;

  return (
    <div className="container section">
      <div className="section-head">
        <h1 className="section-title">AI 导购「多多」</h1>
        <span className="small muted">描述你的需求，多多从真实商品库里帮你挑</span>
      </div>

      {capability ? (
        <Alert kind={available ? 'info' : 'warn'}>
          {capability.hint}
          {capability.mode === 'mock' ? '（当前为演示模式，推荐商品仍来自真实商品库）' : null}
        </Alert>
      ) : null}

      <div
        className="card"
        style={{ marginTop: 14, display: 'flex', flexDirection: 'column', height: 460, padding: 0 }}
      >
        <div ref={listRef} style={{ flex: 1, overflowY: 'auto', padding: 16 }}>
          {messages.length === 0 ? (
            <div className="muted small">
              你好，我是多多 🐾 想找什么？比如「幼犬狗粮」「猫咪零食」，我帮你从在售商品里挑几款。
            </div>
          ) : null}

          {messages.map((m) => (
            <div
              key={m.id}
              style={{
                display: 'flex',
                justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start',
                marginBottom: 12,
              }}
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
                  {m.text}
                </div>

                {m.recommendations && m.recommendations.length > 0 ? (
                  <div style={{ marginTop: 8, display: 'grid', gap: 8 }}>
                    {m.recommendations.map((rec) => (
                      <Link
                        key={rec.id}
                        to={`/product/${rec.id}`}
                        className="card"
                        style={{ padding: 10, display: 'block' }}
                      >
                        <div style={{ fontWeight: 600 }}>{rec.title}</div>
                        {rec.subtitle ? <div className="small muted">{rec.subtitle}</div> : null}
                        <div style={{ marginTop: 4, display: 'flex', gap: 10, alignItems: 'center' }}>
                          <span style={{ color: '#e5484d', fontWeight: 600 }}>
                            {formatPrice(rec.minPrice)}
                            {rec.maxPrice > rec.minPrice ? ' 起' : ''}
                          </span>
                          {rec.inStock ? (
                            <span className="small muted">有货</span>
                          ) : (
                            <span className="small muted">暂时缺货</span>
                          )}
                        </div>
                      </Link>
                    ))}
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
              {QUICK_QUESTIONS.map((q) => (
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

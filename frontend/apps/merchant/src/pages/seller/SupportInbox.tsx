/**
 * 商家工作台 · 客服信息（第七十九轮 A 批改造）。
 *
 * <h2>本轮修的四件事</h2>
 * <ol>
 *   <li><b>筛选按未读</b>：会话状态（进行中/已关闭）不是运营每天筛的对象，
 *       改为「全部 / 未读」；关闭状态用行内标签表达，不再占筛选位。</li>
 *   <li><b>未读数字</b>：接口把 long 序列化成字符串，`reduce(sum + s.unread)` 会变成
 *       字符串拼接（截图里的 `0000000000000000000`）。统一 `Number()`。</li>
 *   <li><b>点开即清零</b>：标记已读成功后本地乐观置 0 并刷新汇总，不等回复、不等刷新。</li>
 *   <li><b>卡片只读弹窗</b>：卡片不再是指向商城的外链（会带着当前浏览器里商城的登录态打开），
 *       改为工作台内弹窗展示图片/标题/价格。</li>
 * </ol>
 *
 * <h2>已读回执</h2>
 * 服务端返回 `peerLastReadId` 与 `myUnreadCount`（对端未读的我方条数）：
 * 有未读 → 在最后一条我方消息下显示「未读 N 条」；读完了 → 显示「已读」。
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  sellerSupportApi,
  type SupportMessageView,
  type SupportProductCard,
  type SupportSessionView,
  type SupportThreadView,
} from '@duoduo/api-sdk/endpoints';
import { productImage } from '@duoduo/api-sdk/images';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Empty, LoadingBlock, Modal, useToast } from '@duoduo/shared/components/ui';
import { formatBjShort } from '@duoduo/shared/lib/time';
import { ChatMediaMenu, MediaBubble, isEmojiOnly, type MediaPayload } from '@duoduo/shared/components/chat-media';
import { realtime, useRealtime } from '@duoduo/shared/realtime';
import { OrderCard } from '@duoduo/shared/components/order-card';

const POLL_MS = 5000;
const PAGE_SIZE = 30;

/**
 * 会话筛选（2026-10-23 扩展）。
 *
 * ALL 全部 / UNREAD 还有待回 / ANSWERED 已处理（没有待回，含已关闭）/ CLOSED 已关闭。
 */
type Filter = 'ALL' | 'UNREAD' | 'ANSWERED' | 'CLOSED';

const FILTERS: { value: Filter; label: string; hint: string }[] = [
  { value: 'ALL', label: '全部', hint: '本店全部客服会话' },
  { value: 'UNREAD', label: '未读', hint: '还有买家消息没处理' },
  { value: 'ANSWERED', label: '已处理', hint: '没有待回复的会话（已读）' },
  { value: 'CLOSED', label: '已关闭', hint: '买家已结束的会话' },
];

export const SellerSupportPage: React.FC = () => {
  const { push } = useToast();
  const [filter, setFilter] = useState<Filter>('ALL');
  const [sessions, setSessions] = useState<SupportSessionView[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [thread, setThread] = useState<SupportThreadView | null>(null);
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  /** 非店铺主体（运营 / 店员）访问：后端 403，这里转成说明页 */
  const [denied, setDenied] = useState(false);
  const [card, setCard] = useState<SupportProductCard | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);
  const bodyRef = useRef<HTMLDivElement | null>(null);
  const atBottomRef = useRef(true);
  /** 买家正在输入（2026-10-23）：3 秒无新事件自动消失 */
  const [peerTyping, setPeerTyping] = useState(false);
  const typingTimer = useRef<number | null>(null);

  const loadSessions = useCallback(async () => {
    try {
      const page = await sellerSupportApi.sessions({
        filter,
        page: 1,
        pageSize: 50,
      });
      const rows = page?.items ?? [];
      setSessions(rows);
      setError(null);
      return rows;
    } catch (e) {
      const code = (e as { code?: string } | null)?.code;
      if (code === 'FORBIDDEN') {
        setDenied(true);
      } else {
        setError(toFriendlyMessage(e));
      }
      return [];
    }
  }, [filter]);

  const loadThread = useCallback(async (id: string) => {
    try {
      const data = await sellerSupportApi.messages(id, { page: 1, pageSize: PAGE_SIZE });
      setThread(data);
      // 点开即清零（与微信一致）：不等回复、不等下一次刷新
      void sellerSupportApi.markRead(id).catch(() => undefined);
      setSessions((prev) => prev.map((s) => (s.id === id ? { ...s, unread: 0 } : s)));
      return data;
    } catch (e) {
      setError(toFriendlyMessage(e));
      return null;
    }
  }, []);

  useEffect(() => {
    let alive = true;
    (async () => {
      setLoading(true);
      await loadSessions();
      // 只加载列表，不自动选第一条：自动选中会在用户还没看的时候就把会话标成已读，
      // 未读角标莫名其妙消失（这是比"角标不清零"更难排查的问题）。
      if (alive) setLoading(false);
    })();
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filter]);

  useEffect(() => {
    if (!activeId) return undefined;
    const timer = window.setInterval(() => void loadThread(activeId), POLL_MS);
    return () => window.clearInterval(timer);
  }, [activeId, loadThread]);

  /**
   * 实时事件（2026-10-23）：新消息/已读/正在输入。
   * 当前会话 → 立即重拉；其它会话只刷列表。轮询保留为断线兜底。
   */
  useRealtime((event) => {
    if (event.biz !== 'SUPPORT') return;
    const same = Boolean(activeId) && String(event.sessionId) === String(activeId);
    if (event.type === 'MESSAGE') {
      if (same) void loadThread(activeId as string);
      void loadSessions();
      if (same) setPeerTyping(false);
      return;
    }
    if (event.type === 'READ' && same) {
      void loadThread(activeId as string);
      return;
    }
    if (event.type === 'TYPING' && same) {
      setPeerTyping(true);
      if (typingTimer.current) window.clearTimeout(typingTimer.current);
      typingTimer.current = window.setTimeout(() => setPeerTyping(false), 3000);
    }
  });

  /**
   * 打开会话先定位到最新未读（2026-10-23）。
   *
   * 之前只有「条数变化滚到底」，而 `.sup-body` 是内部滚动容器，首次渲染时
   * `scrollIntoView` 很容易滚到窗口上，表现为「点开还停在很早的消息」。
   * 这里按 data-first-unread 标记定位；没有未读就落到底部。
   * 依赖只放 sessionId —— 轮询不该把正在翻历史的接待员拽回底部。
   */
  useEffect(() => {
    if (!thread?.items.length) return;
    const target = bodyRef.current?.querySelector('[data-first-unread="1"]');
    if (target) {
      target.scrollIntoView({ block: 'center' });
      atBottomRef.current = false;
    } else {
      bottomRef.current?.scrollIntoView({ block: 'end' });
      atBottomRef.current = true;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [thread?.sessionId]);

  // 会话内新增消息：只有本来就在底部（或自己刚回复）时才跟着滚。
  // ★ 依赖用最后一条消息 id 而不是 items.length：分页窗口固定 30 条，
  //   长会话里再发一条长度仍是 30（挤掉最老的），长度不变 effect 就不跑（2026-10-23 实测）。
  useEffect(() => {
    if (!thread?.items.length) return;
    const last = thread.items[thread.items.length - 1];
    if (last?.mine || atBottomRef.current) {
      bottomRef.current?.scrollIntoView({ block: 'end' });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [thread?.items[thread.items.length - 1]?.id]);

  const select = async (id: string) => {
    setActiveId(id);
    setThread(null);
    await loadThread(id);
    // 已读后「未读」筛选下这条会消失，刷新列表让状态与筛选一致
    if (filter === 'UNREAD') void loadSessions();
  };

  const reply = async () => {
    const content = draft.trim();
    if (!content || !activeId || sending) return;
    setSending(true);
    try {
      await sellerSupportApi.reply(activeId, { content });
      setDraft('');
      await loadThread(activeId);
      await loadSessions();
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    } finally {
      setSending(false);
    }
  };

  const sendMedia = async (payload: MediaPayload, kind: 'IMAGE' | 'VIDEO') => {
    if (!activeId) return;
    try {
      await sellerSupportApi.reply(activeId, {
        content: '',
        type: kind,
        mediaUrl: payload.mediaUrl,
        thumbUrl: payload.thumbUrl,
        width: payload.width,
        height: payload.height,
        durationMs: payload.durationMs,
      });
      await loadThread(activeId);
      await loadSessions();
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };

  const sendSticker = async (url: string) => {
    if (!activeId) return;
    try {
      await sellerSupportApi.reply(activeId, { content: '', type: 'EMOJI', mediaUrl: url });
      await loadThread(activeId);
      await loadSessions();
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };


  // 说明：关闭 / 重开会话的按钮已下线（2026-10-23）—— 会话只能由买家结束，
  // 客服侧只保留「看见状态」与「按状态筛选」的能力。买家再发消息会自动重开。

  // ★ 数字必须显式转 Number：接口把 long 序列化成字符串
  const unreadTotal = useMemo(
    () => sessions.reduce((sum, s) => sum + Number(s.unread || 0), 0),
    [sessions],
  );

  if (loading) return <LoadingBlock text="正在加载客服会话…" />;

  if (denied) {
    return (
      <div>
        <h2 className="section-title">客服信息</h2>
        <div className="card card-pad" style={{ marginTop: 12 }}>
          <p style={{ marginTop: 0 }}>
            当前账号不是任何店铺的有效成员，无法接待客服会话。
          </p>
          <ul className="small muted" style={{ lineHeight: 1.9, paddingLeft: 18 }}>
            <li>客服会话按**店铺**归属：店主与在职店员看到的是同一份列表（2026-10-23 起）。</li>
            <li>平台运营 / 管理员只处理平台级事务（审核、仲裁、干预），不读买卖双方会话内容。</li>
            <li>若你刚被加入店铺，请确认入驻状态为「已通过」且成员状态为「在职」。</li>
          </ul>
          <p className="small muted" style={{ marginBottom: 0 }}>
            如需查看某家店铺的客服记录，请让该店铺的店主或店员登录商家工作台。
          </p>
        </div>
      </div>
    );
  }

  return (
    <div>
      <h2 className="section-title">客服信息</h2>
      <p className="small muted" style={{ marginBottom: 12 }}>
        接收并处理买家发来的咨询。共 {sessions.length} 个会话，未读 {unreadTotal} 条。
        闲置/二手交易属于买卖双方私信，不在这里。
      </p>

      {error && <Alert kind="error">{error}</Alert>}

      <div className="row" style={{ gap: 8, marginBottom: 12 }}>
        {FILTERS.map((f) => (
          <button
            key={f.value}
            type="button"
            title={f.hint}
            className={`btn btn-sm ${filter === f.value ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => {
              setFilter(f.value);
              setActiveId(null);
              setThread(null);
            }}
          >
            {f.label}
          </button>
        ))}
      </div>

      <div className="sup-layout">
        <aside className="card sup-list">
          {sessions.length === 0 ? (
            <p className="small muted" style={{ padding: 12 }}>
              {filter === 'UNREAD'
                ? '没有未读会话'
                : filter === 'ANSWERED'
                  ? '还没有已处理的会话'
                  : filter === 'CLOSED'
                    ? '还没有已关闭的会话'
                    : '暂无会话'}
            </p>
          ) : (
            sessions.map((s) => {
              const isActive = s.id === activeId;
              return (
                <button
                  key={s.id}
                  type="button"
                  onClick={() => void select(s.id)}
                  className={`sup-item${isActive ? ' active' : ''}`}
                >
                  <span style={{ minWidth: 0 }}>
                    <span style={{ display: 'block', fontSize: 14 }}>
                      {s.userName || `用户${s.userId}`}
                      {s.status === 'CLOSED' && (
                        <span className="tag tag-warn" style={{ marginLeft: 6, fontSize: 11 }}>已关闭</span>
                      )}
                    </span>
                    <span className="sup-item-preview">{s.lastMessage || '暂无消息'}</span>
                  </span>
                  {Number(s.unread) > 0 && (
                    <span className="sup-badge">{Number(s.unread) > 99 ? '99+' : Number(s.unread)}</span>
                  )}
                </button>
              );
            })
          )}
        </aside>

        <section className="card sup-thread">
          <header className="sup-thread-head">
            <strong>{thread ? thread.userName || `用户${thread.userId}` : '选择左侧会话'}</strong>
            {thread && (
              <span className="row" style={{ gap: 8, alignItems: 'center' }}>
                {thread.status === 'CLOSED' ? (
                  <span className="tag tag-warn" style={{ fontSize: 11 }}>已关闭</span>
                ) : null}
                <span className="small muted">
                  {formatBjShort(thread.items[thread.items.length - 1]?.createdAt)}
                </span>
              </span>
            )}
          </header>

          <div
            className="sup-body"
            ref={bodyRef}
            onScroll={(e) => {
              const el = e.currentTarget;
              atBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
            }}
          >
            {!thread || thread.items.length === 0 ? (
              <Empty title="没有消息" desc="买家还没有发来咨询" />
            ) : (
              thread.items.map((m, idx) => {
                const isLastMine = m.mine && !thread.items.slice(idx + 1).some((x) => x.mine);
                const firstUnread =
                  !m.mine && !m.read && !thread.items.slice(0, idx).some((x) => !x.mine && !x.read);
                return (
                  <Bubble
                    key={m.id}
                    message={m}
                    unreadTag={m.mine && !m.read}
                    firstUnread={firstUnread}
                    receipt={
                      isLastMine && Number(thread.myUnreadCount) === 0 ? '已读' : null
                    }
                    onOpenCard={setCard}
                  />
                );
              })
            )}
            <div ref={bottomRef} />
          </div>

          <footer className="sup-foot">
            {!thread ? (
              <p className="small muted" style={{ margin: 0 }}>
                选择左侧一个会话后才能回复买家。
              </p>
            ) : (
            <>
            {peerTyping ? <div className="sup-typing">买家正在输入…</div> : null}
            <div className="row" style={{ gap: 8 }}>
              <ChatMediaMenu
                onMedia={sendMedia}
                onEmoji={(emoji) => setDraft((v) => v + emoji)}
                onSticker={(url) => void sendSticker(url)}
                onError={(msg) => push(msg, 'error')}
                disabled={sending || !thread || thread.status === 'CLOSED'}
              />
              <input
                className="input"
                style={{ flex: 1 }}
                placeholder={thread?.status === 'CLOSED' ? '会话已关闭（买家发消息会自动重开）' : '回复买家…'}
                value={draft}
                onChange={(e) => {
                  setDraft(e.target.value);
                  if (activeId) realtime.typing('SUPPORT', { sessionId: activeId });
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    void reply();
                  }
                }}
                disabled={sending || !thread || thread.status === 'CLOSED'}
              />
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => void reply()}
                disabled={sending || !draft.trim() || !thread || thread.status === 'CLOSED'}
              >
                {sending ? '发送中…' : '回复'}
              </button>
            </div>
            </>
            )}
          </footer>
        </section>
      </div>

      {/* 商品卡只读弹窗：不跳商城、不带登录态离开工作台 */}
      <Modal
        open={Boolean(card)}
        title="商品信息"
        onClose={() => setCard(null)}
        footer={
          <button type="button" className="btn btn-ghost" onClick={() => setCard(null)}>
            关闭
          </button>
        }
      >
        {card && (
          <div className="row" style={{ gap: 12, alignItems: 'flex-start' }}>
            <img
              className="sup-card-img"
              src={productImage(card.productId, card.image)}
              alt=""
              style={{ width: 96, height: 96 }}
            />
            <div style={{ minWidth: 0 }}>
              <div style={{ fontWeight: 600 }}>{card.title || '商品'}</div>
              {card.price != null && (
                <div className="small sup-card-price" style={{ marginTop: 6 }}>
                  ¥{(card.price / 100).toFixed(2)}
                </div>
              )}
              <div className="small muted" style={{ marginTop: 6 }}>
                商品 ID：{card.productId}
              </div>
              <div className="small muted" style={{ marginTop: 10 }}>
                工作台内只读查看；如需处理该商品，请到「商品管理」。
              </div>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
};

function renderContent(message: SupportMessageView) {
  if (message.bizType === 'ORDER') {
    // 商家端只读：订单详情是买家页面，跳过去会带着当前浏览器里商城的登录态
    return <OrderCard metaJson={message.metaJson} href={null} />;
  }
  const kind = message.bizType === 'IMAGE' || message.bizType === 'VIDEO' || message.bizType === 'EMOJI'
    ? message.bizType
    : null;
  if (kind) {
    return (
      <>
        <MediaBubble
          kind={kind}
          url={message.mediaUrl || ''}
          thumbUrl={message.thumbUrl}
          width={message.width}
          height={message.height}
          durationMs={message.durationMs}
        />
        {message.content && !/^\[(图片|视频|表情)\]$/.test(message.content) ? (
          <div className="sup-bubble" style={{ marginTop: 6 }}>{message.content}</div>
        ) : null}
      </>
    );
  }
  const emojiOnly = isEmojiOnly(message.content);
  return (
    <div className="sup-bubble" style={emojiOnly ? { fontSize: 30, lineHeight: 1.25 } : undefined}>
      {message.content}
    </div>
  );
}

const Bubble: React.FC<{
  message: SupportMessageView;
  /** 我发出、对方还没读的消息：时间行右侧显示琥珀色「未读」，与「已读」同位置 */
  unreadTag?: boolean;
  /** 打开会话时的定位锚点（最新未读） */
  firstUnread?: boolean;
  receipt: string | null;
  onOpenCard: (card: SupportProductCard) => void;
}> = ({ message, unreadTag, firstUnread, receipt, onOpenCard }) => (
  <div className={`sup-row${message.mine ? ' mine' : ''}`} data-first-unread={firstUnread ? '1' : undefined}>
    <div className="sup-col">
      {renderContent(message)}
      {message.card && (
        <button
          type="button"
          className="card sup-card"
          style={{ textAlign: 'left', cursor: 'pointer' }}
          onClick={() => onOpenCard(message.card as SupportProductCard)}
        >
          <img
            className="sup-card-img"
            src={productImage(message.card.productId, message.card.image)}
            alt=""
          />
          <span style={{ minWidth: 0 }}>
            <span className="sup-card-title">{message.card.title}</span>
            {message.card.price != null && (
              <span className="small sup-card-price">¥{(message.card.price / 100).toFixed(2)}</span>
            )}
          </span>
        </button>
      )}
      <div className="sup-time">
        {formatBjShort(message.createdAt)}
        {unreadTag ? (
          <span className="sup-unread"> · 未读</span>
        ) : receipt ? (
          ` · ${receipt}`
        ) : null}
      </div>
    </div>
  </div>
);

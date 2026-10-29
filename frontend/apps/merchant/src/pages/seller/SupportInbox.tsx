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
  uploadApi,
  type SupportMessageView,
  type SupportProductCard,
  type SupportSessionView,
  type SupportThreadView,
  type SupportTicketView,
} from '@duoduo/api-sdk/endpoints';
import { productImage } from '@duoduo/api-sdk/images';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Empty, Field, LoadingBlock, Modal, useToast } from '@duoduo/shared/components/ui';
import { formatBjShort } from '@duoduo/shared/lib/time';
import { ChatMediaMenu, MediaBubble, VoiceBubble, useVoiceRecorder, QuotedStrip, RecallLine,
  isEmojiOnly, MessageActions, MultiSelectBar, type MediaPayload } from '@duoduo/shared/components/chat-media';
import { realtime, useRealtime } from '@duoduo/shared/realtime';
import { OrderCard } from '@duoduo/shared/components/order-card';
import { chatSeparatorLabel } from '@duoduo/shared/lib/time';
import { useConfirm } from '@duoduo/shared/components/confirm';
import { useAuth } from '@duoduo/shared/store/auth';

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
  const { user } = useAuth();
  const confirm = useConfirm();
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
  /** C-04 · 工单：认领 / 转交 / 释放 / 处理记录 */
  const [ticketOpen, setTicketOpen] = useState(false);
  const [ticket, setTicket] = useState<SupportTicketView | null>(null);
  const [ticketLoading, setTicketLoading] = useState(false);
  const [ticketBusy, setTicketBusy] = useState(false);
  const [ticketTarget, setTicketTarget] = useState('');
  const [ticketNote, setTicketNote] = useState('');

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

  const loadThread = useCallback(async (id: string, aroundId?: string) => {
    try {
      const data = await sellerSupportApi.messages(id, { page: 1, pageSize: PAGE_SIZE, aroundId });
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

  // ---------------- C-04 · 工单 ----------------

  const openTicket = async () => {
    if (!activeId) return;
    setTicketOpen(true);
    setTicketLoading(true);
    setTicketNote('');
    setTicketTarget('');
    try {
      const data = await sellerSupportApi.ticket(activeId);
      setTicket(data);
      setTicketTarget(
        data.members.find((m) => m.userId !== data.assigneeId)?.userId ?? '',
      );
    } catch (e) {
      setError(toFriendlyMessage(e));
      setTicketOpen(false);
    } finally {
      setTicketLoading(false);
    }
  };

  const applyTicket = (data: SupportTicketView, message: string) => {
    setTicket(data);
    setTicketTarget(data.members.find((m) => m.userId !== data.assigneeId)?.userId ?? '');
    void loadSessions();
    push(message, 'success');
  };

  const claimTicket = async () => {
    if (!activeId || ticketBusy) return;
    setTicketBusy(true);
    try {
      const data = await sellerSupportApi.claimTicket(activeId);
      applyTicket(data, `已认领，当前处理人：${data.assigneeName ?? '我'}`);
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    } finally {
      setTicketBusy(false);
    }
  };

  const releaseTicket = async () => {
    if (!activeId || ticketBusy) return;
    setTicketBusy(true);
    try {
      const data = await sellerSupportApi.releaseTicket(activeId);
      applyTicket(data, '已释放为未认领，其他同事可以接手');
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    } finally {
      setTicketBusy(false);
    }
  };

  const submitTransfer = async () => {
    if (!activeId || ticketBusy) return;
    if (!ticketTarget) {
      push('请选择转交对象', 'error');
      return;
    }
    setTicketBusy(true);
    try {
      const data = await sellerSupportApi.transferTicket(activeId, {
        toUserId: ticketTarget,
        note: ticketNote.trim() || undefined,
      });
      applyTicket(data, `已转交给 ${data.assigneeName ?? '同事'}，处理记录已留痕`);
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    } finally {
      setTicketBusy(false);
    }
  };

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
    // 撤回信号（2026-10-24）：回源刷新，灰字替换原气泡
    if (event.type === 'RECALL') {
      if (same) void loadThread(activeId as string);
      void loadSessions();
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

  // ---------------- 引用 / 撤回 / 删除 / 语音（2026-10-24/25） ----------------
  const [replyTo, setReplyTo] = useState<SupportMessageView | null>(null);
  /** 多选删除模式（商家侧同样单向：只对商家隐藏，买家不受影响） */
  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [pendingScrollId, setPendingScrollId] = useState<string | null>(null);
  const recallWithinWindow = (m: SupportMessageView): boolean =>
    m.mine && !m.recalledAt && Date.now() - new Date(m.createdAt).getTime() < 120_000;

  const recallMessage = async (m: SupportMessageView) => {
    if (!(await confirm({ title: '撤回回复', message: '撤回后双方都看不到这条回复，确定撤回吗？' }))) return;
    try {
      await sellerSupportApi.recall(activeId as string, m.id);
      await loadThread(activeId as string);
      void loadSessions();
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };

  /** 单向删除（2026-10-25）：只对商家侧隐藏，买家仍可见 */
  const deleteMessages = async (ids: string[]) => {
    if (ids.length === 0 || !activeId) return;
    if (!(await confirm({
      title: '删除消息',
      message: ids.length === 1 ? '删除后仅本店看不到这条消息，买家不受影响。确定删除吗？'
        : `已选 ${ids.length} 条，删除后仅本店看不到，买家不受影响。确定删除吗？`,
      danger: true,
      confirmText: '删除',
    }))) return;
    try {
      await sellerSupportApi.deleteMessages(activeId, ids);
      setSelectedIds(new Set());
      setSelectMode(false);
      await loadThread(activeId);
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };

  const scrollToMessage = (messageId: string) => {
    const el = bodyRef.current?.querySelector(`[data-mid="${messageId}"]`);
    if (el) {
      el.scrollIntoView({ block: 'center' });
      el.classList.remove('msg-flash');
      void (el as HTMLElement).offsetWidth;
      el.classList.add('msg-flash');
    }
  };
  /** 引用跳转定位（2026-10-25）：目标不在窗口内时用 aroundId 拉一页 */
  const jumpToQuoted = async (messageId: string | null | undefined) => {
    if (!messageId || !activeId) return;
    if (thread?.items.some((x) => x.id === messageId)) {
      scrollToMessage(messageId);
    } else {
      setPendingScrollId(messageId);
      await loadThread(activeId, messageId);
    }
  };
  useEffect(() => {
    if (!pendingScrollId || !thread?.items.length) return;
    if (!thread.items.some((x) => x.id === pendingScrollId)) return;
    scrollToMessage(pendingScrollId);
    setPendingScrollId(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [thread?.items[thread.items.length - 1]?.id, pendingScrollId]);

  const sendVoice = async (blob: Blob, durationMs: number) => {
    if (!activeId) return;
    const ext = blob.type.includes('mp4') ? 'mp4' : 'webm';
    try {
      const file = new File([blob], `voice.${ext}`, { type: blob.type });
      const { url } = await uploadApi.image(file);
      await sellerSupportApi.reply(activeId, { content: '[语音]', type: 'VOICE', mediaUrl: url, durationMs });
      await loadThread(activeId);
      await loadSessions();
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };
  const recorder = useVoiceRecorder({
    onReady: (blob, durationMs) => void sendVoice(blob, durationMs),
    onError: (message) => push(message, 'error'),
  });

  const reply = async () => {
    const content = draft.trim();
    if (!content || !activeId || sending) return;
    setSending(true);
    try {
      await sellerSupportApi.reply(activeId, { content, replyToId: replyTo?.id });
      setDraft('');
      setReplyTo(null);
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
  /** C-04：当前会话的工单状态；店主 = 会话归属店铺主体 */
  const activeSession = sessions.find((s) => s.id === activeId) ?? null;
  const isOwner = Boolean(user?.id && activeSession && String(user.id) === String(activeSession.sellerId));

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
        <button
          className="btn btn-sm btn-ghost"
          type="button"
          onClick={() => {
            window.location.href = sellerSupportApi.sessionsExportUrl({ filter });
          }}
        >
          导出 CSV
        </button>
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
                    <span className="small muted" style={{ display: 'block', marginTop: 2 }}>
                      {s.assigneeName ? `处理人：${s.assigneeName}` : '未认领'}
                    </span>
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
                <span className="tag" style={{ fontSize: 11 }}>
                  {activeSession?.assigneeName ? `处理人：${activeSession.assigneeName}` : '未认领'}
                </span>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => void openTicket()}
                  disabled={!activeId}
                >
                  工单
                </button>
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
              <>
                {thread.items.map((m, idx) => {
                  const isLastMine = m.mine && !thread.items.slice(idx + 1).some((x) => x.mine);
                  const firstUnread =
                    !m.mine && !m.read && !thread.items.slice(0, idx).some((x) => !x.mine && !x.read);
                  const separator = chatSeparatorLabel(m.createdAt, idx > 0 ? thread.items[idx - 1]?.createdAt : null);
                  const checked = selectedIds.has(m.id);
                  return (
                    <React.Fragment key={m.id}>
                      {separator ? <div className="chat-time-sep">{separator}</div> : null}
                      {selectMode ? (
                        <label className={`sup-row${m.mine ? ' mine' : ''} msg-select-row`} data-mid={m.id}>
                          <input
                            type="checkbox"
                            className="msg-check"
                            checked={checked}
                            onChange={() =>
                              setSelectedIds((prev) => {
                                const next = new Set(prev);
                                if (next.has(m.id)) next.delete(m.id); else next.add(m.id);
                                return next;
                              })
                            }
                          />
                          <div className="sup-col">
                            {m.recalledAt ? <RecallLine mine={m.mine} /> : <MsgBody message={m} />}
                          </div>
                        </label>
                      ) : m.recalledAt ? (
                        <div className="sup-row" data-mid={m.id}>
                          <RecallLine mine={m.mine} />
                        </div>
                      ) : (
                        <Bubble
                          message={m}
                          unreadTag={m.mine && !m.read}
                          firstUnread={firstUnread}
                          receipt={isLastMine && Number(thread.myUnreadCount) === 0 ? '已读' : null}
                          onOpenCard={setCard}
                          onQuote={(mm) => setReplyTo(mm)}
                          onRecall={(mm) => void recallMessage(mm)}
                          canRecall={recallWithinWindow(m)}
                          onDelete={(mm) => void deleteMessages([mm.id])}
                          onMultiSelect={() => { setSelectMode(true); setSelectedIds(new Set()); }}
                          onQuotedClick={() => void jumpToQuoted(m.replyToId)}
                        />
                      )}
                    </React.Fragment>
                  );
                })}
              </>
            )}
            {selectMode ? (
              <MultiSelectBar
                count={selectedIds.size}
                totalCount={thread?.items.length ?? 0}
                onToggleAll={() =>
                  setSelectedIds((prev) =>
                    prev.size === (thread?.items.length ?? 0)
                      ? new Set()
                      : new Set((thread?.items ?? []).map((x) => x.id)))
                }
                onDelete={() => void deleteMessages([...selectedIds])}
                onCancel={() => { setSelectMode(false); setSelectedIds(new Set()); }}
              />
            ) : null}
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
            {replyTo ? (
              <div className="chat-quoted" role="button" onClick={() => setReplyTo(null)} title="点击取消引用">
                引用 买家：{replyTo.quoted?.excerpt ?? replyTo.content}
                {'　✕'}
              </div>
            ) : null}
            {recorder.status !== 'idle' ? (
              <div className="recording-tip recording-bar" role="status">
                <span>{recorder.status === 'paused' ? '已暂停' : '录音中'} {recorder.seconds}s（最长 60 秒）</span>
                <button type="button" className="btn btn-sm btn-ghost" onClick={recorder.cancel}>取消</button>
                {recorder.status === 'paused'
                  ? <button type="button" className="btn btn-sm btn-ghost" onClick={recorder.resume}>继续</button>
                  : <button type="button" className="btn btn-sm btn-ghost" onClick={recorder.pause}>暂停</button>}
                <button type="button" className="btn btn-sm btn-primary" onClick={recorder.stop}>结束并发送</button>
              </div>
            ) : null}
            <div className="row" style={{ gap: 8 }}>
              <ChatMediaMenu
                onMedia={sendMedia}
                onEmoji={(emoji) => setDraft((v) => v + emoji)}
                onSticker={(url) => void sendSticker(url)}
                onError={(msg) => push(msg, 'error')}
                disabled={sending || !thread || thread.status === 'CLOSED'}
              />
              <button
                type="button"
                className="btn btn-sm btn-ghost mic-btn"
                disabled={sending || !thread || thread.status === 'CLOSED'}
                onClick={() => (recorder.status === 'idle' ? recorder.start() : recorder.stop())}
                aria-label={recorder.status === 'idle' ? '开始录音' : '结束并发送'}
                title={recorder.status === 'idle' ? '点击开始录音' : '点击结束并发送'}
              >
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <rect x="9" y="3" width="6" height="11" rx="3" fill="currentColor" />
                  <path d="M5 11a7 7 0 0 0 14 0" stroke="currentColor" strokeWidth="2" strokeLinecap="round" fill="none" />
                  <path d="M12 18v3M8.5 21h7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
                </svg>
              </button>
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

      {/* C-04 · 客服工单：认领 / 转交 / 释放 + 处理记录 */}
      <Modal
        open={ticketOpen}
        title="客服工单"
        onClose={() => setTicketOpen(false)}
        footer={
          <button type="button" className="btn btn-ghost" onClick={() => setTicketOpen(false)}>
            关闭
          </button>
        }
      >
        {ticketLoading || !ticket ? (
          <LoadingBlock text="正在读取工单…" />
        ) : (
          <div>
            <Alert kind="info">
              处理人只表示「现在是谁在接这个买家」，不改变店主与在职店员都能查看全店会话的权限。
              认领与转交都会写入处理记录，供交接与审计追溯。
            </Alert>

            <div className="row-between row-wrap" style={{ marginTop: 12 }}>
              <span>
                当前处理人：<strong>{ticket.assigneeName ?? '未认领'}</strong>
                {ticket.assignedAt ? (
                  <span className="small muted" style={{ marginLeft: 8 }}>
                    {formatBjShort(ticket.assignedAt)}
                  </span>
                ) : null}
              </span>
              <div className="row" style={{ gap: 8 }}>
                {!ticket.assigneeId && ticket.status === 'OPEN' ? (
                  <button
                    type="button"
                    className="btn btn-primary btn-sm"
                    disabled={ticketBusy}
                    onClick={() => void claimTicket()}
                  >
                    {ticketBusy ? '处理中…' : '认领'}
                  </button>
                ) : null}
                {ticket.assigneeId && (isOwner || ticket.assigneeId === String(user?.id)) ? (
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm"
                    disabled={ticketBusy}
                    onClick={() => void releaseTicket()}
                  >
                    释放为未认领
                  </button>
                ) : null}
              </div>
            </div>

            {(() => {
              const candidates = ticket.members.filter((m) => m.userId !== ticket.assigneeId);
              const canTransfer = isOwner
                || !ticket.assigneeId
                || ticket.assigneeId === String(user?.id);
              if (!canTransfer) {
                return (
                  <p className="small muted" style={{ marginTop: 12 }}>
                    只有当前处理人或店主可以转交。当前由 {ticket.assigneeName ?? '同事'} 处理中。
                  </p>
                );
              }
              if (candidates.length === 0) {
                return (
                  <p className="small muted" style={{ marginTop: 12 }}>
                    本店暂无其他在职成员可转交（店主可在「店员管理」邀请同事）。
                  </p>
                );
              }
              return (
                <div style={{ marginTop: 12 }}>
                  <Field label="转交对象" hint="只能转交给本店在职成员（店主 / 店员）">
                    <select
                      className="input"
                      value={ticketTarget}
                      onChange={(e) => setTicketTarget(e.target.value)}
                      aria-label="转交对象"
                    >
                      <option value="">请选择</option>
                      {candidates.map((m) => (
                        <option key={m.userId} value={m.userId}>
                          {m.name}{m.role === 'OWNER' ? '（店主）' : ''}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="转交备注（可选）" hint="例如：我去处理发货问题，这笔咨询请接手">
                    <input
                      className="input"
                      value={ticketNote}
                      maxLength={200}
                      onChange={(e) => setTicketNote(e.target.value)}
                      placeholder="最多 200 字"
                    />
                  </Field>
                  <button
                    type="button"
                    className="btn btn-primary btn-sm"
                    disabled={ticketBusy || !ticketTarget}
                    onClick={() => void submitTransfer()}
                  >
                    {ticketBusy ? '转交中…' : '转交'}
                  </button>
                </div>
              );
            })()}

            <h3 style={{ marginTop: 18, marginBottom: 8, fontSize: 15 }}>处理记录</h3>
            {ticket.logs.length === 0 ? (
              <p className="small muted">还没有认领 / 转交记录。</p>
            ) : (
              <div className="stack" style={{ gap: 8 }}>
                {ticket.logs.slice().reverse().map((log) => (
                  <div key={log.id} className="card card-pad" style={{ padding: '8px 10px' }}>
                    <div className="small">
                      <strong>{log.actorName ?? '成员'}</strong> {log.actionLabel}
                      {log.action === 'TRANSFER'
                        ? `：${log.fromAssigneeName ?? '未认领'} → ${log.toAssigneeName ?? '未认领'}`
                        : ''}
                      {log.action === 'CLAIM' ? `（未认领 → ${log.toAssigneeName ?? '本人'}）` : ''}
                      {log.action === 'RELEASE' ? `（${log.fromAssigneeName ?? '本人'} → 未认领）` : ''}
                    </div>
                    <div className="small muted">
                      {formatBjShort(log.createdAt)}
                      {log.note ? ` · 备注：${log.note}` : ''}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </Modal>

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

/** 消息主体渲染，多选行与气泡共用 */
function MsgBody({ message, onQuotedClick }: { message: SupportMessageView; onQuotedClick?: () => void }) {
  if (message.bizType === 'ORDER') {
    // 商家端只读：订单详情是买家页面，跳过去会带着当前浏览器里商城的登录态
    return <OrderCard metaJson={message.metaJson} href={null} />;
  }
  if (message.bizType === 'VOICE') {
    return <VoiceBubble url={message.mediaUrl || ''} durationMs={message.durationMs} />;
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
      {message.quoted ? <QuotedStrip excerpt={message.quoted.excerpt} onClick={onQuotedClick} /> : null}
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
  onQuote?: (m: SupportMessageView) => void;
  onRecall?: (m: SupportMessageView) => void;
  canRecall?: boolean;
  onDelete?: (m: SupportMessageView) => void;
  onMultiSelect?: () => void;
  onQuotedClick?: () => void;
}> = ({ message, unreadTag, firstUnread, receipt, onOpenCard, onQuote, onRecall, canRecall, onDelete, onMultiSelect, onQuotedClick }) => (
  <div
    className={`sup-row${message.mine ? ' mine' : ''}`}
    data-first-unread={firstUnread ? '1' : undefined}
    data-mid={message.id}
  >
    <div className="sup-col">
      <MsgBody message={message} onQuotedClick={onQuotedClick} />
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
        {unreadTag ? <span className="sup-unread">未读</span> : receipt}
        <MessageActions
          onQuote={onQuote ? () => onQuote(message) : null}
          onRecall={onRecall && canRecall ? () => onRecall(message) : null}
          onDelete={onDelete ? () => onDelete(message) : null}
          onMultiSelect={onMultiSelect ?? null}
        />
      </div>
    </div>
  </div>
);

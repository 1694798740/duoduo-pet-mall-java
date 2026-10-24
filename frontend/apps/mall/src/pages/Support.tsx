/**
 * 客服会话页（Web）。
 *
 * <h2>为什么不是复用 Chat.tsx</h2>
 * 私信页里有「互相关注才能续聊」「只能发一条」「返回消息列表」这套社交语义，
 * 而客服页一条都不该有。把两套规则塞进一个组件，最后一定是靠 mode 参数分支，
 * 分支里漏一个判断就变成"客服要关注才能说话"这类离谱体验。
 *
 * <h2>一个用户可能有多条客服会话</h2>
 * 自营客服一条、每个买过东西的商家各一条。所以左侧是会话列表，右侧是当前会话；
 * 从商品页带 sellerId 进来时直接定位到对应店铺，没有则落到第一条。
 *
 * <h2>首条带商品卡</h2>
 * `productId` 只在**第一条**消息上附带（"我想了解一下「xx」"），发完就从地址栏语义里摘掉，
 * 否则用户后面每句话都会被塞一张商品卡。
 *
 * <h2>★ 卡片图片必须过 productImage()</h2>
 * 后端 main_image 存的是 `/img/products/xxx.svg` 这类**语义占位路径**，文件并不存在
 * （Web 端商品图裂图的同一个根因）；直接塞 `<img src>` 必然 404。
 * 必须经 `@duoduo/api-sdk/images` 的 `productImage()` 重映射到包内本地图池。
 *
 * <h2>★ 颜色不内联</h2>
 * 首版把激活底色写成 `var(--brand-weak, #eef1ff)`，但主题里只有 `--brand-soft` ——
 * 未定义变量永远落浅色兜底，暗夜档出现「浅底白字」。现在全部走 styles.css 的 `.sup-*`
 * 语义类，明暗两档由主题统一管理。
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  supportApi,
  uploadApi,
  type SupportMessageView,
  type SupportSessionView,
  type SupportThreadView,
} from '@duoduo/api-sdk/endpoints';
import { productImage } from '@duoduo/api-sdk/images';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Empty, LoadingBlock, useToast } from '@duoduo/shared/components/ui';
import { ProductPicker } from '@duoduo/shared/components/product-picker';
import { OrderCard, parseOrderMeta } from '@duoduo/shared/components/order-card';
import type { PickerProduct } from '@duoduo/api-sdk/endpoints';
import { ChatMediaMenu, MediaBubble, VoiceBubble, useVoiceRecorder, QuotedStrip, RecallLine,
  isEmojiOnly, MessageActions, MultiSelectBar, type MediaPayload } from '@duoduo/shared/components/chat-media';
import { realtime, useRealtime } from '@duoduo/shared/realtime';
import { chatSeparatorLabel } from '@duoduo/shared/lib/time';
import { useConfirm } from '@duoduo/shared/components/confirm';

const POLL_MS = 5000;
const PAGE_SIZE = 30;

const peerOf = (s: SupportSessionView): string => s.sellerName || `店铺${s.sellerId}`;

export const SupportPage: React.FC = () => {
  const [search] = useSearchParams();
  const { push } = useToast();
  const confirm = useConfirm();

  const sellerIdParam = search.get('sellerId') ?? undefined;

  const [sessions, setSessions] = useState<SupportSessionView[]>([]);
  const [activeSellerId, setActiveSellerId] = useState<string | undefined>(sellerIdParam);
  const [thread, setThread] = useState<SupportThreadView | null>(null);
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [pickerOpen, setPickerOpen] = useState(false);
  /**
   * 本次打开期间「已结束」的会话 id（2026-10-23）。
   *
   * <p>只放在本地：服务端对买家**不回** status（关闭对用户无感），所以刷新后无法得知。
   * 这也符合"结束"的语义 —— 它只是给客服侧的收尾信号，用户随时可以再发。
   */
  const [endedSessionId, setEndedSessionId] = useState<string | null>(null);
  /** 对方正在输入（2026-10-23）：3 秒没有新的 TYPING 事件自动消失 */
  const [peerTyping, setPeerTyping] = useState(false);
  const typingTimer = useRef<number | null>(null);
  const currentProductId = search.get('productId');
  const bottomRef = useRef<HTMLDivElement | null>(null);
  const activeItemRef = useRef<HTMLButtonElement | null>(null);
  const bodyRef = useRef<HTMLDivElement | null>(null);
  /** 当前是否停在底部：决定「新消息到达时要不要跟着滚」 */
  const atBottomRef = useRef(true);

  const loadSessions = useCallback(async () => {
    try {
      const rows = await supportApi.sessions();
      setSessions(rows ?? []);
      return rows ?? [];
    } catch (e) {
      setError(toFriendlyMessage(e));
      return [];
    }
  }, []);

  const loadThread = useCallback(async (sellerId?: string, aroundId?: string) => {
    try {
      const data = await supportApi.messages({ sellerId, page: 1, pageSize: PAGE_SIZE, aroundId });
      setThread(data);
      setError(null);
      if (data?.sessionId) {
        void supportApi.markRead(data.sessionId).catch(() => undefined);
        // 点开即清零：列表同步更新，不等下一次刷新
        setSessions((prev) => prev.map((x) => (x.sellerId === sellerId ? { ...x, unread: 0 } : x)));
      }
      return data;
    } catch (e) {
      setError(toFriendlyMessage(e));
      return null;
    }
  }, []);

  // 首次进入 / 切换目标店铺：拉会话列表 + 精确定位到该店铺的会话
  useEffect(() => {
    let alive = true;
    (async () => {
      setLoading(true);
      await loadSessions();
      if (!alive) return;
      // 从商品页/订单页进来时直接定位（这是明确意图）；否则不自动选，
      // 避免「没点开就被标已读」。
      if (sellerIdParam) {
        setActiveSellerId(sellerIdParam);
        const data = await loadThread(sellerIdParam);
        if (!alive) return;
        // sellerId=0 是自营口径，服务端把它落到官方客服账号：用返回的真实
        // sellerId 回填，左栏才能高亮到对应会话（否则 active 永远匹配不上）。
        if (data?.sellerId) setActiveSellerId(data.sellerId);
        // 首次咨询时会话是这次调用才创建的，补拉一次列表让它出现在左栏
        await loadSessions();
      }
      if (alive) setLoading(false);
    })();
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sellerIdParam]);

  // 定位到的会话若在左栏可视区外，滚动到可见（block: nearest 不会整页跳动）
  useEffect(() => {
    activeItemRef.current?.scrollIntoView({ block: 'nearest' });
  }, [activeSellerId, sessions.length]);

  /**
   * 实时事件（2026-10-23）：WebSocket 只推信号，渲染仍走 HTTP。
   *  · MESSAGE：当前会话 → 立即重拉；其它会话 → 只刷列表（未读角标）
   *  · READ：对端已读 → 重拉（把我方消息的「未读」标记与已读回执一起刷新）
   *  · TYPING：显示「对方正在输入…」，3 秒无新事件自动消失
   * 轮询保留为兜底（WS 断开时仍能靠 5 秒轮询收到消息）。
   */
  useRealtime((event) => {
    if (event.biz !== 'SUPPORT') return;
    const same = Boolean(thread?.sessionId) && String(event.sessionId) === String(thread?.sessionId);
    if (event.type === 'MESSAGE') {
      if (same) void loadThread(activeSellerId);
      void loadSessions();
      if (same) setPeerTyping(false);
      return;
    }
    if (event.type === 'READ' && same) {
      void loadThread(activeSellerId);
      return;
    }
    if (event.type === 'TYPING' && same) {
      setPeerTyping(true);
      if (typingTimer.current) window.clearTimeout(typingTimer.current);
      typingTimer.current = window.setTimeout(() => setPeerTyping(false), 3000);
    }
    // 撤回信号（2026-10-24）：对端撤回后回源刷新，灰字立刻替换原气泡
    if (event.type === 'RECALL') {
      if (same) void loadThread(activeSellerId);
      void loadSessions();
    }
  });

  // 轮询：客服回复不像私信那么即时，5 秒足够，也避免把轮询变成压力
  useEffect(() => {
    if (!activeSellerId) return undefined;
    const timer = window.setInterval(() => {
      void loadThread(activeSellerId);
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [activeSellerId, loadThread]);

  /**
   * 打开会话后的定位（2026-10-23）。
   *
   * <p>此前的实现只有「条数变化就滚到底」，而 `.sup-body` 是内部滚动容器：
   * `scrollIntoView` 在首次渲染时经常滚的是窗口而不是容器，结果页面停在第一条历史消息。
   *
   * <p>现在的顺序是：**先找最新未读，没有未读再到底**。
   * 定位点在渲染时打 `data-first-unread` 标记，这里用 querySelector 找它 ——
   * 逐条挂 ref 数组在长会话里既啰嗦又容易错位。
   * 依赖只放 sessionId：轮询刷新不该把正在往回翻历史的用户拽回底部。
   */
  useEffect(() => {
    if (!thread?.items.length) return;
    const container = bodyRef.current;
    const target = container?.querySelector('[data-first-unread="1"]');
    if (target) {
      target.scrollIntoView({ block: 'center' });
    } else {
      bottomRef.current?.scrollIntoView({ block: 'end' });
    }
    atBottomRef.current = !target;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [thread?.sessionId, loading]);

  /**
   * 会话内新增消息：只有本来就在底部（或自己刚发出）时才跟着滚，避免打断向上翻页。
   *
   * <p>★ 依赖必须是**最后一条消息的 id**，不能是 `items.length`：
   * 分页窗口固定 30 条，长会话里再发一条，数组长度仍然是 30（挤掉最老的一条），
   * 长度不变 → effect 不跑 → 视图停在原处（这就是「发送后不定位」的根因）。
   */
  useEffect(() => {
    if (!thread?.items.length) return;
    const last = thread.items[thread.items.length - 1];
    if (last?.mine || atBottomRef.current) {
      bottomRef.current?.scrollIntoView({ block: 'end' });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [thread?.items[thread.items.length - 1]?.id]);

  const switchTo = async (sellerId: string) => {
    setActiveSellerId(sellerId);
    setThread(null);
    await loadThread(sellerId);
  };

  /**
   * 发送的**唯一成功出口**（2026-10-23 修复）。
   *
   * <p>为什么必须收敛到一个函数：文字 / 图片视频 / 表情包 / 商品卡是四条不同的调用路径，
   * 而「发送成功 = 会话已被服务端重开」这件事对四者完全相同。此前把
   * `setEndedSessionId(null)` 只写在文字那条分支里，于是非文字消息发出去后
   * 底部仍挂着「当前会话已结束」、按钮也不回来 —— 典型的「状态清理只做了 1/N 条分支」。
   *
   * <p>现在四条路径都只调这里：新增消息类型时**不可能**忘记重置会话状态，
   * 因为成功点只有一个。
   */
  const deliver = async (payload: {
    content: string;
    type?: 'TEXT' | 'PRODUCT' | 'IMAGE' | 'VIDEO' | 'EMOJI' | 'VOICE';
    productId?: string;
    mediaUrl?: string;
    thumbUrl?: string;
    width?: number;
    height?: number;
    durationMs?: number;
    /** 引用的消息 id（2026-10-24）：随任意类型发送 */
    replyToId?: string;
  }) => {
    if (!activeSellerId) return;
    await supportApi.send({ sellerId: activeSellerId, ...payload });
    // 任意类型发送成功 → 服务端已把 CLOSED 置回 OPEN：撤下结束提示、恢复按钮
    setEndedSessionId(null);
    setReplyTo(null);
    await loadThread(activeSellerId);
    void loadSessions();
  };

  // ---------------- 引用 / 撤回 / 删除 / 语音（2026-10-24/25） ----------------
  /** 正在引用的消息：输入框上方显示摘要条，随下一条消息一起发出 */
  const [replyTo, setReplyTo] = useState<SupportMessageView | null>(null);
  /** 多选删除模式（2026-10-25）：勾选若干条批量「仅本人可见地」删除 */
  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  /** 引用跳转：目标不在当前窗口时，用它记住待定位 id，aroundId 拉回后滚动 */
  const [pendingScrollId, setPendingScrollId] = useState<string | null>(null);
  const recallWithinWindow = (m: SupportMessageView): boolean =>
    m.mine && !m.recalledAt && Date.now() - new Date(m.createdAt).getTime() < 120_000;

  const recallMessage = async (m: SupportMessageView) => {
    if (!(await confirm({ title: '撤回消息', message: '撤回后双方都看不到这条消息，确定撤回吗？' }))) return;
    try {
      await supportApi.recall(m.id);
      await loadThread(activeSellerId);
      void loadSessions();
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };

  /** 单向删除（2026-10-25）：只对我隐藏，对方仍可见 */
  const deleteMessages = async (ids: string[]) => {
    if (ids.length === 0) return;
    if (!(await confirm({
      title: '删除消息',
      message: ids.length === 1 ? '删除后仅自己看不到这条消息，对方不受影响。确定删除吗？'
        : `已选 ${ids.length} 条，删除后仅自己看不到，对方不受影响。确定删除吗？`,
      danger: true,
      confirmText: '删除',
    }))) return;
    try {
      await supportApi.deleteMessages(ids);
      setSelectedIds(new Set());
      setSelectMode(false);
      await loadThread(activeSellerId);
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };

  /**
   * 引用跳转定位（2026-10-25）：点击引用条滚到被引用的原始消息。
   * 目标在当前分页窗口外时，带 aroundId 重新拉一页（服务端返回以该消息为中心的窗口）。
   */
  const jumpToQuoted = async (messageId: string | null | undefined) => {
    if (!messageId) return;
    const inWindow = thread?.items.some((x) => x.id === messageId);
    if (inWindow) {
      scrollToMessage(messageId);
    } else {
      setPendingScrollId(messageId);
      await loadThread(activeSellerId, messageId);
    }
  };

  const scrollToMessage = (messageId: string) => {
    const el = bodyRef.current?.querySelector(`[data-mid="${messageId}"]`);
    if (el) {
      el.scrollIntoView({ block: 'center' });
      el.classList.remove('msg-flash');
      // 强制重启动画
      void (el as HTMLElement).offsetWidth;
      el.classList.add('msg-flash');
    }
  };

  // aroundId 拉回的目标消息渲染完成后滚动定位
  useEffect(() => {
    if (!pendingScrollId || !thread?.items.length) return;
    if (!thread.items.some((x) => x.id === pendingScrollId)) return;
    scrollToMessage(pendingScrollId);
    setPendingScrollId(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [thread?.items[thread.items.length - 1]?.id, pendingScrollId]);

  const sendVoice = async (blob: Blob, durationMs: number) => {
    const ext = blob.type.includes('mp4') ? 'mp4' : 'webm';
    try {
      const file = new File([blob], `voice.${ext}`, { type: blob.type });
      const { url } = await uploadApi.image(file);
      await deliver({ content: '[语音]', type: 'VOICE', mediaUrl: url, durationMs });
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };
  const recorder = useVoiceRecorder({
    onReady: (blob, durationMs) => void sendVoice(blob, durationMs),
    onError: (message) => push(message, 'error'),
  });

  const sendMedia = async (payload: MediaPayload, kind: 'IMAGE' | 'VIDEO') => {
    try {
      await deliver({
        content: '',
        type: kind,
        mediaUrl: payload.mediaUrl,
        thumbUrl: payload.thumbUrl,
        width: payload.width,
        height: payload.height,
        durationMs: payload.durationMs,
      });
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };

  /** 发送商品卡：从「当前浏览 / 购物车 / 收藏夹 / 已购买」四类来源里选 */
  const sendProduct = async (product: PickerProduct) => {
    setPickerOpen(false);
    try {
      await deliver({ content: '', type: 'PRODUCT', productId: product.id });
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };

  const sendSticker = async (url: string) => {
    try {
      await deliver({ content: '', type: 'EMOJI', mediaUrl: url });
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };


  const send = async () => {
    const content = draft.trim();
    if (!content || sending || !activeSellerId) return;
    setSending(true);
    try {
      await deliver({ content, replyToId: replyTo?.id });
      setDraft('');
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    } finally {
      setSending(false);
    }
  };

  /** 结束本次咨询：只影响客服侧的收尾与筛选；买家侧只在会话流里留一行小字，不出现「关闭」字样 */
  const endConsult = async () => {
    if (!thread?.sessionId || ended) return;
    try {
      await supportApi.close(thread.sessionId);
      // 立即隐藏按钮 + 在会话流里落一条系统提示（不再用 toast：toast 会消失，用户看不到"已结束"的痕迹）
      setEndedSessionId(thread.sessionId);
      void loadSessions();
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };

  const active = useMemo(
    () => sessions.find((s) => s.sellerId === activeSellerId) ?? null,
    [sessions, activeSellerId],
  );

  /** 打开会话要定位的那一条：最新一条「对方发来、我还没读」的消息 */
  const firstUnreadId = thread?.items.find((m) => !m.mine && !m.read)?.id;
  /** 当前会话是否已被本人在本次会话中结束（结束后按钮隐藏，直到再次发消息） */
  const ended = Boolean(thread?.sessionId && endedSessionId === thread.sessionId);

  if (loading) return <LoadingBlock text="正在接通客服…" />;

  return (
    <div className="container" style={{ paddingTop: 16, paddingBottom: 32 }}>
      <h2 className="section-title">客服消息</h2>
      <p className="small muted" style={{ marginBottom: 12 }}>
        自营客服与商家客服分开接待；闲置/二手交易请直接在「私信」里与对方沟通。
      </p>

      {error && <Alert kind="error">{error}</Alert>}

      <div className="sup-layout">
        <aside className="card sup-list">
          {sessions.length === 0 ? (
            <p className="small muted" style={{ padding: 12 }}>
              还没有客服会话。可以从商品详情页「问客服」开始咨询。
            </p>
          ) : (
            sessions.map((s) => {
              const isActive = s.sellerId === activeSellerId;
              return (
                <button
                  key={s.id}
                  ref={isActive ? activeItemRef : undefined}
                  type="button"
                  onClick={() => void switchTo(s.sellerId)}
                  className={`sup-item${isActive ? ' active' : ''}`}
                >
                  <span style={{ minWidth: 0 }}>
                    <span className="row" style={{ gap: 6, alignItems: 'center' }}>
                      {s.sellerAvatar ? (
                        <img
                          src={productImage(s.sellerId, s.sellerAvatar)}
                          alt=""
                          style={{ width: 22, height: 22, borderRadius: 6, objectFit: 'cover' }}
                        />
                      ) : null}
                      <span className="sup-item-name">{peerOf(s)}</span>
                      {s.sellerId === '710000000000000001' && (
                        <span className="tag tag-self" style={{ fontSize: 11 }}>自营</span>
                      )}
                    </span>
                    <span className="sup-item-preview">{s.lastMessage || '暂无消息'}</span>
                  </span>
                  {Number(s.unread) > 0 && (
                    <span className="sup-badge">
                      {Number(s.unread) > 99 ? '99+' : Number(s.unread)}
                    </span>
                  )}
                </button>
              );
            })
          )}
        </aside>

        <section className="card sup-thread">
          <header className="sup-thread-head">
            <strong>{active ? peerOf(active) : thread?.sellerName || '客服'}</strong>
            <span className="row" style={{ gap: 8, alignItems: 'center' }}>
              <span className="small muted">工作时间外可能回复较慢</span>
              {activeSellerId && thread?.items.length && !ended ? (
                <button type="button" className="btn btn-sm btn-ghost" onClick={() => void endConsult()}>
                  结束咨询
                </button>
              ) : null}
            </span>
          </header>

          <div
            className="sup-body"
            ref={bodyRef}
            onScroll={(e) => {
              const el = e.currentTarget;
              atBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
            }}
          >
            {!activeSellerId ? (
              <Empty
                title="请选择一个会话"
                desc="从左侧选一家店铺的客服，或从商品 / 订单页点「问客服」直接进入"
              />
            ) : !thread || thread.items.length === 0 ? (
              <Empty title="还没有消息，说点什么吧" />
            ) : (
              <>
                {thread.items.map((m, idx) => {
                  const isLastMine = m.mine && !thread.items.slice(idx + 1).some((x) => x.mine);
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
                          <div className="sup-col"><MsgBody message={m} /></div>
                        </label>
                      ) : (
                        <Bubble
                          message={m}
                          unreadTag={m.mine && !m.read}
                          firstUnread={m.id === firstUnreadId}
                          receipt={isLastMine && Number(thread.myUnreadCount) === 0 ? '已读' : null}
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
            {/* 结束后只在会话流里留一行系统小字：不弹 toast（会消失）、不出现「关闭」字样 */}
            {ended ? <div className="sup-system">当前会话已结束</div> : null}
            <div ref={bottomRef} />
          </div>

          <footer className="sup-foot">
            {!activeSellerId ? (
              <p className="small muted" style={{ margin: 0 }}>
                选择一个会话后才能发送消息。
              </p>
            ) : (
            <>
            {peerTyping ? <div className="sup-typing">对方正在输入…</div> : null}
            {replyTo ? (
              <div className="chat-quoted" role="button" onClick={() => setReplyTo(null)} title="点击取消引用">
                引用 {replyTo.senderRole === 'USER' ? '我' : '对方'}：{replyTo.quoted?.excerpt ?? replyTo.content}
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
                onProduct={() => setPickerOpen(true)}
                onError={(msg) => push(msg, 'error')}
                disabled={sending}
              />
              <button
                type="button"
                className="btn btn-sm btn-ghost mic-btn"
                disabled={sending}
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
                placeholder="描述你的问题…"
                value={draft}
                onChange={(e) => {
                  setDraft(e.target.value);
                  if (thread?.sessionId) realtime.typing('SUPPORT', { sessionId: thread.sessionId });
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    void send();
                  }
                }}
                disabled={sending}
              />
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => void send()}
                disabled={sending || !draft.trim()}
              >
                {sending ? '发送中…' : '发送'}
              </button>
            </div>
            </>
            )}
          </footer>
        </section>
      </div>

      <ProductPicker
        open={pickerOpen}
        currentProductId={currentProductId}
        onClose={() => setPickerOpen(false)}
        onPick={(p) => void sendProduct(p)}
      />
    </div>
  );
};

/** 消息主体渲染（文本 / 媒体 / 卡片），多选行与气泡共用 */
function MsgBody({ message, onQuotedClick }: { message: SupportMessageView; onQuotedClick?: () => void }) {
  if (message.bizType === 'ORDER') {
    const meta = parseOrderMeta(message.metaJson);
    return <OrderCard metaJson={message.metaJson} href={meta?.orderNo ? `/orders/${meta.orderNo}` : null} />;
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
      {message.quoted ? (
        <QuotedStrip excerpt={message.quoted.excerpt} onClick={onQuotedClick} />
      ) : null}
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
  onQuote?: (m: SupportMessageView) => void;
  onRecall?: (m: SupportMessageView) => void;
  canRecall?: boolean;
  onDelete?: (m: SupportMessageView) => void;
  onMultiSelect?: () => void;
  /** 点击引用条 → 跳转定位到被引用的原始消息（2026-10-25） */
  onQuotedClick?: () => void;
}> = ({ message, unreadTag, firstUnread, receipt, onQuote, onRecall, canRecall, onDelete, onMultiSelect, onQuotedClick }) => {
  if (message.recalledAt) {
    // 撤回文案独立整行居中（2026-10-25）：不再是气泡内小字
    return (
      <div className="sup-row" data-mid={message.id} data-first-unread={firstUnread ? '1' : undefined}>
        <RecallLine mine={message.mine} />
      </div>
    );
  }
  return (
    <div
      className={`sup-row${message.mine ? ' mine' : ''}`}
      data-first-unread={firstUnread ? '1' : undefined}
      data-mid={message.id}
    >
      <div className="sup-col">
        <MsgBody message={message} onQuotedClick={onQuotedClick} />
        {message.card && (
          <a href={`/product/${message.card.productId}`} className="card sup-card">
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
          </a>
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
};

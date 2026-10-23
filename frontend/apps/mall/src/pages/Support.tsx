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
import { ChatMediaMenu, MediaBubble, isEmojiOnly, type MediaPayload } from '@duoduo/shared/components/chat-media';
import { formatBjShort } from '@duoduo/shared/lib/time';

const POLL_MS = 5000;
const PAGE_SIZE = 30;

const timeText = (iso: string | null | undefined): string => formatBjShort(iso);

const peerOf = (s: SupportSessionView): string => s.sellerName || `店铺${s.sellerId}`;

export const SupportPage: React.FC = () => {
  const [search] = useSearchParams();
  const { push } = useToast();

  const sellerIdParam = search.get('sellerId') ?? undefined;

  const [sessions, setSessions] = useState<SupportSessionView[]>([]);
  const [activeSellerId, setActiveSellerId] = useState<string | undefined>(sellerIdParam);
  const [thread, setThread] = useState<SupportThreadView | null>(null);
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [pickerOpen, setPickerOpen] = useState(false);
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

  const loadThread = useCallback(async (sellerId?: string) => {
    try {
      const data = await supportApi.messages({ sellerId, page: 1, pageSize: PAGE_SIZE });
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

  // 会话内新增消息：只有本来就在底部（或自己刚发出）时才跟着滚，避免打断向上翻页
  useEffect(() => {
    if (!thread?.items.length) return;
    const last = thread.items[thread.items.length - 1];
    if (last?.mine || atBottomRef.current) {
      bottomRef.current?.scrollIntoView({ block: 'end' });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [thread?.items.length]);

  const switchTo = async (sellerId: string) => {
    setActiveSellerId(sellerId);
    setThread(null);
    await loadThread(sellerId);
  };

  const sendMedia = async (payload: MediaPayload, kind: 'IMAGE' | 'VIDEO') => {
    if (!activeSellerId) return;
    try {
      await supportApi.send({
        sellerId: activeSellerId,
        content: '',
        type: kind,
        mediaUrl: payload.mediaUrl,
        thumbUrl: payload.thumbUrl,
        width: payload.width,
        height: payload.height,
        durationMs: payload.durationMs,
      });
      await loadThread(activeSellerId);
      void loadSessions();
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };

  /** 发送商品卡：从「当前浏览 / 购物车 / 收藏夹 / 已购买」四类来源里选 */
  const sendProduct = async (product: PickerProduct) => {
    setPickerOpen(false);
    if (!activeSellerId) return;
    try {
      await supportApi.send({
        sellerId: activeSellerId,
        content: '',
        type: 'PRODUCT',
        productId: product.id,
      });
      await loadThread(activeSellerId);
      void loadSessions();
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };

  const sendSticker = async (url: string) => {
    if (!activeSellerId) return;
    try {
      await supportApi.send({ sellerId: activeSellerId, content: '', type: 'EMOJI', mediaUrl: url });
      await loadThread(activeSellerId);
      void loadSessions();
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    }
  };


  const send = async () => {
    const content = draft.trim();
    if (!content || sending || !activeSellerId) return;
    setSending(true);
    try {
      await supportApi.send({ sellerId: activeSellerId, content });
      setDraft('');
      await loadThread(activeSellerId);
      void loadSessions();
    } catch (e) {
      push(toFriendlyMessage(e), 'error');
    } finally {
      setSending(false);
    }
  };

  /** 结束本次咨询：只影响客服侧的收尾与筛选；买家侧不出现任何「已关闭」字样 */
  const endConsult = async () => {
    if (!thread?.sessionId) return;
    try {
      await supportApi.close(thread.sessionId);
      push('已结束本次咨询，需要时可以继续发送消息', 'success');
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
              {activeSellerId && thread?.items.length ? (
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
              thread.items.map((m, idx) => {
                const isLastMine = m.mine && !thread.items.slice(idx + 1).some((x) => x.mine);
                return (
                  <Bubble
                    key={m.id}
                    message={m}
                    unreadTag={m.mine && !m.read}
                    firstUnread={m.id === firstUnreadId}
                    receipt={
                      isLastMine && Number(thread.myUnreadCount) === 0 ? '已读' : null
                    }
                  />
                );
              })
            )}
            <div ref={bottomRef} />
          </div>

          <footer className="sup-foot">
            {!activeSellerId ? (
              <p className="small muted" style={{ margin: 0 }}>
                选择一个会话后才能发送消息。
              </p>
            ) : (
            <div className="row" style={{ gap: 8 }}>
              <ChatMediaMenu
                onMedia={sendMedia}
                onEmoji={(emoji) => setDraft((v) => v + emoji)}
                onSticker={(url) => void sendSticker(url)}
                onProduct={() => setPickerOpen(true)}
                onError={(msg) => push(msg, 'error')}
                disabled={sending}
              />
              <input
                className="input"
                style={{ flex: 1 }}
                placeholder="描述你的问题…"
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
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

/** 媒体消息与纯表情的渲染：文本消息走原气泡，媒体走专用组件 */
function renderContent(message: SupportMessageView) {
  if (message.bizType === 'ORDER') {
    const meta = parseOrderMeta(message.metaJson);
    return <OrderCard metaJson={message.metaJson} href={meta?.orderNo ? `/orders/${meta.orderNo}` : null} />;
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
}> = ({ message, unreadTag, firstUnread, receipt }) => (
  <div className={`sup-row${message.mine ? ' mine' : ''}`} data-first-unread={firstUnread ? '1' : undefined}>
    <div className="sup-col">
      {renderContent(message)}
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
        {timeText(message.createdAt)}
        {unreadTag ? (
          <span className="sup-unread"> · 未读</span>
        ) : receipt ? (
          ` · ${receipt}`
        ) : null}
      </div>
    </div>
  </div>
);

/**
 * 私信 · 会话详情（R74-5，Web 端补齐）。
 *
 * <p>与小程序 `pages/chat` 同一套接口与口径：
 *  · **会话键带上下文段**（`productId` / `listingId` 走查询参数，服务端拼键）——
 *    同一对用户的多类会话互不串线，所以**进页面必须把上下文带上**，
 *    否则打开的是「主会话」，看起来像“消息丢了”；
 *  · 打开即 `markRead`（幂等），未读角标随之收敛；
 *  · `canSend=false` 时**禁用输入并说明原因**（非互关每人限发一条，咨询会话豁免），
 *    而不是让人点发送后静默失败。
 *
 * <p>刻意**不做**的两件事（v1 边界，避免半成品）：实时推送（无 WebSocket，用「刷新」按钮 + 发送后自动拉取）；
 * 图片 / 卡片消息的富渲染（`bizType != TEXT` 的消息按文本展示，卡片细节留在小程序）。
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { messageApi, uploadApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Empty, LoadingBlock, useToast } from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';
import { ChatMediaMenu, MediaBubble, VoiceBubble, useVoiceRecorder, QuotedStrip, RecallLine,
  isEmojiOnly, MessageActions, MultiSelectBar, type MediaPayload } from '@duoduo/shared/components/chat-media';
import { realtime, useRealtime } from '@duoduo/shared/realtime';
import { chatSeparatorLabel } from '@duoduo/shared/lib/time';
import { useConfirm } from '@duoduo/shared/components/confirm';

const PAGE_SIZE = 30;

export const ChatPage: React.FC = () => {
  const { peerId = '' } = useParams();
  const [search] = useSearchParams();
  const navigate = useNavigate();
  const { push } = useToast();
  const confirm = useConfirm();

  const productId = search.get('productId') ?? undefined;
  const listingId = search.get('listingId') ?? undefined;

  const [page, setPage] = useState(1);
  /** 引用跳转定位窗口（2026-10-25）：非空时服务端返回以该消息为中心的一页 */
  const [aroundId, setAroundId] = useState<string | undefined>(undefined);
  /** 多选删除模式 */
  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  /** aroundId 拉回后待滚动的消息 id */
  const [pendingScrollId, setPendingScrollId] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const bottomRef = useRef<HTMLDivElement | null>(null);
  const bodyRef = useRef<HTMLDivElement | null>(null);
  /** 对方正在输入（2026-10-23） */
  const [peerTyping, setPeerTyping] = useState(false);
  const typingTimer = useRef<number | null>(null);

  const ctx = useCallback(
    () => ({ productId, listingId, page, pageSize: PAGE_SIZE, aroundId }),
    [productId, listingId, page, aroundId],
  );

  const { data, loading, error } = useAsync(
    useCallback(() => messageApi.conversation(peerId, ctx()), [peerId, ctx]),
    Boolean(peerId),
    [peerId, tick, page, aroundId],
  );

  const items = data?.items ?? [];
  const canSend = data?.canSend !== false;

  /**
   * 实时事件（2026-10-23）：私信的新消息 / 已读回执 / 正在输入。
   * 收到信号后只做一次重拉（tick+1），渲染仍由 HTTP 那一套负责 —— 避免两套消息格式。
   */
  useRealtime((event) => {
    if (event.biz !== 'DIRECT') return;
    const same = Boolean(data?.conversationId) && event.conversationId === data?.conversationId;
    if (event.type === 'MESSAGE' && same) {
      setPeerTyping(false);
      setTick((v) => v + 1);
      return;
    }
    if (event.type === 'READ' && same) {
      setTick((v) => v + 1);
      return;
    }
    if (event.type === 'TYPING' && same) {
      setPeerTyping(true);
      if (typingTimer.current) window.clearTimeout(typingTimer.current);
      typingTimer.current = window.setTimeout(() => setPeerTyping(false), 3000);
    }
    // 撤回信号（2026-10-24）：回源刷新，灰字替换原气泡
    if (event.type === 'RECALL' && same) {
      setTick((v) => v + 1);
    }
  });

  // 打开会话即标记已读（幂等、尽力而为：失败不影响阅读）
  useEffect(() => {
    if (!peerId) return;
    void messageApi.markRead(peerId, { productId, listingId }).catch(() => undefined);
  }, [peerId, productId, listingId]);

  // 打开会话定位到最新未读（2026-10-23 与客服页同一口径）
  useEffect(() => {
    if (!items.length) return;
    const target = bodyRef.current?.querySelector('[data-first-unread="1"]');
    if (target) target.scrollIntoView({ block: 'center' });
    else bottomRef.current?.scrollIntoView({ block: 'end' });
    // 首屏 data 到达前列表还没渲染（bodyRef 为空），必须等它变成有数据再定位一次
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [peerId, productId, listingId, Boolean(data)]);

  // 新消息进来滚到底部（消息按时间正序，最新在最下）。
  // ★ 依赖用最后一条 id：分页窗口固定，长会话里条数不变但内容已滑动（2026-10-23）
  useEffect(() => {
    if (!items.length) return;
    bottomRef.current?.scrollIntoView({ block: 'end' });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items[items.length - 1]?.id]);

  // ---------------- 引用 / 撤回 / 语音（2026-10-24） ----------------
  /** 正在引用的消息：随下一条消息一起发出 */
  const [replyTo, setReplyTo] = useState<import('@duoduo/api-sdk/types').ChatMessageView | null>(null);
  const recallWithinWindow = (m: import('@duoduo/api-sdk/types').ChatMessageView): boolean =>
    m.mine && !m.recalledAt && Date.now() - new Date(m.createdAt).getTime() < 120_000;

  const recallMessage = async (m: import('@duoduo/api-sdk/types').ChatMessageView) => {
    if (!(await confirm({ title: '撤回消息', message: '撤回后双方都看不到这条消息，确定撤回吗？' }))) return;
    try {
      await messageApi.recall(m.id);
      setTick((v) => v + 1);
    } catch (err) {
      setSendError(toFriendlyMessage(err));
      push(toFriendlyMessage(err), 'error');
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
      await messageApi.deleteMessages(ids);
      setSelectedIds(new Set());
      setSelectMode(false);
      setTick((v) => v + 1);
    } catch (err) {
      setSendError(toFriendlyMessage(err));
      push(toFriendlyMessage(err), 'error');
    }
  };

  /** 引用跳转定位（2026-10-25）：目标不在当前窗口时用 aroundId 拉一页 */
  const scrollToMessage = (messageId: string) => {
    const el = bodyRef.current?.querySelector(`[data-mid="${messageId}"]`);
    if (el) {
      el.scrollIntoView({ block: 'center' });
      el.classList.remove('msg-flash');
      void (el as HTMLElement).offsetWidth;
      el.classList.add('msg-flash');
    }
  };
  const jumpToQuoted = async (messageId: string | null | undefined) => {
    if (!messageId) return;
    if (items.some((x) => x.id === messageId)) {
      scrollToMessage(messageId);
    } else {
      setPendingScrollId(messageId);
      setPage(1);
      setAroundId(messageId);
    }
  };
  // aroundId 拉回的目标消息渲染完成后滚动定位并清掉窗口参数
  useEffect(() => {
    if (!pendingScrollId || !items.length) return;
    if (!items.some((x) => x.id === pendingScrollId)) return;
    scrollToMessage(pendingScrollId);
    setPendingScrollId(null);
    setAroundId(undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items[items.length - 1]?.id, pendingScrollId]);

  const sendVoice = async (blob: Blob, durationMs: number) => {
    setBusy(true);
    setSendError(null);
    try {
      const ext = blob.type.includes('mp4') ? 'mp4' : 'webm';
      const file = new File([blob], `voice.${ext}`, { type: blob.type });
      const { url } = await uploadApi.image(file);
      await messageApi.send({
        receiverId: peerId, content: '[语音]', bizType: 'VOICE',
        productId, listingId, mediaUrl: url, durationMs, replyToId: replyTo?.id,
      });
      setReplyTo(null);
      setTick((v) => v + 1);
    } catch (err) {
      setSendError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };
  const recorder = useVoiceRecorder({
    onReady: (blob, durationMs) => void sendVoice(blob, durationMs),
    onError: (message) => setSendError(message),
  });

  /** 私信只允许发送「当前咨询的商品」卡，不提供收藏/购物车等来源 */
  const sendSticker = async (url: string) => {
    setBusy(true);
    setSendError(null);
    try {
      await messageApi.send({
        receiverId: peerId,
        content: '',
        bizType: 'EMOJI',
        productId,
        listingId,
        mediaUrl: url,
      });
      setTick((v) => v + 1);
    } catch (err) {
      setSendError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };


  const sendProductCard = async () => {
    if (!productId) return;
    setBusy(true);
    setSendError(null);
    try {
      await messageApi.send({ receiverId: peerId, content: '', bizType: 'PRODUCT', bizId: productId });
      setTick((v) => v + 1);
    } catch (err) {
      setSendError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const sendMedia = async (payload: MediaPayload, kind: 'IMAGE' | 'VIDEO') => {
    setBusy(true);
    setSendError(null);
    try {
      await messageApi.send({
        receiverId: peerId,
        content: '',
        bizType: kind,
        productId,
        listingId,
        mediaUrl: payload.mediaUrl,
        thumbUrl: payload.thumbUrl,
        width: payload.width,
        height: payload.height,
        durationMs: payload.durationMs,
      });
      setTick((v) => v + 1);
    } catch (err) {
      setSendError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const send = async () => {
    const content = draft.trim();
    if (!content || !peerId) return;
    setBusy(true);
    setSendError(null);
    try {
      await messageApi.send({ receiverId: peerId, content, productId, listingId, replyToId: replyTo?.id });
      setDraft('');
      setReplyTo(null);
      setTick((v) => v + 1);
    } catch (err) {
      // 服务端会拒绝「非互关的第二条」，原因原样展示（不吞、不假装成功）
      setSendError(toFriendlyMessage(err));
      push(toFriendlyMessage(err), 'error');
    } finally {
      setBusy(false);
    }
  };

  if (!peerId) return <Alert kind="error">缺少会话对象（peerId）</Alert>;
  if (loading && !data) return <LoadingBlock text="正在加载会话…" />;
  if (error) return <Alert kind="error">{error}</Alert>;

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">{data?.peerNickname ?? `用户 ${peerId}`}</h2>
        <span className="section-sub">
          {data?.mutual ? '互相关注' : '未互关'}
          {canSend ? '' : ' · 只能发一条'}
        </span>
        <button className="btn btn-ghost btn-sm" style={{ marginLeft: 'auto' }} onClick={() => setTick((v) => v + 1)}>
          刷新
        </button>
        <button className="btn btn-ghost btn-sm" onClick={() => navigate('/messages')}>
          返回列表
        </button>
      </div>

      {(productId || listingId) ? (
        <p className="small muted">
          本会话是{listingId ? '闲置' : '商品'}咨询会话（上下文 id：{listingId ?? productId}），
          与同一用户的其他会话分开。
        </p>
      ) : null}

      {items.length === 0 ? (
        <Empty icon="💬" title="还没有消息" desc="发一条打个招呼吧" />
      ) : (
        <div className="col" ref={bodyRef} style={{ gap: 8, marginTop: 8 }}>
          {items.map((m, idx) => {
            const separator = chatSeparatorLabel(m.createdAt, idx > 0 ? items[idx - 1]?.createdAt : null);
            const checked = selectedIds.has(m.id);
            return (
              <React.Fragment key={m.id}>
                {separator ? <div className="chat-time-sep">{separator}</div> : null}
                {selectMode ? (
                  <label
                    className="row msg-select-row"
                    style={{ justifyContent: m.mine ? 'flex-end' : 'flex-start' }}
                    data-mid={m.id}
                  >
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
                    <div className="card card-pad" style={{ maxWidth: '72%' }}>
                      {m.recalledAt ? <RecallLine mine={m.mine} /> : <MessageBody message={m} />}
                    </div>
                  </label>
                ) : m.recalledAt ? (
                  // 撤回文案独立整行居中（2026-10-25）
                  <div className="row" style={{ justifyContent: 'center' }} data-mid={m.id}>
                    <RecallLine mine={m.mine} />
                  </div>
                ) : (
                  <div
                    className="row"
                    style={{ justifyContent: m.mine ? 'flex-end' : 'flex-start' }}
                    data-first-unread={
                      !m.mine && !m.read && !items.slice(0, idx).some((x) => !x.mine && !x.read) ? '1' : undefined
                    }
                    data-mid={m.id}
                  >
                    <div
                      className="card card-pad"
                      style={{
                        maxWidth: '72%',
                        background: m.mine ? 'var(--brand-soft, #fff3e6)' : undefined,
                      }}
                    >
                      <MessageBody message={m} onQuotedClick={() => void jumpToQuoted(m.replyToId)} />
                      <div className="small muted" style={{ marginTop: 4, textAlign: 'right' }}>
                        {m.mine ? (m.read ? '已读' : '未读') : ''}
                        <MessageActions
                          onQuote={() => setReplyTo(m)}
                          onRecall={recallWithinWindow(m) ? () => void recallMessage(m) : null}
                          onDelete={() => void deleteMessages([m.id])}
                          onMultiSelect={() => { setSelectMode(true); setSelectedIds(new Set()); }}
                        />
                      </div>
                    </div>
                  </div>
                )}
              </React.Fragment>
            );
          })}
          <div ref={bottomRef} />
        </div>
      )}

      {selectMode ? (
        <MultiSelectBar
          count={selectedIds.size}
          totalCount={items.length}
          onToggleAll={() =>
            setSelectedIds((prev) => (prev.size === items.length ? new Set() : new Set(items.map((x) => x.id))))
          }
          onDelete={() => void deleteMessages([...selectedIds])}
          onCancel={() => { setSelectMode(false); setSelectedIds(new Set()); }}
        />
      ) : null}

      {peerTyping ? <div className="sup-typing">对方正在输入…</div> : null}
      {sendError ? <Alert kind="error">{sendError}</Alert> : null}
      {replyTo ? (
        <div className="chat-quoted" role="button" onClick={() => setReplyTo(null)} title="点击取消引用">
          引用：{replyTo.quoted?.excerpt ?? replyTo.content}
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

      <div className="row" style={{ gap: 8, marginTop: 12, alignItems: 'flex-start' }}>
        <ChatMediaMenu
          onMedia={sendMedia}
          onEmoji={(emoji) => setDraft((v) => v + emoji)}
          onSticker={(url) => void sendSticker(url)}
          onProduct={productId ? () => void sendProductCard() : undefined}
          onError={(msg) => setSendError(msg)}
          disabled={busy || !canSend}
        />
        <button
          type="button"
          className="btn btn-sm btn-ghost mic-btn"
          disabled={busy || !canSend}
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
        <textarea
          className="input"
          style={{ flex: 1, minHeight: 62, resize: 'vertical' }}
          value={draft}
          maxLength={500}
          placeholder={canSend ? '输入消息（最多 500 字）' : '对方尚未回关，你只能发一条（若已发过则无法再发）'}
          disabled={!canSend || busy}
          onChange={(e) => {
            setDraft(e.target.value);
            if (data?.conversationId) realtime.typing('DIRECT', { conversationId: data.conversationId });
          }}
        />
        <button
          className="btn btn-primary"
          disabled={!canSend || busy || draft.trim() === ''}
          onClick={() => void send()}
        >
          {busy ? '发送中…' : '发送'}
        </button>
      </div>

      {!canSend ? (
        <p className="small muted">
          规则：**非互关时每对用户只能发一条**（防止骚扰）；对方回关后即可继续聊，
          商品 / 闲置咨询会话不受此限。你可以在<Link to="/follow"> 关注与粉丝 </Link>里查看对方是否回关。
        </p>
      ) : null}

      {data && data.total > items.length ? (
        <div className="row" style={{ gap: 10, justifyContent: 'center', marginTop: 8 }}>
          <button className="btn btn-ghost btn-sm" disabled={page >= Math.ceil(data.total / PAGE_SIZE)} onClick={() => setPage((p) => p + 1)}>
            加载更早的消息
          </button>
        </div>
      ) : null}
    </div>
  );
};


/** 私信消息体：媒体走专用渲染，纯表情放大；引用条可点击定位原消息 */
const MessageBody: React.FC<{ message: import('@duoduo/api-sdk/types').ChatMessageView; onQuotedClick?: () => void }> = ({ message, onQuotedClick }) => {
  if (message.bizType === 'VOICE') {
    return <VoiceBubble url={message.mediaUrl || ''} durationMs={message.durationMs ?? null} />;
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
          <div className="small" style={{ marginTop: 6, whiteSpace: 'pre-wrap' }}>{message.content}</div>
        ) : null}
      </>
    );
  }
  const emojiOnly = isEmojiOnly(message.content);
  return (
    <div
      className="small"
      style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: emojiOnly ? 28 : undefined }}
    >
      {message.quoted ? <QuotedStrip excerpt={message.quoted.excerpt} onClick={onQuotedClick} /> : null}
      {message.content}
    </div>
  );
};

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
import { messageApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Empty, LoadingBlock, useToast } from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';
import { ChatMediaMenu, MediaBubble, isEmojiOnly, type MediaPayload } from '@duoduo/shared/components/chat-media';
import { realtime, useRealtime } from '@duoduo/shared/realtime';
import { formatBjShort } from '@duoduo/shared/lib/time';

const PAGE_SIZE = 30;

const timeText = (iso: string | null | undefined): string => formatBjShort(iso);

export const ChatPage: React.FC = () => {
  const { peerId = '' } = useParams();
  const [search] = useSearchParams();
  const navigate = useNavigate();
  const { push } = useToast();

  const productId = search.get('productId') ?? undefined;
  const listingId = search.get('listingId') ?? undefined;

  const [page, setPage] = useState(1);
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
    () => ({ productId, listingId, page, pageSize: PAGE_SIZE }),
    [productId, listingId, page],
  );

  const { data, loading, error } = useAsync(
    useCallback(() => messageApi.conversation(peerId, ctx()), [peerId, ctx]),
    Boolean(peerId),
    [peerId, tick, page],
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
      await messageApi.send({ receiverId: peerId, content, productId, listingId });
      setDraft('');
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
          {items.map((m, idx) => (
            <div
              key={m.id}
              className="row"
              style={{ justifyContent: m.mine ? 'flex-end' : 'flex-start' }}
              data-first-unread={
                !m.mine && !m.read && !items.slice(0, idx).some((x) => !x.mine && !x.read) ? '1' : undefined
              }
            >
              <div
                className="card card-pad"
                style={{
                  maxWidth: '72%',
                  background: m.mine ? 'var(--brand-soft, #fff3e6)' : undefined,
                }}
              >
                <MessageBody message={m} />
                <div className="small muted" style={{ marginTop: 4, textAlign: 'right' }}>
                  {timeText(m.createdAt)}
                  {m.mine ? (m.read ? ' · 已读' : ' · 未读') : ''}
                </div>
              </div>
            </div>
          ))}
          <div ref={bottomRef} />
        </div>
      )}

      {peerTyping ? <div className="sup-typing">对方正在输入…</div> : null}
      {sendError ? <Alert kind="error">{sendError}</Alert> : null}

      <div className="row" style={{ gap: 8, marginTop: 12, alignItems: 'flex-start' }}>
        <ChatMediaMenu
          onMedia={sendMedia}
          onEmoji={(emoji) => setDraft((v) => v + emoji)}
          onSticker={(url) => void sendSticker(url)}
          onProduct={productId ? () => void sendProductCard() : undefined}
          onError={(msg) => setSendError(msg)}
          disabled={busy || !canSend}
        />
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


/** 私信消息体：媒体走专用渲染，纯表情放大 */
const MessageBody: React.FC<{ message: import('@duoduo/api-sdk/types').ChatMessageView }> = ({ message }) => {
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
      {message.content}
    </div>
  );
};

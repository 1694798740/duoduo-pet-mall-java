/**
 * 私信 · 会话列表（R74-5，Web 端补齐）。
 *
 * <p>背景：社交域的私信能力（会话键按上下文隔离、未读、发信限制）早就齐备，小程序端也已接了，
 * 但 **Web 端一个私信页面都没有** —— 买家在电脑 / 手机浏览器上根本收不到、也发不出私信。
 * 本页与 `Chat.tsx` 把这块补上，接口口径与小程序完全同源（`/api/socials/messages/**`）。
 *
 * <p>两条必须显式呈现的口径（都由服务端下发，前端不自己推断）：
 *  1. **`canSend=false`**：非互关时每对用户只能发一条（**咨询会话豁免**）。服务端会拒第二条，
 *     若前端把输入框留在那儿、让人点发送后静默失败，用户会以为「消息发丢了」；
 *  2. **未读数**用服务端给的 `unread`，前端不拿「最新一条」去猜。
 */

import React, { useCallback, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { messageApi } from '@duoduo/api-sdk/endpoints';
import type { ConversationView } from '@duoduo/api-sdk/types';
import { Empty, LoadingBlock, Pagination } from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';
import { formatBjShort } from '@duoduo/shared/lib/time';

const PAGE_SIZE = 15;

/**
 * ISO 串 → 北京时间 'MM-DD HH:mm'。
 *
 * <p>★ 旧实现是「直接截字符串」，注释还写着「全 UTC，按原样展示」——那是错的：
 * 后端存的是 UTC，原样展示就等于把 08:41Z 显示成 08:41，比北京时间早 8 小时。
 * 时区换算交给 {@link formatBjShort}，各页共用一套口径。
 */
const shortTime = (iso: string | null | undefined): string => formatBjShort(iso);

/** 会话要带上下文参数才能打开：同一对用户的多类会话互不串线 */
const chatLink = (c: ConversationView): string => {
  const qs = new URLSearchParams();
  if (c.productId) qs.set('productId', c.productId);
  if (c.listingId) qs.set('listingId', c.listingId);
  const q = qs.toString();
  return `/messages/${c.peerId}${q ? `?${q}` : ''}`;
};

export const MessagesPage: React.FC = () => {
  const navigate = useNavigate();
  const [page, setPage] = useState(1);

  const { data, loading } = useAsync(
    useCallback(() => messageApi.conversations({ page, pageSize: PAGE_SIZE }), [page]),
    true,
    [page],
  );

  const items = data?.items ?? [];
  const total = data?.total ?? 0;
  const unreadAll = items.reduce((sum, c) => sum + (c.unread || 0), 0);
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  if (loading && !data) return <LoadingBlock text="正在加载会话…" />;

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">私信</h2>
        <span className="section-sub">
          {total} 个会话{unreadAll > 0 ? ` · 本页未读 ${unreadAll} 条` : ''}
        </span>
      </div>

      {items.length === 0 ? (
        <Empty
          icon="💬"
          title="还没有私信"
          desc="在商品或闲置详情页发起咨询后，会话会出现在这里"
        />
      ) : (
        <div className="col" style={{ gap: 10 }}>
          {items.map((c) => (
            <button
              key={`${c.peerId}-${c.productId ?? ''}-${c.listingId ?? ''}`}
              className="card card-pad"
              style={{
                textAlign: 'left',
                cursor: 'pointer',
                width: '100%',
                borderLeft: c.unread > 0 ? '3px solid var(--brand)' : undefined,
              }}
              onClick={() => navigate(chatLink(c))}
            >
              <div className="row" style={{ gap: 10, alignItems: 'center' }}>
                <b>{c.nickname}</b>
                {c.unread > 0 ? <span className="tag tag-active">{c.unread} 条未读</span> : null}
                {c.consulting ? <span className="tag">商品咨询</span> : null}
                {!c.canSend ? (
                  // 说出来比让人撞墙好：非互关只能发一条，且对方尚未回关
                  <span className="tag" title="非互关时每对用户只能发一条；对方回关后可继续">
                    仅可发一条
                  </span>
                ) : null}
                <span className="small muted" style={{ marginLeft: 'auto' }}>
                  {shortTime(c.lastAt)}
                </span>
              </div>
              {(c.productTitle || c.listingTitle) ? (
                <div className="small muted" style={{ marginTop: 6 }}>
                  关于：{c.productTitle ?? c.listingTitle}
                </div>
              ) : null}
              <div className="small" style={{ marginTop: 6 }}>{c.lastContent}</div>
            </button>
          ))}
        </div>
      )}

      <Pagination page={page} pageCount={pageCount} total={total} onChange={setPage} />

      <p className="small muted" style={{ marginTop: 12 }}>
        会话按商品 / 闲置分开：同一件商品下的咨询不会和别的商品串线。
        也可以从<Link to="/follow"> 关注与粉丝 </Link>页找人聊。
      </p>
    </div>
  );
};

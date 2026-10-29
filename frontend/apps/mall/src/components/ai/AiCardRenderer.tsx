/**
 * AI 卡片渲染器（2026-10-29 · 阶段四）。
 *
 * <h2>职责边界</h2>
 * 只做「展示 + 跳转」：卡片里的标题、金额、订单号、跳转地址全部来自服务端装配，
 * 前端不补字段、不拼 URL、不猜业务含义。模型从未参与这张卡的内容，
 * 所以这里也没有任何「模型说了什么」需要防御。
 *
 * <h2>为什么还要在客户端做一次路径校验</h2>
 * 服务端已经过了 RouteRegistry 白名单，但前端是**最后一道执行者**：
 * 一份被篡改的响应（中间人、调试注入、老版本服务端）如果带着
 * `https://evil.com` 或 `//evil.com` 进来，直接交给 `navigate()` 就是开放重定向。
 * 这里的正则只放行「单个 `/` 开头的站内路径」，与服务端白名单同一口径。
 *
 * <h2>金额为什么要 Number() 兜一层</h2>
 * 后端把 64 位标识序列化成字符串（防 JS 精度丢失），金额字段在部分 record 组件里
 * 也会跟着变成字符串。展示层统一转数字，避免 `"15800" / 100` 这类静默 NaN。
 */

import React, { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { formatPrice, formatYuan } from '@duoduo/api-sdk/client';
import type {
  AiActionGridCard,
  AiCard,
  AiCardAction,
  AiCouponItem,
  AiCouponListCard,
  AiGridItem,
  AiNotificationItem,
  AiNotificationListCard,
  AiOrderItem,
  AiOrderListCard,
} from '@duoduo/api-sdk/types';
import { Badge } from '@duoduo/shared/components/badge';
import { formatBjDate, formatBjShort } from '@duoduo/shared/lib/time';

/** 与服务端 RouteWhitelist 同口径：单斜杠开头、不允许 `//`、不允许协议与空白 */
const SAFE_WEB_PATH = /^\/(?!\/)[A-Za-z0-9_\-/]*(?:\?[^\s#]*)?$/;

/** 金额 / ID 可能是字符串（后端 64 位序列化口径），统一转成有限数字 */
export function toNum(value: number | string | null | undefined): number {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n : 0;
}

/** 校验并返回可跳转的站内路径；非法返回 null（调用方直接忽略这次点击） */
export function safeWebPath(url?: string | null): string | null {
  if (typeof url !== 'string') return null;
  const trimmed = url.trim();
  return SAFE_WEB_PATH.test(trimmed) ? trimmed : null;
}

export interface AiCardRendererProps {
  cards?: AiCard[] | null;
  /** 覆盖默认跳转（例如商家端不允许跳买家页面时传一个 no-op） */
  onNavigate?: (url: string) => void;
}

/**
 * 卡片总入口：按 `type` 分派到四个卡片组件。
 * 没有卡片时返回 null —— 不渲染空壳，避免聊天气泡下多出一块空白。
 */
export const AiCardRenderer: React.FC<AiCardRendererProps> = ({ cards, onNavigate }) => {
  const navigate = useNavigate();

  const go = useCallback(
    (url?: string | null) => {
      const path = safeWebPath(url);
      if (!path) return;
      if (onNavigate) onNavigate(path);
      else navigate(path);
    },
    [navigate, onNavigate],
  );

  if (!cards || cards.length === 0) return null;

  return (
    <div style={{ marginTop: 8, display: 'grid', gap: 8 }}>
      {cards.map((card, index) => {
        switch (card.type) {
          case 'notification_list':
            return <NotificationCard key={`n-${index}`} card={card} onNavigate={go} />;
          case 'coupon_list':
            return <CouponCard key={`c-${index}`} card={card} onNavigate={go} />;
          case 'order_list':
            return <OrderCard key={`o-${index}`} card={card} onNavigate={go} />;
          case 'action_grid':
            return <ActionGridCard key={`g-${index}`} card={card} onNavigate={go} />;
          default:
            return null;
        }
      })}
    </div>
  );
};

// ---------------- 通知 ----------------

const NotificationCard: React.FC<{
  card: AiNotificationListCard;
  onNavigate: (url?: string | null) => void;
}> = ({ card, onNavigate }) => {
  const unread = toNum(card.unreadCount);
  return (
    <div className="card" style={{ padding: 12 }}>
      <CardHead title={card.title || '通知'} extra={<Badge count={unread} />} />
      {card.items.length === 0 ? (
        <div className="small muted" style={{ padding: '6px 0' }}>
          没有符合条件的通知
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 6 }}>
          {card.items.map((item) => (
            <NotificationRow key={String(item.id)} item={item} onNavigate={onNavigate} />
          ))}
        </div>
      )}
      <CardActions actions={card.actions} onNavigate={onNavigate} />
    </div>
  );
};

const NotificationRow: React.FC<{
  item: AiNotificationItem;
  onNavigate: (url?: string | null) => void;
}> = ({ item, onNavigate }) => {
  // pageUrl 为 null 表示网页端没有这个页面（例如纯公告）：只展示，不提供点击
  const clickable = Boolean(safeWebPath(item.pageUrl));
  return (
    <div
      role={clickable ? 'button' : undefined}
      tabIndex={clickable ? 0 : undefined}
      onClick={clickable ? () => onNavigate(item.pageUrl) : undefined}
      onKeyDown={
        clickable
          ? (e) => {
              if (e.key === 'Enter') onNavigate(item.pageUrl);
            }
          : undefined
      }
      style={{
        display: 'flex',
        gap: 8,
        alignItems: 'flex-start',
        padding: '8px 10px',
        borderRadius: 8,
        background: item.read ? 'transparent' : 'var(--surface-2, #f7f8fa)',
        cursor: clickable ? 'pointer' : 'default',
      }}
    >
      <span
        aria-hidden
        style={{
          width: 6,
          height: 6,
          marginTop: 6,
          borderRadius: '50%',
          flexShrink: 0,
          background: item.read ? 'transparent' : '#e5484d',
        }}
      />
      <span style={{ minWidth: 0, flex: 1 }}>
        <span style={{ display: 'block', fontSize: 13, fontWeight: item.read ? 400 : 600 }}>
          {item.title}
        </span>
        {item.summary ? (
          <span className="small muted" style={{ display: 'block', marginTop: 2 }}>
            {item.summary}
          </span>
        ) : null}
        <span className="small muted" style={{ display: 'block', marginTop: 2, fontSize: 11 }}>
          {[categoryLabel(item.category), formatBjShort(item.createdAt)].filter(Boolean).join(' · ')}
        </span>
      </span>
    </div>
  );
};

// ---------------- 优惠券 ----------------

const CouponCard: React.FC<{
  card: AiCouponListCard;
  onNavigate: (url?: string | null) => void;
}> = ({ card, onNavigate }) => (
  <div className="card" style={{ padding: 12 }}>
    <CardHead title={card.title || '我的优惠券'} extra={<span className="small muted">共 {card.items.length} 张</span>} />
    {card.items.length === 0 ? (
      <div className="small muted" style={{ padding: '6px 0' }}>
        券包里还没有符合条件的券
      </div>
    ) : (
      <div style={{ display: 'grid', gap: 6 }}>
        {card.items.map((item) => (
          <CouponRow key={String(item.templateId ?? item.code)} item={item} onNavigate={onNavigate} />
        ))}
      </div>
    )}
    <CardActions actions={card.actions} onNavigate={onNavigate} />
  </div>
);

const CouponRow: React.FC<{
  item: AiCouponItem;
  onNavigate: (url?: string | null) => void;
}> = ({ item, onNavigate }) => {
  const threshold = toNum(item.thresholdAmount);
  const discount = toNum(item.discountAmount);
  const percent = toNum(item.discountPercent);
  const maxDiscount = toNum(item.maxDiscountAmount);
  const isDiscount = percent > 0;
  const clickable = Boolean(safeWebPath(item.pageUrl));

  return (
    <div
      role={clickable ? 'button' : undefined}
      tabIndex={clickable ? 0 : undefined}
      onClick={clickable ? () => onNavigate(item.pageUrl) : undefined}
      onKeyDown={
        clickable
          ? (e) => {
              if (e.key === 'Enter') onNavigate(item.pageUrl);
            }
          : undefined
      }
      style={{
        display: 'flex',
        gap: 10,
        alignItems: 'center',
        padding: '8px 10px',
        borderRadius: 8,
        background: 'var(--surface-2, #f7f8fa)',
        cursor: clickable ? 'pointer' : 'default',
      }}
    >
      <span style={{ minWidth: 76, textAlign: 'center' }}>
        <span style={{ display: 'block', fontSize: 18, fontWeight: 700, color: '#e5484d' }}>
          {isDiscount ? `${(percent / 10).toFixed(percent % 10 === 0 ? 0 : 1)}折` : formatPrice(discount)}
        </span>
        <span className="small muted" style={{ fontSize: 11 }}>
          {threshold > 0 ? `满 ${formatYuan(threshold)} 可用` : '无门槛'}
        </span>
      </span>
      <span style={{ minWidth: 0, flex: 1 }}>
        <span style={{ display: 'block', fontSize: 13, fontWeight: 600 }}>{item.name}</span>
        <span className="small muted" style={{ display: 'block', marginTop: 2 }}>
          {[
            item.typeLabel,
            maxDiscount > 0 ? `封顶 ${formatPrice(maxDiscount)}` : null,
            item.expireAt ? `有效期至 ${formatBjDate(item.expireAt)}` : null,
          ]
            .filter(Boolean)
            .join(' · ')}
        </span>
      </span>
      {item.statusLabel ? <span className="tag">{item.statusLabel}</span> : null}
    </div>
  );
};

// ---------------- 订单 ----------------

const OrderCard: React.FC<{
  card: AiOrderListCard;
  onNavigate: (url?: string | null) => void;
}> = ({ card, onNavigate }) => (
  <div className="card" style={{ padding: 12 }}>
    <CardHead title={card.title || '我的订单'} extra={<span className="small muted">共 {card.items.length} 笔</span>} />
    {card.items.length === 0 ? (
      <div className="small muted" style={{ padding: '6px 0' }}>
        没有符合条件的订单
      </div>
    ) : (
      <div style={{ display: 'grid', gap: 6 }}>
        {card.items.map((item) => (
          <OrderRow key={item.orderNo} item={item} onNavigate={onNavigate} />
        ))}
      </div>
    )}
    <CardActions actions={card.actions} onNavigate={onNavigate} />
  </div>
);

const OrderRow: React.FC<{
  item: AiOrderItem;
  onNavigate: (url?: string | null) => void;
}> = ({ item, onNavigate }) => {
  const clickable = Boolean(safeWebPath(item.pageUrl));
  const tail = item.orderNo && item.orderNo.length > 6 ? item.orderNo.slice(-6) : item.orderNo;
  return (
    <div
      role={clickable ? 'button' : undefined}
      tabIndex={clickable ? 0 : undefined}
      onClick={clickable ? () => onNavigate(item.pageUrl) : undefined}
      onKeyDown={
        clickable
          ? (e) => {
              if (e.key === 'Enter') onNavigate(item.pageUrl);
            }
          : undefined
      }
      style={{
        padding: '8px 10px',
        borderRadius: 8,
        background: 'var(--surface-2, #f7f8fa)',
        cursor: clickable ? 'pointer' : 'default',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
        <span className="small muted">订单 …{tail}</span>
        <span className="tag">{item.statusText || item.status || '—'}</span>
      </div>
      <div style={{ fontSize: 13, marginTop: 4 }}>{item.itemsSummary || '（无商品明细）'}</div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 4, gap: 8 }}>
        <span className="small muted">{formatBjShort(item.createdAt)}</span>
        <span style={{ color: '#e5484d', fontWeight: 600, fontSize: 13 }}>
          {formatPrice(toNum(item.amountCents))}
        </span>
      </div>
    </div>
  );
};

// ---------------- 导航宫格 ----------------

const ActionGridCard: React.FC<{
  card: AiActionGridCard;
  onNavigate: (url?: string | null) => void;
}> = ({ card, onNavigate }) => (
  <div className="card" style={{ padding: 12 }}>
    <CardHead title={card.title || '快捷入口'} extra={null} />
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
      {card.items.map((item, index) => (
        <GridButton
          key={`${item.label}-${index}`}
          item={item}
          onNavigate={onNavigate}
        />
      ))}
    </div>
  </div>
);

const GridButton: React.FC<{
  item: AiGridItem;
  onNavigate: (url?: string | null) => void;
}> = ({ item, onNavigate }) => {
  const clickable = Boolean(safeWebPath(item.url));
  return (
    <button
      type="button"
      className="btn btn-ghost btn-sm"
      disabled={!clickable}
      onClick={() => onNavigate(item.url)}
      style={{ minWidth: 96 }}
    >
      {item.label}
    </button>
  );
};

// ---------------- 共用小件 ----------------

const CardHead: React.FC<{ title: string; extra?: React.ReactNode }> = ({ title, extra }) => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: 8,
      marginBottom: 8,
    }}
  >
    <span style={{ fontSize: 13, fontWeight: 600 }}>{title}</span>
    {extra}
  </div>
);

/** 卡片底部动作（如「打开通知中心」）：地址同样来自服务端，非法时按钮不可点 */
const CardActions: React.FC<{
  actions?: AiCardAction[];
  onNavigate: (url?: string | null) => void;
}> = ({ actions, onNavigate }) => {
  if (!actions || actions.length === 0) return null;
  return (
    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 8 }}>
      {actions.map((action, index) => {
        const clickable = Boolean(safeWebPath(action.url));
        return (
          <button
            key={`${action.label}-${index}`}
            type="button"
            className="btn btn-ghost btn-sm"
            disabled={!clickable}
            onClick={() => onNavigate(action.url)}
          >
            {action.label}
          </button>
        );
      })}
    </div>
  );
};

/** 通知分类中文名：只用于展示，未知分类原样透出（不猜业务含义） */
function categoryLabel(category?: string | null): string {
  switch (category) {
    case 'ORDER':
      return '订单';
    case 'FRIEND':
      return '好友';
    case 'MERCHANT':
      return '商家';
    case 'SYSTEM':
      return '系统';
    case 'ANNOUNCEMENT':
      return '公告';
    default:
      return category || '';
  }
}

/**
 * 订单卡片（C4）。
 *
 * <h2>为什么卡片只存快照</h2>
 * 订单状态会变（发货、完成、退款），如果每次渲染都去交易域查一次，
 * 一个会话 30 条消息就是 30 次远程调用；而且客服看到的应当与用户发送时一致，
 * 否则会出现「用户说没发货、卡片显示已签收」这种对不上。
 * 因此发送时由服务端落快照，渲染只读快照。
 *
 * <h2>商家端不可跳转</h2>
 * 订单详情是买家侧页面，商家点进去会带着当前浏览器的商城登录态 ——
 * 与商品卡同一类越权面。商家端只展示，`href` 传 null。
 */
import React from 'react';
import { productImage } from '@duoduo/api-sdk/images';

export interface OrderCardMeta {
  orderNo?: string;
  status?: string;
  statusLabel?: string;
  itemCount?: number;
  items?: { title?: string; image?: string; price?: number; quantity?: number }[];
}

export function parseOrderMeta(metaJson: string | null | undefined): OrderCardMeta | null {
  if (!metaJson) return null;
  try {
    return JSON.parse(metaJson) as OrderCardMeta;
  } catch {
    return null;
  }
}

export const OrderCard: React.FC<{ metaJson: string | null; href?: string | null }> = ({
  metaJson,
  href,
}) => {
  const meta = parseOrderMeta(metaJson);
  if (!meta) {
    return <div className="sup-bubble">订单信息（无法解析）</div>;
  }
  const first = (meta.items ?? [])[0];
  const tail = meta.orderNo && meta.orderNo.length > 4 ? meta.orderNo.slice(-4) : meta.orderNo;
  const body = (
    <>
      <span style={{ display: 'block', fontSize: 13, marginBottom: 4 }}>
        订单尾号 {tail} · {meta.statusLabel || meta.status || '—'}
      </span>
      {first ? (
        <span className="row" style={{ gap: 8, alignItems: 'center' }}>
          {first.image && (
            <img
              src={productImage(meta.orderNo, first.image)}
              alt=""
              style={{ width: 44, height: 44, borderRadius: 8, objectFit: 'cover' }}
            />
          )}
          <span style={{ minWidth: 0 }}>
            <span style={{ display: 'block', fontSize: 13 }}>{first.title}</span>
            {first.price != null && (
              <span className="small" style={{ color: 'var(--brand-strong)' }}>
                ¥{(first.price / 100).toFixed(2)} × {first.quantity ?? 1}
              </span>
            )}
          </span>
        </span>
      ) : null}
      {Number(meta.itemCount) > 1 ? (
        <span className="small muted" style={{ display: 'block', marginTop: 4 }}>
          共 {meta.itemCount} 件商品
        </span>
      ) : null}
    </>
  );

  const style: React.CSSProperties = {
    display: 'block',
    padding: 10,
    marginTop: 6,
    borderRadius: 10,
    background: 'var(--surface-2)',
    border: '1px solid var(--border)',
    color: 'var(--text)',
    textDecoration: 'none',
    minWidth: 200,
  };

  if (!href) {
    return <div style={style}>{body}</div>;
  }
  return (
    <a href={href} style={style}>
      {body}
    </a>
  );
};

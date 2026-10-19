/**
 * 分享圈共享卡片（信息流 / 详情页共用）。
 *
 * <p>渲染规则与小程序端一致：
 * <ul>
 *   <li>转发单显示「转发自 @原作者」引用块；原帖被作者删除时显示提示语而不是引用内容 ——
 *       删除意图必须被尊重（服务端把 parentContent 刻意置 null）；</li>
 *   <li>被编辑过显示「已编辑」；</li>
 *   <li>商品卡图片走统一解析层（后端的 /img/… 占位路径映射到本地图池）；</li>
 *   <li>互动按钮的**未登录降级**由调用方决定（信息流公开可逛，点互动才要求登录）。</li>
 * </ul>
 */

import React from 'react';
import { Link } from 'react-router-dom';
import type { ShareView } from '@duoduo/api-sdk/types';
import { productImage } from '@duoduo/api-sdk/images';
import { formatPrice } from '@duoduo/api-sdk/client';
import { useSpuSeckill } from '../store/seckill';
import { SeckillPrice, SeckillTag } from './seckill';

/** 相对时间：1 小时内显示「x 分钟前」，当天显示「x 小时前」，其余显示日期 */
export function relativeTime(iso: string | null | undefined): string {
  if (!iso) return '';
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return iso.replace('T', ' ').slice(0, 16);
  const diff = Date.now() - t;
  const min = Math.floor(diff / 60000);
  if (min < 1) return '刚刚';
  if (min < 60) return `${min} 分钟前`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h} 小时前`;
  return iso.slice(0, 10);
}

export interface ShareCardProps {
  item: ShareView;
  /** 详情页传 true：不渲染「查看全文」跳转，互动区由页面自己接 */
  detailed?: boolean;
  onLike?: () => void;
  onFavorite?: () => void;
  onRepost?: () => void;
  onComment?: () => void;
  /** 互动操作进行中的 id（禁用按钮防连点） */
  busy?: boolean;
}

export const ShareCard: React.FC<ShareCardProps> = ({ item, detailed, onLike, onFavorite, onRepost, onComment, busy }) => {
  // 分享圈里的商品卡同样是「商品出现的位置」：参与秒杀就挂标并换价
  const seckill = useSpuSeckill(item.spuId);
  return (
  <div className="card card-pad">
    <div className="row" style={{ gap: 10, alignItems: 'center' }}>
      <div
        style={{
          width: 38, height: 38, borderRadius: '50%', flex: 'none',
          background: 'var(--surface-2)', display: 'grid', placeItems: 'center', overflow: 'hidden',
        }}
      >
        {item.avatar ? (
          <img src={item.avatar} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
        ) : (
          <span>🐾</span>
        )}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        {/* S6-D1：作者名可点 → 他人主页。小程序 `share-circle.js` 早有这个入口，
            网页端此前点头像/名字都没反应，C2C 的「看到人 → 进主页 → 看他其他内容」断在最后一跳。
            用 <Link> 而不是 onClick+navigate：这里本来就在 react-router 里，语义更直接。 */}
        <Link className="link-btn" to={'/user/' + item.userId} title={`查看 ${item.nickname || item.userId} 的主页`}>
          <b>{item.nickname || `用户 ${item.userId}`}</b>
        </Link>
        {item.edited ? <span className="small muted" style={{ marginLeft: 6 }}>已编辑</span> : null}
        <div className="small muted">{relativeTime(item.createdAt)}</div>
      </div>
      {item.mine ? <Link className="btn btn-ghost btn-sm" to="/share/mine">管理</Link> : null}
    </div>

    {item.content ? (
      <p style={{ margin: '10px 0', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{item.content}</p>
    ) : null}

    {/* 转发引用块：原帖删除时只给提示语，不给内容 */}
    {item.shareType === 'REPOST' ? (
      item.parentDeleted || item.parentContent == null ? (
        <div className="small muted" style={{
          borderLeft: '3px solid var(--surface-3)', padding: '6px 10px', margin: '8px 0',
          background: 'var(--surface-2)', borderRadius: 6,
        }}>
          原内容已被作者删除
        </div>
      ) : (
        <div style={{
          borderLeft: '3px solid var(--surface-3)', padding: '6px 10px', margin: '8px 0',
          background: 'var(--surface-2)', borderRadius: 6,
        }}>
          <div className="small muted">转发自 @{item.parentNickname ?? '原作者'}</div>
          <div className="small" style={{ marginTop: 4, whiteSpace: 'pre-wrap' }}>{item.parentContent}</div>
        </div>
      )
    ) : null}

    {item.topics?.length ? (
      <div className="row row-wrap" style={{ gap: 6, marginTop: 4 }}>
        {item.topics.map((t) => (
          <Link key={t} className="tag tag-second" to={`/share/topic/${encodeURIComponent(t)}`}>#{t}</Link>
        ))}
      </div>
    ) : null}

    {/* 商品卡：spuId 是快照，点进去就是这件商品 */}
    {item.spuId ? (
      <Link to={`/product/${item.spuId}`} style={{ display: 'block', marginTop: 8 }}>
        <div className="card" style={{
          display: 'flex', gap: 10, padding: 8, alignItems: 'center',
          background: 'var(--surface-2)',
        }}>
          <div style={{
            width: 56, height: 56, flex: 'none', borderRadius: 8, overflow: 'hidden',
            display: 'grid', placeItems: 'center',
          }}>
            <img
              src={productImage(item.spuId, item.productImage)}
              alt={item.productTitle ?? ''}
              style={{ width: '100%', height: '100%', objectFit: 'cover' }}
              loading="lazy"
            />
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{
              fontSize: 13, overflow: 'hidden', textOverflow: 'ellipsis',
              display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical' as const,
            }}>{item.productTitle}</div>
            {seckill ? (
              <div className="row row-wrap" style={{ gap: 6, marginTop: 3, alignItems: 'center' }}>
                <SeckillTag phase={seckill.phase} />
                <SeckillPrice
                  price={seckill.sku.seckillPrice}
                  original={seckill.sku.originalPrice}
                  size="sm"
                />
              </div>
            ) : item.minPrice > 0 ? (
              <div className="price" style={{ marginTop: 2 }}>{formatPrice(item.minPrice)}</div>
            ) : null}
          </div>
        </div>
      </Link>
    ) : null}

    {/* 互动区 */}
    <div className="row" style={{ gap: 14, marginTop: 10 }}>
      <button className="btn btn-ghost btn-sm" disabled={busy} onClick={onLike}>
        {item.liked ? '❤️' : '🤍'} {item.likeCount}
      </button>
      <button className="btn btn-ghost btn-sm" disabled={busy} onClick={onComment}>
        💬 {item.commentCount}
      </button>
      <button className="btn btn-ghost btn-sm" disabled={busy} onClick={onFavorite}>
        {item.favorited ? '⭐' : '☆'} {item.favoriteCount}
      </button>
      <button className="btn btn-ghost btn-sm" disabled={busy} onClick={onRepost}>
        🔁 转发
      </button>
      {!detailed ? (
        <Link className="btn btn-ghost btn-sm" style={{ marginLeft: 'auto' }} to={`/share/${item.id}`}>
          查看全文 →
        </Link>
      ) : null}
    </div>
  </div>
  );
};

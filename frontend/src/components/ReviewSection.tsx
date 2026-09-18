/**
 * 商品评价区（公开可见）。
 *
 * 数据源：
 *   GET /api/reviews/product/{spuId}/summary   —— 评分汇总（平均分 / 星级分布 / 好评率）
 *   GET /api/reviews/product/{spuId}           —— 评价分页（可按星级、是否有图过滤）
 *
 * 为什么把评价区做成独立组件而不是写进商品详情页：
 * 详情页已经很长，而评价区自带「筛选 + 分页」两套状态；独立组件后
 * 详情页只需要 `<ReviewSection spuId={id} />`，状态互不干扰。
 *
 * 空评价的处理刻意与「加载失败」分开：新商品没有评价是**正常状态**，
 * 不该显示成错误提示；只有真的请求失败才提示重试。
 */

import React, { useCallback, useState } from 'react';
import { reviewApi } from '../api/endpoints';
import type { ReviewSummary, ReviewView } from '../api/types';
import { Alert, Empty, Pagination, Spinner, Stars, formatDate } from './ui';
import { useAsync } from './hooks';

const RATING_TABS: Array<{ value: number | undefined; label: string }> = [
  { value: undefined, label: '全部' },
  { value: 5, label: '5 星' },
  { value: 4, label: '4 星' },
  { value: 3, label: '3 星' },
  { value: 2, label: '2 星' },
  { value: 1, label: '1 星' },
];

export const ReviewSection: React.FC<{ spuId: number }> = ({ spuId }) => {
  const [rating, setRating] = useState<number | undefined>(undefined);
  const [hasImage, setHasImage] = useState(false);
  const [page, setPage] = useState(1);

  const summary = useAsync<ReviewSummary>(
    useCallback(() => reviewApi.summary(spuId), [spuId]),
    true,
    [spuId],
  );
  const list = useAsync(
    useCallback(
      () => reviewApi.byProduct(spuId, { rating, hasImage: hasImage || undefined, page, pageSize: 5 }),
      [spuId, rating, hasImage, page],
    ),
    true,
    [spuId, rating, hasImage, page],
  );

  const s = summary.data;
  const maxStarCount = s ? Math.max(1, ...s.starCounts) : 1;

  return (
    <section className="section" style={{ marginTop: 28 }}>
      <div className="section-head">
        <h2 className="section-title">商品评价{s && s.total > 0 ? `（${s.total}）` : ''}</h2>
      </div>

      {summary.error ? <Alert kind="error">{summary.error}</Alert> : null}

      {s && s.total > 0 ? (
        <div className="card card-pad" style={{ marginBottom: 14 }}>
          <div className="row row-wrap" style={{ gap: 28, alignItems: 'flex-start' }}>
            <div style={{ minWidth: 120 }}>
              <div style={{ fontSize: 34, fontWeight: 700, lineHeight: 1 }}>
                {s.avgRating}
                <span className="small muted"> 分</span>
              </div>
              <Stars value={Number(s.avgRating) || 0} />
              <div className="small muted" style={{ marginTop: 4 }}>
                好评率 {s.goodRate}
              </div>
            </div>

            <div style={{ flex: 1, minWidth: 220 }}>
              {[5, 4, 3, 2, 1].map((star) => {
                const count = s.starCounts[star - 1] ?? 0;
                const pct = maxStarCount === 0 ? 0 : Math.round((count / maxStarCount) * 100);
                return (
                  <div key={star} className="row" style={{ gap: 8, alignItems: 'center', marginBottom: 4 }}>
                    <span className="small muted nowrap" style={{ width: 34 }}>{star} 星</span>
                    <div
                      style={{
                        flex: 1,
                        height: 8,
                        background: 'var(--surface-2, #eee)',
                        borderRadius: 4,
                        overflow: 'hidden',
                      }}
                    >
                      <div style={{ width: `${pct}%`, height: '100%', background: '#f5a524' }} />
                    </div>
                    <span className="small muted" style={{ width: 30, textAlign: 'right' }}>{count}</span>
                  </div>
                );
              })}
            </div>

            <div className="small muted" style={{ minWidth: 100 }}>
              <div>带图 {s.imageCount}</div>
              <div>追评 {s.appendCount}</div>
            </div>
          </div>
        </div>
      ) : null}

      <div className="row row-wrap" style={{ gap: 8, marginBottom: 12 }}>
        {RATING_TABS.map((tab) => (
          <button
            key={tab.label}
            className={`btn btn-sm ${rating === tab.value ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => {
              setRating(tab.value);
              setPage(1);
            }}
          >
            {tab.label}
          </button>
        ))}
        <button
          className={`btn btn-sm ${hasImage ? 'btn-primary' : 'btn-ghost'}`}
          onClick={() => {
            setHasImage((v) => !v);
            setPage(1);
          }}
        >
          有图
        </button>
      </div>

      {list.error ? <Alert kind="error">{list.error}</Alert> : null}
      {list.loading ? <Spinner /> : null}

      {!list.loading && list.data && list.data.items.length === 0 ? (
        <Empty
          icon="💬"
          title={s && s.total > 0 ? '该筛选条件下没有评价' : '还没有评价，买过的朋友来说两句吧'}
        />
      ) : null}

      {!list.loading && list.data
        ? list.data.items.map((rv) => <ReviewItem key={rv.id} review={rv} />)
        : null}

      {list.data && list.data.pageCount > 1 ? (
        <Pagination page={page} pageCount={list.data.pageCount} total={list.data.total} onChange={setPage} />
      ) : null}
    </section>
  );
};

/** 单条评价：星级 + 正文 + 图片 + 追评；匿名评价展示服务端已脱敏的昵称 */
const ReviewItem: React.FC<{ review: ReviewView }> = ({ review }) => (
  <div className="card card-pad" style={{ marginBottom: 10 }}>
    <div className="row row-wrap" style={{ gap: 10, alignItems: 'center' }}>
      <div className="row" style={{ gap: 8, alignItems: 'center' }}>
        {review.avatar ? (
          <img
            src={review.avatar}
            alt=""
            style={{ width: 28, height: 28, borderRadius: '50%', objectFit: 'cover' }}
          />
        ) : (
          <span
            aria-hidden
            style={{
              width: 28,
              height: 28,
              borderRadius: '50%',
              background: 'var(--surface-2, #eee)',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 14,
            }}
          >
            🐾
          </span>
        )}
        <span className="small">{review.nickname}</span>
      </div>
      <Stars value={review.rating} />
      <span className="small muted">{formatDate(review.createdAt)}</span>
    </div>

    {review.content ? <p style={{ margin: '8px 0 0' }}>{review.content}</p> : null}

    {review.images.length > 0 ? (
      <div className="row row-wrap" style={{ gap: 8, marginTop: 8 }}>
        {review.images.map((url) => (
          <a key={url} href={url} target="_blank" rel="noreferrer">
            <img
              src={url}
              alt="评价图片"
              style={{ width: 72, height: 72, objectFit: 'cover', borderRadius: 8 }}
            />
          </a>
        ))}
      </div>
    ) : null}

    {review.appendContent ? (
      <div
        style={{
          marginTop: 10,
          padding: '8px 10px',
          borderLeft: '3px solid var(--border-strong, #ddd)',
          background: 'var(--surface-2, #fafafa)',
        }}
      >
        <div className="small muted">追评 · {formatDate(review.appendAt)}</div>
        <div className="small" style={{ marginTop: 4 }}>{review.appendContent}</div>
      </div>
    ) : null}
  </div>
);

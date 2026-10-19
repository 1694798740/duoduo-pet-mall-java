/**
 * 我的评价（含追评入口）。
 *
 * 数据源：
 *   GET  /api/reviews/mine            —— 我的评价分页
 *   POST /api/reviews/{id}/append     —— 追评（每条一次）
 *
 * 「可追评」由服务端下发（`appendable`），前端不自己算窗口期：
 * 追评期限是业务口径，改口径时只该改服务端一处，否则前端会继续放行已经过期的追评，
 * 用户点了才吃一个 422 —— 这种「前端说可以、后端说不行」的分歧最难解释给用户。
 */

import React, { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { reviewApi } from '@duoduo/api-sdk/endpoints';
import type { PageResult, ReviewView } from '@duoduo/api-sdk/types';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, Stars, formatDate, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';

export const MyReviewsPage: React.FC = () => {
  const { push } = useToast();
  const [page, setPage] = useState(1);
  const [appending, setAppending] = useState<ReviewView | null>(null);
  const [content, setContent] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const { data, loading, error: loadError, reload } = useAsync<PageResult<ReviewView>>(
    useCallback(() => reviewApi.mine({ page, pageSize: 10 }), [page]),
    true,
    [page],
  );

  if (loading) return <LoadingBlock text="正在加载我的评价…" />;

  const submitAppend = async () => {
    if (!appending) return;
    setError(null);
    setBusy(true);
    try {
      await reviewApi.append(appending.id, content.trim());
      push('追评已发布', 'success');
      setAppending(null);
      setContent('');
      reload();
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <h2 className="section-title">我的评价</h2>

      {loadError ? <Alert kind="error">{loadError}</Alert> : null}

      {data && data.items.length === 0 ? (
        <Empty
          icon="💬"
          title="还没有评价过商品"
          action={
            <Link className="btn btn-primary" to="/orders">
              去我的订单看看
            </Link>
          }
        />
      ) : null}

      {data
        ? data.items.map((rv) => (
            <div key={rv.id} className="card card-pad" style={{ marginBottom: 10 }}>
              <div className="row row-wrap" style={{ gap: 8, alignItems: 'center' }}>
                <Stars value={rv.rating} />
                <span className="small muted">{formatDate(rv.createdAt)}</span>
                <span className="small muted">订单 {rv.orderNo}</span>
                {rv.anonymous ? <span className="small muted">· 匿名</span> : null}
              </div>

              <div className="row" style={{ gap: 10, marginTop: 8, alignItems: 'center' }}>
                {rv.productImage ? (
                  <img
                    src={rv.productImage}
                    alt=""
                    style={{ width: 44, height: 44, objectFit: 'cover', borderRadius: 6 }}
                  />
                ) : null}
                <span className="small" style={{ minWidth: 0 }}>{rv.productTitle}</span>
              </div>

              {rv.content ? <p style={{ margin: '8px 0 0' }}>{rv.content}</p> : null}

              {rv.images.length > 0 ? (
                <div className="row row-wrap" style={{ gap: 6, marginTop: 8 }}>
                  {rv.images.map((url) => (
                    <img
                      key={url}
                      src={url}
                      alt="评价图片"
                      style={{ width: 56, height: 56, objectFit: 'cover', borderRadius: 6 }}
                    />
                  ))}
                </div>
              ) : null}

              {rv.appendContent ? (
                <div
                  style={{
                    marginTop: 10,
                    padding: '8px 10px',
                    borderLeft: '3px solid var(--border-strong, #ddd)',
                    background: 'var(--surface-2, #fafafa)',
                  }}
                >
                  <div className="small muted">追评 · {formatDate(rv.appendAt)}</div>
                  <div className="small" style={{ marginTop: 4 }}>{rv.appendContent}</div>
                </div>
              ) : null}

              <div className="row" style={{ gap: 8, marginTop: 10 }}>
                {rv.appendable ? (
                  <button
                    className="btn btn-ghost btn-sm"
                    onClick={() => {
                      setAppending(rv);
                      setContent('');
                      setError(null);
                    }}
                  >
                    追评
                  </button>
                ) : (
                  <span className="small muted">
                    {rv.appendContent ? '已追评' : '已超过追评期限'}
                  </span>
                )}
                <Link className="btn btn-ghost btn-sm" to={`/product/${rv.spuId}`}>
                  查看商品
                </Link>
              </div>
            </div>
          ))
        : null}

      {data ? (
        <Pagination page={page} pageCount={data.pageCount} total={data.total} onChange={setPage} />
      ) : null}

      <Modal
        open={appending !== null}
        title="追加评价"
        onClose={() => setAppending(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setAppending(null)}>取消</button>
            <button
              className="btn btn-primary"
              disabled={busy || content.trim().length === 0}
              onClick={submitAppend}
            >
              {busy ? '提交中…' : '发布追评'}
            </button>
          </>
        }
      >
        {appending ? (
          <div>
            <p className="small muted" style={{ marginBottom: 10 }}>
              为「{appending.productTitle}」的这条评价追加内容。每条评价只能追评一次。
            </p>
            {error ? <Alert kind="error">{error}</Alert> : null}
            <Field label="追评内容">
              <textarea
                className="input"
                rows={3}
                maxLength={1000}
                placeholder="用了一段时间后有什么新感受？"
                value={content}
                onChange={(e) => setContent(e.target.value)}
              />
            </Field>
          </div>
        ) : null}
      </Modal>
    </div>
  );
};

/**
 * 提交评价（按订单，可对订单里的多件商品分别评价）。
 *
 * 数据源：
 *   GET  /api/orders/{orderNo}        —— 订单明细（要评哪些商品）
 *   GET  /api/reviews/order/{orderNo} —— 该订单我已评过的商品（避免重复展示表单）
 *   POST /api/reviews                 —— 提交
 *
 * 三条刻意的交互取舍：
 *   1. **已评过的商品不再出现在表单里**，只列出「还差哪几件」—— 让用户一眼看到待办，
 *      而不是面对一堆已填好的表单猜「我到底评过没有」；
 *   2. 全部评完时给出明确完成态与回订单入口，而不是显示空表单；
 *   3. 星级用可点击的大星号（点第 N 颗即 N 星），不做下拉框 —— 评价是手机上一个顺手动作，
 *      下拉框要多两跳。
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { productImage } from '@duoduo/api-sdk/images';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { orderApi, reviewApi } from '@duoduo/api-sdk/endpoints';
import type { OrderView, ReviewItemRequest, ReviewView } from '@duoduo/api-sdk/types';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Empty, Field, LoadingBlock, useToast } from '@duoduo/shared/components/ui';
import { ImageUploader } from '@duoduo/shared/components/ImageUploader';
import { useAsync } from '@duoduo/shared/components/hooks';

interface DraftItem {
  spuId: string;
  skuId: string | null;
  title: string;
  mainImage: string | null;
  specText: string | null;
  rating: number;
  content: string;
  /** 已完成上传/转存、可以直接提交的图片地址 */
  images: string[];
  /** 该商品正在上传，提交按钮需要等它结束 */
  uploading: boolean;
}

const MAX_IMAGES = 9;

export const ReviewNewPage: React.FC = () => {
  const [params] = useSearchParams();
  const orderNo = params.get('orderNo') ?? '';
  const navigate = useNavigate();
  const { push } = useToast();

  const [drafts, setDrafts] = useState<DraftItem[]>([]);
  const [anonymous, setAnonymous] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const order = useAsync<OrderView>(
    useCallback(() => orderApi.detail(orderNo), [orderNo]),
    Boolean(orderNo),
    [orderNo],
  );
  const mine = useAsync<ReviewView[]>(
    useCallback(() => reviewApi.byOrder(orderNo), [orderNo]),
    Boolean(orderNo),
    [orderNo],
  );

  // 用「订单明细 − 我已评过的商品」生成待评草稿；评过的商品不再出现在表单里
  useEffect(() => {
    const data = order.data;
    const reviewed = new Set((mine.data ?? []).map((r) => r.spuId));
    if (!data) {
      setDrafts([]);
      return;
    }
    const seen = new Set<string>();
    const next: DraftItem[] = [];
    for (const item of data.items) {
      const spuId = item.spuId;
      if (spuId == null || reviewed.has(spuId) || seen.has(spuId)) continue;
      seen.add(spuId);
      next.push({
        spuId,
        skuId: item.skuId,
        title: item.title,
        mainImage: item.mainImage,
        specText: item.specText,
        rating: 5,
        content: '',
        images: [],
        uploading: false,
      });
    }
    setDrafts(next);
  }, [order.data, mine.data]);

  const reviewedCount = mine.data?.length ?? 0;
  const loading = order.loading || mine.loading;
  const loadError = order.error || mine.error;

  const canSubmit = useMemo(
    () => drafts.length > 0 && drafts.every((d) => d.rating >= 1 && d.rating <= 5 && !d.uploading) && !busy,
    [drafts, busy],
  );

  if (!orderNo) {
    return (
      <div>
        <h2 className="section-title">评价商品</h2>
        <Alert kind="error">缺少订单号，请从「我的订单」进入评价。</Alert>
      </div>
    );
  }
  if (loading) return <LoadingBlock text="正在准备评价…" />;
  if (loadError) {
    return (
      <div>
        <h2 className="section-title">评价商品</h2>
        <Alert kind="error">{loadError}</Alert>
        <Link className="btn btn-ghost" to="/orders">返回我的订单</Link>
      </div>
    );
  }
  if (order.data && order.data.status !== 'COMPLETED') {
    return (
      <div>
        <h2 className="section-title">评价商品</h2>
        <Alert kind="warn">
          订单当前状态为「{order.data.statusLabel}」，确认收货后才能评价。
        </Alert>
        <Link className="btn btn-ghost" to={`/orders/${orderNo}`}>查看订单</Link>
      </div>
    );
  }

  const update = (spuId: string, patch: Partial<DraftItem>) => {
    setDrafts((list) => list.map((d) => (d.spuId === spuId ? { ...d, ...patch } : d)));
  };

  const submit = async () => {
    setError(null);
    setBusy(true);
    try {
      const items: ReviewItemRequest[] = drafts.map((d) => ({
        spuId: d.spuId,
        skuId: d.skuId,
        rating: d.rating,
        content: d.content.trim() || undefined,
        images: d.images,
      }));
      const result = await reviewApi.create({ orderNo, anonymous, items });
      push(`已提交 ${result.created} 条评价`, 'success');
      navigate('/reviews/mine');
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <h2 className="section-title">评价商品 · 订单 {orderNo}</h2>

      {error ? <Alert kind="error">{error}</Alert> : null}

      {drafts.length === 0 ? (
        <div>
          <Alert kind="success">
            该订单的商品都已评价过（共 {reviewedCount} 条）。
          </Alert>
          <div className="row" style={{ gap: 8, marginTop: 12 }}>
            <Link className="btn btn-primary" to="/reviews/mine">查看我的评价</Link>
            <Link className="btn btn-ghost" to={`/orders/${orderNo}`}>返回订单</Link>
          </div>
        </div>
      ) : (
        <>
          {reviewedCount > 0 ? (
            <Alert kind="info">
              该订单已有 {reviewedCount} 件商品评价过，下面只列出还没评的部分。
            </Alert>
          ) : null}

          {drafts.map((draft) => (
            <div key={draft.spuId} className="card card-pad" style={{ marginTop: 12 }}>
              <div className="row" style={{ gap: 10, alignItems: 'center' }}>
                {draft.mainImage ? (
                  <img
                    src={productImage(draft.spuId, draft.mainImage)}
                    alt=""
                    style={{ width: 56, height: 56, objectFit: 'cover', borderRadius: 8 }}
                    loading="lazy"
                    decoding="async"
                  />
                ) : null}
                <div style={{ minWidth: 0 }}>
                  <div className="goods-title">{draft.title}</div>
                  <div className="small muted">{draft.specText ?? '默认规格'}</div>
                </div>
              </div>

              <div style={{ marginTop: 12 }}>
                <div className="small muted" style={{ marginBottom: 4 }}>评分</div>
                <div className="row" style={{ gap: 6 }}>
                  {[1, 2, 3, 4, 5].map((star) => (
                    <button
                      key={star}
                      type="button"
                      aria-label={`${star} 星`}
                      onClick={() => update(draft.spuId, { rating: star })}
                      style={{
                        border: 'none',
                        background: 'none',
                        cursor: 'pointer',
                        fontSize: 26,
                        lineHeight: 1,
                        color: star <= draft.rating ? '#f5a524' : 'var(--border-strong, #ccc)',
                      }}
                    >
                      ★
                    </button>
                  ))}
                  <span className="small muted" style={{ marginLeft: 6 }}>
                    {ratingLabel(draft.rating)}
                  </span>
                </div>
              </div>

              <Field label="评价内容（可选）">
                <textarea
                  className="input"
                  rows={3}
                  maxLength={1000}
                  placeholder="说说商品怎么样：口味 / 质量 / 物流 / 毛孩子的反应…"
                  value={draft.content}
                  onChange={(e) => update(draft.spuId, { content: e.target.value })}
                />
              </Field>

              <Field label={`评价图片（可选，最多 ${MAX_IMAGES} 张）`}>
                <ImageUploader
                  value={draft.images}
                  onChange={(next) => update(draft.spuId, { images: next })}
                  max={MAX_IMAGES}
                  size={72}
                  onBusyChange={(busy) => update(draft.spuId, { uploading: busy })}
                  addLabel="上传图片"
                />
              </Field>
            </div>
          ))}

          <label className="row small" style={{ gap: 6, marginTop: 12, alignItems: 'center' }}>
            <input
              type="checkbox"
              checked={anonymous}
              onChange={(e) => setAnonymous(e.target.checked)}
            />
            匿名评价（昵称将显示为「匿名用户」，但平台仍保留真实账号用于售后与风控）
          </label>

          <div className="row" style={{ gap: 8, marginTop: 16 }}>
            <button className="btn btn-primary" disabled={!canSubmit} onClick={submit}>
              {busy ? '提交中…' : `提交 ${drafts.length} 条评价`}
            </button>
            <Link className="btn btn-ghost" to="/orders">稍后再说</Link>
          </div>
        </>
      )}

      {reviewedCount === 0 && drafts.length === 0 && order.data ? (
        <Empty icon="📦" title="这个订单没有可评价的商品" />
      ) : null}
    </div>
  );
};

function ratingLabel(rating: number): string {
  if (rating >= 5) return '非常满意';
  if (rating === 4) return '满意';
  if (rating === 3) return '一般';
  if (rating === 2) return '不太满意';
  return '很不满意';
}

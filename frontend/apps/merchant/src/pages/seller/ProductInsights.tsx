/**
 * 商家端 · 商品分析（2026-10-24）。
 *
 * <p>回答三个问题：哪些商品在卖、卖得怎么样、买家满不满意。
 *
 * <h2>数据来自哪里（三个域拼起来）</h2>
 * <ul>
 *   <li>商品主档与评分：商品域 {@code GET /api/seller/products}（rating/ratingCount 已由评价域回写）；</li>
 *   <li>销量 / 成交额 / 退款件数：交易域 {@code GET /api/seller/product-stats}（从订单明细聚合，
 *       **不读 t_spu.sales_count** —— 那一列没有写入链路，是"看起来像销量"的常量）；</li>
 *   <li>评价明细与好评率：评价域公开接口 {@code /api/reviews/product/{spuId}/summary|byProduct}
 *       —— 评价本身是公开信息，商家看到的与买家看到的必须一致，不另做一套口径。</li>
 * </ul>
 *
 * <p>为什么在端侧合并：三个域各有自己的库，跨库 JOIN 不存在；这些数据量很小（一页几十行），
 * 端侧按 spuId 合并比在任一域里造一个"大杂烩接口"更清晰，也不会把域边界搞乱。
 */

import React, { useCallback, useMemo, useState } from 'react';
import { reviewApi, sellerApi } from '@duoduo/api-sdk/endpoints';
import type { ReviewSummary, SellerProductStat, SellerProductView } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import { Alert, Empty, LoadingBlock, Modal, formatPrice, useToast } from '@duoduo/shared/components/ui';
import { ReviewSection } from '@duoduo/shared/components/ReviewSection';

const RATE = (s?: ReviewSummary | null): number => {
  if (!s || !s.total) return 0;
  const parsed = Number(String(s.goodRate).replace('%', ''));
  return Number.isFinite(parsed) ? parsed : 0;
};

export const SellerProductInsightsPage: React.FC = () => {
  const { push } = useToast();
  const [reviewing, setReviewing] = useState<SellerProductView | null>(null);

  const products = useAsync(
    useCallback(() => sellerApi.products({ page: 1, pageSize: 100 }), []),
    true,
  );
  const stats = useAsync(useCallback(() => sellerApi.productStats(), []), true);
  const [summaries, setSummaries] = useState<Record<string, ReviewSummary | null>>({});

  // 评价汇总：逐商品并发拉取（最多 100 个）。失败的商品记为 null，
  // 不影响整页 —— 「某个商品查不到评价」不应该让分析页白屏。
  const loadSummaries = useCallback(async (list: SellerProductView[]) => {
    const entries = await Promise.all(
      list.map(async (p) => {
        try {
          return [p.id, await reviewApi.summary(p.id)] as const;
        } catch {
          return [p.id, null] as const;
        }
      }),
    );
    setSummaries(Object.fromEntries(entries));
  }, []);

  const list = products.data?.items ?? [];
  React.useEffect(() => {
    if (list.length) void loadSummaries(list).catch(() => push('部分商品评价读取失败', 'error'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [products.data]);

  const statMap = useMemo(() => {
    const map = new Map<string, SellerProductStat>();
    for (const row of stats.data ?? []) map.set(String(row.spuId), row);
    return map;
  }, [stats.data]);

  const rows = useMemo(() => {
    return list
      .map((p) => {
        const st = statMap.get(p.id);
        const sm = summaries[p.id] ?? null;
        return {
          product: p,
          quantity: st?.quantity ?? 0,
          amount: st?.amount ?? 0,
          refundQty: st?.refundQty ?? 0,
          rating: sm ? Number(sm.avgRating) : p.rating ?? 0,
          reviewCount: sm?.total ?? p.ratingCount ?? 0,
          goodRate: RATE(sm),
          summary: sm,
        };
      })
      .sort((a, b) => b.quantity - a.quantity);
  }, [list, statMap, summaries]);

  const kpi = useMemo(() => {
    const totalReviews = rows.reduce((n, r) => n + r.reviewCount, 0);
    const weighted = rows.reduce((n, r) => n + r.rating * r.reviewCount, 0);
    const goodReviews = rows.reduce((n, r) => n + (r.reviewCount * r.goodRate) / 100, 0);
    return {
      productCount: rows.length,
      onSale: rows.filter((r) => r.product.status === 'ON_SALE' && r.product.auditStatus === 'APPROVED').length,
      pending: rows.filter((r) => r.product.auditStatus === 'PENDING').length,
      rejected: rows.filter((r) => r.product.auditStatus === 'REJECTED').length,
      quantity: rows.reduce((n, r) => n + r.quantity, 0),
      amount: rows.reduce((n, r) => n + r.amount, 0),
      refundQty: rows.reduce((n, r) => n + r.refundQty, 0),
      totalReviews,
      avgRating: totalReviews ? (weighted / totalReviews).toFixed(2) : '—',
      goodRate: totalReviews ? ((goodReviews / totalReviews) * 100).toFixed(1) + '%' : '—',
    };
  }, [rows]);

  if (products.loading && !products.data) return <LoadingBlock text="正在汇总商品数据…" />;

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">商品分析</h2>
      </div>
      <p className="small muted" style={{ marginTop: -4 }}>
        销量与成交额来自真实订单明细（含已退款订单）；退款件数单列，不从销量里悄悄扣掉。
        评分与好评率来自买家评价，口径与商城详情页一致（好评 = 4 星及以上）。
      </p>

      {products.error ? <Alert kind="error">{products.error}</Alert> : null}
      {stats.error ? <Alert kind="warn">经营数据读取失败：{stats.error}（评分与评价仍可查看）</Alert> : null}

      <div className="stat-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12, margin: '14px 0 18px' }}>
        {[
          ['商品总数', String(kpi.productCount)],
          ['在售中', String(kpi.onSale)],
          ['待审核 / 已驳回', `${kpi.pending} / ${kpi.rejected}`],
          ['累计销量', `${kpi.quantity} 件`],
          ['成交额', formatPrice(kpi.amount)],
          ['退款件数', `${kpi.refundQty} 件`],
          ['平均评分', String(kpi.avgRating)],
          ['好评率', String(kpi.goodRate)],
        ].map(([label, value]) => (
          <div className="card card-pad" key={label} style={{ padding: '12px 14px' }}>
            <div className="small muted">{label}</div>
            <div style={{ fontSize: 20, fontWeight: 700, marginTop: 4 }}>{value}</div>
          </div>
        ))}
      </div>

      {rows.length === 0 ? (
        <Empty title="还没有商品" desc="先发布商品，积累成交与评价后这里会有数据。" />
      ) : (
        <div className="card card-pad" style={{ overflowX: 'auto' }}>
          <table className="table" style={{ width: '100%', minWidth: 820 }}>
            <thead>
              <tr>
                <th>商品</th>
                <th style={{ textAlign: 'right' }}>销量</th>
                <th style={{ textAlign: 'right' }}>成交额</th>
                <th style={{ textAlign: 'right' }}>退款</th>
                <th style={{ textAlign: 'right' }}>评分</th>
                <th style={{ textAlign: 'right' }}>评价数</th>
                <th style={{ textAlign: 'right' }}>好评率</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.product.id}>
                  <td>
                    <div className="row" style={{ gap: 8, alignItems: 'center' }}>
                      {r.product.mainImage ? (
                        <img src={r.product.mainImage} alt="" width={36} height={36} style={{ borderRadius: 6, objectFit: 'cover' }} />
                      ) : null}
                      <div style={{ minWidth: 0 }}>
                        <div style={{ maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {r.product.title}
                        </div>
                        <div className="small muted">{r.product.auditLabel}</div>
                      </div>
                    </div>
                  </td>
                  <td style={{ textAlign: 'right' }}>{r.quantity}</td>
                  <td style={{ textAlign: 'right' }}>{formatPrice(r.amount)}</td>
                  <td style={{ textAlign: 'right' }}>{r.refundQty || '—'}</td>
                  <td style={{ textAlign: 'right' }}>{r.reviewCount ? r.rating.toFixed(1) : '—'}</td>
                  <td style={{ textAlign: 'right' }}>{r.reviewCount || '—'}</td>
                  <td style={{ textAlign: 'right' }}>{r.reviewCount ? r.goodRate.toFixed(1) + '%' : '—'}</td>
                  <td style={{ textAlign: 'right' }}>
                    <button className="btn btn-sm btn-ghost" onClick={() => setReviewing(r.product)}>
                      查看评价
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Modal
        open={reviewing !== null}
        title={reviewing ? `商品评价 · ${reviewing.title}` : '商品评价'}
        onClose={() => setReviewing(null)}
        footer={<button className="btn btn-ghost" onClick={() => setReviewing(null)}>关闭</button>}
      >
        {reviewing ? <ReviewSection spuId={reviewing.id} /> : null}
      </Modal>
    </div>
  );
};

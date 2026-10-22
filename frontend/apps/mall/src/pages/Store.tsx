/**
 * 店铺主页（第八十二轮，参考淘宝店铺页）。
 *
 * 数据来源（全部公开免登）：
 *   GET /api/users/stores/{ownerUserId}   店铺页头（仅营业中店铺，其余 404）
 *   GET /api/products/store/{sellerId}    该店在售商品（分页；sellerId 与 ownerUserId 同键）
 *
 * 关键口径：
 *  · sellerId 是**店主 userId**（与 t_spu.seller_id 同键），不是 t_merchant.id；
 *    sellerId = 0 即平台自营 —— 自营没有 t_merchant 行，页头**不调** storeInfo，
 *    直接渲染「多多自营」+ 自营徽标（和购物车组头同一口径）。
 *  · 商品区用「加载更多」分页而不是无限滚动：与站内其它列表页一致，也避免
 *    锚点定位在移动端被软键盘/地址栏伸缩打断。
 *  · 商品卡直接复用 ProductCardView（整卡点进 /product/:id）。
 */

import React, { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { productApi, userApi } from '@duoduo/api-sdk/endpoints';
import type { ProductCard, StorePublicView } from '@duoduo/api-sdk/types';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Empty, LoadingBlock, ProductCardView, formatPrice } from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';

const PAGE_SIZE = 20;

export const StorePage: React.FC = () => {
  const { sellerId = '' } = useParams();
  /** 自营（0 号卖家）没有 t_merchant 行：页头不走 user 域，商品列表照常（自营货架） */
  const isSelf = sellerId === '0';

  const storeFetcher = useCallback(
    () => (isSelf ? Promise.resolve(null) : userApi.storeInfo(sellerId)),
    [isSelf, sellerId],
  );
  const { data: store, loading: storeLoading, error: storeError } =
    useAsync<StorePublicView | null>(storeFetcher);

  // ── 商品分页（加载更多）+ 排序 + 店内搜索（2026-10-22，对齐淘宝店铺页） ──
  const [items, setItems] = useState<ProductCard[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [sort, setSort] = useState('default');
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState<string | null>(null);

  useEffect(() => {
    // 切店（路由参数变化）时重置分页/排序/搜索状态
    setItems([]);
    setTotal(0);
    setPage(1);
    setSort('default');
    setKeywordInput('');
    setKeyword('');
    setListError(null);
  }, [sellerId]);

  useEffect(() => {
    if (!sellerId) return;
    let cancelled = false;
    setListLoading(true);
    productApi
      .storeProducts(sellerId, {
        sort: sort === 'default' ? undefined : sort,
        keyword: keyword.trim() || undefined,
        page,
        pageSize: PAGE_SIZE,
      })
      .then((res) => {
        if (cancelled) return;
        setTotal(res.total);
        setItems((prev) => (page === 1 ? res.items : [...prev, ...res.items]));
        setListError(null);
      })
      .catch((err) => {
        if (!cancelled) setListError(toFriendlyMessage(err));
      })
      .finally(() => {
        if (!cancelled) setListLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [sellerId, page, sort, keyword]);

  if (!isSelf && storeLoading) return <LoadingBlock />;
  // 非自营店铺查不到（未入驻 / 未过审 / 停业）→ 明确报错，绝不渲染空壳页面
  if (!isSelf && storeError) return <Alert kind="error">{toFriendlyMessage(storeError)}</Alert>;

  const hasMore = items.length < total;
  const minPrice = items.length > 0 ? Math.min(...items.map((i) => i.minPrice)) : 0;

  return (
    <div className="col" style={{ gap: 14 }}>
      {/* ── 店铺页头（参考淘宝：店名 + 标识 + 开店信息） ── */}
      <section className="card card-pad" style={{ display: 'flex', gap: 14, alignItems: 'center' }}>
        <div
          style={{
            width: 64, height: 64, borderRadius: 14, flex: 'none',
            background: 'var(--surface-2)', display: 'grid', placeItems: 'center',
            fontSize: 26, color: 'var(--primary)', fontWeight: 700,
          }}
          aria-hidden
        >
          {(isSelf ? '多' : (store?.name ?? '店').slice(0, 1))}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <b style={{ fontSize: 18 }}>{isSelf ? '多多自营' : (store?.name ?? '')}</b>
            {isSelf ? (
              <span className="small" style={{ color: 'var(--primary)', border: '1px solid var(--primary)', borderRadius: 4, padding: '0 4px' }}>
                自营
              </span>
            ) : null}
          </div>
          <div className="small muted" style={{ marginTop: 4 }}>
            {isSelf
              ? '平台自营 · 售后无忧'
              : store?.openedAt
                ? `开店于 ${store.openedAt.slice(0, 10)}`
                : '入驻商家'}
          </div>
        </div>
        <div style={{ textAlign: 'right', flex: 'none' }}>
          <div style={{ fontWeight: 700 }}>{total}</div>
          <div className="small muted">在售商品</div>
        </div>
      </section>

      {/* ── 在售商品（排序 + 店内搜索 + 网格 + 加载更多） ── */}
      <section>
        <h3 className="section-title" style={{ fontSize: 16, marginBottom: 10 }}>
          全部商品
          {total > 0 ? <span className="small muted" style={{ fontWeight: 400, marginLeft: 8 }}>{total} 件在售</span> : null}
        </h3>

        {/* 工具条：排序胶囊 + 店内搜索（风格与全站列表页一致） */}
        <div className="row row-wrap" style={{ gap: 8, marginBottom: 12, alignItems: 'center' }}>
          {(
            [
              { value: 'default', label: '综合' },
              { value: 'sales', label: '销量' },
              { value: 'price_asc', label: '价格↑' },
              { value: 'price_desc', label: '价格↓' },
              { value: 'newest', label: '最新' },
            ] as { value: string; label: string }[]
          ).map((s) => (
            <button
              key={s.value}
              type="button"
              className={`btn btn-sm ${sort === s.value ? 'btn-primary' : 'btn-ghost'}`}
              onClick={() => {
                setSort(s.value);
                setPage(1);
              }}
            >
              {s.label}
            </button>
          ))}
          <div className="row" style={{ gap: 6, marginLeft: 'auto' }}>
            <input
              className="input"
              style={{ width: 200 }}
              placeholder="搜索本店商品"
              value={keywordInput}
              onChange={(e) => setKeywordInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  setKeyword(keywordInput);
                  setPage(1);
                }
              }}
            />
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={() => {
                setKeyword(keywordInput);
                setPage(1);
              }}
            >
              搜索
            </button>
            {keyword ? (
              <button
                type="button"
                className="btn btn-sm btn-ghost"
                onClick={() => {
                  setKeywordInput('');
                  setKeyword('');
                  setPage(1);
                }}
              >
                清除
              </button>
            ) : null}
          </div>
        </div>

        {listError ? <Alert kind="error">{listError}</Alert> : null}

        {items.length === 0 && !listLoading && !listError ? (
          <Empty
            icon="🏪"
            title={keyword ? `本店没有与「${keyword}」匹配的商品` : '这家店暂时没有在售商品'}
            desc={keyword ? '换个关键词试试' : '换个店铺看看，或稍后再来'}
          />
        ) : (
          <div className="grid grid-goods">
            {items.map((item) => (
              <ProductCardView key={item.id} item={item} />
            ))}
          </div>
        )}

        {listLoading ? <LoadingBlock /> : null}

        {!listLoading && hasMore ? (
          <div style={{ textAlign: 'center', marginTop: 14 }}>
            <button type="button" className="btn btn-ghost" onClick={() => setPage((p) => p + 1)}>
              加载更多（已展示 {items.length}/{total}）
            </button>
          </div>
        ) : null}

        {items.length > 0 && !hasMore && !listLoading ? (
          <div className="small muted" style={{ textAlign: 'center', marginTop: 12 }}>
            已展示全部 {total} 件商品{minPrice > 0 ? ` · 低至 ¥${formatPrice(minPrice)}` : ''}
          </div>
        ) : null}
      </section>
    </div>
  );
};

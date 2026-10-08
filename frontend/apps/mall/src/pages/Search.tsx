/**
 * 商品检索页。
 *
 * 数据来源：GET /api/products（对应后端 ProductQuery）
 *  · 全部筛选条件都写进 URL query，保证可分享、可刷新、可回退
 *  · 类目树来自 GET /api/categories，筛选字典来自 GET /api/meta/dictionaries
 *  · 后端对违禁词返回 422，文案直接透出 error.message
 *
 * 注意：价格筛选的单位是「分」，输入框按「元」收，提交前乘 100。
 */

import React, { useCallback, useMemo } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { catalogApi, productApi } from '@duoduo/api-sdk/endpoints';
import type { ProductQuery } from '@duoduo/api-sdk/endpoints';
import type { CategoryNode, DictItem, PageResult, ProductCard } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import { Alert, LoadingBlock, Pagination, ProductGrid } from '@duoduo/shared/components/ui';

const SORTS: { value: NonNullable<ProductQuery['sort']>; label: string }[] = [
  { value: 'default', label: '综合' },
  { value: 'sales', label: '销量' },
  { value: 'price_asc', label: '价格从低到高' },
  { value: 'price_desc', label: '价格从高到低' },
  { value: 'rating', label: '评分' },
  { value: 'newest', label: '最新' },
];

/** 把 URL 上的字符串安全转成数字（非法值返回 undefined，不下发脏参数） */
function num(value: string | null): number | undefined {
  if (value === null || value === '') return undefined;
  const n = Number(value);
  return Number.isFinite(n) ? n : undefined;
}

/**
 * 把 URL 上的字符串原样传下去，只做**非空**校验。
 *
 * ⚠️ id 类参数（categoryId / brandId）**不能**走 Num()：它们是 18 位雪花值，
 * `Number()` 会静默四舍五入成另一个 id，筛选结果直接变空（T-39）。
 * 价格类参数（minPrice）是真正需要运算的，继续用 num()。
 */
function rawId(value: string | null): string | undefined {
  return value === null || value === '' ? undefined : value;
}

export const SearchPage: React.FC = () => {
  const [params, setParams] = useSearchParams();

  const query = useMemo<ProductQuery>(
    () => ({
      keyword: params.get('keyword') ?? undefined,
      categoryId: rawId(params.get('categoryId')),
      brandId: rawId(params.get('brandId')),
      supplyType: (params.get('supplyType') as ProductQuery['supplyType']) ?? undefined,
      // 输入是「元」，转成「分」下发
      minPrice: num(params.get('minPrice')) != null ? num(params.get('minPrice'))! * 100 : undefined,
      maxPrice: num(params.get('maxPrice')) != null ? num(params.get('maxPrice'))! * 100 : undefined,
      species: (params.get('species') as ProductQuery['species']) ?? undefined,
      lifeStage: (params.get('lifeStage') as ProductQuery['lifeStage']) ?? undefined,
      inStockOnly: params.get('inStockOnly') === '1' ? true : undefined,
      sort: (params.get('sort') as ProductQuery['sort']) ?? undefined,
      page: num(params.get('page')) ?? 1,
      pageSize: 20,
    }),
    [params],
  );

  const fetcher = useCallback(
    (signal: AbortSignal) => productApi.search({ ...query, signal } as ProductQuery),
    [query],
  );
  const { data, loading, error, reload } = useAsync<PageResult<ProductCard>>(fetcher, true, [params]);

  const categoriesFetcher = useCallback(() => catalogApi.categories(), []);
  const { data: categories } = useAsync<CategoryNode[]>(categoriesFetcher);

  const dictFetcher = useCallback(() => catalogApi.dictionaries(), []);
  const { data: dicts } = useAsync<Record<string, DictItem[]>>(dictFetcher);

  /** 改一个筛选条件时回到第 1 页（除非改的就是 page 本身） */
  const patch = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value === undefined || value === '') next.delete(key);
      else next.set(key, value);
    }
    if (!('page' in changes)) next.delete('page');
    setParams(next);
  };

  const speciesOptions = dicts?.species ?? [
    { code: 'cat', label: '猫咪' },
    { code: 'dog', label: '狗狗' },
    { code: 'other', label: '其他宠物' },
  ];
  const lifeStageOptions = dicts?.lifeStage ?? [
    { code: 'BABY', label: '幼年' },
    { code: 'ADULT', label: '成年' },
    { code: 'SENIOR', label: '老年' },
  ];

  const activeCategoryId = query.categoryId;
  const flatCategories = useMemo(() => {
    const out: { id: string; name: string; depth: number }[] = [];
    const walk = (nodes: CategoryNode[]) => {
      for (const node of nodes) {
        out.push({ id: node.id, name: node.name, depth: node.depth });
        if (node.children?.length) walk(node.children);
      }
    };
    walk(categories ?? []);
    return out;
  }, [categories]);

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">
          {query.keyword ? `「${query.keyword}」的搜索结果` : '全部商品'}
        </h2>
        <span className="section-sub">
          {data ? `共 ${data.total} 件` : loading ? '检索中…' : ''}
        </span>
      </div>

      <div className="card card-pad" style={{ marginBottom: 18 }}>
        <div className="filter-row">
          <span className="filter-label">分类</span>
          <div className="row row-wrap">
            <button
              className={`tag${!activeCategoryId ? ' tag-active' : ''}`}
              onClick={() => patch({ categoryId: undefined })}
              type="button"
            >
              不限
            </button>
            {flatCategories.map((cat) => (
              <button
                key={cat.id}
                className={`tag${activeCategoryId === cat.id ? ' tag-active' : ''}`}
                onClick={() => patch({ categoryId: String(cat.id) })}
                type="button"
              >
                {cat.name}
              </button>
            ))}
          </div>
        </div>

        <div className="filter-row">
          <span className="filter-label">供货</span>
          <div className="row row-wrap">
            {(dicts?.supplyType ?? [
              { code: 'self', label: '自营保真' },
              { code: 'merchant', label: '第三方商家' },
            ]).map((opt) => (
              <button
                key={opt.code}
                className={`tag${query.supplyType === opt.code ? ' tag-active' : ''}`}
                onClick={() => patch({ supplyType: query.supplyType === opt.code ? undefined : opt.code })}
                type="button"
              >
                {opt.label}
              </button>
            ))}
            <button
              className={`tag${!query.supplyType ? ' tag-active' : ''}`}
              onClick={() => patch({ supplyType: undefined })}
              type="button"
            >
              不限
            </button>
          </div>
        </div>

        <div className="filter-row">
          <span className="filter-label">适用</span>
          <div className="row row-wrap">
            {speciesOptions.map((opt) => (
              <button
                key={opt.code}
                className={`tag${query.species === opt.code ? ' tag-active' : ''}`}
                onClick={() => patch({ species: query.species === opt.code ? undefined : opt.code })}
                type="button"
              >
                {opt.label}
              </button>
            ))}
            <span className="muted">|</span>
            {lifeStageOptions.map((opt) => (
              <button
                key={opt.code}
                className={`tag${query.lifeStage === opt.code ? ' tag-active' : ''}`}
                onClick={() =>
                  patch({ lifeStage: query.lifeStage === opt.code ? undefined : opt.code })
                }
                type="button"
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>

        <div className="filter-row">
          <span className="filter-label">价格</span>
          <div className="row">
            <input
              className="input"
              style={{ width: 92 }}
              defaultValue={params.get('minPrice') ?? ''}
              placeholder="最低价"
              inputMode="decimal"
              onBlur={(e) => patch({ minPrice: e.target.value.trim() || undefined })}
            />
            <span className="muted">—</span>
            <input
              className="input"
              style={{ width: 92 }}
              defaultValue={params.get('maxPrice') ?? ''}
              placeholder="最高价"
              inputMode="decimal"
              onBlur={(e) => patch({ maxPrice: e.target.value.trim() || undefined })}
            />
            <span className="small muted">元</span>
            <label className="row small" style={{ gap: 6 }}>
              <input
                type="checkbox"
                checked={query.inStockOnly === true}
                onChange={(e) => patch({ inStockOnly: e.target.checked ? '1' : undefined })}
              />
              仅看有货
            </label>
          </div>
        </div>

        <div className="filter-row" style={{ marginBottom: 0 }}>
          <span className="filter-label">排序</span>
          <div className="row row-wrap">
            {SORTS.map((opt) => (
              <button
                key={opt.value}
                className={`tag${(query.sort ?? 'default') === opt.value ? ' tag-active' : ''}`}
                onClick={() => patch({ sort: opt.value === 'default' ? undefined : opt.value })}
                type="button"
              >
                {opt.label}
              </button>
            ))}
            {params.toString() ? (
              <button className="btn btn-ghost btn-sm" type="button" onClick={() => setParams(new URLSearchParams())}>
                清空筛选
              </button>
            ) : null}
          </div>
        </div>
      </div>

      {error ? (
        <Alert kind="error">
          {error}
          <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={reload}>
            重试
          </button>
        </Alert>
      ) : loading && !data ? (
        <LoadingBlock text="正在检索…" />
      ) : data && data.items.length ? (
        <>
          <ProductGrid items={data.items} />
          <Pagination
            page={data.page}
            pageCount={data.pageCount}
            total={data.total}
            onChange={(page) => patch({ page: String(page) })}
          />
        </>
      ) : (
        <div className="empty">
          <div className="empty-icon">🔍</div>
          <div className="empty-title">没有找到符合条件的商品</div>
          <div className="small">试着放宽价格区间，或清空部分筛选条件</div>
          <div style={{ marginTop: 14 }}>
            <Link className="btn btn-ghost btn-sm" to="/search">
              重置筛选
            </Link>
          </div>
        </div>
      )}
    </div>
  );
};

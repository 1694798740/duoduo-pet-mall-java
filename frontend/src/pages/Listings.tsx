/**
 * C2C 闲置列表页。
 *
 * 数据来源：GET /api/listings（对应后端 ListingQuery）
 *  · 后端只返回「已上架且审核通过」的闲置，前端不做二次过滤
 *  · 与商品检索页一样，筛选条件全部写进 URL query
 *  · **Web 端不提供「我要卖」入口** —— 注意与「后端没有接口」区分开：
 *    后端 R52 已建齐（`POST /api/listings` 发布、`GET /api/listings/mine/list` 我的发布、
 *    `POST /api/listings/{id}/status` 上下架、`/{id}/edit`、`/{id}/delete`、`/{id}/resubmit`），
 *    且只要求「登录 + 实名」、**不要求 SELLER 角色**；缺口在 Web 页面没接
 *    （小程序已有 flea-publish / flea-mine 四页）。此处注释曾写作「没有对应后端接口」，与事实不符，
 *    2026-10-05 更正 —— 免得下一个人照它判断「做不了」。
 */

import React, { useCallback, useMemo } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { catalogApi, productApi } from '../api/endpoints';
import type { ListingQuery } from '../api/endpoints';
import type { DictItem, ListingCard, PageResult } from '../api/types';
import { useAsync } from '../components/hooks';
import { Alert, LoadingBlock, ListingGrid, Pagination } from '../components/ui';

/** 兜底字典：与 /api/meta/dictionaries 的 conditionLevel 完全一致 */
const FALLBACK_CONDITIONS: DictItem[] = [
  { code: 'NEW', label: '全新未拆' },
  { code: 'LIKE_NEW', label: '几乎全新' },
  { code: 'GOOD', label: '成色良好' },
  { code: 'FAIR', label: '有明显使用痕迹' },
];

const SORTS: (DictItem & { code: string })[] = [
  { code: 'default', label: '综合' },
  { code: 'price_asc', label: '价格从低到高' },
  { code: 'price_desc', label: '价格从高到低' },
  { code: 'newest', label: '最新发布' },
];

function num(value: string | null): number | undefined {
  if (value === null || value === '') return undefined;
  const n = Number(value);
  return Number.isFinite(n) ? n : undefined;
}

export const ListingsPage: React.FC = () => {
  const [params, setParams] = useSearchParams();

  const query = useMemo<ListingQuery>(
    () => ({
      keyword: params.get('keyword') ?? undefined,
      city: params.get('city') ?? undefined,
      species: params.get('species') ?? undefined,
      conditionLevel: params.get('conditionLevel') ?? undefined,
      minPrice: num(params.get('minPrice')) != null ? num(params.get('minPrice'))! * 100 : undefined,
      maxPrice: num(params.get('maxPrice')) != null ? num(params.get('maxPrice'))! * 100 : undefined,
      sort: params.get('sort') ?? undefined,
      page: num(params.get('page')) ?? 1,
      pageSize: 20,
    }),
    [params],
  );

  const fetcher = useCallback(
    (signal: AbortSignal) => productApi.listings({ ...query, signal }),
    [query],
  );
  const { data, loading, error, reload } = useAsync<PageResult<ListingCard>>(fetcher, true, [params]);

  // 成色等筛选项由服务端字典下发，避免前端硬编码与后端枚举漂移
  const dictFetcher = useCallback(() => catalogApi.dictionaries(), []);
  const { data: dicts } = useAsync<Record<string, DictItem[]>>(dictFetcher);
  const conditions = dicts?.conditionLevel?.length ? dicts.conditionLevel : FALLBACK_CONDITIONS;

  const patch = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value === undefined || value === '') next.delete(key);
      else next.set(key, value);
    }
    if (!('page' in changes)) next.delete('page');
    setParams(next);
  };

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">
          {query.keyword ? `闲置「${query.keyword}」` : '二手闲置'}
        </h2>
        <span className="section-sub">
          {data ? `共 ${data.total} 件` : loading ? '加载中…' : ''}
        </span>
      </div>

      <Alert kind="info">
        闲鱼式 C2C 交易：下单后货款先由平台托管，你确认收货后才会结算给卖家。
        未发货前可自助申请退款。
      </Alert>

      <div className="card card-pad" style={{ margin: '16px 0 18px' }}>
        <div className="filter-row">
          <span className="filter-label">物种</span>
          <div className="row row-wrap">
            {[
              { value: undefined, label: '不限' },
              { value: 'cat', label: '猫' },
              { value: 'dog', label: '狗' },
            ].map((opt) => (
              <button
                key={opt.label}
                type="button"
                className={`tag${query.species === opt.value ? ' tag-active' : ''}`}
                onClick={() => patch({ species: opt.value })}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>

        <div className="filter-row">
          <span className="filter-label">成色</span>
          <div className="row row-wrap">
            <button
              type="button"
              className={`tag${!query.conditionLevel ? ' tag-active' : ''}`}
              onClick={() => patch({ conditionLevel: undefined })}
            >
              不限
            </button>
            {conditions.map((c) => (
              <button
                key={c.code}
                type="button"
                className={`tag${query.conditionLevel === c.code ? ' tag-active' : ''}`}
                onClick={() =>
                  patch({ conditionLevel: query.conditionLevel === c.code ? undefined : c.code })
                }
              >
                {c.label}
              </button>
            ))}
          </div>
        </div>

        <div className="filter-row">
          <span className="filter-label">价格 / 城市</span>
          <div className="row row-wrap">
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
            <input
              className="input"
              style={{ width: 132 }}
              defaultValue={params.get('city') ?? ''}
              placeholder="城市，如 杭州"
              onBlur={(e) => patch({ city: e.target.value.trim() || undefined })}
            />
          </div>
        </div>

        <div className="filter-row" style={{ marginBottom: 0 }}>
          <span className="filter-label">排序</span>
          <div className="row row-wrap">
            {SORTS.map((opt) => (
              <button
                key={opt.code}
                type="button"
                className={`tag${(query.sort ?? 'default') === opt.code ? ' tag-active' : ''}`}
                onClick={() => patch({ sort: opt.code === 'default' ? undefined : opt.code })}
              >
                {opt.label}
              </button>
            ))}
            {params.toString() ? (
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={() => setParams(new URLSearchParams())}
              >
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
        <LoadingBlock text="正在加载闲置…" />
      ) : data && data.items.length ? (
        <>
          <ListingGrid items={data.items} />
          <Pagination
            page={data.page}
            pageCount={data.pageCount}
            total={data.total}
            onChange={(page) => patch({ page: String(page) })}
          />
        </>
      ) : (
        <div className="empty">
          <div className="empty-icon">♻️</div>
          <div className="empty-title">暂时没有符合条件的闲置</div>
          <div className="small">换个城市或放宽价格区间试试</div>
          <div style={{ marginTop: 14 }}>
            <Link className="btn btn-ghost btn-sm" to="/listings">
              重置筛选
            </Link>
          </div>
        </div>
      )}
    </div>
  );
};

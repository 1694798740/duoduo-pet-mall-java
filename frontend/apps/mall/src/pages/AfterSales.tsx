/**
 * 我的售后（买家视角，A3）。
 *
 * 数据源：`GET /api/aftersales/mine`（仅返回登录用户名下的单，服务端限定归属）。
 *
 * 三态齐全：加载 / 空 / 错误。空数据不是错误 ——
 * 「还没提过售后」是绝大多数用户的常态，用提示错误的方式呈现会让人以为系统坏了。
 */

import React, { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { afterSaleApi } from '@duoduo/api-sdk/endpoints';
import type { StatusCount } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, LoadingBlock, Pagination, SearchBar, formatDate, formatPrice,
} from '@duoduo/shared/components/ui';
import { useAsync, useDebounced, useLiveRefresh } from '@duoduo/shared/components/hooks';
import { AFTER_SALE_STATUS_TABS, AfterSaleStatusTag } from '@duoduo/shared/components/afterSale';
import { Badge, toCountMap } from '@duoduo/shared/components/badge';
import {
  applyOptimisticDismiss, shouldDismissAfterSale,
} from '@duoduo/shared/lib/badge-rules';
import type { AfterSaleView } from '@duoduo/api-sdk/types';

export const AfterSalesPage: React.FC = () => {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);

  const { data, loading, error, reloadSilently } = useAsync(
    useCallback(
      () => afterSaleApi.mine({ status: status || undefined, keyword: keyword || undefined, page, pageSize: 10 }),
      [status, keyword, page],
    ),
    true,
    [status, keyword, page],
  );

  /**
   * 各状态计数（页签角标，2026-10-27）。口径与列表接口同源（{@code side=buyer}），
   * 但覆盖**全量**而不是当前页 —— 分页外的待处理单也要在页签上看得见。
   */
  const countsFetcher = useCallback(() => afterSaleApi.statusCounts('buyer'), []);
  const { data: countRows, reloadSilently: reloadCounts } = useAsync<StatusCount[]>(
    countsFetcher,
    true,
    [],
  );

  /** 角标本地态：允许乐观减，之后一律以服务端分桶覆盖 */
  const [counts, setCounts] = useState<Record<string, number>>({});
  useEffect(() => {
    setCounts(toCountMap(countRows));
  }, [countRows]);

  /**
   * 点开售后详情 → 上报已读 → 服务端分桶覆盖（阅后即消）。
   *
   * <p>只有终态（已退款 / 已关闭）会消；进行中的看过也留着（规则见 badge-rules.ts）。
   * 失败不本地硬减，回滚到服务端值，避免「刷新后又回来」。
   */
  const dismissBadge = useCallback(
    async (item: AfterSaleView) => {
      if (!shouldDismissAfterSale('buyer', item.status)) return;
      setCounts((prev) => applyOptimisticDismiss(prev, item.status));
      try {
        const rows = await afterSaleApi.markRead(item.id, 'buyer');
        setCounts(toCountMap(rows));
      } catch {
        void reloadCounts();
      }
    },
    [reloadCounts],
  );

  /** 售后由卖家/平台推进状态 —— 列表页同样三层静默刷新，不用手动按 F5；角标同刻刷新 */
  useLiveRefresh(
    useCallback(() => {
      reloadSilently();
      reloadCounts();
    }, [reloadSilently, reloadCounts]),
  );

  return (
    <div>
      <div className="row-between" style={{ marginBottom: 12 }}>
        <h2 className="section-title">我的售后</h2>
        <Link className="btn btn-sm btn-ghost" to="/orders">
          去订单里申请
        </Link>
      </div>

      <Alert kind="info">
        售后从<b>订单</b>发起：在「我的订单」里找到已发货或已完成的订单，点「申请售后」。
        卖家拒绝时可以<b>申请平台仲裁</b>，由运营裁定。
      </Alert>

      <div className="row row-wrap" style={{ gap: 8, margin: '14px 0' }}>
        {AFTER_SALE_STATUS_TABS.map((tab) => (
          <button
            key={tab.value}
            className={`btn btn-sm ${status === tab.value ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => {
              setStatus(tab.value);
              setPage(1);
            }}
          >
            {tab.label}
            <Badge count={counts[tab.value]} />
          </button>
        ))}
      </div>

      <div className="list-toolbar">
        <SearchBar
          value={keywordInput}
          onChange={(v) => {
            setKeywordInput(v);
            setPage(1);
          }}
          busy={loading && Boolean(keywordInput.trim())}
          placeholder="搜售后单号或订单号"
          width={300}
        />
        {keywordInput.trim() ? (
          <span className="small muted">匹配「{keywordInput.trim()}」的售后 {data ? data.total : 0} 条</span>
        ) : null}
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading ? <LoadingBlock text="正在加载售后单…" /> : null}

      {!loading && data && data.items.length === 0 ? (
        <Empty
          icon="🧾"
          title={keywordInput.trim() ? `没有匹配「${keywordInput.trim()}」的售后` : '还没有售后记录'}
          desc={keywordInput.trim() ? '试试售后单号后几位或订单号' : '在订单里点「申请售后」即可发起'}
        />
      ) : null}

      {!loading && data
        ? data.items.map((item) => (
            <div className="card card-pad" key={item.id} style={{ marginBottom: 12 }}>
              <div className="row-between">
                <div>
                  <div className="mono">{item.orderNo}</div>
                  <div className="small muted">
                    {item.typeLabel} · 申请于 {formatDate(item.createdAt)}
                  </div>
                </div>
                <AfterSaleStatusTag status={item.status} label={item.statusLabel} />
              </div>

              <div style={{ marginTop: 10 }}>{item.reason}</div>

              {item.images.length > 0 ? (
                <div className="row row-wrap" style={{ gap: 8, marginTop: 8 }}>
                  {item.images.map((url) => (
                    <a key={url} className="small" href={url} target="_blank" rel="noreferrer">
                      举证图
                    </a>
                  ))}
                </div>
              ) : null}

              {item.sellerReply ? (
                <div style={{ marginTop: 10 }}>
                  <Alert kind="warn">卖家处理说明：{item.sellerReply}</Alert>
                </div>
              ) : null}

              {item.arbitrateNote ? (
                <div style={{ marginTop: 10 }}>
                  <Alert kind="info">平台裁定说明：{item.arbitrateNote}</Alert>
                </div>
              ) : null}

              <div className="row-between" style={{ marginTop: 12 }}>
                <span className="small muted">
                  {item.refundAmount !== null ? `已退款 ${formatPrice(item.refundAmount)}` : '退款金额待定'}
                </span>
                <Link
                  className="btn btn-sm btn-ghost"
                  to={`/after-sales/${item.id}`}
                  onClick={() => void dismissBadge(item)}
                >
                  查看详情
                </Link>
              </div>
            </div>
          ))
        : null}

      {data ? (
        <Pagination
          page={page}
          pageCount={Math.max(1, data.pageCount)}
          total={data.total}
          onChange={setPage}
        />
      ) : null}
    </div>
  );
};

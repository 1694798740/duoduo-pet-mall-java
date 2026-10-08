/**
 * 商家端 · 资金对账。
 *
 * 数据来源：GET /api/seller/settlements?status=&page=&pageSize=
 *
 * 一个关键的口径：顶部三个汇总数字是<b>全量</b>的，不受分页与状态过滤影响。
 * 分页只是「一次看不完」，而「我一共托管多少、已到账多少」是对账问题的答案；
 * 若让汇总跟着过滤走，勾一个「已放款」就会让人以为自己只赚了这么多。
 */

import React, { useCallback } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { sellerApi } from '@duoduo/api-sdk/endpoints';
import type { SettlementPage } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import {
  Alert,
  Empty,
  LoadingBlock,
  Pagination,
  formatDate,
  formatPrice,
} from '@duoduo/shared/components/ui';
import { SellerApplyCard, canAccessSellerConsole } from './shared';
import { useAuth } from '@duoduo/shared/store/auth';

const TABS: { value: string; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'CUSTODY', label: '托管中' },
  { value: 'RELEASED', label: '已放款' },
  { value: 'REFUNDED', label: '已退回' },
];

function num(value: string | null): number | undefined {
  if (value === null || value === '') return undefined;
  const n = Number(value);
  return Number.isFinite(n) ? n : undefined;
}

export const SellerSettlementsPage: React.FC = () => {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();

  const status = params.get('status') ?? '';
  const page = num(params.get('page')) ?? 1;
  const allowed = canAccessSellerConsole(user?.role);

  const fetcher = useCallback(
    () => sellerApi.settlements({ status: status || undefined, page, pageSize: 20 }),
    [status, page],
  );
  const { data, loading, error, reload } = useAsync<SettlementPage>(
    fetcher,
    allowed,
    [allowed, status, page],
  );

  const setStatus = (value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set('status', value);
    else next.delete('status');
    next.delete('page');
    setParams(next);
  };

  const setPage = (value: number) => {
    const next = new URLSearchParams(params);
    next.set('page', String(value));
    setParams(next);
  };

  if (!allowed) {
    return (
      <div>
        <div className="section-head" style={{ marginTop: 8 }}>
          <h2 className="section-title">商家端 · 资金对账</h2>
        </div>
        <SellerApplyCard />
      </div>
    );
  }

  const pageCount = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">资金对账</h2>
        <span className="section-sub">{data ? `共 ${data.total} 笔托管` : ''}</span>
      </div>

      {error ? (
        <Alert kind="error">
          {error}
          <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={reload}>
            重试
          </button>
        </Alert>
      ) : null}

      {data ? (
        <div className="grid grid-3" style={{ marginBottom: 16 }}>
          <div className="stat">
            <div className="stat-label">托管中 · 你可得</div>
            <div className="stat-value">{formatPrice(data.custodySellerAmount)}</div>
            <div className="stat-hint">
              实付 {formatPrice(data.custodyAmount)} − 佣金 {formatPrice(data.custodyFee)}
            </div>
          </div>
          <div className="stat">
            <div className="stat-label">已到账 · 实收</div>
            <div className="stat-value" style={{ color: 'var(--brand)' }}>
              {formatPrice(data.releasedSellerAmount)}
            </div>
            <div className="stat-hint">
              实付 {formatPrice(data.releasedAmount)} − 佣金 {formatPrice(data.releasedFee)}
            </div>
          </div>
          <div className="stat">
            <div className="stat-label">已退回买家</div>
            <div className="stat-value">{formatPrice(data.refundedAmount)}</div>
            <div className="stat-hint">售后退款合计</div>
          </div>
        </div>
      ) : null}

      <div className="tabs" style={{ marginBottom: 16 }}>
        {TABS.map((tab) => (
          <button
            key={tab.label}
            type="button"
            className={`tab${status === tab.value ? ' active' : ''}`}
            onClick={() => setStatus(tab.value)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {loading && !data ? (
        <LoadingBlock text="正在汇总资金流水…" />
      ) : data && data.items.length ? (
        <>
          <div className="card card-pad" style={{ padding: 0, overflow: 'hidden' }}>
            <table className="table">
              <thead>
                <tr>
                  <th>订单号</th>
                  <th style={{ textAlign: 'right' }}>买家实付</th>
                  <th style={{ textAlign: 'right' }}>平台佣金</th>
                  <th style={{ textAlign: 'right' }}>你可得</th>
                  <th>状态</th>
                  <th>时间</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((row) => (
                  <tr key={`${row.orderNo}-${row.createdAt}`}>
                    <td>
                      {row.orderNo ? (
                        <Link className="text-brand" to={`/seller/orders/${row.orderNo}`}>
                          {row.orderNo}
                        </Link>
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                    <td style={{ textAlign: 'right' }}>{formatPrice(row.amount)}</td>
                    <td style={{ textAlign: 'right' }}>−{formatPrice(row.platformFee)}</td>
                    <td style={{ textAlign: 'right' }}>
                      <strong>{formatPrice(row.sellerAmount)}</strong>
                    </td>
                    <td>
                      <span className="tag">{row.statusLabel}</span>
                    </td>
                    <td className="small muted">
                      {row.releasedAt ? `放款 ${formatDate(row.releasedAt)}` : formatDate(row.createdAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <Pagination page={data.page} pageCount={pageCount} total={data.total} onChange={setPage} />
        </>
      ) : (
        <Empty
          icon="💰"
          title={status ? '该状态下没有资金流水' : '还没有资金流水'}
          desc="订单完成支付后才会生成托管记录；买家确认收货后放款。"
          action={
            <Link className="btn btn-ghost" to="/seller">
              返回概览
            </Link>
          }
        />
      )}
    </div>
  );
};

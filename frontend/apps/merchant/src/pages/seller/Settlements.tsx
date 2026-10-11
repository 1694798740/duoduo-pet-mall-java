/**
 * 商家端 · 结算与提现。
 *
 * 数据来源（**两个接口，各有分工，不要互相推理**）：
 *   - GET /api/seller/settlements/wallet —— 「我现在能取多少」（E22 起为唯一权威口径）
 *   - GET /api/seller/settlements/bills  —— 「本期钱是怎么动的」（租户 × 周期）
 *   - GET /api/seller/settlements        —— 逐笔明细台账（分页）
 *
 * 一个关键的口径：台账顶部三个汇总数字是<b>全量</b>的，不受分页与状态过滤影响。
 * 分页只是「一次看不完」，而「我一共托管多少、已到账多少」是对账问题的答案；
 * 若让汇总跟着过滤走，勾一个「已放款」就会让人以为自己只赚了这么多。
 *
 * ★ 而「能提多少」**只认钱包接口**。前端绝不从明细里自己加总 ——
 *   冻结/抵扣规则一变，自己加的总就与提现结果对不上，而对不上的那个数
 *   恰好是用户最在意的（他会认为系统少给了钱）。
 */

import React, { useCallback } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { sellerApi } from '@duoduo/api-sdk/endpoints';
import type { SellerWalletView, SettlementBill, SettlementPage } from '@duoduo/api-sdk/types';
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

  // 钱包与账单各自独立请求：它们比明细台账重要得多，
  // 明细取数失败不该把「我能取多少钱」一起拖下水（两个 useAsync 各自降级）。
  const walletFetcher = useCallback(() => sellerApi.wallet(), []);
  const wallet = useAsync<SellerWalletView>(walletFetcher, allowed, [allowed]);

  // 不传 period：由服务端按它自己的 UTC 当前月计算。
  // 客户端算月份会在月末/月初的时区边界上与服务端错开一格（本地 11 月 1 日 00:30 = UTC 10 月 31 日），
  // 而那种错位表现为"账单显示上月"，很难归因。
  const billFetcher = useCallback(() => sellerApi.bill(), []);
  const bill = useAsync<SettlementBill>(billFetcher, allowed, [allowed]);

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
          <h2 className="section-title">商家端 · 结算与提现</h2>
        </div>
        <SellerApplyCard />
      </div>
    );
  }

  const pageCount = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">结算与提现</h2>
        <span className="section-sub">{data ? `共 ${data.total} 笔托管` : ''}</span>
      </div>

      {/* ---- 钱包：能取多少（E22 · 唯一权威口径：待结算 / 冻结中 / 可提现 / 累计到账）---- */}
      {wallet.data ? (
        <>
          <div className="grid grid-4" style={{ marginBottom: 12 }}>
            <div className="stat">
              <div className="stat-label">可提现</div>
              <div className="stat-value" style={{ color: 'var(--brand)' }}>
                {formatPrice(wallet.data.netWithdrawableAmount)}
              </div>
              <div className="stat-hint">
                {wallet.data.outstandingDebt > 0
                  ? `毛额 ${formatPrice(wallet.data.withdrawableAmount)} − 欠款 ${formatPrice(wallet.data.outstandingDebt)}`
                  : '已过售后期，可直接提现'}
              </div>
            </div>
            <div className="stat">
              <div className="stat-label">待结算</div>
              <div className="stat-value">{formatPrice(wallet.data.pendingAmount)}</div>
              <div className="stat-hint">买家确认收货后转入冻结中</div>
            </div>
            <div className="stat">
              <div className="stat-label">冻结中</div>
              <div className="stat-value">{formatPrice(wallet.data.frozenAmount)}</div>
              <div className="stat-hint">
                售后期 {wallet.data.holdDays} 天内不可提取
                {wallet.data.nextReleaseAt ? `，最近解冻 ${formatDate(wallet.data.nextReleaseAt)}` : ''}
              </div>
            </div>
            <div className="stat">
              <div className="stat-label">累计到账</div>
              <div className="stat-value">{formatPrice(wallet.data.releasedAmount)}</div>
              <div className="stat-hint">已记到你名下的总额（含冻结中）</div>
            </div>
          </div>

          {/* 欠款单列一条：它解释"为什么毛额有钱却提不出来"，藏在数字里没人看得懂 */}
          {wallet.data.outstandingDebt > 0 ? (
            <Alert kind="warn">
              你有 <strong>{formatPrice(wallet.data.outstandingDebt)}</strong>{' '}
              未结欠款（平台已先行垫付退款给买家）。提现时会自动抵扣，因此可提现金额已扣除这部分。
            </Alert>
          ) : null}
        </>
      ) : wallet.error ? (
        <Alert kind="error">
          钱包数据加载失败：{wallet.error}
          <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={wallet.reload}>
            重试
          </button>
        </Alert>
      ) : (
        <LoadingBlock text="正在计算可提现余额…" />
      )}

      {/* ---- 本期账单（租户 × 周期）：这个周期里钱是怎么动的 ---- */}
      {bill.data ? (
        <div className="card card-pad" style={{ marginBottom: 16 }}>
          <div className="section-head" style={{ marginTop: 0 }}>
            <h3 className="section-title" style={{ fontSize: 15 }}>
              {bill.data.period} 账单
            </h3>
            <span className="section-sub">
              入账 {bill.data.orderCount} 笔 ｜ 放款 {bill.data.releasedCount} 笔 ｜ 退回{' '}
              {bill.data.refundedCount} 笔
            </span>
          </div>
          <div className="small muted" style={{ lineHeight: 1.9 }}>
            本期入账 <strong>{formatPrice(bill.data.orderAmount)}</strong>（其中平台佣金{' '}
            {formatPrice(bill.data.platformFeeAmount)}，你可得{' '}
            {formatPrice(bill.data.sellerEarningAmount)}）
            <br />
            本期放款 {formatPrice(bill.data.releasedSellerAmount)} ｜ 本期解冻{' '}
            {formatPrice(bill.data.unfrozenAmount)} ｜ 本期退回买家{' '}
            {formatPrice(bill.data.refundedAmount)}
            {bill.data.advancedAmount > 0 ? (
              <>
                <br />
                本期平台垫付 {formatPrice(bill.data.advancedAmount)}（已形成欠款，将在提现时抵扣）
              </>
            ) : null}
          </div>
        </div>
      ) : null}

      <div className="section-head" style={{ marginTop: 0 }}>
        <h3 className="section-title" style={{ fontSize: 15 }}>
          托管台账
        </h3>
        <span className="section-sub">逐笔明细；下方汇总为全量口径，不受分页与筛选影响</span>
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

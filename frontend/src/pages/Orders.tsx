/**
 * 订单列表页。
 *
 * 数据来源：GET /api/orders?status=&page=&pageSize=
 *  · status 为空即「全部」；取值与后端 OrderStatus 枚举一致
 *  · 状态中文文案直接用服务端返回的 statusLabel，前端不维护映射表
 *  · 分页结构在 data 内部（items/total/page/pageSize/pageCount），不是顶层 meta
 */

import React, { useCallback, useMemo } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { orderApi, reviewApi } from '../api/endpoints';
import type { OrderView, PageResult, ReviewedOrders } from '../api/types';
import { useAsync } from '../components/hooks';
import { Alert, Empty, LoadingBlock, Pagination, formatDate, formatPrice } from '../components/ui';

const TABS: { value: string; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'PENDING_PAYMENT', label: '待支付' },
  { value: 'PAID', label: '待发货' },
  { value: 'SHIPPED', label: '待收货' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'CLOSED', label: '已关闭' },
];

function num(value: string | null): number | undefined {
  if (value === null || value === '') return undefined;
  const n = Number(value);
  return Number.isFinite(n) ? n : undefined;
}

/**
 * 每条订单卡片的动作区：按状态给出可执行操作。
 *
 * 「已完成」的订单要显示去评价 / 已评价 —— 评价入口只在订单域出现一次，
 * 比在导航里加一个「评价中心」更符合用户心智（评价总是从「我买过的那单」出发）。
 * 是否已评价由父组件批量查询后传入，避免每条卡片各发一次请求。
 */
const OrderActions: React.FC<{ order: OrderView; reviewed?: boolean }> = ({ order, reviewed }) => (
  <div className="row row-wrap">
    <Link className="btn btn-ghost btn-sm" to={`/orders/${order.orderNo}`}>
      {order.status === 'PENDING_PAYMENT' ? '去支付' : '查看详情'}
    </Link>
    {order.status === 'COMPLETED' ? (
      reviewed ? (
        <Link className="btn btn-ghost btn-sm" to="/reviews/mine">
          已评价
        </Link>
      ) : (
        <Link className="btn btn-primary btn-sm" to={`/reviews/new?orderNo=${order.orderNo}`}>
          去评价
        </Link>
      )
    ) : null}
    {order.status === 'PENDING_PAYMENT' ? (
      <span className="small muted" style={{ alignSelf: 'center' }}>
        剩余 {order.expireAt ? formatDate(order.expireAt) : '—'} 前支付
      </span>
    ) : null}
    {order.status === 'SHIPPED' ? (
      <span className="small muted" style={{ alignSelf: 'center' }}>
        确认收货后货款才会结算给卖家
      </span>
    ) : null}
    {order.settlement?.status === 'CUSTODY' ? (
      <span className="tag" style={{ alignSelf: 'center' }}>
        资金托管中
      </span>
    ) : null}
  </div>
);

export const OrdersPage: React.FC = () => {
  const [params, setParams] = useSearchParams();

  const status = params.get('status') ?? '';
  const page = num(params.get('page')) ?? 1;

  const fetcher = useCallback(
    () =>
      orderApi.list({
        status: status || undefined,
        page,
        pageSize: 10,
      }),
    [status, page],
  );
  const { data, loading, error, reload } = useAsync<PageResult<OrderView>>(fetcher, true, [
    status,
    page,
  ]);

  /**
   * 「已完成」订单里我评过哪些 —— 一次批量查，避免每张卡片各发一次请求。
   * 依赖用 orderNos 拼出的字符串而不是数组：数组每次渲染都是新引用，
   * 会让下面的 useAsync 每轮都重发请求（数组当依赖的经典坑）。
   */
  const completedNos = useMemo(
    () => (data?.items ?? []).filter((o) => o.status === 'COMPLETED').map((o) => o.orderNo),
    [data],
  );
  const completedKey = completedNos.join(',');
  const reviewed = useAsync<ReviewedOrders>(
    useCallback(
      () => (completedNos.length ? reviewApi.reviewed(completedNos) : Promise.resolve({ orderNos: [] })),
      // eslint-disable-next-line react-hooks/exhaustive-deps
      [completedKey],
    ),
    completedNos.length > 0,
    [completedKey],
  );
  const reviewedSet = useMemo(() => new Set(reviewed.data?.orderNos ?? []), [reviewed.data]);

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

  const emptyHint = useMemo(() => {
    if (!status) return '还没有订单，去挑一件试试';
    const label = TABS.find((t) => t.value === status)?.label ?? status;
    return `没有「${label}」的订单`;
  }, [status]);

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">我的订单</h2>
        <span className="section-sub">{data ? `共 ${data.total} 笔` : ''}</span>
      </div>

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

      {error ? (
        <Alert kind="error">
          {error}
          <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={reload}>
            重试
          </button>
        </Alert>
      ) : loading && !data ? (
        <LoadingBlock text="正在加载订单…" />
      ) : data && data.items.length ? (
        <>
          <div className="stack">
            {data.items.map((order) => (
              <div className="card card-pad" key={order.orderNo}>
                <div className="row-between row-wrap">
                  <div className="row row-wrap">
                    <strong className="small">订单号 {order.orderNo}</strong>
                    <span className="tag">{order.statusLabel}</span>
                    {order.orderType ? <span className="tag">{order.orderType}</span> : null}
                  </div>
                  <span className="small muted">{formatDate(order.createdAt)}</span>
                </div>

                <div className="stack" style={{ margin: '12px 0' }}>
                  {order.items.slice(0, 3).map((item) => (
                    <div className="row" style={{ gap: 10 }} key={`${order.orderNo}-${item.skuId}`}>
                      {item.mainImage ? (
                        <img
                          src={item.mainImage}
                          alt={item.title}
                          style={{
                            width: 48,
                            height: 48,
                            objectFit: 'cover',
                            borderRadius: 8,
                            flex: '0 0 auto',
                          }}
                        />
                      ) : null}
                      <div style={{ minWidth: 0, flex: 1 }}>
                        <div className="goods-title">{item.title}</div>
                        <div className="small muted">
                          {item.specText ?? '默认规格'} · × {item.quantity}
                        </div>
                      </div>
                      <span className="small">{formatPrice(item.amount)}</span>
                    </div>
                  ))}
                  {order.items.length > 3 ? (
                    <div className="small muted">等共 {order.items.length} 件商品</div>
                  ) : null}
                </div>

                <div className="row-between row-wrap">
                  <span className="small muted">
                    收货人 {order.receiverName} · {order.receiverPhone}
                  </span>
                  <span>
                    应付{' '}
                    <strong style={{ color: 'var(--danger)', fontSize: 18 }}>
                      {formatPrice(order.payAmount)}
                    </strong>
                  </span>
                </div>

                <div className="divider" />
                <OrderActions order={order} reviewed={reviewedSet.has(order.orderNo)} />
              </div>
            ))}
          </div>

          <Pagination
            page={data.page}
            pageCount={data.pageCount}
            total={data.total}
            onChange={setPage}
          />
        </>
      ) : (
        <Empty
          icon="📦"
          title={emptyHint}
          action={
            <Link className="btn btn-primary" to="/search">
              去逛商品
            </Link>
          }
        />
      )}
    </div>
  );
};

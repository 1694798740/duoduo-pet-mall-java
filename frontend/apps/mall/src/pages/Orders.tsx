/**
 * 订单列表页。
 *
 * 数据来源：GET /api/orders?status=&type=&page=&pageSize=
 *  · status 为空即「全部」；取值与后端 OrderStatus 枚举一致
 *  · type=SECKILL 即「只看秒杀抢购」（D4）：秒杀页的「我的抢购记录」直接链到这里
 *  · 状态中文文案直接用服务端返回的 statusLabel，前端不维护映射表
 *  · 分页结构在 data 内部（items/total/page/pageSize/pageCount），不是顶层 meta
 */

import React, { useCallback, useMemo, useState } from 'react';
import { productImage } from '@duoduo/api-sdk/images';
import { Link, useSearchParams } from 'react-router-dom';
import { orderApi, reviewApi } from '@duoduo/api-sdk/endpoints';
import type { OrderView, PageResult, ReviewedOrders } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import { Alert, Empty, LoadingBlock, Pagination, formatDate, formatPrice, useToast } from '@duoduo/shared/components/ui';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { useConfirm } from '@duoduo/shared/components/confirm';
import { notifyDataChanged } from '@duoduo/shared/lib/dataBus';
import { SupportEntry } from '../components/SupportEntry';

const TABS: { value: string; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'PENDING_PAYMENT', label: '待支付' },
  { value: 'PAID', label: '待发货' },
  { value: 'SHIPPED', label: '待收货' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'CLOSED', label: '已关闭' },
];

/** 订单类型中文名。服务端只给枚举值，展示文案由前端决定（与状态文案的口径不同）。 */
const TYPE_LABEL: Record<string, string> = {
  NORMAL: '普通单',
  SECKILL: '秒杀单',
  C2C: '闲置单',
};

function num(value: string | null): number | undefined {
  if (value === null || value === '') return undefined;
  const n = Number(value);
  return Number.isFinite(n) ? n : undefined;
}

/**
 * 每条订单卡片的动作区：按状态给出可执行操作。
 *
 * 状态 → 按钮口径（2026-10-18 明确化，配色见下）：
 *   · 待付款：**绿色渐变**「去支付」+ **红实心**「取消订单」+ 问客服 + 剩余支付时间
 *   · 待发货：**青实心**「查看详情」+ 问客服
 *   · 待收货：青实心「查看详情」+ 描边「申请售后」+ 问客服 + 资金托管提示
 *   · 已完成：青实心「查看详情」+ 描边「申请售后」+ **浅橙「去评价」**（已评价为描边）+ 问客服
 *   · 已关闭 / 已退款：青实心「查看详情」+ 问客服（终态，无写操作）
 *
 * 六种视觉层级，任意两个动作都不会"长得一样"：
 *   绿渐变 = 付钱；红实心 = 破坏性；青实心 = 查看/导航；
 *   浅橙 = 建设性次操作（评价）；青描边 = 售后；灰描边 = 客服 / 已评价等中性信息。
 *
 * 取消为什么放卡片上：这是「待付款」最高频的后悔路径，原先必须先点「去支付」
 * 进收银台再找取消，等于把用户往付款方向推了一步。
 */
const OrderActions: React.FC<{
  order: OrderView;
  reviewed?: boolean;
  onCancel: (orderNo: string) => void;
  cancelling: boolean;
}> = ({ order, reviewed, onCancel, cancelling }) => (
  <div className="row row-wrap">
    <Link
      className={`btn btn-sm ${order.status === 'PENDING_PAYMENT' ? 'btn-pay' : 'btn-accent'}`}
      to={`/orders/${order.orderNo}`}
    >
      {order.status === 'PENDING_PAYMENT' ? '去支付' : '查看详情'}
    </Link>
    {order.status === 'PENDING_PAYMENT' ? (
      <button
        type="button"
        className="btn btn-danger-solid btn-sm"
        disabled={cancelling}
        onClick={() => onCancel(order.orderNo)}
      >
        {cancelling ? '取消中…' : '取消订单'}
      </button>
    ) : null}
    {/* 客服入口放在「取消订单」之后、「售后/评价」之前：同一条问题处理动线 */}
    <SupportEntry small />
    {order.status === 'SHIPPED' || order.status === 'COMPLETED' ? (
      <Link className="btn btn-accent-outline btn-sm" to={`/after-sales/new?orderNo=${order.orderNo}`}>
        申请售后
      </Link>
    ) : null}
    {order.status === 'COMPLETED' ? (
      reviewed ? (
        <Link className="btn btn-ghost btn-sm" to="/reviews/mine">
          已评价
        </Link>
      ) : (
        <Link className="btn btn-brand-soft btn-sm" to={`/reviews/new?orderNo=${order.orderNo}`}>
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
  const { push } = useToast();
  const confirm = useConfirm();
  const [cancelling, setCancelling] = useState<string | null>(null);

  const status = params.get('status') ?? '';
  const orderType = params.get('type') ?? '';
  const page = num(params.get('page')) ?? 1;

  const fetcher = useCallback(
    () =>
      orderApi.list({
        status: status || undefined,
        orderType: orderType || undefined,
        page,
        pageSize: 10,
      }),
    [status, orderType, page],
  );
  const { data, loading, error, reload } = useAsync<PageResult<OrderView>>(fetcher, true, [
    status,
    orderType,
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

  /**
   * 卡片上直接取消（仅待付款）。取消后库存冻结与优惠券由服务端立即释放，
   * 所以这里必须 reload —— 不刷新会看到一条已关单却仍显示「去支付」的卡片。
   */
  const cancelOrder = useCallback(
    async (orderNo: string) => {
      const ok = await confirm({
        title: '取消订单',
        message: '确定取消这笔订单吗？取消后库存与优惠券会立即释放。',
        confirmText: '确定取消',
        cancelText: '再想想',
        danger: true,
      });
      if (!ok) return;
      setCancelling(orderNo);
      try {
        await orderApi.cancel(orderNo, '买家在订单列表取消');
        push('订单已取消', 'success');
        reload();
        // 广播：取消释放库存与秒杀配额，首页/详情/秒杀页立即重拉
        notifyDataChanged();
      } catch (err) {
        push(toFriendlyMessage(err), 'error');
      } finally {
        setCancelling(null);
      }
    },
    [push, reload],
  );

  const setStatus = (value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set('status', value);
    else next.delete('status');
    next.delete('page');
    setParams(next);
  };

  /** 从「只看秒杀」退回全部订单 —— 页码必须一起清掉，否则会停在原页看到空列表 */
  const clearType = () => {
    const next = new URLSearchParams(params);
    next.delete('type');
    next.delete('page');
    setParams(next);
  };

  const setPage = (value: number) => {
    const next = new URLSearchParams(params);
    next.set('page', String(value));
    setParams(next);
  };

  const emptyHint = useMemo(() => {
    if (orderType === 'SECKILL' && !status) return '还没有抢到过秒杀商品，去秒杀会场看看';
    if (!status) return '还没有订单，去挑一件试试';
    const label = TABS.find((t) => t.value === status)?.label ?? status;
    return `没有「${label}」的订单`;
  }, [status, orderType]);

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">我的订单</h2>
        <span className="section-sub">{data ? `共 ${data.total} 笔` : ''}</span>
      </div>

      {orderType === 'SECKILL' ? (
        <div
          className="card card-pad"
          style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}
        >
          <span className="small">
            当前只显示 <span className="tag tag-warn">秒杀单</span>，其他订单不会出现在这里
          </span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={clearType}>
            查看全部订单
          </button>
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
                    {order.orderType ? (
                      <span className={order.orderType === 'SECKILL' ? 'tag tag-warn' : 'tag'}>
                        {TYPE_LABEL[order.orderType] ?? order.orderType}
                      </span>
                    ) : null}
                  </div>
                  <span className="small muted">{formatDate(order.createdAt)}</span>
                </div>

                <div className="stack" style={{ margin: '12px 0' }}>
                  {order.items.slice(0, 3).map((item) => (
                    <div className="row" style={{ gap: 10 }} key={`${order.orderNo}-${item.skuId}`}>
                      {item.mainImage ? (
                        <img
                          src={productImage(item.spuId, item.mainImage)}
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
                <OrderActions
                  order={order}
                  reviewed={reviewedSet.has(order.orderNo)}
                  onCancel={cancelOrder}
                  cancelling={cancelling === order.orderNo}
                />
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
            <Link className="btn btn-primary" to={orderType === 'SECKILL' ? '/seckill' : '/search'}>
              {orderType === 'SECKILL' ? '去秒杀会场' : '去逛商品'}
            </Link>
          }
        />
      )}
    </div>
  );
};

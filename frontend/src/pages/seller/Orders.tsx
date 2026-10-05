/**
 * 商家端 · 卖出订单。
 *
 * 数据来源：GET /api/seller/orders、POST /api/seller/orders/{no}/ship、/refund
 *
 * 与买家端「我的订单」的关键差别：
 *  · 这里展示的是<b>买家收货信息</b>（卖家要按它发货），而不是「去支付」之类的买家动作
 *  · 列表不含任何 sellerId 参数 —— 归属由服务端按令牌判定，
 *    不属于你的订单根本不会出现在结果里，越权访问返回 403
 *
 * 为什么要「一键发货」而不是只放详情页里：待发货是卖家最高频的动作，
 * 每单都进详情再点一次，等于把 80% 的操作成本翻了一倍。
 */

import React, { useCallback, useState } from 'react';
import { productImage } from '../../api/images';
import { Link, useSearchParams } from 'react-router-dom';
import { toFriendlyMessage } from '../../api/client';
import { sellerApi } from '../../api/endpoints';
import type { OrderView, PageResult } from '../../api/types';
import { useAsync } from '../../components/hooks';
import {
  Alert,
  Empty,
  Field,
  LoadingBlock,
  Modal,
  Pagination,
  formatDate,
  formatPrice,
  useToast,
} from '../../components/ui';
import { SellerApplyCard, canAccessSellerConsole } from './shared';
import { useAuth } from '../../store/auth';

const TABS: { value: string; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'PENDING_PAYMENT', label: '待付款' },
  { value: 'PAID', label: '待发货' },
  { value: 'SHIPPED', label: '已发货' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'CLOSED', label: '已关闭' },
];

function num(value: string | null): number | undefined {
  if (value === null || value === '') return undefined;
  const n = Number(value);
  return Number.isFinite(n) ? n : undefined;
}

/** 卖家能对这一单做什么：由状态决定，与后端状态机保持同一口径 */
function canShip(order: OrderView): boolean {
  return order.status === 'PAID';
}

function canRefund(order: OrderView): boolean {
  return order.status === 'PAID' || order.status === 'SHIPPED';
}

export const SellerOrdersPage: React.FC = () => {
  const { user } = useAuth();
  const { push } = useToast();
  const [params, setParams] = useSearchParams();

  const status = params.get('status') ?? '';
  const page = num(params.get('page')) ?? 1;
  const allowed = canAccessSellerConsole(user?.role);

  const fetcher = useCallback(
    () => sellerApi.orders({ status: status || undefined, page, pageSize: 10 }),
    [status, page],
  );
  const { data, loading, error, reload } = useAsync<PageResult<OrderView>>(
    fetcher,
    allowed,
    [allowed, status, page],
  );

  const [shipTarget, setShipTarget] = useState<OrderView | null>(null);
  const [refundTarget, setRefundTarget] = useState<OrderView | null>(null);
  const [company, setCompany] = useState('');
  const [logisticsNo, setLogisticsNo] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

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

  const openShip = (order: OrderView) => {
    setActionError(null);
    setCompany('');
    setLogisticsNo('');
    setShipTarget(order);
  };

  const openRefund = (order: OrderView) => {
    setActionError(null);
    setReason('');
    setRefundTarget(order);
  };

  const submitShip = async () => {
    if (!shipTarget) return;
    setBusy(true);
    setActionError(null);
    try {
      await sellerApi.ship(shipTarget.orderNo, {
        logisticsCompany: company.trim() || undefined,
        logisticsNo: logisticsNo.trim() || undefined,
      });
      push('已发货；资金仍在托管中，买家确认收货后放款', 'success');
      setShipTarget(null);
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const submitRefund = async () => {
    if (!refundTarget) return;
    setBusy(true);
    setActionError(null);
    try {
      await sellerApi.refund(refundTarget.orderNo, reason.trim() || undefined);
      push('退款已完成，款项退回买家', 'success');
      setRefundTarget(null);
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  if (!allowed) {
    return (
      <div>
        <div className="section-head" style={{ marginTop: 8 }}>
          <h2 className="section-title">商家端 · 卖出订单</h2>
        </div>
        <SellerApplyCard />
      </div>
    );
  }

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">卖出订单</h2>
        <div className="row row-wrap">
          <span className="section-sub">{data ? `共 ${data.total} 笔` : ''}</span>
          {/* 售后入口挂在订单页顶部而非订单卡片里：一个订单只有一笔售后，
              卡片内放按钮会让「已在处理中」的订单看起来还能再点一次 */}
          <Link className="btn btn-ghost btn-sm" to="/seller/after-sales">
            售后处理
          </Link>
        </div>
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
        <LoadingBlock text="正在加载卖出订单…" />
      ) : data && data.items.length ? (
        <>
          <div className="stack">
            {data.items.map((order) => (
              <div className="card card-pad" key={order.orderNo}>
                <div className="row-between row-wrap">
                  <div className="row row-wrap">
                    <Link className="small" to={`/seller/orders/${order.orderNo}`}>
                      <strong>订单号 {order.orderNo}</strong>
                    </Link>
                    <span className="tag">{order.statusLabel}</span>
                    {order.settlement ? (
                      <span className="tag">{order.settlement.statusLabel}</span>
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

                <div className="small muted">
                  收货：{order.receiverName} · {order.receiverPhone} · {order.receiverAddress}
                </div>

                <div className="row-between row-wrap" style={{ marginTop: 8 }}>
                  <span className="small muted">买家实付</span>
                  <strong style={{ color: 'var(--danger)', fontSize: 18 }}>
                    {formatPrice(order.payAmount)}
                  </strong>
                </div>

                <div className="divider" />
                <div className="row row-wrap">
                  <Link className="btn btn-ghost btn-sm" to={`/seller/orders/${order.orderNo}`}>
                    查看详情
                  </Link>
                  {canShip(order) ? (
                    <button className="btn btn-primary btn-sm" onClick={() => openShip(order)}>
                      立即发货
                    </button>
                  ) : null}
                  {canRefund(order) ? (
                    <button className="btn btn-ghost btn-sm" onClick={() => openRefund(order)}>
                      售后退款
                    </button>
                  ) : null}
                  {order.status === 'SHIPPED' ? (
                    <span className="small muted" style={{ alignSelf: 'center' }}>
                      买家确认收货后放款
                    </span>
                  ) : null}
                </div>
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
          icon="🧾"
          title={status ? '该状态下没有订单' : '还没有卖出订单'}
          desc={
            status
              ? undefined
              : '当前商品域 SPU 未接入商家字段，平台自营销量挂在 seller_id = 0 下，仅运营 / 管理员可见。第三方商家商品接入后，这里会展示属于你的订单。'
          }
          action={
            <Link className="btn btn-ghost" to="/seller">
              返回概览
            </Link>
          }
        />
      )}

      <Modal
        open={shipTarget !== null}
        title="发货"
        onClose={() => setShipTarget(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setShipTarget(null)} disabled={busy}>
              取消
            </button>
            <button className="btn btn-primary" onClick={submitShip} disabled={busy}>
              {busy ? '提交中…' : '确认发货'}
            </button>
          </>
        }
      >
        {actionError ? (
          <div style={{ marginBottom: 12 }}>
            <Alert kind="error">{actionError}</Alert>
          </div>
        ) : null}
        <Alert kind="info">
          发货后资金<b>仍在平台托管</b>，放款要等买家确认收货（或超时自动确认）。
        </Alert>
        <div style={{ marginTop: 12 }}>
          <Field label="物流公司" hint="可留空；填写后随订单状态日志留痕">
            <input
              className="input"
              value={company}
              onChange={(e) => setCompany(e.target.value)}
              placeholder="如 顺丰速运"
              maxLength={50}
            />
          </Field>
          <Field label="物流单号">
            <input
              className="input"
              value={logisticsNo}
              onChange={(e) => setLogisticsNo(e.target.value)}
              placeholder="如 SF1234567890"
              maxLength={64}
            />
          </Field>
        </div>
      </Modal>

      <Modal
        open={refundTarget !== null}
        title="售后退款"
        onClose={() => setRefundTarget(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setRefundTarget(null)} disabled={busy}>
              取消
            </button>
            <button className="btn btn-primary" onClick={submitRefund} disabled={busy}>
              {busy ? '处理中…' : '确认退款'}
            </button>
          </>
        }
      >
        {actionError ? (
          <div style={{ marginBottom: 12 }}>
            <Alert kind="error">{actionError}</Alert>
          </div>
        ) : null}
        <Alert kind="warn">
          退款会从托管资金直接退回买家，<b>不可撤销</b>。仅在已确认收到退货后执行。
          已完成的订单无法在此退款 —— 钱已放款给卖家，需走售后追偿（尚未实现）。
        </Alert>
        <div style={{ marginTop: 12 }}>
          <Field label="退款原因">
            <input
              className="input"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="如 买家退回，商品完好"
              maxLength={100}
            />
          </Field>
        </div>
      </Modal>
    </div>
  );
};

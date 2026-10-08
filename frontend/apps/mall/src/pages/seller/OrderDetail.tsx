/**
 * 商家端 · 订单详情（履约工作台）。
 *
 * 数据来源：GET /api/seller/orders/{orderNo} + 发货 / 售后退款
 *           GET /api/logistics/orders/{orderNo}/tracks（物流轨迹 A2，与买家端共用组件）
 *
 * 与买家端订单详情页的差别：
 *  · 收货信息是<b>履约依据</b>而不是「我的收货地址」，因此放在最显眼的位置
 *  · 资金区回答的是「这笔钱什么时候到我账上」，而不是「我的钱现在在哪」
 *  · 动作按钮由服务端状态机决定，前端只做显示/隐藏 —— 真正的约束在后端，
 *    因为隐藏按钮挡不住直接调接口
 */

import React, { useCallback, useState } from 'react';
import { productImage } from '@duoduo/api-sdk/images';
import { Link, useParams } from 'react-router-dom';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { sellerApi } from '@duoduo/api-sdk/endpoints';
import type { OrderView } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import {
  Alert,
  Empty,
  Field,
  LoadingBlock,
  StatusSteps,
  formatDate,
  formatPrice,
  useToast,
} from '@duoduo/shared/components/ui';
import { SellerApplyCard, canAccessSellerConsole } from './shared';
import { useAuth } from '@duoduo/shared/store/auth';
import { LogisticsTimeline } from '@duoduo/shared/components/LogisticsTimeline';

export const SellerOrderDetailPage: React.FC = () => {
  const { orderNo = '' } = useParams();
  const { user } = useAuth();
  const { push } = useToast();
  const allowed = canAccessSellerConsole(user?.role);

  const fetcher = useCallback(() => sellerApi.orderDetail(orderNo), [orderNo]);
  const { data: order, loading, error, reload } = useAsync<OrderView>(
    fetcher,
    allowed && Boolean(orderNo),
    [allowed, orderNo],
  );

  const [company, setCompany] = useState('');
  const [logisticsNo, setLogisticsNo] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState<'ship' | 'refund' | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const ship = async () => {
    setBusy('ship');
    setActionError(null);
    try {
      await sellerApi.ship(orderNo, {
        logisticsCompany: company.trim() || undefined,
        logisticsNo: logisticsNo.trim() || undefined,
      });
      push('已发货；资金仍在托管中', 'success');
      setCompany('');
      setLogisticsNo('');
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(null);
    }
  };

  const refund = async () => {
    setBusy('refund');
    setActionError(null);
    try {
      await sellerApi.refund(orderNo, reason.trim() || undefined);
      push('退款已完成，款项退回买家', 'success');
      setReason('');
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(null);
    }
  };

  if (!allowed) {
    return (
      <div>
        <div className="section-head" style={{ marginTop: 8 }}>
          <h2 className="section-title">商家端 · 订单详情</h2>
        </div>
        <SellerApplyCard />
      </div>
    );
  }

  if (loading && !order) return <LoadingBlock text="正在加载订单…" />;

  if (error) {
    return (
      <div>
        <div className="section-head" style={{ marginTop: 8 }}>
          <h2 className="section-title">订单详情</h2>
        </div>
        <Alert kind="error">
          {error}
          <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={reload}>
            重试
          </button>
        </Alert>
        <div style={{ marginTop: 16 }}>
          <Link className="btn btn-ghost" to="/seller/orders">
            返回卖出订单
          </Link>
        </div>
      </div>
    );
  }

  if (!order) {
    return (
      <Empty
        icon="🔍"
        title="订单不存在或无权查看"
        desc="不属于你的订单会返回 403；不存在的订单号返回 404。"
        action={
          <Link className="btn btn-ghost" to="/seller/orders">
            返回卖出订单
          </Link>
        }
      />
    );
  }

  const canShip = order.status === 'PAID';
  const canRefund = order.status === 'PAID' || order.status === 'SHIPPED';
  // 与买家端同一口径（时间线是同一个组件）：同时看状态与 shippedAt ——
  // 退款后的订单状态会变，但包裹可能还在路上，只认状态会把真实存在的轨迹藏起来。
  const shipped =
    Boolean(order.shippedAt) || order.status === 'SHIPPED' || order.status === 'COMPLETED';

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">订单 {order.orderNo}</h2>
        <span className="section-sub">{order.statusLabel}</span>
      </div>

      <div className="card card-pad" style={{ marginBottom: 16 }}>
        <StatusSteps status={order.status} />
        <div className="divider" />
        <dl className="spec-list">
          <dt>下单时间</dt>
          <dd>{formatDate(order.createdAt)}</dd>
          <dt>付款时间</dt>
          <dd>{order.paidAt ? formatDate(order.paidAt) : '—'}</dd>
          <dt>发货时间</dt>
          <dd>{order.shippedAt ? formatDate(order.shippedAt) : '—'}</dd>
          <dt>完成时间</dt>
          <dd>{order.finishedAt ? formatDate(order.finishedAt) : '—'}</dd>
          <dt>买家备注</dt>
          <dd>{order.remark ?? '—'}</dd>
        </dl>
      </div>

      {/* 物流轨迹：卖家被买家问「货到哪了」时，答案就在这里 —— 不用再去快递官网挨个查单号。
          与买家端共用同一个组件，避免两边对同一包裹给出不一样的说法（A2）。 */}
      <LogisticsTimeline orderNo={orderNo} shipped={shipped} />

      <div className="grid grid-2" style={{ alignItems: 'start', marginTop: 16 }}>
        <div className="card card-pad">
          <h3 style={{ marginBottom: 12 }}>履约信息</h3>
          <dl className="spec-list">
            <dt>收货人</dt>
            <dd>{order.receiverName}</dd>
            <dt>联系电话</dt>
            <dd>{order.receiverPhone}</dd>
            <dt>收货地址</dt>
            <dd>{order.receiverAddress}</dd>
            {/* 物流信息只在已发货后有值。
                为什么必须显示在这里：卖家填完单号后要能回头核对，
                买家来问「我的货到哪了」时，这一屏就是唯一的事实来源。 */}
            <dt>物流公司</dt>
            <dd>{order.logisticsCompany ?? '—'}</dd>
            <dt>物流单号</dt>
            <dd>{order.logisticsNo ?? '—'}</dd>
          </dl>

          <div className="divider" />
          <h3 style={{ marginBottom: 12 }}>商品明细</h3>
          <div className="stack">
            {order.items.map((item) => (
              <div className="row" style={{ gap: 10 }} key={item.skuId}>
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
                    {item.specText ?? '默认规格'} · {formatPrice(item.price)} × {item.quantity}
                  </div>
                </div>
                <span className="small">{formatPrice(item.amount)}</span>
              </div>
            ))}
          </div>

          <div className="divider" />
          <div className="row-between">
            <span className="small muted">买家实付</span>
            <strong style={{ color: 'var(--danger)', fontSize: 20 }}>
              {formatPrice(order.payAmount)}
            </strong>
          </div>
        </div>

        <div>
          <div className="card card-pad">
            <h3 style={{ marginBottom: 12 }}>资金</h3>
            {order.settlement ? (
              <dl className="spec-list">
                <dt>托管状态</dt>
                <dd>
                  <span className="tag">{order.settlement.statusLabel}</span>
                </dd>
                <dt>买家实付</dt>
                <dd>{formatPrice(order.settlement.amount)}</dd>
                <dt>平台佣金</dt>
                <dd>−{formatPrice(order.settlement.platformFee)}</dd>
                <dt>你可得</dt>
                <dd>
                  <strong>{formatPrice(order.settlement.sellerAmount)}</strong>
                </dd>
                <dt>放款时间</dt>
                <dd>{order.settlement.releasedAt ? formatDate(order.settlement.releasedAt) : '—'}</dd>
              </dl>
            ) : (
              <div className="small muted">订单尚未付款，暂无资金托管记录。</div>
            )}
          </div>

          {actionError ? (
            <div style={{ marginTop: 16 }}>
              <Alert kind="error">{actionError}</Alert>
            </div>
          ) : null}

          {canShip ? (
            <div className="card card-pad" style={{ marginTop: 16 }}>
              <h3 style={{ marginBottom: 12 }}>发货</h3>
              <Alert kind="info">
                发货后资金仍在托管中，买家确认收货（或超时自动确认）后放款到你账上。
              </Alert>
              <div style={{ marginTop: 12 }}>
                <Field label="物流公司" hint="可留空；随状态日志留痕">
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
                <button
                  className="btn btn-primary"
                  onClick={ship}
                  disabled={busy !== null}
                >
                  {busy === 'ship' ? '提交中…' : '确认发货'}
                </button>
              </div>
            </div>
          ) : null}

          {canRefund ? (
            <div className="card card-pad" style={{ marginTop: 16 }}>
              <h3 style={{ marginBottom: 12 }}>售后退款</h3>
              <Alert kind="warn">
                仅在已确认收到退货后执行，退款不可撤销。
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
                <button className="btn btn-ghost" onClick={refund} disabled={busy !== null}>
                  {busy === 'refund' ? '处理中…' : '确认退款'}
                </button>
              </div>
            </div>
          ) : null}

          <div style={{ marginTop: 16 }}>
            <Link className="btn btn-ghost" to="/seller/orders">
              返回卖出订单
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
};

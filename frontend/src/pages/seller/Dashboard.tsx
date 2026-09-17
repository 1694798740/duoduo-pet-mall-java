/**
 * 商家端 · 概览。
 *
 * 数据来源：GET /api/seller/summary
 *
 * 两个刻意的设计：
 *  · 概览同时充当「权限探测」：非卖家会被后端 403，页面据此渲染入驻引导，
 *    而不是进一个到处都是红色报错的空壳页面
 *  · 所有金额都是后端算好的（托管中 / 已到账 / 已退回），前端不做减法 ——
 *    佣金口径只应有一处实现，前端做减法等于把它复制了 N 份
 */

import React, { useCallback } from 'react';
import { Link } from 'react-router-dom';
import { sellerApi } from '../../api/endpoints';
import type { SellerSummary } from '../../api/types';
import { useAsync } from '../../components/hooks';
import {
  Alert,
  LoadingBlock,
  MiniBars,
  formatPrice,
} from '../../components/ui';
import { SellerApplyCard, canAccessSellerConsole } from './shared';
import { useAuth } from '../../store/auth';

const StatCard: React.FC<{ label: string; value: React.ReactNode; hint?: string; to?: string }> = ({
  label,
  value,
  hint,
  to,
}) => {
  const body = (
    <>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {hint ? <div className="stat-hint">{hint}</div> : null}
    </>
  );
  return to ? (
    <Link className="stat" to={to} style={{ display: 'block' }}>
      {body}
    </Link>
  ) : (
    <div className="stat">{body}</div>
  );
};

export const SellerDashboardPage: React.FC = () => {
  const { user } = useAuth();
  const allowed = canAccessSellerConsole(user?.role);

  const fetcher = useCallback(() => sellerApi.summary(), []);
  const { data, loading, error, reload } = useAsync<SellerSummary>(fetcher, allowed, [allowed]);

  if (!allowed) {
    return (
      <div>
        <div className="section-head" style={{ marginTop: 8 }}>
          <h2 className="section-title">商家端</h2>
        </div>
        <SellerApplyCard />
      </div>
    );
  }

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">商家端 · 概览</h2>
        <span className="section-sub">
          {data ? `卖家 ID ${data.sellerId} · 共 ${data.totalOrderCount} 笔订单` : ''}
        </span>
      </div>

      {error ? (
        <Alert kind="error">
          {error}
          <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={reload}>
            重试
          </button>
        </Alert>
      ) : null}

      {loading && !data ? <LoadingBlock text="正在汇总经营数据…" /> : null}

      {data ? (
        <>
          {data.platformScope ? (
            <div style={{ marginBottom: 14 }}>
              <Alert kind="info">
                当前账号为运营 / 管理员，可见范围额外包含<b>平台自营</b>（seller_id = 0）。
                原因：商品域 SPU 目前没有商家字段，自营销量全部挂在平台账号下，
                不开放就没有任何人能履约。第三方商家接入后会收窄为纯代运营用途。
              </Alert>
            </div>
          ) : null}

          <div className="grid grid-4">
            <StatCard
              label="待发货"
              value={data.pendingShipCount}
              hint="已付款，等待你发货"
              to="/seller/orders?status=PAID"
            />
            <StatCard
              label="已发货 · 在途"
              value={data.shippedCount}
              hint="等买家确认收货"
              to="/seller/orders?status=SHIPPED"
            />
            <StatCard
              label="已完成"
              value={data.completedCount}
              hint="款项已放款"
              to="/seller/orders?status=COMPLETED"
            />
            <StatCard
              label="已退款"
              value={data.refundedCount}
              hint="售后已退回买家"
              to="/seller/orders?status=REFUNDED"
            />
          </div>

          <div className="card card-pad" style={{ marginTop: 16 }}>
            <h3 style={{ marginBottom: 12 }}>资金</h3>
            <div className="grid grid-3">
              <div>
                <div className="small muted">托管中（买家已付款，你还没收到）</div>
                <div style={{ fontSize: 22, fontWeight: 700 }}>
                  {formatPrice(data.custodySellerAmount)}
                </div>
                <div className="small muted">买家实付 {formatPrice(data.custodyAmount)}</div>
              </div>
              <div>
                <div className="small muted">已到账（已放款，扣佣后）</div>
                <div style={{ fontSize: 22, fontWeight: 700, color: 'var(--brand)' }}>
                  {formatPrice(data.releasedSellerAmount)}
                </div>
                <div className="small muted">对应实付 {formatPrice(data.releasedAmount)}</div>
              </div>
              <div>
                <div className="small muted">已退回买家</div>
                <div style={{ fontSize: 22, fontWeight: 700 }}>{formatPrice(data.refundedAmount)}</div>
                <div className="small muted">售后退款合计</div>
              </div>
            </div>

            <div className="divider" />
            <div className="small muted">
              放款发生在买家确认收货（或超时自动确认）之后 —— 发货只说明货出去了，
              买家还没见到货，此时放款等于让平台替卖家承担全部风险。
            </div>
          </div>

          <div className="grid grid-2" style={{ marginTop: 16, alignItems: 'start' }}>
            <div className="card card-pad">
              <h3 style={{ marginBottom: 12 }}>订单状态分布</h3>
              <MiniBars
                data={[
                  { label: '待付款', value: data.pendingPaymentCount },
                  { label: '待发货', value: data.pendingShipCount },
                  { label: '在途', value: data.shippedCount },
                  { label: '已完成', value: data.completedCount },
                  { label: '已退款', value: data.refundedCount },
                  { label: '已关闭', value: data.closedCount },
                ]}
                unit=" 笔"
              />
            </div>

            <div className="card card-pad">
              <h3 style={{ marginBottom: 12 }}>快捷操作</h3>
              <div className="row row-wrap">
                <Link className="btn btn-primary btn-sm" to="/seller/orders?status=PAID">
                  处理待发货
                </Link>
                <Link className="btn btn-ghost btn-sm" to="/seller/orders">
                  全部卖出订单
                </Link>
                <Link className="btn btn-ghost btn-sm" to="/seller/settlements">
                  资金对账
                </Link>
                <Link className="btn btn-ghost btn-sm" to="/seller/products">
                  商品管理
                </Link>
                <Link className="btn btn-ghost btn-sm" to="/me">
                  账号与实名
                </Link>
              </div>

              <div className="divider" />
              <div className="small muted">
                本期商家端覆盖<b>订单履约</b>、<b>资金结算</b>与<b>商品上下架</b>。
                新建商品涉及类目 / 品牌 / SKU / 库存的完整表单与审核，属入驻运营流程，另行排期。
              </div>
            </div>
          </div>
        </>
      ) : null}
    </div>
  );
};

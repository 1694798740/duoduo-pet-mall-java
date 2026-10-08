/**
 * 商家端 · 概览。
 *
 * 数据来源：GET /api/seller/summary（统计卡与资金）+ GET /api/seller/analytics（多维图表）
 *
 * 三个刻意的设计：
 *  · 概览同时充当「权限探测」：非卖家会被后端 403，页面据此渲染入驻引导，
 *    而不是进一个到处都是红色报错的空壳页面
 *  · 所有金额都是后端算好的（托管中 / 已到账 / 已退回），前端不做减法 ——
 *    佣金口径只应有一处实现，前端做减法等于把它复制了 N 份
 *  · 图表走**懒加载**（`components/lazy-charts`）：ECharts 是本项目第一个运行时
 *    第三方 UI 依赖，只有商家端/后台用得到，买家端首屏不该为它买单
 */

import React, { useCallback } from 'react';
import { Link } from 'react-router-dom';
import { sellerApi } from '@duoduo/api-sdk/endpoints';
import type { SellerAnalytics, SellerSummary } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import { LazyChart } from '@duoduo/shared/components/lazy-charts';
import {
  Alert,
  LoadingBlock,
  formatPrice,
} from '@duoduo/shared/components/ui';
import { SellerApplyCard, canAccessSellerConsole } from './shared';
import { useAuth } from '@duoduo/shared/store/auth';

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
  // 图表数据单独一个请求、单独降级：统计卡比图表重要得多，
  // 图表接口出问题不该让「待发货 144」也跟着消失。
  const chartFetcher = useCallback(() => sellerApi.analytics(), []);
  const { data: charts, loading: chartsLoading } = useAsync<SellerAnalytics>(chartFetcher, allowed, [allowed]);

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

          {/* ---------- 多维图表（第六十六轮） ----------
              五个维度：状态分布 / 成交趋势 / 商品销量 / 资金构成 / 订单类型。
              全部交给 ECharts（按需引入 + 懒加载），悬停即时出数据 ——
              原实现用 HTML 原生 title 做提示，延迟由操作系统决定（1–2 秒）且不可配置。 */}
          <div className="section-head" style={{ marginTop: 22, marginBottom: 10 }}>
            <h3 className="section-title" style={{ fontSize: 15 }}>经营图表</h3>
            <span className="section-sub">
              {chartsLoading && !charts ? '加载中…' : '悬停任意图形即可查看明细'}
            </span>
          </div>

          <div className="grid grid-2" style={{ marginBottom: 16 }}>
            <div className="card card-pad">
              <h4 style={{ marginBottom: 4 }}>订单状态分布</h4>
              <div className="small muted" style={{ marginBottom: 8 }}>六个状态各多少笔（含已关闭与待付款）</div>
              <LazyChart type="bar" data={charts?.statusDistribution ?? []} unit=" 笔" height={230} />
            </div>

            <div className="card card-pad">
              <h4 style={{ marginBottom: 4 }}>近 14 天成交趋势</h4>
              <div className="small muted" style={{ marginBottom: 8 }}>
                按下单日期归集（UTC 自然日，与库内存储口径一致）；无成交的日期补 0，不是断点
              </div>
              <LazyChart type="line" data={charts?.trend ?? []} height={230} />
            </div>
          </div>

          <div className="grid grid-3" style={{ marginBottom: 16 }}>
            <div className="card card-pad">
              <h4 style={{ marginBottom: 4 }}>商品销量 Top 8</h4>
              <div className="small muted" style={{ marginBottom: 8 }}>只统计已付款及之后的订单</div>
              <LazyChart type="hbar" data={charts?.topProducts ?? []} unit=" 件" height={230} />
            </div>

            <div className="card card-pad">
              <h4 style={{ marginBottom: 4 }}>资金构成</h4>
              <div className="small muted" style={{ marginBottom: 8 }}>托管中 / 已放款 / 已退回</div>
              <LazyChart type="pie" data={charts?.fundComposition ?? []} money height={230} />
            </div>

            <div className="card card-pad">
              <h4 style={{ marginBottom: 4 }}>订单类型占比</h4>
              <div className="small muted" style={{ marginBottom: 8 }}>普通下单 vs 秒杀单</div>
              <LazyChart type="pie" data={charts?.orderTypeMix ?? []} unit=" 笔" height={230} />
            </div>
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
              <Link className="btn btn-ghost btn-sm" to="/seller/after-sales">
                售后处理
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
        </>
      ) : null}
    </div>
  );
};

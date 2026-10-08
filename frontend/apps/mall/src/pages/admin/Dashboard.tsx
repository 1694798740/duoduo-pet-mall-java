/**
 * 平台管理后台 · 运营概览（M10）。
 *
 * 数据源：
 *   GET /api/admin/reports/overview    —— GMV / 订单量 / 退款率 / 转化率
 *   GET /api/admin/reports/charts      —— 分布图 / 折线图 / 柱状图 / 饼图（第六十六轮）
 *   GET /api/admin/settlements/summary —— 全平台托管资金汇总
 *   GET /api/admin/settlements/abnormal —— 异常挂账（已付款却缺托管单）
 *
 * 与商家端概览的关键差别：商家端回答「我这个卖家有多少钱」，这里回答
 * 「整个平台的钱对不对得上」。四者同时拉，任何一个失败都应给出可读提示而不是白屏。
 *
 * ⚠️ **图表不参与页面的 loading 门**：图表是「细看的形状」，报表卡与资金对账是
 * 「一眼要看的数」。把图表算进门里，等于让一个画得慢的图把整页数字一起拖住 ——
 * 所以图表自己走骨架屏，其余三块照常先渲染。
 *
 * 图表库走懒加载（`components/lazy-charts`）：ECharts 只有本页与商家端用得到，
 * 买家端首屏不该为它买单。
 */

import React, { useCallback } from 'react';
import { adminApi } from '@duoduo/api-sdk/endpoints';
import { Alert, LoadingBlock, formatPrice } from '@duoduo/shared/components/ui';
import { LazyChart } from '@duoduo/shared/components/lazy-charts';
import { useAsync } from '@duoduo/shared/components/hooks';
import { useAuth } from '@duoduo/shared/store/auth';
import { AdminDenied, AdminStat, AdminTabs, isOperator } from './shared';

export const AdminDashboardPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);

  const report = useAsync(
    useCallback(() => adminApi.reportOverview(), []),
    operator,
    [operator],
  );
  const summary = useAsync(
    useCallback(() => adminApi.settlementSummary(), []),
    operator,
    [operator],
  );
  const abnormal = useAsync(
    useCallback(() => adminApi.settlementAbnormal(), []),
    operator,
    [operator],
  );
  const charts = useAsync(
    useCallback(() => adminApi.reportCharts(), []),
    operator,
    [operator],
  );

  if (!operator) return <AdminDenied />;
  if (report.loading || summary.loading || abnormal.loading) {
    return <LoadingBlock text="正在汇总平台运营数据…" />;
  }

  const r = report.data;
  const s = summary.data;
  const ab = abnormal.data ?? [];

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 运营概览</h2>
      <AdminTabs />

      {report.error ? <Alert kind="error">{report.error}</Alert> : null}
      {summary.error ? <Alert kind="error">{summary.error}</Alert> : null}
      {abnormal.error ? <Alert kind="error">{abnormal.error}</Alert> : null}

      <h3 style={{ marginBottom: 12, marginTop: 8 }}>经营报表</h3>
      <div className="grid grid-4">
        <AdminStat label="GMV（已付款成交额）" value={r ? formatPrice(r.gmv) : '—'} hint="PAID / SHIPPED / COMPLETED" />
        <AdminStat label="总订单量" value={r?.totalOrders ?? '—'} hint="含待付款与已关闭" />
        <AdminStat label="已付款订单" value={r?.paidOrders ?? '—'} hint={`转化率 ${r?.conversionRate ?? '—'}`} />
        <AdminStat label="退款单" value={r?.refundedOrders ?? '—'} hint={`退款率 ${r?.refundRate ?? '—'}`} />
      </div>

      <h3 style={{ marginBottom: 12, marginTop: 24 }}>资金对账</h3>
      <div className="grid grid-4">
        <AdminStat label="托管中金额" value={s ? formatPrice(s.custodyAmount) : '—'} hint={`${s?.custodyCount ?? 0} 笔 · 佣金 ${s ? formatPrice(s.custodyFee) : '—'}`} />
        <AdminStat label="已放款金额" value={s ? formatPrice(s.releasedAmount) : '—'} hint={`${s?.releasedCount ?? 0} 笔 · 佣金 ${s ? formatPrice(s.releasedFee) : '—'}`} />
        <AdminStat label="已退回买家" value={s ? formatPrice(s.refundedAmount) : '—'} hint={`${s?.refundedCount ?? 0} 笔`} />
        <AdminStat label="异常挂账" value={ab.length} hint="已付款却缺托管单的订单" />
      </div>

      {/* ---------- 图表区（第六十六轮） ----------
          四类图形各司其职：
            折线图 = 看趋势（时间上的形状，柱状图看趋势会糊）
            柱状图 = 看排名（商品销量 Top）
            饼图   = 看构成（资金三态占比，一眼看出钱压在哪）
            分布图 = 看结构（订单状态横向条形，类目名长时横着排更易读）
          悬停即时出数 —— 由 ECharts 自绘 tooltip 提供，不再是浏览器原生 title 的 1–2 秒延迟。 */}
      <h3 style={{ marginBottom: 12, marginTop: 24 }}>经营图表</h3>
      {charts.error ? <Alert kind="error">{charts.error}</Alert> : null}

      <div className="grid grid-2">
        <div className="card card-pad">
          <h4 style={{ marginBottom: 4 }}>近 14 天成交趋势</h4>
          <div className="small muted" style={{ marginBottom: 8 }}>
            按下单日期归集（UTC 自然日）；无成交的日期补 0，不是断点
          </div>
          <LazyChart type="line" data={charts.data?.trend ?? []} height={240} />
        </div>

        <div className="card card-pad">
          <h4 style={{ marginBottom: 4 }}>资金构成</h4>
          <div className="small muted" style={{ marginBottom: 8 }}>托管中 / 已放款 / 已退回的金额占比</div>
          <LazyChart type="pie" data={charts.data?.fundComposition ?? []} money height={240} />
        </div>
      </div>

      <div className="grid grid-2" style={{ marginTop: 16 }}>
        <div className="card card-pad">
          <h4 style={{ marginBottom: 4 }}>商品销量 Top 8</h4>
          <div className="small muted" style={{ marginBottom: 8 }}>全平台，只统计已付款及之后的订单</div>
          <LazyChart type="hbar" data={charts.data?.topProducts ?? []} unit=" 件" height={240} />
        </div>

        <div className="card card-pad">
          <h4 style={{ marginBottom: 4 }}>订单状态分布</h4>
          <div className="small muted" style={{ marginBottom: 8 }}>六个状态各多少笔</div>
          <LazyChart type="hbar" data={charts.data?.statusDistribution ?? []} unit=" 笔" height={240} />
        </div>
      </div>

      {ab.length > 0 ? (
        <div className="card card-pad" style={{ marginTop: 24 }}>
          <h3 style={{ marginBottom: 12 }}>异常挂账（需人工核对）</h3>
          <Alert kind="error">
            以下订单已付款但<b>没有对应的资金托管单</b>，确认收货放款时会因找不到账本而失败。
            这类数据通常来自托管能力上线前的历史订单，需一次性回补（见项目台账 I-14）。
          </Alert>
          <table className="table" style={{ marginTop: 12 }}>
            <thead>
              <tr>
                <th>订单号</th>
                <th>订单状态</th>
                <th>实付金额</th>
              </tr>
            </thead>
            <tbody>
              {ab.map((row) => (
                <tr key={row.orderNo}>
                  <td className="mono">{row.orderNo}</td>
                  <td>{row.orderStatus}</td>
                  <td>{formatPrice(row.payAmount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Alert kind="success">
          资金对账正常：所有已付款订单都有对应的托管单，无异常挂账。
        </Alert>
      )}
    </div>
  );
};

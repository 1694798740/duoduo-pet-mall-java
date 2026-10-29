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

import React, { useCallback, useEffect, useState } from 'react';
import { productImage } from '@duoduo/api-sdk/images';
import { Link, useSearchParams } from 'react-router-dom';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { logisticsApi, sellerApi } from '@duoduo/api-sdk/endpoints';
import type { BatchResultView, OrderView, PageResult, WaybillView } from '@duoduo/api-sdk/types';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { BatchBar, BatchResultModal, useBatchSelection } from '@duoduo/shared/components/batch';
import { TabWithBadge, UnreadDot, toCountMap } from '@duoduo/shared/components/badge';
import { applyOptimisticDismiss, shouldDismissOnRead } from '@duoduo/shared/lib/badge-rules';
import { notifyBadgeChanged } from '@duoduo/shared/lib/badge-events';
import {
  Alert,
  Empty,
  Field,
  LoadingBlock,
  Modal,
  Pagination,
  SearchBar,
  formatDate,
  formatPrice,
  useToast,
} from '@duoduo/shared/components/ui';
import { SellerApplyCard, canAccessSellerConsole } from './shared';
import { useAuth } from '@duoduo/shared/store/auth';

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
  const rawKeyword = params.get('q') ?? '';
  const keyword = useDebounced(rawKeyword.trim(), 350);
  const allowed = canAccessSellerConsole(user?.role);

  const fetcher = useCallback(
    () => sellerApi.orders({ status: status || undefined, keyword: keyword || undefined, page, pageSize: 10 }),
    [status, keyword, page],
  );
  const { data, loading, error, reload } = useAsync<PageResult<OrderView>>(
    fetcher,
    allowed,
    [allowed, status, keyword, page],
  );
  /** B-05 · 快递公司列表（服务端下发，前端不维护第二份编码表） */
  const couriers = useAsync(useCallback(() => logisticsApi.couriers(), []), allowed);

  /**
   * 订单页签角标（2026-10-27）。
   *
   * <p><b>不再用 {@code sellerApi.summary()}</b>：summary 是经营存量（「我一共有多少单」），
   * 不会因为卖家看过而变；角标要的是「还有多少条我没看过」。
   * 已发货/已完成/已关闭/已退款读过的会被服务端从分桶里减掉，
   * 而发货动作本身会写一条店铺已读 —— 所以**自己发货不会让「已发货」+1**。
   */
  const countsState = useAsync(
    useCallback(() => sellerApi.orderStatusCounts(), []),
    allowed,
  );

  /** 角标本地态：允许乐观减，之后一律以服务端分桶覆盖 */
  const [orderCounts, setOrderCounts] = useState<Record<string, number>>({});
  useEffect(() => {
    setOrderCounts(toCountMap(countsState.data));
  }, [countsState.data]);
  const reloadCounts = countsState.reloadSilently;

  /**
   * 逐条已读的本地覆盖（2026-10-27 · 橙色未读印记）。
   * 切筛选/翻页时清空，由服务端回传的 `read` 字段重新决定橙点。
   */
  const [readIds, setReadIds] = useState<Set<string>>(new Set());
  useEffect(() => {
    setReadIds(new Set());
  }, [status, keyword, page]);
  const isRead = (order: OrderView) => order.read === true || readIds.has(order.orderNo);

  /** 批量选择（2026-10-27 · 批量操作即已读）：只作用于当前页，切筛选/翻页自动清空 */
  const pageIds = (data?.items ?? []).map((order) => order.orderNo);
  const selection = useBatchSelection(pageIds, `${status}|${keyword}|${page}`);
  const [batchShipOpen, setBatchShipOpen] = useState(false);
  const [batchCompany, setBatchCompany] = useState('');
  const [batchLogisticsNo, setBatchLogisticsNo] = useState('');
  const [batchBusy, setBatchBusy] = useState(false);
  const [batchResult, setBatchResult] = useState<BatchResultView | null>(null);

  /** 预览卡片（2026-10-27）：点卡片先看轻量预览，不直接跳详情页 */
  const [preview, setPreview] = useState<OrderView | null>(null);

  /**
   * 点开订单 = 已读：上报 + 更新本地橙点/角标。
   *
   * <p>橙点对**所有状态**都消失（看过了）；数字角标只有「阅后即消」类
   * （已发货/已完成/已退款/已关闭）才减 —— 待付款/待发货是还没办完的事，
   * 读过也必须留着。上报失败撤掉橙点覆盖并回读服务端值，不做假账。
   */
  const dismissBadge = useCallback(
    async (order: OrderView) => {
      if (readIds.has(order.orderNo)) return;
      const dismissible = shouldDismissOnRead('seller', order.status);
      setReadIds((prev) => new Set(prev).add(order.orderNo));
      if (dismissible) {
        setOrderCounts((prev) => applyOptimisticDismiss(prev, order.status));
      }
      try {
        const rows = await sellerApi.markOrderRead(order.orderNo);
        setOrderCounts(toCountMap(rows));
        // 父级菜单角标与页签同一刻收敛
        notifyBadgeChanged();
      } catch {
        setReadIds((prev) => {
          const next = new Set(prev);
          next.delete(order.orderNo);
          return next;
        });
        void reloadCounts();
      }
    },
    [reloadCounts, readIds],
  );

  /** 打开预览 = 已读（原子操作：渲染与上报只在这一个入口里） */
  const openPreview = (order: OrderView) => {
    setPreview(order);
    void dismissBadge(order);
  };

  const [shipTarget, setShipTarget] = useState<OrderView | null>(null);
  const [refundTarget, setRefundTarget] = useState<OrderView | null>(null);
  const [company, setCompany] = useState('');
  const [logisticsNo, setLogisticsNo] = useState('');
  /** B-05 · 电子面单：本地无快递鸟/菜鸟凭据时由服务端生成模拟面单 */
  const [courierCode, setCourierCode] = useState('SF');
  const [waybill, setWaybill] = useState<WaybillView | null>(null);
  const [waybillBusy, setWaybillBusy] = useState(false);
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
    setCourierCode('SF');
    setWaybill(null);
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
      // 发货后「待发货」减一：「已发货」不新增未读（服务端发货即写店铺已读）
      reloadCounts();
      notifyBadgeChanged();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  /**
   * 申请电子面单（B-05）。
   *
   * 服务端幂等：同一订单重复点击返回同一个运单号；本地没有真实渠道时
   * 返回 simulated=true，界面必须明示「模拟面单」，不能让卖家以为已对接快递鸟/菜鸟。
   */
  const createWaybill = async () => {
    if (!shipTarget || waybillBusy) return;
    setWaybillBusy(true);
    setActionError(null);
    try {
      const created = await logisticsApi.createWaybill({
        orderNo: shipTarget.orderNo,
        courierCode,
      });
      setWaybill(created);
      setCompany(created.courierName);
      setLogisticsNo(created.waybillNo);
      push(created.simulated ? '模拟电子面单已生成，可直接用于发货' : '电子面单已生成', 'success');
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setWaybillBusy(false);
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
      // 退款后「已退款」加一，列表与角标同刻刷新
      reloadCounts();
      notifyBadgeChanged();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  /**
   * 批量发货（2026-10-27 · 批量操作即已读）。
   *
   * <p>与商品管理同一闭环：逐条结果 → 成功条目清橙点 → 用服务端返回的最新分桶
   * 覆盖本地角标（拿不到分桶才回读）→ 清空选择 → 刷新列表并广播父级角标。
   * 选中的订单里混入非「待发货」时，服务端会逐条拒绝并带回原因，不静默跳过。
   */
  const submitBatchShip = async () => {
    if (!selection.count || batchBusy) return;
    setBatchBusy(true);
    setActionError(null);
    try {
      const result = await sellerApi.batchShip(selection.selected, {
        logisticsCompany: batchCompany.trim() || undefined,
        logisticsNo: batchLogisticsNo.trim() || undefined,
      });
      setBatchResult(result);
      const successIds = result.items.filter((item) => item.success).map((item) => item.id);
      if (successIds.length) {
        setReadIds((prev) => {
          const next = new Set(prev);
          successIds.forEach((id) => next.add(id));
          return next;
        });
      }
      if (result.counts) setOrderCounts(toCountMap(result.counts));
      else void reloadCounts();
      selection.clear();
      setBatchShipOpen(false);
      reload();
      notifyBadgeChanged();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBatchBusy(false);
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

      <div className="list-toolbar">
        <SearchBar
          value={rawKeyword}
          onChange={(v) => {
            const next = new URLSearchParams(params);
            if (v) next.set('q', v);
            else next.delete('q');
            next.delete('page');
            setParams(next, { replace: true });
          }}
          busy={loading && Boolean(rawKeyword.trim())}
          placeholder="搜订单号或商品名称"
          width={300}
        />
        {rawKeyword.trim() ? (
          <span className="small muted">匹配「{rawKeyword.trim()}」的订单 {data ? data.total : 0} 笔</span>
        ) : null}
        <button
          className="btn btn-ghost btn-sm"
          title="按当前页签与关键词导出本店订单（CSV）"
          onClick={() => {
            window.location.href = sellerApi.orderExportUrl({
              status: status || undefined,
              keyword: keyword || undefined,
            });
          }}
        >
          导出 CSV
        </button>
        <label className="checkbox-row small" style={{ marginLeft: 'auto' }}>
          <input
            type="checkbox"
            checked={selection.allSelected}
            disabled={!pageIds.length}
            onChange={selection.toggleAll}
          />
          全选当前页
        </label>
      </div>

      <div className="tabs" style={{ marginBottom: 16 }}>
        {TABS.map((tab) => (
          <TabWithBadge
            key={tab.label}
            label={tab.label}
            count={orderCounts[tab.value]}
            active={status === tab.value}
            onClick={() => setStatus(tab.value)}
          />
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
          <BatchBar count={selection.count} onClear={selection.clear}>
            <button
              className="btn btn-primary btn-sm"
              disabled={batchBusy}
              onClick={() => {
                setBatchCompany('');
                setBatchLogisticsNo('');
                setActionError(null);
                setBatchShipOpen(true);
              }}
            >
              批量发货
            </button>
          </BatchBar>
          <div className="stack">
            {/* 未读优先：未读在前；组内按下单时间倒序。
                后端已按同一口径排序，这里再排一次是为了让「刚点开的那一条」
                立刻回到已读组的时间位置（不必等刷新） */}
            {[...data.items]
              .sort((a, b) => {
                const byRead = Number(isRead(a)) - Number(isRead(b));
                if (byRead !== 0) return byRead;
                return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();
              })
              .map((order) => (
              <div
                className={`card card-pad${isRead(order) ? '' : ' unread-card'}`}
                key={order.orderNo}
                onClick={() => openPreview(order)}
                style={{ cursor: 'pointer' }}
              >
                <div className="row-between row-wrap">
                  <div className="row row-wrap">
                    <input
                      type="checkbox"
                      aria-label={`选择订单 ${order.orderNo}`}
                      checked={selection.selectedSet.has(order.orderNo)}
                      disabled={batchBusy}
                      onClick={(e) => e.stopPropagation()}
                      onChange={() => selection.toggle(order.orderNo)}
                    />
                    {!isRead(order) ? <UnreadDot /> : null}
                    <Link
                      className="small"
                      to={`/seller/orders/${order.orderNo}`}
                      onClick={(e) => {
                        e.stopPropagation();
                        void dismissBadge(order);
                      }}
                    >
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
                          loading="lazy"
                          decoding="async"
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
                <div className="row row-wrap" onClick={(e) => e.stopPropagation()}>
                  <Link
                    className="btn btn-ghost btn-sm"
                    to={`/seller/orders/${order.orderNo}`}
                    onClick={() => void dismissBadge(order)}
                  >
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
          title={
            rawKeyword.trim()
              ? `没有匹配「${rawKeyword.trim()}」的订单`
              : status
                ? '该状态下没有订单'
                : '还没有卖出订单'
          }
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

      {/* 订单预览卡片（2026-10-27）：点卡片先看摘要，不直接跳全屏详情；
          打开即已读，橙点消失与角标联动在同一入口完成 */}
      <Modal
        open={preview !== null}
        title="订单预览"
        onClose={() => setPreview(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setPreview(null)}>
              关闭
            </button>
            {preview ? (
              <Link
                className="btn btn-primary"
                to={`/seller/orders/${preview.orderNo}`}
                onClick={() => setPreview(null)}
              >
                查看完整详情
              </Link>
            ) : null}
          </>
        }
      >
        {preview ? (
          <div className="stack" style={{ gap: 10 }}>
            <div className="row row-wrap" style={{ gap: 8, alignItems: 'center' }}>
              <strong>订单号 {preview.orderNo}</strong>
              <span className="tag">{preview.statusLabel}</span>
              {preview.settlement ? <span className="tag">{preview.settlement.statusLabel}</span> : null}
            </div>
            <div className="small muted">
              下单 {formatDate(preview.createdAt)} · 买家实付{' '}
              <b style={{ color: 'var(--danger)' }}>{formatPrice(preview.payAmount)}</b>
            </div>
            <div className="stack" style={{ gap: 6 }}>
              {preview.items.map((item) => (
                <div className="row-between" key={`${preview.orderNo}-${item.skuId}`}>
                  <span className="small" style={{ minWidth: 0 }}>
                    {item.title} · {item.specText ?? '默认规格'} × {item.quantity}
                  </span>
                  <span className="small">{formatPrice(item.amount)}</span>
                </div>
              ))}
            </div>
            {/* 金额明细：卖家核对「买家实付 / 平台补贴 / 券与币抵扣」的唯一口径 */}
            <div className="card card-pad" style={{ padding: 12 }}>
              <div className="small" style={{ fontWeight: 600, marginBottom: 6 }}>金额明细</div>
              <div className="small muted" style={{ display: 'grid', gap: 4 }}>
                <div className="row-between">
                  <span>商品总额</span>
                  <span>{formatPrice(preview.totalAmount)}</span>
                </div>
                <div className="row-between">
                  <span>运费</span>
                  <span>{formatPrice(preview.freightAmount)}</span>
                </div>
                <div className="row-between">
                  <span>优惠</span>
                  <span>-{formatPrice(preview.discountAmount)}</span>
                </div>
                {preview.couponCode ? (
                  <div className="row-between">
                    <span>优惠券</span>
                    <span>{preview.couponCode}</span>
                  </div>
                ) : null}
                {preview.coinUsed > 0 ? (
                  <div className="row-between">
                    <span>多多币抵扣（{preview.coinUsed} 币）</span>
                    <span>-{formatPrice(preview.coinDeductAmount)}</span>
                  </div>
                ) : null}
                <div className="row-between" style={{ color: 'var(--danger)', fontWeight: 600 }}>
                  <span>买家实付</span>
                  <span>{formatPrice(preview.payAmount)}</span>
                </div>
              </div>
            </div>

            <div className="small muted">
              收货：{preview.receiverName} · {preview.receiverPhone} · {preview.receiverAddress}
            </div>
            {preview.remark ? (
              <div className="small muted">买家备注：{preview.remark}</div>
            ) : null}
            {preview.logisticsNo ? (
              <div className="small muted">
                物流：{preview.logisticsCompany ?? '—'} {preview.logisticsNo}
              </div>
            ) : null}

            {/* 状态流转时间线：只列已发生的节点，没发生的时段不占位 */}
            <div>
              <div className="small" style={{ fontWeight: 600, marginBottom: 6 }}>订单时间线</div>
              <div className="small muted" style={{ display: 'grid', gap: 4 }}>
                <span>下单：{formatDate(preview.createdAt)}</span>
                {preview.paidAt ? <span>支付：{formatDate(preview.paidAt)}</span> : null}
                {preview.shippedAt ? <span>发货：{formatDate(preview.shippedAt)}</span> : null}
                {preview.finishedAt ? <span>完成：{formatDate(preview.finishedAt)}</span> : null}
                {preview.refundedAt ? <span>退款：{formatDate(preview.refundedAt)}</span> : null}
                {preview.closedAt ? <span>关闭：{formatDate(preview.closedAt)}</span> : null}
              </div>
            </div>

            <div className="small muted">
              打开预览即视为已读：橙点消失；已发货 / 已完成 / 已退款 / 已关闭的角标同步 -1，
              待付款 / 待发货仍保留提醒（那是还没办完的事）。
            </div>
          </div>
        ) : null}
      </Modal>

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
          <Field
            label="电子面单"
            hint="本地未接快递鸟/菜鸟，生成的是模拟面单；运单号会直接填入下方物流信息"
          >
            <div className="row" style={{ gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
              <select
                className="input"
                style={{ maxWidth: 180 }}
                value={courierCode}
                onChange={(e) => setCourierCode(e.target.value)}
                aria-label="快递公司"
              >
                {(couriers.data ?? [{ code: 'SF', name: '顺丰速运' }]).map((item) => (
                  <option key={item.code} value={item.code}>
                    {item.name}
                  </option>
                ))}
              </select>
              <button
                className="btn btn-ghost btn-sm"
                onClick={createWaybill}
                disabled={waybillBusy || busy}
              >
                {waybillBusy ? '生成中…' : '获取电子面单'}
              </button>
              {waybill ? (
                <span className="small muted">
                  已生成 {waybill.courierName} {waybill.waybillNo}
                  {waybill.simulated ? '（模拟面单）' : '（真实渠道）'}
                </span>
              ) : null}
            </div>
          </Field>
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

      {/* 批量发货：物流信息对选中订单一共用；仅「待发货」会成功，其余逐条失败 */}
      <Modal
        open={batchShipOpen}
        title="批量发货"
        onClose={() => setBatchShipOpen(false)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setBatchShipOpen(false)} disabled={batchBusy}>
              取消
            </button>
            <button className="btn btn-primary" onClick={submitBatchShip} disabled={batchBusy}>
              {batchBusy ? '提交中…' : `确认发货（${selection.count} 笔）`}
            </button>
          </>
        }
      >
        <Alert kind="info">
          将尝试发货 <b>{selection.count}</b> 笔订单（仅当前页）。只有<b>待发货</b>订单会成功；
          已付款之外的状态会逐条失败并给出原因，不会静默跳过。
          发货后资金仍在平台托管，放款要等买家确认收货。
        </Alert>
        <div style={{ marginTop: 12 }}>
          <Field label="物流公司（可选）" hint="留空则只改状态；填写后对全部选中订单共用">
            <input
              className="input"
              value={batchCompany}
              onChange={(e) => setBatchCompany(e.target.value)}
              placeholder="如 顺丰速运"
              maxLength={50}
            />
          </Field>
          <Field label="物流单号（可选）">
            <input
              className="input"
              value={batchLogisticsNo}
              onChange={(e) => setBatchLogisticsNo(e.target.value)}
              placeholder="批量交寄通常不逐单填写；可留空"
              maxLength={64}
            />
          </Field>
        </div>
        {actionError ? (
          <div style={{ marginTop: 10 }}>
            <Alert kind="error">{actionError}</Alert>
          </div>
        ) : null}
      </Modal>

      <BatchResultModal result={batchResult} onClose={() => setBatchResult(null)} idLabel="订单号" />
    </div>
  );
};

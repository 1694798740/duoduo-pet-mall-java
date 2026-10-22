/**
 * 订单详情页 —— 主交易闭环的核心页面。
 *
 * 覆盖「待支付 → 已付款 → 已发货 → 已完成」，以及取消 / 退款分支，
 * 并完整呈现 C2C 资金托管状态。
 *
 * 接口：
 *   GET  /api/orders/{orderNo}                订单详情（含 settlement）
 *   GET  /api/orders/{orderNo}/settlement     资金托管状态
 *   POST /api/orders/{orderNo}/pay            预下单 → 返回 codeUrl（二维码内容）+ expireAt
 *   GET  /api/payments/{payNo}                轮询支付单状态
 *   POST /api/payments/sandbox/{payNo}        沙箱「扫码后确认支付」（本机模拟扫码）
 *   POST /api/orders/{orderNo}/cancel         取消（仅待付款）
 *   POST /api/orders/{orderNo}/confirm        确认收货（已发货 → 已完成，并放款）
 *   POST /api/orders/{orderNo}/refund         买家自助退款（仅已付款且未发货）
 *   GET  /api/logistics/orders/{orderNo}/tracks  物流轨迹（履约域 A2，按发生时间正序）
 *
 * 支付交互（与真实扫码支付一致）：
 *   PC 端展示二维码 → 前端每 3 秒轮询支付单状态 →
 *   PENDING 显示倒计时，SUCCESS 停止轮询并刷新订单。
 *   演示环境不便用第二台设备扫码，故额外提供「本机模拟扫码」按钮，
 *   它调用的是与真实回调同一条验签链路的沙箱接口。
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { productImage } from '@duoduo/api-sdk/images';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { newIdempotentKey, toFriendlyMessage } from '@duoduo/api-sdk/client';
import { orderApi, paymentApi, supportApi } from '@duoduo/api-sdk/endpoints';
import type { OrderView, PayResult, SettlementView } from '@duoduo/api-sdk/types';
import { useAsync, useLiveRefresh } from '@duoduo/shared/components/hooks';
import {
  Alert,
  LoadingBlock,
  Modal,
  StatusSteps,
  formatDate,
  formatPrice,
  useToast,
} from '@duoduo/shared/components/ui';
import { QRCode } from '@duoduo/shared/components/QRCode';
import { LogisticsTimeline } from '@duoduo/shared/components/LogisticsTimeline';
import { SupportEntry } from '../components/SupportEntry';
import { notifyDataChanged } from '@duoduo/shared/lib/dataBus';
import { useApp } from '@duoduo/shared/store/app';

/** 轮询间隔（毫秒）——与真实扫码支付的体验对齐 */
const POLL_INTERVAL = 3000;
/** 支付单剩余秒数的兜底上限，防止服务端未回 expireAt 时无限倒计时 */
const DEFAULT_TTL_SECONDS = 30 * 60;

const CHANNELS = [
  { value: 'SANDBOX', label: '沙箱扫码支付' },
  { value: 'ALIPAY_PC', label: '支付宝' },
  { value: 'MOCK', label: '模拟即时支付' },
] as const;

/**
 * 支付宝 page.pay 的正确打开方式（官方规范）：
 * 服务端返回的是「自动提交的 HTML 表单」而不是 URL —— 注入 DOM、
 * 以新标签提交（收银台在新页打开，原页轮询继续），禁止用 URL 直接跳转。
 */
const submitAlipayForm = (formHtml: string) => {
  const container = document.createElement('div');
  container.style.display = 'none';
  container.innerHTML = formHtml;
  document.body.appendChild(container);
  const form = container.querySelector('form');
  if (form) {
    form.target = '_blank';
    form.submit();
  }
};

const SETTLEMENT_TEXT: Record<string, { label: string; kind: 'info' | 'success' | 'warn' }> = {
  CUSTODY: { label: '资金托管中：货款暂存平台，确认收货后结算给卖家', kind: 'warn' },
  RELEASED: { label: '已放款：货款已结算给卖家', kind: 'success' },
  REFUNDED: { label: '已退回买家：货款已原路退回', kind: 'info' },
};

export const OrderDetailPage: React.FC = () => {
  const { orderNo = '' } = useParams<{ orderNo: string }>();
  const { push } = useToast();
  /** 支付成功会生成「支付成功」通知：与购物车同一口径，立即刷新角标 */
  const { refreshUnread } = useApp();

  const [acting, setActing] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  // ---- 支付弹窗状态 ----
  const [payOpen, setPayOpen] = useState(false);
  const [channel, setChannel] = useState<'SANDBOX' | 'ALIPAY_PC' | 'MOCK'>('SANDBOX');
  const [payResult, setPayResult] = useState<PayResult | null>(null);
  const [payError, setPayError] = useState<string | null>(null);
  const [payBusy, setPayBusy] = useState(false);
  const [remaining, setRemaining] = useState(DEFAULT_TTL_SECONDS);
  const payKeyRef = useRef<string>(newIdempotentKey());
  const pollRef = useRef<number | null>(null);

  const orderFetcher = useCallback(() => orderApi.detail(orderNo), [orderNo]);
  const {
    data: order,
    loading,
    error,
    reload: reloadOrder,
    reloadSilently: reloadOrderSilently,
    setData: setOrder,
  } = useAsync<OrderView>(orderFetcher, Boolean(orderNo), [orderNo]);

  /**
   * 资金托管记录**只对已付款之后的订单存在**。
   *
   * <p>待付款 / 已关闭的单子去查，服务端只能回 404「资金托管记录」——
   * 页面本来就不展示这块（`custody` 取不到即整段不渲染），但加了实时轮询之后，
   * 这个必然失败的请求会**每 30 秒重打一次**，在控制台里堆成一片红。
   * 因此按状态决定要不要发这个请求，而不是靠「拿到错误就当没有」。
   */
  const canHaveSettlement =
    Boolean(orderNo) && Boolean(order) && order?.status !== 'PENDING_PAYMENT' && order?.status !== 'CLOSED';
  const settlementFetcher = useCallback(() => orderApi.settlement(orderNo), [orderNo]);
  const { data: settlement, reload: reloadSettlement, reloadSilently: reloadSettlementSilently } =
    useAsync<SettlementView>(settlementFetcher, canHaveSettlement, [orderNo, canHaveSettlement]);

  /**
   * 订单状态有很多「服务端单方面变化」：超时自动关单、支付回调、商家发货。
   * 停在详情页时不该等用户手动刷新 —— 广播 / 回前台 / 30s 轮询三层兜底（静默，不闪屏）。
   */
  useLiveRefresh(() => {
    reloadOrderSilently();
    if (canHaveSettlement) reloadSettlementSilently();
  });

  // ---- 支付倒计时 ----
  useEffect(() => {
    if (!payOpen || !payResult || payResult.payStatus !== 'PENDING') return undefined;
    const timer = window.setInterval(() => {
      setRemaining((prev) => (prev <= 1 ? 0 : prev - 1));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [payOpen, payResult]);

  // ---- 支付状态轮询 ----
  useEffect(() => {
    if (!payOpen || !payResult || payResult.payStatus !== 'PENDING') return undefined;

    let alive = true;
    const tick = async () => {
      try {
        const latest = await paymentApi.get(payResult.payNo);
        if (!alive) return;
        setPayResult(latest);
        if (latest.payStatus === 'SUCCESS') {
          push('支付成功', 'success');
          await reloadOrder();
          await reloadSettlement();
          await refreshUnread();
          // 广播：支付改变库存/销量，商品详情与秒杀余量立即重拉
          notifyDataChanged();
          setPayOpen(false);
        } else if (latest.payStatus === 'EXPIRED' || latest.payStatus === 'CLOSED') {
          push(latest.payStatus === 'EXPIRED' ? '支付单已过期，请重新下单' : '支付单已关闭', 'error');
          await reloadOrder();
          setPayOpen(false);
        }
      } catch {
        // 轮询失败静默重试，避免高频 toast 干扰用户
      }
    };

    pollRef.current = window.setInterval(() => void tick(), POLL_INTERVAL);
    void tick();

    return () => {
      alive = false;
      if (pollRef.current !== null) {
        window.clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [payOpen, payResult, push, reloadOrder, reloadSettlement, refreshUnread]);

  /** 关闭弹窗时停掉轮询与倒计时 */
  const closePay = useCallback(() => {
    setPayOpen(false);
    if (pollRef.current !== null) {
      window.clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const startPay = async () => {
    setPayError(null);
    setPayBusy(true);
    try {
      const result = await orderApi.pay(orderNo, {
        channel,
        idempotentKey: payKeyRef.current,
      });
      setPayResult(result);
      setRemaining(DEFAULT_TTL_SECONDS);
      setPayOpen(true);

      // MOCK 渠道即时成功，不需要展示二维码
      if (result.payStatus === 'SUCCESS') {
        push('支付成功', 'success');
        await reloadOrder();
        await reloadSettlement();
        await refreshUnread();
        setPayOpen(false);
      }
    } catch (err) {
      setPayError(toFriendlyMessage(err));
    } finally {
      setPayBusy(false);
    }
  };

  /** 本机模拟「扫码后确认支付」——走与真实回调同一条验签链路 */
  const simulateScan = async () => {
    if (!payResult) return;
    setPayError(null);
    setPayBusy(true);
    try {
      const result = await paymentApi.sandboxPay(payResult.payNo);
      setPayResult(result);
      if (result.payStatus === 'SUCCESS') {
        push('支付成功', 'success');
        await reloadOrder();
        await reloadSettlement();
        await refreshUnread();
        // 广播：支付改变库存/销量，商品详情与秒杀余量立即重拉
        notifyDataChanged();
        setPayOpen(false);
      }
    } catch (err) {
      setPayError(toFriendlyMessage(err));
    } finally {
      setPayBusy(false);
    }
  };

  const doCancel = async () => {
    setActionError(null);
    setActing(true);
    try {
      const updated = await orderApi.cancel(orderNo, '买家主动取消');
      setOrder(updated);
      push('订单已取消', 'success');
      await reloadSettlement();
      // 广播（2026-10-19）：取消会释放库存与秒杀配额 —— 首页/详情/秒杀页立即重拉，
      // 不再出现「取消后还显示已抢完、要手动刷新才恢复」
      notifyDataChanged();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setActing(false);
    }
  };

  const doConfirm = async () => {
    setActionError(null);
    setActing(true);
    try {
      const updated = await orderApi.confirm(orderNo);
      setOrder(updated);
      push('已确认收货，货款将结算给卖家', 'success');
      await reloadSettlement();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setActing(false);
    }
  };

  const doRefund = async () => {
    setActionError(null);
    setActing(true);
    try {
      const result = await orderApi.refund(orderNo, '买家申请退款');
      push(`退款成功，退回 ${formatPrice(result.refundAmount)}`, 'success');
      await reloadOrder();
      await reloadSettlement();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setActing(false);
    }
  };

  const countdownText = useMemo(() => {
    const m = Math.floor(remaining / 60);
    const s = remaining % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }, [remaining]);

  if (!orderNo) return <Alert kind="error">订单号缺失</Alert>;
  if (loading && !order) return <LoadingBlock text="正在加载订单…" />;

  if (error && !order) {
    return (
      <Alert kind="error">
        订单加载失败：{error}
        <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={reloadOrder}>
          重试
        </button>
      </Alert>
    );
  }

  if (!order) return null;

  const canPay = order.status === 'PENDING_PAYMENT';
  const canCancel = order.status === 'PENDING_PAYMENT';
  const canConfirm = order.status === 'SHIPPED';
  const canRefund = order.status === 'PAID';
  const custody = settlement ?? order.settlement;
  const settlementMeta = custody ? SETTLEMENT_TEXT[custody.status] : undefined;
  // 是否已进入「已发货 / 已完成」——决定物流轨迹要不要去请求，以及空态该怎么措辞。
  // 同时看状态与 shippedAt：退款后的订单状态会变（REFUNDED），但包裹可能还在路上，
  // 只认状态会把一条真实存在的轨迹藏起来。
  const shipped =
    Boolean(order.shippedAt) || order.status === 'SHIPPED' || order.status === 'COMPLETED';

  return (
    <div>
      <div className="small muted" style={{ marginBottom: 12 }}>
        <Link to="/">首页</Link> / <Link to="/orders">我的订单</Link> /{' '}
        <span>{order.orderNo}</span>
      </div>

      <div className="section-head" style={{ marginTop: 4 }}>
        <h2 className="section-title">
          订单 {order.orderNo}
          <span className="tag" style={{ marginLeft: 10 }}>
            {order.statusLabel}
          </span>
        </h2>
        <span className="section-sub">下单于 {formatDate(order.createdAt)}</span>
      </div>

      <StatusSteps status={order.status} />

      {actionError ? (
        <div style={{ margin: '14px 0' }}>
          <Alert kind="error">{actionError}</Alert>
        </div>
      ) : null}

      {custody && settlementMeta ? (
        <div style={{ margin: '14px 0' }}>
          <Alert kind={settlementMeta.kind}>{settlementMeta.label}</Alert>
        </div>
      ) : null}

      {canPay && order.expireAt ? (
        <div style={{ margin: '14px 0' }}>
          <Alert kind="warn">
            请在 {formatDate(order.expireAt)} 前完成支付，超时订单会自动关闭并释放库存。
          </Alert>
        </div>
      ) : null}

      {/* 物流轨迹：买家打开订单详情，最想知道的就是「我的货到哪了」，
          所以放在收货信息之前 —— 而不是埋在页面底部。数据来自履约域（A2）。 */}
      <LogisticsTimeline orderNo={orderNo} shipped={shipped} />

      <div className="grid grid-2" style={{ alignItems: 'start', marginTop: 16 }}>
        <div>
          <div className="card card-pad">
            <h3 style={{ marginBottom: 12 }}>收货信息</h3>
            <dl className="spec-list">
              <dt>收货人</dt>
              <dd>{order.receiverName}</dd>
              <dt>手机号</dt>
              <dd>{order.receiverPhone}</dd>
              <dt>收货地址</dt>
              <dd>{order.receiverAddress}</dd>
              <dt>备注</dt>
              <dd>{order.remark ?? '—'}</dd>
              {/* 已发货后才有值：买家问「我的货到哪了」时，这里就是答案 */}
              {order.logisticsNo ? (
                <>
                  <dt>物流公司</dt>
                  <dd>{order.logisticsCompany ?? '—'}</dd>
                  <dt>物流单号</dt>
                  <dd>{order.logisticsNo}</dd>
                </>
              ) : null}
            </dl>
          </div>

          <div className="card card-pad" style={{ marginTop: 16 }}>
            <h3 style={{ marginBottom: 12 }}>商品清单</h3>
            <div className="stack">
              {order.items.map((item) => (
                <div className="row-between row-wrap" key={item.skuId}>
                  <div className="row" style={{ gap: 10, minWidth: 0, flex: 1 }}>
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
                    <div style={{ minWidth: 0 }}>
                      <Link className="goods-title" to={`/product/${item.spuId}`}>
                        {item.title}
                      </Link>
                      <div className="small muted">
                        {item.specText ?? '默认规格'} · {formatPrice(item.price)} × {item.quantity}
                      </div>
                    </div>
                  </div>
                  <span style={{ color: 'var(--danger)' }}>{formatPrice(item.amount)}</span>
                </div>
              ))}
            </div>
          </div>

          {custody ? (
            <div className="card card-pad" style={{ marginTop: 16 }}>
              <div className="row-between">
                <h3>资金托管明细</h3>
                <span className="tag">{custody.statusLabel}</span>
              </div>
              <dl className="spec-list" style={{ marginTop: 10 }}>
                <dt>订单金额</dt>
                <dd>{formatPrice(custody.amount)}</dd>
                <dt>平台服务费</dt>
                <dd>{formatPrice(custody.platformFee)}</dd>
                <dt>卖家实收</dt>
                <dd>{formatPrice(custody.sellerAmount)}</dd>
                <dt>预计放款时间</dt>
                <dd>{custody.releaseAt ? formatDate(custody.releaseAt) : '—'}</dd>
                <dt>实际放款时间</dt>
                <dd>{custody.releasedAt ? formatDate(custody.releasedAt) : '尚未放款'}</dd>
              </dl>
              <div className="small muted" style={{ marginTop: 8 }}>
                卖家实收 = 订单金额 − 平台服务费，由服务端计算，前端不重复扣减。
              </div>
            </div>
          ) : null}
        </div>

        <div>
          <div className="card card-pad">
            <h3 style={{ marginBottom: 12 }}>金额与时间</h3>
            <dl className="spec-list">
              <dt>商品总额</dt>
              <dd>{formatPrice(order.totalAmount)}</dd>
              <dt>运费</dt>
              <dd>{formatPrice(order.freightAmount)}</dd>
              <dt>优惠</dt>
              <dd>−{formatPrice(order.discountAmount)}</dd>
              {order.couponCode ? (
                <>
                  <dt>优惠券</dt>
                  <dd>
                    <code>{order.couponCode}</code>
                  </dd>
                </>
              ) : null}
              <dt>应付金额</dt>
              <dd>
                <strong style={{ color: 'var(--danger)', fontSize: 18 }}>
                  {formatPrice(order.payAmount)}
                </strong>
              </dd>
            </dl>
            <div className="divider" />
            <dl className="spec-list">
              <dt>下单</dt>
              <dd>{formatDate(order.createdAt)}</dd>
              <dt>付款</dt>
              <dd>{order.paidAt ? formatDate(order.paidAt) : '—'}</dd>
              <dt>发货</dt>
              <dd>{order.shippedAt ? formatDate(order.shippedAt) : '—'}</dd>
              <dt>完成</dt>
              <dd>{order.finishedAt ? formatDate(order.finishedAt) : '—'}</dd>
              {order.closedAt ? (
                <>
                  <dt>关闭</dt>
                  <dd>
                    {formatDate(order.closedAt)}
                    {order.closeReason ? ` · ${order.closeReason}` : ''}
                  </dd>
                </>
              ) : null}
              {order.refundedAt ? (
                <>
                  <dt>退款</dt>
                  <dd>{formatDate(order.refundedAt)}</dd>
                </>
              ) : null}
            </dl>
          </div>

          <div className="card card-pad" style={{ marginTop: 16 }}>
            <h3 style={{ marginBottom: 12 }}>可执行操作</h3>

            {canPay ? (
              <>
                <div className="field-label">支付渠道</div>
                <div className="row row-wrap" style={{ marginBottom: 12 }}>
                  {CHANNELS.map((c) => (
                    <button
                      key={c.value}
                      type="button"
                      className={`tag${channel === c.value ? ' tag-active' : ''}`}
                      onClick={() => setChannel(c.value)}
                    >
                      {c.label}
                    </button>
                  ))}
                </div>
              </>
            ) : null}

            {payError && canPay ? (
              <div style={{ marginBottom: 12 }}>
                <Alert kind="error">{payError}</Alert>
              </div>
            ) : null}

            <div className="row row-wrap">
              {canPay ? (
                <button className="btn btn-pay" disabled={acting || payBusy} onClick={() => void startPay()}>
                  {payBusy ? '正在创建支付单…' : '去支付'}
                </button>
              ) : null}

              {canCancel ? (
                <button className="btn btn-danger-solid" disabled={acting} onClick={() => void doCancel()}>
                  取消订单
                </button>
              ) : null}

              {canConfirm ? (
                <button className="btn btn-primary" disabled={acting} onClick={() => void doConfirm()}>
                  确认收货
                </button>
              ) : null}

              {canRefund ? (
                <button className="btn btn-danger" disabled={acting} onClick={() => void doRefund()}>
                  申请退款
                </button>
              ) : null}

              {/* 位置与文案与订单列表一致：取消 → 问客服 → 其他写操作 → 刷新 */}
              <OrderConsultButton orderNo={order.orderNo} sellerId={order.sellerId} />
              <SupportEntry />

              <button className="btn btn-ghost" disabled={acting} onClick={() => void reloadOrder()}>
                刷新
              </button>
            </div>

            {canRefund ? (
              <div className="small muted" style={{ marginTop: 10 }}>
                仅在「已付款且卖家未发货」时可自助退款；卖家已发货时需走售后流程。
              </div>
            ) : null}
            {canConfirm ? (
              <div className="small muted" style={{ marginTop: 10 }}>
                确认收货是不可撤销操作，确认后货款将结算给卖家。
              </div>
            ) : null}
          </div>
        </div>
      </div>

      <Modal
        open={payOpen}
        title={payResult?.payStatus === 'SUCCESS' ? '支付结果' : '扫码支付'}
        onClose={closePay}
        footer={
          <>
            <button className="btn btn-ghost" type="button" onClick={closePay}>
              {payResult?.payStatus === 'PENDING' ? '稍后再付' : '关闭'}
            </button>
            {payResult?.payStatus === 'PENDING' && payResult.channel === 'SANDBOX' ? (
              <button className="btn btn-primary" type="button" disabled={payBusy} onClick={() => void simulateScan()}>
                {payBusy ? '处理中…' : '本机模拟扫码'}
              </button>
            ) : null}
          </>
        }
      >
        {payResult ? (
          <div style={{ textAlign: 'center' }}>
            <div className="row-between">
              <span className="small muted">支付单号 {payResult.payNo}</span>
              <span className="tag">{payResult.channel}</span>
            </div>

            <div style={{ margin: '14px 0 6px', fontSize: 26, fontWeight: 700, color: 'var(--danger)' }}>
              {formatPrice(payResult.amount)}
            </div>

            {payResult.payStatus === 'PENDING' ? (
              <>
                {payResult.channel === 'ALIPAY_PC' && payResult.codeUrl ? (
                  <div style={{ margin: '14px 0' }}>
                    {/* 支付宝 page.pay 返回的是自动提交表单 HTML（不是 URL）：
                        注入 DOM 后以新标签提交，收银台在新页打开，本页轮询不受影响 */}
                    <button
                      className="btn btn-pay"
                      type="button"
                      onClick={() => submitAlipayForm(payResult.codeUrl ?? '')}
                    >
                      跳转支付宝付款
                    </button>
                    <div className="small muted" style={{ marginTop: 8 }}>
                      将打开支付宝收银台完成付款；付款后本页会自动查到支付结果，
                      入账以支付宝异步回调为准。
                    </div>
                  </div>
                ) : payResult.codeUrl ? (
                  <div style={{ margin: '14px 0' }}>
                    <QRCode value={payResult.codeUrl} size={200} />
                    <div className="small muted" style={{ marginTop: 8 }}>
                      用手机扫码完成支付（沙箱渠道会打开平台收银台）
                    </div>
                  </div>
                ) : null}

                <div className="row" style={{ justifyContent: 'center', gap: 8 }}>
                  <span className="small muted">支付剩余时间</span>
                  <strong>{countdownText}</strong>
                </div>

                <div className="small muted" style={{ marginTop: 8 }}>
                  页面每 {POLL_INTERVAL / 1000} 秒自动查询一次支付结果，无需手动刷新。
                  演示环境可用「本机模拟扫码」代替第二台设备。
                </div>

                {payResult.codeUrl ? (
                  <div className="small muted" style={{ marginTop: 10, wordBreak: 'break-all' }}>
                    二维码内容：{payResult.codeUrl}
                  </div>
                ) : null}
              </>
            ) : (
              <div style={{ marginTop: 16 }}>
                <Alert
                  kind={payResult.payStatus === 'SUCCESS' ? 'success' : 'error'}
                >
                  支付状态：{payResult.payStatus} · 订单状态：{payResult.orderStatusLabel}
                </Alert>
              </div>
            )}
          </div>
        ) : null}
      </Modal>
    </div>
  );
};


/**
 * 「咨询订单」按钮（C4）：把订单卡发给接待该订单的商家客服。
 *
 * <p>为什么先发消息再跳转、而不是在客服页填个输入框：
 * 用户点这个按钮的意图就是「就这单有问题」，卡片必须已经在会话里，
 * 否则跳过去还要再选一次商品/订单，等于把一步做成两步。
 */
const OrderConsultButton: React.FC<{ orderNo: string; sellerId?: string | number | null }> = ({
  orderNo,
  sellerId,
}) => {
  const navigate = useNavigate();
  const [busy, setBusy] = React.useState(false);

  /** 自营订单 sellerId=0：0 不是店铺，不带参走自营客服（2026-10-22 修复「店铺不存在」） */
  const sellerQuery = sellerId != null && String(sellerId) !== '0' && String(sellerId) !== ''
    ? `?sellerId=${sellerId}`
    : '';
  const gotoSupport = () => navigate(`/support${sellerQuery}`);

  const consult = async () => {
    if (busy) return;
    setBusy(true);
    try {
      await supportApi.send({ type: 'ORDER', content: '', orderNo });
      gotoSupport();
    } catch {
      // 失败时仍然把用户带到客服页：消息没发出去，但人不能卡在订单页
      gotoSupport();
    } finally {
      setBusy(false);
    }
  };

  return (
    <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => void consult()}>
      咨询订单
    </button>
  );
};

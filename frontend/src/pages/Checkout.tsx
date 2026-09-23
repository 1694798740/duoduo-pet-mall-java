/**
 * 结算页。
 *
 * 流程：
 *   1. GET /api/cart 拿到「已勾选且有效」的条目与 selectedAmount（服务端口径）
 *   2. GET /api/coupons/mine/usable?amount=selectedAmount 拿可用券与**服务端推荐的**最优券
 *      · 门槛 / 有效期 / 是否已被别的订单锁住这三件事都在服务端，前端只负责展示与选择
 *      · 「未选择」与「明确不用券」是两个不同状态（undefined vs null），不能合并
 *   3. 填写收货信息 → POST /api/orders
 *      · 请求体含 receiverName / receiverPhone / receiverAddress / remark / couponCode / idempotentKey
 *      · **只传券码、不传优惠金额** —— 抵扣多少由营销域按券模板算出、交易域落账
 *      · **没有 addressId**，本期不做地址簿
 *      · idempotentKey 在进入本页时生成一次并全程复用：网络重试或用户连点两次
 *        都会命中同一个键，后端只会创建一个订单
 *   4. 下单成功后清空已勾选条目，跳转到订单详情继续支付
 *
 * 后端会按卖家拆单（sellerId 维度），因此一次提交可能产出多个订单；
 * 一张券只作用在**商品总额最大**的那一单（v1 口径），因此上面的「应付合计」
 * 等于各订单 payAmount 之和，与所选券的抵扣一致。
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { newIdempotentKey, toFriendlyMessage } from '../api/client';
import { cartApi, couponApi, orderApi } from '../api/endpoints';
import type { CartView, PlaceOrderResult, UsableCouponResult } from '../api/types';
import { useAsync } from '../components/hooks';
import { Alert, Field, LoadingBlock, formatPrice, useToast } from '../components/ui';
import { useApp } from '../store/app';

const PHONE_PATTERN = /^1[3-9]\d{9}$/;

export const CheckoutPage: React.FC = () => {
  const navigate = useNavigate();
  const { push } = useToast();
  const { refreshCart } = useApp();

  const [receiverName, setReceiverName] = useState('');
  const [receiverPhone, setReceiverPhone] = useState('');
  const [receiverAddress, setReceiverAddress] = useState('');
  const [remark, setRemark] = useState('');
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<PlaceOrderResult | null>(null);
  /**
   * 选中的券码。
   *
   * `undefined` = 用户还没做选择（此时默认采用服务端推荐的最优券）；
   * `null` = 用户**主动选择不使用**券。两者必须区分开：把「未选择」和「明确不用」
   * 混成一个值，用户取消选券后页面会立刻又帮他选回去。
   */
  const [couponChoice, setCouponChoice] = useState<string | null | undefined>(undefined);

  // 幂等键：进入结算页生成一次，提交失败重试时保持不变
  const idempotentKeyRef = useRef<string>(newIdempotentKey());

  const fetcher = useCallback(() => cartApi.list(), []);
  const { data, loading, error, reload } = useAsync<CartView>(fetcher);

  const selected = (data?.items ?? []).filter((i) => i.selected && i.valid);
  const selectedAmount = data?.selectedAmount ?? 0;

  /**
   * 可用券：金额取服务端的 selectedAmount（购物车口径），由服务端判断门槛与有效期。
   * 前端自己拿券包算会出现「页面说能用、下单说不能用」，所以这里只负责展示与选择。
   */
  const usableFetcher = useCallback(
    () => (selectedAmount > 0 ? couponApi.usable(selectedAmount) : Promise.resolve(null)),
    [selectedAmount],
  );
  const { data: usable, loading: usableLoading } = useAsync<UsableCouponResult | null>(
    usableFetcher,
    true,
    [selectedAmount],
  );

  const coupons = usable?.coupons ?? [];
  // 服务端推荐的最优券（抵扣最大的可用券）；服务端为唯一判定方，前端不重算
  const bestCode = usable?.bestCouponCode ?? null;
  const effectiveCode = couponChoice === undefined ? bestCode : couponChoice;
  const chosen = coupons.find((c) => c.code === effectiveCode && c.usable) ?? null;
  const discountAmount = chosen ? chosen.discountAmount : 0;
  const payAmount = Math.max(0, selectedAmount - discountAmount);

  // 全部所选条目失效（或被清空）时，不允许停留在结算页盲目提交
  useEffect(() => {
    if (data && selected.length === 0 && !result) {
      setFormError('没有可结算的商品，请回到购物车重新勾选');
    }
  }, [data, selected.length, result]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);

    if (!selected.length) {
      setFormError('没有可结算的商品');
      return;
    }
    if (!receiverName.trim()) {
      setFormError('请填写收货人姓名');
      return;
    }
    if (!PHONE_PATTERN.test(receiverPhone.trim())) {
      setFormError('请填写合法的 11 位收货手机号');
      return;
    }
    if (receiverAddress.trim().length < 6) {
      setFormError('收货地址太短，请填写详细地址');
      return;
    }

    setBusy(true);
    try {
      const placed = await orderApi.place({
        receiverName: receiverName.trim(),
        receiverPhone: receiverPhone.trim(),
        receiverAddress: receiverAddress.trim(),
        remark: remark.trim() || undefined,
        // 只传券码，不传优惠金额：能抵多少由营销域算、交易域落账
        couponCode: chosen ? chosen.code : undefined,
        idempotentKey: idempotentKeyRef.current,
      });
      setResult(placed);

      // 下单成功后清空已勾选条目（服务端已把它们从购物车移除）
      try {
        await cartApi.removeSelected();
      } catch {
        // 清购物车失败不影响下单结果，静默即可
      }
      await refreshCart();

      if (placed.idempotentReplay) {
        push('该笔订单已提交过，为你展示已有订单', 'info');
      } else {
        push(`下单成功，共 ${placed.orderCount} 笔订单`, 'success');
      }
    } catch (err) {
      setFormError(toFriendlyMessage(err));
      // 失败后换新键？不换 —— 若失败原因是超时而非业务拒绝，
      // 复用同一个键能避免重复下单；真正的业务拒绝也不会有副作用。
    } finally {
      setBusy(false);
    }
  };

  if (loading && !data) return <LoadingBlock text="正在准备结算信息…" />;

  if (error && !data) {
    return (
      <Alert kind="error">
        结算信息加载失败：{error}
        <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={reload}>
          重试
        </button>
      </Alert>
    );
  }

  // 下单成功：展示拆单结果，逐个引导去支付
  if (result) {
    return (
      <div>
        <div className="section-head" style={{ marginTop: 8 }}>
          <h2 className="section-title">下单成功</h2>
          <span className="section-sub">
            {result.idempotentReplay ? '命中幂等键，以下为已存在的订单' : `共 ${result.orderCount} 笔订单`}
          </span>
        </div>

        <Alert kind="success">
          订单已创建，请在 30 分钟内完成支付，超时订单会自动关闭并释放库存。
          应付合计 <strong>{formatPrice(result.totalPayAmount)}</strong>。
        </Alert>

        <div className="stack" style={{ marginTop: 16 }}>
          {result.orders.map((order) => (
            <div className="card card-pad" key={order.orderNo}>
              <div className="row-between row-wrap">
                <strong>订单号 {order.orderNo}</strong>
                <span className="tag">{order.statusLabel}</span>
              </div>
              <div className="small muted" style={{ marginTop: 6 }}>
                {order.items.length} 件商品 · 应付{' '}
                <span style={{ color: 'var(--danger)', fontWeight: 600 }}>
                  {formatPrice(order.payAmount)}
                </span>
              </div>
              <div className="row row-wrap" style={{ marginTop: 12 }}>
                <button
                  className="btn btn-primary btn-sm"
                  onClick={() => navigate(`/orders/${order.orderNo}`)}
                >
                  去支付 / 查看详情
                </button>
              </div>
            </div>
          ))}
        </div>

        <div className="row row-wrap" style={{ marginTop: 18 }}>
          <Link className="btn btn-ghost" to="/orders">
            我的订单
          </Link>
          <Link className="btn btn-ghost" to="/">
            继续逛逛
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">确认订单</h2>
        <span className="section-sub">共 {selected.length} 件商品</span>
      </div>

      {formError ? (
        <div style={{ marginBottom: 14 }}>
          <Alert kind="error">{formError}</Alert>
        </div>
      ) : null}

      <div className="checkout-layout">
        <div>
          <div className="card card-pad">
            <h3 style={{ marginBottom: 12 }}>收货信息</h3>
            <form id="checkout-form" onSubmit={submit}>
              <div className="grid grid-2">
                <Field label="收货人" required>
                  <input
                    className="input"
                    value={receiverName}
                    onChange={(e) => setReceiverName(e.target.value)}
                    placeholder="姓名"
                    maxLength={30}
                  />
                </Field>
                <Field label="手机号" required>
                  <input
                    className="input"
                    value={receiverPhone}
                    onChange={(e) => setReceiverPhone(e.target.value.replace(/\D/g, '').slice(0, 11))}
                    placeholder="11 位手机号"
                    inputMode="numeric"
                  />
                </Field>
              </div>

              <Field label="收货地址" required hint="本期不支持地址簿，请直接填写完整地址">
                <textarea
                  className="textarea"
                  rows={3}
                  value={receiverAddress}
                  onChange={(e) => setReceiverAddress(e.target.value)}
                  placeholder="省 / 市 / 区 + 详细地址"
                  maxLength={180}
                />
              </Field>

              <Field label="订单备注">
                <input
                  className="input"
                  value={remark}
                  onChange={(e) => setRemark(e.target.value)}
                  placeholder="选填，例如「工作日送，放快递柜」"
                  maxLength={120}
                />
              </Field>
            </form>
          </div>

          <div className="card card-pad" style={{ marginTop: 16 }}>
            <h3 style={{ marginBottom: 12 }}>商品清单</h3>
            <div className="stack">
              {selected.map((item) => (
                <div className="row-between row-wrap" key={item.skuId}>
                  <div className="row" style={{ gap: 10, minWidth: 0, flex: 1 }}>
                    {item.image ? (
                      <img
                        src={item.image}
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
                    <div style={{ minWidth: 0 }}>
                      <div className="goods-title">{item.title}</div>
                      <div className="small muted">
                        {item.specText ?? '默认规格'} · × {item.quantity}
                      </div>
                    </div>
                  </div>
                  <span style={{ color: 'var(--danger)' }}>{formatPrice(item.amount)}</span>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div>
          <div className="card card-pad">
            <h3 style={{ marginBottom: 12 }}>优惠券</h3>

            {usableLoading ? <div className="small muted">正在查询可用券…</div> : null}

            {!usableLoading && coupons.length === 0 ? (
              <div className="small muted">
                没有可用的优惠券。<Link to="/coupons">去领券中心看看</Link>
              </div>
            ) : null}

            {coupons.length ? (
              <div className="stack" style={{ gap: 10 }}>
                <label className="row" style={{ gap: 8, alignItems: 'center', cursor: 'pointer' }}>
                  <input
                    type="radio"
                    name="coupon"
                    checked={effectiveCode === null}
                    onChange={() => setCouponChoice(null)}
                  />
                  <span>不使用优惠券</span>
                </label>

                {coupons.map((c) => (
                  <label
                    key={c.code}
                    className="row row-wrap"
                    style={{ gap: 8, alignItems: 'center', cursor: c.usable ? 'pointer' : 'not-allowed' }}
                  >
                    <input
                      type="radio"
                      name="coupon"
                      disabled={!c.usable}
                      checked={effectiveCode === c.code}
                      onChange={() => setCouponChoice(c.code)}
                    />
                    <span style={{ minWidth: 0 }}>
                      {c.name} ·{' '}
                      {c.thresholdAmount > 0
                        ? `满 ${formatPrice(c.thresholdAmount)} 可用`
                        : '无门槛'}{' '}
                      · 抵 {formatPrice(c.discountAmount)}
                      {bestCode === c.code ? (
                        <span className="tag tag-success" style={{ marginLeft: 6 }}>
                          推荐
                        </span>
                      ) : null}
                      {/* 不可用原因由服务端给出：用户要知道「还差多少钱」，而不是看到一个灰掉的选项 */}
                      {!c.usable && c.reason ? (
                        <span className="small muted" style={{ marginLeft: 6 }}>
                          {c.reason}
                        </span>
                      ) : null}
                    </span>
                  </label>
                ))}
              </div>
            ) : null}
          </div>

          <div className="card card-pad" style={{ marginTop: 16 }}>
            <h3 style={{ marginBottom: 12 }}>费用明细</h3>
            <dl className="spec-list">
              <dt>商品总额</dt>
              <dd>{formatPrice(selectedAmount)}</dd>
              <dt>运费</dt>
              <dd>{formatPrice(0)}</dd>
              <dt>优惠</dt>
              <dd style={{ color: discountAmount > 0 ? 'var(--success)' : undefined }}>
                {discountAmount > 0 ? `-${formatPrice(discountAmount)}` : formatPrice(0)}
              </dd>
            </dl>
            <div className="divider" />
            <div className="row-between">
              <span>应付金额</span>
              <span style={{ fontSize: 22, fontWeight: 700, color: 'var(--danger)' }}>
                {formatPrice(payAmount)}
              </span>
            </div>

            <button className="btn btn-primary btn-block" type="submit" form="checkout-form" disabled={busy}>
              {busy ? '提交中…' : '提交订单'}
            </button>

            <div className="small muted" style={{ marginTop: 10 }}>
              提交后进入支付页，30 分钟内未支付订单将自动关闭。
              提交按钮重复点击不会重复下单（幂等键已固定）。
            </div>
          </div>

          <div className="card card-pad" style={{ marginTop: 16 }}>
            <div className="small muted">
              收费口径：金额单位为「分」，展示时统一折算为元。
              下单时<b>券被锁定</b>（此时还不能给别人用），支付成功才核销、取消订单自动退回；
              购物车含多家卖家时，一张券只作用在<b>商品总额最大</b>的那一单（v1 口径）。
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

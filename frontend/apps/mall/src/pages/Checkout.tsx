/**
 * 结算页。
 *
 * 流程：
 *   1. GET /api/cart 拿到「已勾选且有效」的条目与 selectedAmount（服务端口径）
 *   2. GET /api/coupons/mine/usable?amount=selectedAmount 拿可用券与**服务端推荐的**最优券
 *      · 门槛 / 有效期 / 是否已被别的订单锁住这三件事都在服务端，前端只负责展示与选择
 *      · 「未选择」与「明确不用券」是两个不同状态（undefined vs null），不能合并
 *   3. GET /api/addresses 拿地址簿（A8），自动选中默认地址
 *   4. 提交 → POST /api/orders，收货信息**二选一**：
 *      · 有选中地址 → 只传 `addressId`，服务端去用户域取地址并写入订单快照；
 *      · 选择「手动填写」 → 传 receiverName / receiverPhone / receiverAddress（旧流程）。
 *      两种都传时服务端以 addressId 为准，因此这里**只会传其中一种**，不去试探优先级。
 *      · 请求体含 remark / couponCode / idempotentKey
 *      · **只传券码、不传优惠金额** —— 抵扣多少由营销域按券模板算出、交易域落账
 *      · idempotentKey 在进入本页时生成一次并全程复用：网络重试或用户连点两次
 *        都会命中同一个键，后端只会创建一个订单
 *   5. 下单成功后清空已勾选条目，跳转到订单详情继续支付
 *
 * 后端会按卖家拆单（sellerId 维度），因此一次提交可能产出多个订单；
 * 一张券只作用在**商品总额最大**的那一单（v1 口径），因此上面的「应付合计」
 * 等于各订单 payAmount 之和，与所选券的抵扣一致。
 *
 * A8 的一个刻意取舍：**地址簿为空时不阻塞下单**。
 * 新用户第一次下单通常还没有地址，若强制先去建地址，等于给首单加了一道墙。
 * 因此界面上「地址簿 + 手动填写」始终并存，只是有地址时预选默认地址。
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { newIdempotentKey, toFriendlyMessage } from '@duoduo/api-sdk/client';
import { addressApi, cartApi, couponApi, orderApi } from '@duoduo/api-sdk/endpoints';
import type { AddressView, CartView, PlaceOrderResult, UsableCouponResult } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import { Alert, Field, LoadingBlock, formatPrice, useToast } from '@duoduo/shared/components/ui';
import { useApp } from '@duoduo/shared/store/app';

const PHONE_PATTERN = /^1[3-9]\d{9}$/;

export const CheckoutPage: React.FC = () => {
  const navigate = useNavigate();
  const { push } = useToast();
  const { refreshCart, refreshUnread } = useApp();

  const [receiverName, setReceiverName] = useState('');
  const [receiverPhone, setReceiverPhone] = useState('');
  const [receiverAddress, setReceiverAddress] = useState('');
  /** 手动填写模式：地址簿为空、或用户主动选择「手动填写」时为 true */
  const [manualEntry, setManualEntry] = useState(false);
  /**
   * 选中的地址簿条目 id；null = 未选中任何已存地址（此时走手动填写）。
   *
   * ⚠️ 类型是 **string**：地址 id 是 18 位雪花值，转成 JS number 会被静默四舍五入，
   * 拼进 URL 就查不到那条地址（T-39）。这里只做「相等比较」与「拼 URL」，不参与运算。
   */
  const [selectedAddressId, setSelectedAddressId] = useState<string | null>(null);
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

  /**
   * 地址簿（A8）。
   *
   * 单独取一次列表而不是只调 /default：用户需要在结算页**切换**地址，
   * 只拿默认地址会导致「想换一个就得先去地址簿页」——那正是本需求要消灭的摩擦。
   */
  const addrFetcher = useCallback(() => addressApi.list(), []);
  const { data: addressList, loading: addrLoading } = useAsync<AddressView[]>(addrFetcher);
  const addresses = useMemo(() => addressList ?? [], [addressList]);

  /**
   * 地址簿加载完成后决定初始选中项 —— 只做一次，不覆盖用户之后的手动选择。
   *
   * 用 ref 记住「是否已初始化」而不是把 selectedAddressId 放进依赖数组：
   * 后者会在用户每次切换地址后又跑一遍，把选择重置回默认地址。
   *
   * ⚠ 这里必须用 `addrLoading` 门控，不能只判 `addressList == null`：
   *   useAsync 的 data 初值是 **undefined**（不是 null），所以"还没回来"与
   *   "回来了但是空列表"两者都表现为 falsy。若只判 null，首次渲染时
   *   addresses 会被当成空数组 → 直接把用户推进「手动填写」模式，
   *   地址簿里的地址虽然渲染出来了却选不上（A8 UI e2e 抓到过这个 bug：
   *   页面上「UI张三」在列表里、单选却是空的，点提交报「请填写收货人姓名」）。
   */
  const addrInitializedRef = useRef(false);
  useEffect(() => {
    // 还在加载、或加载失败（addressList 仍为 undefined）时不要下结论
    if (addrInitializedRef.current || addrLoading || addressList === undefined) return;
    addrInitializedRef.current = true;
    if (addresses.length === 0) {
      // 确认过地址簿确实是空的：才进手动填写，不让用户先去建地址（首单不能被挡住）
      setManualEntry(true);
      return;
    }
    const preferred = addresses.find((a) => a.isDefault) ?? addresses[0];
    // addresses 非空已在上面的分支排除，这里再兜一次默认地址缺失的情况（防御性，不用 ! 断言）
    if (preferred) setSelectedAddressId(preferred.id);
  }, [addressList, addresses, addrLoading]);

  const selectedAddress = addresses.find((a) => a.id === selectedAddressId) ?? null;

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

    // 收货信息校验按「用地址簿」还是「手动填写」分两条路走。
    // 只有手动填写才校验三个字段 —— 用已存地址时这些输入框根本不显示，
    // 若在这里一并校验，会报出一个用户根本没机会填的字段的错误。
    if (!manualEntry) {
      if (!selectedAddress) {
        setFormError('请选择一个收货地址，或改为手动填写');
        return;
      }
    } else {
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
    }

    setBusy(true);
    try {
      const placed = await orderApi.place({
        // ★ 二选一：用地址簿时只传 addressId，手动时只传三个字段。
        //   不把两种一起传过去「让后端挑」——虽然后端定了 addressId 优先，
        //   但前端多传一份用不上的收货信息，会在日志里留下与真实来源不符的痕迹。
        addressId: manualEntry ? undefined : (selectedAddress?.id ?? undefined),
        receiverName: manualEntry ? receiverName.trim() : undefined,
        receiverPhone: manualEntry ? receiverPhone.trim() : undefined,
        receiverAddress: manualEntry ? receiverAddress.trim() : undefined,
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
      // 下单会生成「订单已提交」通知：与购物车角标同一口径，立刻刷新，不等轮询
      await refreshUnread();

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
            <div className="row-between" style={{ marginBottom: 12 }}>
              <h3>收货信息</h3>
              <Link className="small text-brand" to="/addresses">
                管理地址簿
              </Link>
            </div>

            {/* 地址簿为空时不渲染空壳，直接给出手动填写 —— 首单不该被「先去建地址」挡住 */}
            {/* 注意：useAsync 未回来时 data 是 undefined（不是 null），loading 用 addrLoading 自身判断即可 */}
            {addrLoading ? (
              <div className="small muted" style={{ marginBottom: 12 }}>
                正在加载地址簿…
              </div>
            ) : null}

            {addresses.length > 0 ? (
              <div className="stack" style={{ gap: 10, marginBottom: 12 }}>
                {addresses.map((addr) => (
                  <label
                    key={addr.id}
                    className="row row-wrap card card-pad"
                    style={{
                      gap: 10,
                      alignItems: 'flex-start',
                      cursor: 'pointer',
                      margin: 0,
                      borderColor:
                        !manualEntry && selectedAddressId === addr.id
                          ? 'var(--brand)'
                          : undefined,
                    }}
                  >
                    <input
                      type="radio"
                      name="address"
                      style={{ marginTop: 4 }}
                      checked={!manualEntry && selectedAddressId === addr.id}
                      onChange={() => {
                        setManualEntry(false);
                        setSelectedAddressId(addr.id);
                      }}
                    />
                    <span style={{ minWidth: 0, flex: 1 }}>
                      <span className="row row-wrap" style={{ gap: 6 }}>
                        <strong>{addr.receiverName}</strong>
                        <span className="small muted">{addr.receiverPhone}</span>
                        {addr.label ? <span className="tag">{addr.label}</span> : null}
                        {addr.isDefault ? <span className="tag tag-success">默认</span> : null}
                      </span>
                      {/* 用服务端拼好的 fullAddress，不自己拼（直辖市去重只在服务端一处） */}
                      <span className="small" style={{ display: 'block', marginTop: 4, lineHeight: 1.6 }}>
                        {addr.fullAddress}
                      </span>
                    </span>
                  </label>
                ))}

                <label className="row" style={{ gap: 8, alignItems: 'center', cursor: 'pointer' }}>
                  <input
                    type="radio"
                    name="address"
                    checked={manualEntry}
                    onChange={() => {
                      setManualEntry(true);
                      setSelectedAddressId(null);
                    }}
                  />
                  <span>手动填写其他地址</span>
                </label>
              </div>
            ) : null}

            {manualEntry ? (
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

                <Field label="收货地址" required hint="本次填写的地址不会自动存入地址簿">
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
            ) : (
              // 用已存地址时表单仍要存在：提交按钮挂在 form 上，缺了它按钮就成了死按钮。
              // remark 也放在这里 —— 它在两种模式下都要能填。
              <form id="checkout-form" onSubmit={submit}>
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
            )}

            {addresses.length === 0 && !addrLoading ? (
              <div className="small muted" style={{ marginTop: 4 }}>
                还没有保存的收货地址。
                <Link to="/addresses" className="text-brand" style={{ marginLeft: 4 }}>
                  去添加
                </Link>
                ，下次下单就能直接选用。
              </div>
            ) : null}
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

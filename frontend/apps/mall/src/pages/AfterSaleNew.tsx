/**
 * 申请售后（买家，A3）。
 *
 * 入口只挂在订单上（订单列表 / 订单详情 → 「申请售后」），不在导航里另开「售后中心」：
 * 售后总是从「我买过的那单」出发 —— 这与用户的心智一致，也是主流电商的形态。
 *
 * 表单只收集**诉求本身**（类型 / 描述 / 举证图）：
 * 归属（买家是谁）与订单状态全部由服务端从订单事实取，请求体里没有 buyerId 可传。
 * 页面上的「订单状态是否可售后」提示是**体验优化**，真正的判定在后端（422）。
 */

import React, { useCallback, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { afterSaleApi, orderApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Field, LoadingBlock, formatDate, formatPrice, useToast } from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';
import { AFTER_SALE_MAX_IMAGES, AFTER_SALE_TYPE_OPTIONS, splitImageLines } from './afterSaleShared';
import type { AfterSaleType } from '@duoduo/api-sdk/types';

/** 可申请售后的订单状态（与后端 AfterSaleService.ALLOWED_ORDER_STATUS 同口径） */
const ELIGIBLE_STATUS = ['SHIPPED', 'COMPLETED'];

export const AfterSaleNewPage: React.FC = () => {
  const [params] = useSearchParams();
  const orderNo = params.get('orderNo') ?? '';
  const navigate = useNavigate();
  const { push } = useToast();

  const [type, setType] = useState<AfterSaleType>('REFUND_ONLY');
  const [reason, setReason] = useState('');
  const [imageText, setImageText] = useState('');
  const [busy, setBusy] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const { data: order, loading, error } = useAsync(
    useCallback(() => orderApi.detail(orderNo), [orderNo]),
    orderNo.length > 0,
    [orderNo],
  );

  const eligible = order ? ELIGIBLE_STATUS.includes(order.status) : false;
  const images = splitImageLines(imageText);

  const submit = async () => {
    setSubmitError(null);
    if (!orderNo) {
      setSubmitError('缺少订单号，请从订单列表进入本页');
      return;
    }
    if (!reason.trim()) {
      setSubmitError('请填写售后诉求');
      return;
    }
    if (images.length > AFTER_SALE_MAX_IMAGES) {
      setSubmitError(`举证图最多 ${AFTER_SALE_MAX_IMAGES} 张，当前 ${images.length} 张`);
      return;
    }
    setBusy(true);
    try {
      const created = await afterSaleApi.create({
        orderNo,
        type,
        reason: reason.trim(),
        images: images.length > 0 ? images : undefined,
      });
      push('售后已提交，等待卖家处理', 'success');
      navigate(`/after-sales/${created.id}`);
    } catch (err) {
      // 409 = 同单同类型已立案过；422 = 订单状态不允许；403 = 不是自己的订单
      setSubmitError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  if (!orderNo) {
    return (
      <div>
        <h2 className="section-title">申请售后</h2>
        <Alert kind="error">缺少订单号。请从「我的订单」里选择一笔订单再点「申请售后」。</Alert>
        <div className="row" style={{ marginTop: 14 }}>
          <Link className="btn btn-ghost" to="/orders">
            去我的订单
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div>
      <h2 className="section-title">申请售后</h2>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading ? <LoadingBlock text="正在加载订单…" /> : null}

      {order ? (
        <div className="card card-pad" style={{ marginBottom: 14 }}>
          <div className="row-between">
            <div>
              <div className="mono">{order.orderNo}</div>
              <div className="small muted">
                {order.statusLabel} · 实付 {formatPrice(order.payAmount)} · 下单于{' '}
                {formatDate(order.createdAt)}
              </div>
            </div>
          </div>
          {!eligible ? (
            <div style={{ marginTop: 10 }}>
              <Alert kind="warn">
                订单当前是「{order.statusLabel}」，<b>发货后</b>才能申请售后。
                未发货时如果只是想退款，可以直接在订单详情里用「申请退款」。
              </Alert>
            </div>
          ) : null}
        </div>
      ) : null}

      <div className="card card-pad">
        <Field label="售后类型" required>
          <div className="stack-sm">
            {AFTER_SALE_TYPE_OPTIONS.map((option) => (
              <label className="checkbox-row" key={option.value}>
                <input
                  type="radio"
                  name="after-sale-type"
                  checked={type === option.value}
                  onChange={() => setType(option.value)}
                />
                <span>
                  <b>{option.label}</b>
                  <span className="small muted" style={{ display: 'block' }}>
                    {option.hint}
                  </span>
                </span>
              </label>
            ))}
          </div>
        </Field>

        <Field label="售后诉求" required hint="写清问题（哪里不对、什么诉求），卖家与运营都要靠它判断">
          <textarea
            className="textarea"
            rows={4}
            maxLength={500}
            placeholder="例如：收到的猫粮外包装已破损，内袋漏气，要求退款"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </Field>

        <Field
          label="举证图片直链（可选）"
          hint={`一行一条，最多 ${AFTER_SALE_MAX_IMAGES} 张，只接受 http/https 开头的地址`}
        >
          <textarea
            className="textarea"
            rows={3}
            placeholder={'https://example.com/photo-1.jpg\nhttps://example.com/photo-2.jpg'}
            value={imageText}
            onChange={(e) => setImageText(e.target.value)}
          />
        </Field>

        {submitError ? <Alert kind="error">{submitError}</Alert> : null}

        <div className="row" style={{ gap: 8, marginTop: 14 }}>
          <button className="btn btn-primary" disabled={busy || !eligible} onClick={submit}>
            {busy ? '提交中…' : '提交售后申请'}
          </button>
          <Link className="btn btn-ghost" to={`/orders/${orderNo}`}>
            返回订单
          </Link>
        </div>
      </div>
    </div>
  );
};

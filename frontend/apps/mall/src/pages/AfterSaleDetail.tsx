/**
 * 售后详情（买家视角，A3）。
 *
 * 数据源：`GET /api/aftersales/{id}` —— 只有当事人（买家 / 该单卖家）或运营看得见，其余 403。
 *
 * ⚠️ `id` 是**字符串**（18 位雪花值）。这里从路由参数原样取用，**不做任何数值转换** ——
 * `Number(id)` 会把它悄悄改成一个不存在的单号，表现为莫名的 404。
 */

import React, { useCallback, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { afterSaleApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, Field, LoadingBlock, formatDate, formatPrice, useToast } from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';
import { AfterSaleStatusTag, AfterSaleSteps } from '@duoduo/shared/components/afterSale';

/**
 * 可申请平台仲裁的状态（与后端 AfterSaleService.escalate 同口径）。
 *
 * 退货的两个中间态也在内：货寄出去后卖家迟迟不确认收货时，
 * 「退货中」不能变成没有出路的黑洞。
 */
const ESCALATABLE = ['PENDING', 'SELLER_REJECTED', 'WAITING_RETURN', 'RETURNING'];

export const AfterSaleDetailPage: React.FC = () => {
  const { id = '' } = useParams<{ id: string }>();
  const { push } = useToast();
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [courier, setCourier] = useState('');
  const [trackingNo, setTrackingNo] = useState('');

  const { data, loading, error, reload } = useAsync(
    useCallback(() => afterSaleApi.detail(id), [id]),
    id.length > 0,
    [id],
  );

  const escalate = async () => {
    setActionError(null);
    setBusy(true);
    try {
      await afterSaleApi.escalate(id);
      push('已申请平台仲裁，等待运营裁定', 'success');
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const shipReturn = async () => {
    setActionError(null);
    if (!courier.trim() || !trackingNo.trim()) {
      setActionError('请填写快递公司与运单号');
      return;
    }
    setBusy(true);
    try {
      await afterSaleApi.shipReturn(id, { courierCompany: courier.trim(), trackingNo: trackingNo.trim() });
      push('寄回信息已登记，卖家签收后即退款', 'success');
      reload();
    } catch (err) {
      // 重复提交是 409：必须如实提示，让用户知道库里还是第一次填的那个单号
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="row-between" style={{ marginBottom: 12 }}>
        <h2 className="section-title">售后详情</h2>
        {data ? (
          <div className="row" style={{ gap: 8 }}>
            {/* 返回列表（2026-10-18）：原先详情页只有「查看订单」，看完想回到售后记录
                只能靠浏览器后退 —— 菜单入口补上后，这里也要有对称的回程。 */}
            <Link className="btn btn-sm btn-ghost" to="/after-sales">
              返回列表
            </Link>
            <Link className="btn btn-sm btn-ghost" to={`/orders/${data.orderNo}`}>
              查看订单
            </Link>
          </div>
        ) : null}
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading ? <LoadingBlock text="正在加载售后单…" /> : null}

      {data ? (
        <>
          <div className="card card-pad">
            <div className="row-between">
              <div>
                <div className="mono">{data.orderNo}</div>
                <div className="small muted">
                  {data.typeLabel} · 申请于 {formatDate(data.createdAt)}
                </div>
              </div>
              <AfterSaleStatusTag status={data.status} label={data.statusLabel} />
            </div>

            <AfterSaleSteps status={data.status} />

            <div className="divider" />

            <div className="kv">
              <span className="muted">售后诉求</span>
              <span>{data.reason}</span>
            </div>

            {data.images.length > 0 ? (
              <div className="kv">
                <span className="muted">举证图片</span>
                <span className="row row-wrap" style={{ gap: 8 }}>
                  {data.images.map((url) => (
                    <a key={url} href={url} target="_blank" rel="noreferrer" className="small">
                      查看图片
                    </a>
                  ))}
                </span>
              </div>
            ) : null}

            {data.refundAmount !== null ? (
              <div className="kv kv-total">
                <span>已退款金额</span>
                <span className="strong">{formatPrice(data.refundAmount)}</span>
              </div>
            ) : null}

            <div className="small muted" style={{ marginTop: 10 }}>
              最后更新：{formatDate(data.updatedAt)}
            </div>
          </div>

          {data.returnTrackingNo ? (
            <div className="card card-pad" style={{ marginTop: 12 }}>
              <div className="section-sub">寄回物流</div>
              <div className="kv">
                <span className="muted">快递公司</span>
                <span>{data.returnCourier ?? '—'}</span>
              </div>
              <div className="kv">
                <span className="muted">运单号</span>
                <span className="mono">{data.returnTrackingNo}</span>
              </div>
              <div className="kv">
                <span className="muted">寄回时间</span>
                <span>{data.returnShippedAt ? formatDate(data.returnShippedAt) : '—'}</span>
              </div>
              {data.returnReceivedAt ? (
                <div className="kv">
                  <span className="muted">卖家签收</span>
                  <span>{formatDate(data.returnReceivedAt)}（签收后执行退款）</span>
                </div>
              ) : (
                <div className="small muted" style={{ marginTop: 8 }}>
                  等待卖家签收 —— 签收后系统立即退款，在此之前钱不会动。
                </div>
              )}
            </div>
          ) : null}

          {data.status === 'WAITING_RETURN' ? (
            <div className="card card-pad" style={{ marginTop: 12 }}>
              <div className="section-sub">填写寄回物流</div>
              <p className="small muted" style={{ marginTop: 8 }}>
                卖家已同意退货。请按卖家说明的地址寄回商品，并在这里登记运单号 ——
                卖家签收后系统立即退款。
              </p>
              <Field label="快递公司" required>
                <input
                  className="input"
                  value={courier}
                  maxLength={64}
                  onChange={(e) => setCourier(e.target.value)}
                  placeholder="如：顺丰速运"
                />
              </Field>
              <Field label="运单号" required>
                <input
                  className="input"
                  value={trackingNo}
                  maxLength={64}
                  onChange={(e) => setTrackingNo(e.target.value)}
                  placeholder="快递单号"
                />
              </Field>
              {actionError ? (
                <div style={{ marginTop: 10 }}>
                  <Alert kind="error">{actionError}</Alert>
                </div>
              ) : null}
              <div className="row" style={{ marginTop: 12 }}>
                <button className="btn btn-primary" disabled={busy} onClick={shipReturn}>
                  {busy ? '提交中…' : '提交寄回信息'}
                </button>
              </div>
            </div>
          ) : null}

          {data.sellerReply ? (
            <div className="card card-pad" style={{ marginTop: 12 }}>
              <div className="section-sub">卖家的处理说明</div>
              <p style={{ marginTop: 8 }}>{data.sellerReply}</p>
            </div>
          ) : null}

          {data.arbitrateNote ? (
            <div className="card card-pad" style={{ marginTop: 12 }}>
              <div className="section-sub">
                平台裁定说明
                {data.arbitrateResult === 'APPROVE_REFUND' ? '（支持退款）' : ''}
                {data.arbitrateResult === 'REJECT' ? '（驳回）' : ''}
              </div>
              <p style={{ marginTop: 8 }}>{data.arbitrateNote}</p>
            </div>
          ) : null}

          {ESCALATABLE.includes(data.status) ? (
            <div className="card card-pad" style={{ marginTop: 12 }}>
              <div className="section-sub">卖家迟迟不处理，或你不认同卖家的处理？</div>
              <p className="small muted" style={{ marginTop: 8 }}>
                可以申请<b>平台仲裁</b>，由运营根据双方提交的内容裁定。裁定结果会写在这里，
                支持退款时系统会直接执行退款。
              </p>
              {actionError ? (
                <div style={{ marginTop: 10 }}>
                  <Alert kind="error">{actionError}</Alert>
                </div>
              ) : null}
              <div className="row" style={{ marginTop: 12 }}>
                <button className="btn btn-primary" disabled={busy} onClick={escalate}>
                  {busy ? '提交中…' : '申请平台仲裁'}
                </button>
              </div>
            </div>
          ) : null}

          {data.status === 'ARBITRATING' ? (
            <Alert kind="info">平台仲裁中，运营会尽快处理。裁定结果会展示在本页。</Alert>
          ) : null}

          {data.status === 'REFUNDED' ? (
            <Alert kind="success">该售后已退款完成，钱已原路退回买家。</Alert>
          ) : null}

          {data.status === 'CLOSED' ? (
            <Alert kind="warn">
              该售后已被驳回并关闭。如果还有异议，可以针对同一订单重新提起另一种类型的售后。
            </Alert>
          ) : null}
        </>
      ) : null}
    </div>
  );
};

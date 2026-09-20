/**
 * 商家端 · 售后服务（A3）。
 *
 * 数据源：
 *   GET  /api/aftersales/seller          —— 我店铺的售后（可见范围由登录态推导，传不了 sellerId）
 *   POST /api/aftersales/{id}/reply      —— 处理：AGREE（同意并**立刻退款**）/ REJECT（必须填说明）
 *
 * 只处理「待卖家处理」的单：其余状态下按钮不出现。
 * 页面上的显隐是体验，真正的状态机与归属校验都在服务端（越权 403 / 状态不允许 422）。
 */

import React, { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { afterSaleApi } from '../../api/endpoints';
import { toFriendlyMessage } from '../../api/client';
import type { AfterSaleReplyAction, AfterSaleView } from '../../api/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, formatDate, formatPrice, useToast,
} from '../../components/ui';
import { useAsync } from '../../components/hooks';
import { useAuth } from '../../store/auth';
import { AFTER_SALE_STATUS_TABS, AfterSaleStatusTag } from '../afterSaleShared';
import { SellerApplyCard, canAccessSellerConsole } from './shared';

export const SellerAfterSalesPage: React.FC = () => {
  const { user } = useAuth();
  const allowed = canAccessSellerConsole(user?.role);
  const { push } = useToast();

  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);

  const [handling, setHandling] = useState<AfterSaleView | null>(null);
  const [action, setAction] = useState<AfterSaleReplyAction>('AGREE');
  const [reply, setReply] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(
      () => afterSaleApi.seller({ status: status || undefined, page, pageSize: 10 }),
      [status, page],
    ),
    allowed,
    [status, page],
  );

  if (!allowed) {
    return (
      <div>
        <h2 className="section-title">商家端 · 售后服务</h2>
        <SellerApplyCard />
      </div>
    );
  }

  const openHandle = (item: AfterSaleView) => {
    setHandling(item);
    setAction('AGREE');
    setReply('');
    setActionError(null);
  };

  const submit = async () => {
    if (!handling) return;
    setActionError(null);
    if (action === 'REJECT' && !reply.trim()) {
      // 后端同样会拒（422）—— 这里先拦一次是为了省一个来回，不是安全边界
      setActionError('拒绝售后必须填写说明原因');
      return;
    }
    setBusy(true);
    try {
      await afterSaleApi.reply(handling.id, {
        action,
        reply: reply.trim() || undefined,
      });
      push(action === 'AGREE' ? '已同意并执行退款' : '已拒绝该售后', 'success');
      setHandling(null);
      reload();
    } catch (err) {
      // 422 的常见原因：订单已完成（钱已放款给卖家），系统退不了，需人工追偿
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="row-between" style={{ marginBottom: 12 }}>
        <h2 className="section-title">商家端 · 售后服务</h2>
        <Link className="btn btn-sm btn-ghost" to="/seller/orders">
          我的订单
        </Link>
      </div>

      <Alert kind="info">
        「同意」会<b>立刻执行退款</b>（钱退回买家、库存回补），不可撤销；
        「拒绝」必须写清原因，买家可以据此<b>申请平台仲裁</b>。
      </Alert>

      <div className="row row-wrap" style={{ gap: 8, margin: '14px 0' }}>
        {AFTER_SALE_STATUS_TABS.map((tab) => (
          <button
            key={tab.value}
            className={`btn btn-sm ${status === tab.value ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => {
              setStatus(tab.value);
              setPage(1);
            }}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading ? <LoadingBlock text="正在加载售后单…" /> : null}

      {!loading && data && data.items.length === 0 ? (
        <Empty icon="🧾" title="当前没有售后单" desc="买家提起售后后会出现在这里" />
      ) : null}

      {!loading && data && data.items.length > 0 ? (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>订单号</th>
                <th>类型</th>
                <th>状态</th>
                <th>诉求</th>
                <th>申请时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((item) => (
                <tr key={item.id}>
                  <td className="mono">{item.orderNo}</td>
                  <td>{item.typeLabel}</td>
                  <td>
                    <AfterSaleStatusTag status={item.status} label={item.statusLabel} />
                  </td>
                  <td style={{ maxWidth: 260 }}>{item.reason}</td>
                  <td>{formatDate(item.createdAt)}</td>
                  <td>
                    {item.status === 'PENDING' ? (
                      <button className="btn btn-sm btn-primary" onClick={() => openHandle(item)}>
                        处理
                      </button>
                    ) : (
                      <span className="muted">—</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {data && data.total > data.pageSize ? (
        <Pagination
          page={page}
          pageCount={Math.max(1, data.pageCount)}
          total={data.total}
          onChange={setPage}
        />
      ) : null}

      <Modal
        open={handling !== null}
        title="处理售后"
        onClose={() => setHandling(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setHandling(null)}>
              取消
            </button>
            <button
              className={`btn ${action === 'AGREE' ? 'btn-primary' : 'btn-danger'}`}
              disabled={busy}
              onClick={submit}
            >
              {busy ? '处理中…' : action === 'AGREE' ? '同意并退款' : '确认拒绝'}
            </button>
          </>
        }
      >
        {handling ? (
          <div>
            <p style={{ marginBottom: 12 }}>
              订单 <b className="mono">{handling.orderNo}</b> · {handling.typeLabel}
            </p>
            <div className="card card-pad" style={{ marginBottom: 12 }}>
              <div className="small muted">买家诉求</div>
              <div style={{ marginTop: 6 }}>{handling.reason}</div>
              {handling.images.length > 0 ? (
                <div className="row row-wrap" style={{ gap: 8, marginTop: 8 }}>
                  {handling.images.map((url) => (
                    <a key={url} href={url} target="_blank" rel="noreferrer" className="small">
                      举证图
                    </a>
                  ))}
                </div>
              ) : null}
            </div>

            <Field label="处理方式" required>
              <div className="stack-sm">
                <label className="checkbox-row">
                  <input
                    type="radio"
                    name="after-sale-action"
                    checked={action === 'AGREE'}
                    onChange={() => setAction('AGREE')}
                  />
                  <span>
                    <b>同意并退款</b>
                    <span className="small muted" style={{ display: 'block' }}>
                      会立刻执行退款：钱退回买家、库存回补，不可撤销
                    </span>
                  </span>
                </label>
                <label className="checkbox-row">
                  <input
                    type="radio"
                    name="after-sale-action"
                    checked={action === 'REJECT'}
                    onChange={() => setAction('REJECT')}
                  />
                  <span>
                    <b>拒绝</b>
                    <span className="small muted" style={{ display: 'block' }}>
                      必须写清原因；买家可以据此申请平台仲裁
                    </span>
                  </span>
                </label>
              </div>
            </Field>

            <Field
              label={action === 'REJECT' ? '拒绝原因（必填）' : '处理说明（可选）'}
              required={action === 'REJECT'}
              hint="会展示给买家，并作为后续仲裁的依据"
            >
              <textarea
                className="textarea"
                rows={3}
                maxLength={500}
                placeholder={
                  action === 'REJECT'
                    ? '例如：商品已拆封使用，影响二次销售，不同意退款'
                    : '例如：已核对举证，同意退款'
                }
                value={reply}
                onChange={(e) => setReply(e.target.value)}
              />
            </Field>

            {action === 'AGREE' ? (
              <Alert kind="warn">
                注意：如果该订单<b>已完成收货</b>（钱已放款给你），系统无法直接退款，接口会返回
                422 并提示走追偿；此时请不要把货款当成已处理。
              </Alert>
            ) : null}

            {actionError ? (
              <div style={{ marginTop: 10 }}>
                <Alert kind="error">{actionError}</Alert>
              </div>
            ) : null}

            {handling.refundAmount !== null ? (
              <div className="small muted" style={{ marginTop: 10 }}>
                已记录退款金额：{formatPrice(handling.refundAmount)}
              </div>
            ) : null}
          </div>
        ) : null}
      </Modal>
    </div>
  );
};

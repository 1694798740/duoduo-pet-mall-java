/**
 * 平台管理后台 · 售后仲裁（A3）。
 *
 * 数据源：
 *   GET  /api/admin/aftersales               —— 全平台售后列表（按状态过滤）
 *   POST /api/admin/aftersales/{id}/arbitrate —— 裁定：APPROVE_REFUND / REJECT，**说明必填**
 *
 * 这是「争议」在系统里的最后一站：运营看过买家的诉求与举证、卖家的处理说明之后给出结论。
 * 「说明」必填不是形式主义 —— 它是事后复盘与客诉处理的唯一凭据，没有说明的裁定等于零信息。
 *
 * 一条必须让运营看懂的资金边界：若订单**已完成收货**（钱已放款给卖家），裁定支持退款时
 * 交易域会返回 422「需向卖家追偿 / 平台垫付」—— 系统退不了这笔钱，只能人工处理。
 * 这种情况下售后单保持原状态，不会被记成「已退款」。
 */

import React, { useCallback, useState } from 'react';
import { adminApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import type { AfterSaleArbitrateResult, AfterSaleView } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, formatDate, formatPrice, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';
import { useAuth } from '@duoduo/shared/store/auth';
import { AFTER_SALE_STATUS_TABS, AfterSaleStatusTag } from '../afterSaleShared';
import { AdminDenied, AdminTabs, isOperator } from './shared';

/** 可裁定的状态（终态不可再改判 —— 要改判只能新立案） */
const ARBITRABLE = ['PENDING', 'SELLER_REJECTED', 'ARBITRATING'];

export const AdminAfterSalesPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();

  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);

  const [target, setTarget] = useState<AfterSaleView | null>(null);
  const [result, setResult] = useState<AfterSaleArbitrateResult>('REJECT');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(
      () => adminApi.afterSales({ status: status || undefined, page, pageSize: 10 }),
      [status, page],
    ),
    operator,
    [status, page],
  );

  if (!operator) return <AdminDenied />;

  const openArbitrate = (item: AfterSaleView) => {
    setTarget(item);
    setResult('REJECT');
    setNote('');
    setActionError(null);
  };

  const submit = async () => {
    if (!target) return;
    setActionError(null);
    if (!note.trim()) {
      setActionError('裁定必须填写说明');
      return;
    }
    setBusy(true);
    try {
      await adminApi.arbitrateAfterSale(target.id, { result, note: note.trim() });
      push(result === 'APPROVE_REFUND' ? '已裁定支持退款并执行退款' : '已裁定驳回', 'success');
      setTarget(null);
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 售后仲裁</h2>
      <AdminTabs />

      <Alert kind="info">
        裁定「支持退款」会<b>真的执行退款</b>（复用交易域的运营侧退款入口），不可撤销。
        若订单<b>已完成收货</b>且钱已放款给卖家，交易域会拒绝并提示走追偿 / 平台垫付 ——
        那是资金边界，系统退不了，需人工处理。
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
        <Empty icon="⚖️" title="没有符合条件的售后单" desc="买家提起售后、或卖家拒绝后会出现在这里" />
      ) : null}

      {!loading && data && data.items.length > 0 ? (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>订单号</th>
                <th>类型</th>
                <th>状态</th>
                <th>买家诉求</th>
                <th>卖家说明</th>
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
                  <td style={{ maxWidth: 220 }}>{item.reason}</td>
                  <td style={{ maxWidth: 200 }} className="small muted">
                    {item.sellerReply ?? '—'}
                  </td>
                  <td>{formatDate(item.createdAt)}</td>
                  <td>
                    {ARBITRABLE.includes(item.status) ? (
                      <button className="btn btn-sm btn-primary" onClick={() => openArbitrate(item)}>
                        裁定
                      </button>
                    ) : (
                      <span className="muted">{item.arbitrateResult ?? '已终结'}</span>
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
        open={target !== null}
        title="售后裁定"
        onClose={() => setTarget(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setTarget(null)}>
              取消
            </button>
            <button
              className={`btn ${result === 'APPROVE_REFUND' ? 'btn-primary' : 'btn-danger'}`}
              disabled={busy}
              onClick={submit}
            >
              {busy ? '提交中…' : result === 'APPROVE_REFUND' ? '支持退款并执行' : '确认驳回'}
            </button>
          </>
        }
      >
        {target ? (
          <div>
            <p style={{ marginBottom: 12 }}>
              订单 <b className="mono">{target.orderNo}</b> · {target.typeLabel} ·{' '}
              <AfterSaleStatusTag status={target.status} label={target.statusLabel} />
            </p>

            <div className="card card-pad" style={{ marginBottom: 12 }}>
              <div className="small muted">买家诉求</div>
              <div style={{ marginTop: 6 }}>{target.reason}</div>
              {target.images.length > 0 ? (
                <div className="row row-wrap" style={{ gap: 8, marginTop: 8 }}>
                  {target.images.map((url) => (
                    <a key={url} href={url} target="_blank" rel="noreferrer" className="small">
                      举证图
                    </a>
                  ))}
                </div>
              ) : null}
              {target.sellerReply ? (
                <div style={{ marginTop: 10 }}>
                  <div className="small muted">卖家处理说明</div>
                  <div style={{ marginTop: 6 }}>{target.sellerReply}</div>
                </div>
              ) : null}
            </div>

            <Field label="裁定结论" required>
              <div className="stack-sm">
                <label className="checkbox-row">
                  <input
                    type="radio"
                    name="arbitrate-result"
                    checked={result === 'APPROVE_REFUND'}
                    onChange={() => setResult('APPROVE_REFUND')}
                  />
                  <span>
                    <b>支持退款</b>
                    <span className="small muted" style={{ display: 'block' }}>
                      会立刻执行退款（钱退回买家、库存回补），不可撤销
                    </span>
                  </span>
                </label>
                <label className="checkbox-row">
                  <input
                    type="radio"
                    name="arbitrate-result"
                    checked={result === 'REJECT'}
                    onChange={() => setResult('REJECT')}
                  />
                  <span>
                    <b>驳回</b>
                    <span className="small muted" style={{ display: 'block' }}>
                      售后单关闭；买家仍可针对该订单另立其它类型的售后
                    </span>
                  </span>
                </label>
              </div>
            </Field>

            <Field
              label="裁定说明（必填）"
              required
              hint="这是事后复盘与客诉处理的唯一凭据，请写清依据"
            >
              <textarea
                className="textarea"
                rows={3}
                maxLength={500}
                placeholder="例如：买家举证显示外包装破损、内袋漏气，属发货包装不当，支持退款"
                value={note}
                onChange={(e) => setNote(e.target.value)}
              />
            </Field>

            {actionError ? <Alert kind="error">{actionError}</Alert> : null}

            {target.refundAmount !== null ? (
              <div className="small muted" style={{ marginTop: 10 }}>
                已记录退款金额：{formatPrice(target.refundAmount)}
              </div>
            ) : null}
          </div>
        ) : null}
      </Modal>
    </div>
  );
};

/**
 * 平台管理后台 · 订单干预（M10）。
 *
 * 数据源：
 *   GET  /api/admin/orders                 —— 全平台订单列表（状态 / 订单号前缀过滤）
 *   GET  /api/admin/orders/{orderNo}       —— 无归属限制的订单详情
 *   POST /api/admin/orders/{orderNo}/force-close —— 强制关单（仅待付款）
 *
 * 设计约束与后端一致：这里只放「强制关单」这一种干预。
 * 已付款 / 已发货订单的干预是资金动作，必须走退款 / 售后的合法流转，
 * 后台不开「任意改状态」的口子 —— 状态机与资金账本只走合法流转。
 */

import React, { useCallback, useState } from 'react';
import { adminApi } from '../../api/endpoints';
import type { OrderStatus, OrderView } from '../../api/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, formatDate, formatPrice, useToast,
} from '../../components/ui';
import { useAsync } from '../../components/hooks';
import { toFriendlyMessage } from '../../api/client';
import { useAuth } from '../../store/auth';
import { AdminDenied, AdminTabs, isOperator } from './shared';

const STATUS_TABS: Array<{ value: string; label: string }> = [
  { value: '', label: '全部' },
  { value: 'PENDING_PAYMENT', label: '待付款' },
  { value: 'PAID', label: '已付款' },
  { value: 'SHIPPED', label: '已发货' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'CLOSED', label: '已关闭' },
];

export const AdminOrdersPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();

  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [orderNo, setOrderNo] = useState('');
  const [page, setPage] = useState(1);

  const [closing, setClosing] = useState<OrderView | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(
      () => adminApi.orders({ status: status || undefined, orderNo: orderNo || undefined, page, pageSize: 10 }),
      [status, orderNo, page],
    ),
    operator,
    [status, orderNo, page],
  );

  if (!operator) return <AdminDenied />;

  const pageCount = data ? Math.max(1, data.pageCount) : 1;

  const search = () => {
    setPage(1);
    setOrderNo(keyword.trim());
  };

  const forceClose = async () => {
    if (!closing) return;
    setActionError(null);
    setBusy(true);
    try {
      await adminApi.forceCloseOrder(closing.orderNo, reason.trim() || undefined);
      push(`订单 ${closing.orderNo} 已强制关闭`, 'success');
      setClosing(null);
      setReason('');
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 订单干预</h2>
      <AdminTabs />

      <Alert kind="info">
        这里只提供<b>强制关单</b>，且仅限<b>待付款</b>订单（恶意占库存、超时未付却未触发补偿等）。
        已付款 / 已发货订单请走退款 / 售后流程，资金账本不允许绕开状态机。
      </Alert>

      <div className="row row-wrap" style={{ gap: 8, margin: '14px 0' }}>
        {STATUS_TABS.map((tab) => (
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

      <div className="row" style={{ gap: 8, marginBottom: 14 }}>
        <input
          className="input"
          style={{ maxWidth: 320 }}
          placeholder="按订单号前缀搜索…"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') search();
          }}
        />
        <button className="btn btn-ghost" onClick={search}>搜索</button>
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading ? <LoadingBlock text="正在加载订单…" /> : null}

      {!loading && data && data.items.length === 0 ? (
        <Empty title="没有符合条件的订单" />
      ) : null}

      {!loading && data && data.items.length > 0 ? (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>订单号</th>
                <th>状态</th>
                <th>实付金额</th>
                <th>买家</th>
                <th>下单时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((order) => (
                <tr key={order.orderNo}>
                  <td className="mono">{order.orderNo}</td>
                  <td>{order.statusLabel}</td>
                  <td>{formatPrice(order.payAmount)}</td>
                  <td>{order.receiverName}</td>
                  <td>{formatDate(order.createdAt)}</td>
                  <td>
                    {order.status === ('PENDING_PAYMENT' as OrderStatus) ? (
                      <button
                        className="btn btn-sm btn-danger"
                        onClick={() => {
                          setClosing(order);
                          setReason('');
                          setActionError(null);
                        }}
                      >
                        强制关单
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
        <Pagination page={page} pageCount={pageCount} total={data.total} onChange={setPage} />
      ) : null}

      <Modal
        open={closing !== null}
        title="强制关单"
        onClose={() => setClosing(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setClosing(null)}>取消</button>
            <button className="btn btn-danger" disabled={busy} onClick={forceClose}>
              {busy ? '处理中…' : '确认强制关单'}
            </button>
          </>
        }
      >
        {closing ? (
          <div>
            <p style={{ marginBottom: 12 }}>
              订单 <b className="mono">{closing.orderNo}</b>（{formatPrice(closing.payAmount)}）将被关闭：
              未支付的支付单作废、冻结的库存释放。该操作会写入订单状态日志（操作者 OPERATOR）。
            </p>
            {actionError ? <Alert kind="error">{actionError}</Alert> : null}
            <Field label="关单原因（可选，会展示给买家）">
              <textarea
                className="input"
                rows={3}
                placeholder="例如：恶意占库存 / 买家协商一致取消"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </Field>
          </div>
        ) : null}
      </Modal>
    </div>
  );
};

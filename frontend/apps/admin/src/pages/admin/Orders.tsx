/**
 * 平台管理后台 · 订单干预（M10）。
 *
 * 数据源：
 *   GET  /api/admin/orders                 —— 全平台订单列表（状态 / 订单号或商品名过滤）
 *   GET  /api/admin/orders/{orderNo}       —— 无归属限制的订单详情
 *   POST /api/admin/orders/{orderNo}/force-close —— 强制关单（仅待付款）
 *
 * 设计约束与后端一致：这里只放「强制关单」这一种干预。
 * 已付款 / 已发货订单的干预是资金动作，必须走退款 / 售后的合法流转，
 * 后台不开「任意改状态」的口子 —— 状态机与资金账本只走合法流转。
 */

import React, { useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { adminApi } from '@duoduo/api-sdk/endpoints';
import type { BatchResultView, OrderStatus, OrderView } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, SearchBar, formatDate, formatPrice, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { BatchBar, BatchResultModal, useBatchSelection } from '@duoduo/shared/components/batch';
import { notifyBadgeChanged } from '@duoduo/shared/lib/badge-events';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { useAuth } from '@duoduo/shared/store/auth';
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
  const navigate = useNavigate();

  const [status, setStatus] = useState('');
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);
  const [page, setPage] = useState(1);

  const [closing, setClosing] = useState<OrderView | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  /** 异步导出（矩阵第 9 项）独立的忙碌位：不阻塞关单 / 批量操作 */
  const [creatingJob, setCreatingJob] = useState(false);

  /** 批量强关（2026-10-27 · 批量操作即已读）：原因对选中订单共用 */
  const [batchOpen, setBatchOpen] = useState(false);
  const [batchReason, setBatchReason] = useState('');
  const [batchResult, setBatchResult] = useState<BatchResultView | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(
      () => adminApi.orders({ status: status || undefined, keyword: keyword || undefined, page, pageSize: 10 }),
      [status, keyword, page],
    ),
    operator,
    [status, keyword, page],
  );

  const pageIds = (data?.items ?? []).map((order) => order.orderNo);
  const selection = useBatchSelection(pageIds, `${status}|${keyword}|${page}`);

  if (!operator) return <AdminDenied />;

  const pageCount = data ? Math.max(1, data.pageCount) : 1;

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

  const submitBatchClose = async () => {
    if (!selection.count || busy) return;
    setActionError(null);
    setBusy(true);
    try {
      const result = await adminApi.batchForceCloseOrders(selection.selected, batchReason.trim() || undefined);
      setBatchResult(result);
      selection.clear();
      setBatchOpen(false);
      reload();
      notifyBadgeChanged();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  /**
   * 异步导出（矩阵第 9 项）：超过 5 万行的订单在同步端点会被 409 拒绝，
   * 这里直接用同一套筛选建任务，随后跳到「导出任务」页看进度与下载。
   */
  const createExportJob = async () => {
    if (creatingJob) return;
    setActionError(null);
    setCreatingJob(true);
    try {
      const job = await adminApi.createOrderExportJob({
        status: status || undefined,
        keyword: keyword || undefined,
      });
      push(`异步导出任务已创建（预计 ${job.totalRows} 行），生成完成后可下载`, 'success');
      navigate('/admin/export-jobs');
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setCreatingJob(false);
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

      <div className="list-toolbar">
        <SearchBar
          value={keywordInput}
          onChange={(v) => {
            setKeywordInput(v);
            setPage(1);
          }}
          busy={loading && Boolean(keywordInput.trim())}
          placeholder="搜订单号或商品名称"
          width={320}
        />
        {keywordInput.trim() ? (
          <span className="small muted">匹配「{keywordInput.trim()}」的订单 {data ? data.total : 0} 笔</span>
        ) : null}
        <button
          className="btn btn-ghost btn-sm"
          title="按当前状态与关键词导出全平台订单（含完整收货信息，CSV；超过 5 万行请用异步导出）"
          onClick={() => {
            // 客户端先按列表总数拦一次：同步端点超限时返回的是 409 JSON，
            // 直接 window.location 跳过去会让页面停在一屏 JSON 上（体验最差的一种失败）
            if ((data?.total ?? 0) > 50000) {
              push('结果超过 5 万行，请使用「异步导出」', 'info');
              return;
            }
            window.location.href = adminApi.orderExportUrl({
              status: status || undefined,
              keyword: keyword || undefined,
            });
          }}
        >
          导出 CSV
        </button>
        <button
          className="btn btn-primary btn-sm"
          title="后台分页生成 CSV，完成后推站内通知；文件 7 天内可下载"
          disabled={creatingJob}
          onClick={() => void createExportJob()}
        >
          {creatingJob ? '创建中…' : '异步导出'}
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

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading ? <LoadingBlock text="正在加载订单…" /> : null}

      {!loading && data && data.items.length === 0 ? (
        <Empty title={keywordInput.trim() ? `没有匹配「${keywordInput.trim()}」的订单` : '没有符合条件的订单'} />
      ) : null}

      {!loading && data && data.items.length > 0 ? (
        <div>
          <BatchBar count={selection.count} onClear={selection.clear}>
            <button
              className="btn btn-danger btn-sm"
              disabled={busy}
              onClick={() => {
                setBatchReason('');
                setActionError(null);
                setBatchOpen(true);
              }}
            >
              批量强制关单
            </button>
          </BatchBar>
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th style={{ width: 36 }}>
                  <input
                    type="checkbox"
                    aria-label="全选当前页订单"
                    checked={selection.allSelected}
                    disabled={!pageIds.length}
                    onChange={selection.toggleAll}
                  />
                </th>
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
                  <td>
                    <input
                      type="checkbox"
                      aria-label={`选择订单 ${order.orderNo}`}
                      checked={selection.selectedSet.has(order.orderNo)}
                      disabled={busy}
                      onChange={() => selection.toggle(order.orderNo)}
                    />
                  </td>
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
        </div>
      ) : null}

      {data ? (
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

      {/* 批量强关：只对待付款成功；已付款/已发货逐条失败并带回原因 */}
      <Modal
        open={batchOpen}
        title="批量强制关单"
        onClose={() => setBatchOpen(false)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setBatchOpen(false)} disabled={busy}>
              取消
            </button>
            <button className="btn btn-danger" disabled={busy} onClick={submitBatchClose}>
              {busy ? '处理中…' : `确认关闭（${selection.count} 笔）`}
            </button>
          </>
        }
      >
        <Alert kind="warn">
          将关闭 <b>{selection.count}</b> 笔订单（仅当前页）。只有<b>待付款</b>订单会成功：
          未支付的支付单作废、冻结库存释放；已付款 / 已发货会逐条失败，请走退款 / 售后流程。
          该操作会写入订单状态日志（操作者 OPERATOR）。
        </Alert>
        <Field label="关单原因（可选，会展示给买家）" hint="对全部选中订单共用">
          <textarea
            className="input"
            rows={3}
            placeholder="例如：恶意占库存 / 买家协商一致取消"
            value={batchReason}
            onChange={(e) => setBatchReason(e.target.value)}
          />
        </Field>
        {actionError ? <Alert kind="error">{actionError}</Alert> : null}
      </Modal>

      <BatchResultModal result={batchResult} onClose={() => setBatchResult(null)} idLabel="订单号" />
    </div>
  );
};

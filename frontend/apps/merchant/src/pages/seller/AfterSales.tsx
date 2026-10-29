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

import React, { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { afterSaleApi, sellerApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import type { AfterSaleReplyAction, AfterSaleView, BatchResultView, StatusCount } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, SearchBar, formatDate, formatPrice, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { BatchBar, BatchResultModal, useBatchSelection } from '@duoduo/shared/components/batch';
import { useAuth } from '@duoduo/shared/store/auth';
import { AFTER_SALE_STATUS_TABS, AfterSaleStatusTag } from '@duoduo/shared/components/afterSale';
import { Badge, UnreadDot, toCountMap } from '@duoduo/shared/components/badge';
import { notifyBadgeChanged } from '@duoduo/shared/lib/badge-events';
import { SellerApplyCard, canAccessSellerConsole } from './shared';

export const SellerAfterSalesPage: React.FC = () => {
  const { user } = useAuth();
  const allowed = canAccessSellerConsole(user?.role);
  const { push } = useToast();

  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);

  const [handling, setHandling] = useState<AfterSaleView | null>(null);
  const [action, setAction] = useState<AfterSaleReplyAction>('AGREE');
  const [reply, setReply] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  /** 批量处理（2026-10-27 · 批量操作即已读）：动作与说明对选中单共用 */
  const [batchOpen, setBatchOpen] = useState(false);
  const [batchAction, setBatchAction] = useState<AfterSaleReplyAction>('AGREE');
  const [batchReply, setBatchReply] = useState('');
  const [batchResult, setBatchResult] = useState<BatchResultView | null>(null);

  /** 逐条已读覆盖（2026-10-27 · 橙色未读印记）：切筛选/翻页时清空 */
  const [readIds, setReadIds] = useState<Set<string>>(new Set());
  useEffect(() => {
    setReadIds(new Set());
  }, [status, keyword, page]);
  const isRead = (item: AfterSaleView) => item.read === true || readIds.has(item.id);
  /** 预览卡片：点卡片先看详情，不直接进入处理表单 */
  const [preview, setPreview] = useState<AfterSaleView | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(
      () => afterSaleApi.seller({ status: status || undefined, keyword: keyword || undefined, page, pageSize: 10 }),
      [status, keyword, page],
    ),
    allowed,
    [status, keyword, page],
  );

  /** D-05 · 按商品 / SKU 的退款统计（订单明细口径，不随售后单筛选变化） */
  const refundStats = useAsync(
    useCallback(() => sellerApi.productRefundStats(), []),
    allowed,
  );

  /**
   * 售后页签角标（2026-10-27）。口径与列表接口同源（{@code side=seller}：
   * 本人店铺 + 运营可见的自营），覆盖全量而非当前页。
   */
  const countsState = useAsync<StatusCount[]>(
    useCallback(() => afterSaleApi.statusCounts('seller'), []),
    allowed,
  );
  /** 角标本地态：允许乐观减，之后一律以服务端分桶覆盖 */
  const [counts, setCounts] = useState<Record<string, number>>({});
  useEffect(() => {
    setCounts(toCountMap(countsState.data));
  }, [countsState.data]);
  const reloadCounts = countsState.reloadSilently;

  /**
   * 处理完一条售后 → 上报「我已看过」→ 服务端分桶覆盖。
   *
   * <p>商家端没有独立详情页（直接在列表里弹窗处理），所以上报点挂在**处理动作完成后**：
   * 刚处理完的单自己当然看过，不该再挂着已退款/已拒绝的角标。
   * 无条件上报（幂等）：状态可能变成 REFUNDED / SELLER_REJECTED / WAITING_RETURN，
   * 前两种属可消、后一种服务端本来就不会减 —— 由服务端决定，前端不判断。
   */
  const dismissAfterHandled = useCallback(
    async (id: string) => {
      // 本地先把橙点去掉，服务端确认后用分桶覆盖；失败回滚，不做假账
      setReadIds((prev) => new Set(prev).add(id));
      try {
        const rows = await afterSaleApi.markRead(id, 'seller');
        setCounts(toCountMap(rows));
        notifyBadgeChanged();
      } catch {
        setReadIds((prev) => {
          const next = new Set(prev);
          next.delete(id);
          return next;
        });
        void reloadCounts();
      }
    },
    [reloadCounts],
  );

  /** 批量选择：只作用于当前页，切状态 / 搜索 / 翻页自动清空 */
  const pageIds = (data?.items ?? []).map((item) => item.id);
  const selection = useBatchSelection(pageIds, `${status}|${keyword}|${page}`);
  const refundRows = refundStats.data ?? [];
  const refundTotalAmount = refundRows.reduce((sum, row) => sum + Number(row.refundAmount || 0), 0);
  const refundTotalQty = refundRows.reduce((sum, row) => sum + Number(row.refundQty || 0), 0);

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
    void dismissAfterHandled(item.id);
  };

  /** 打开预览 = 已读：橙点消失、终态角标同步减（原子入口） */
  const openPreview = (item: AfterSaleView) => {
    setPreview(item);
    void dismissAfterHandled(item.id);
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
      push(
        action === 'AGREE'
          ? handling.type === 'RETURN_REFUND'
            ? '已同意退货，等待买家寄回（签收后才退款）'
            : '已同意并执行退款'
          : '已拒绝该售后',
        'success',
      );
      setHandling(null);
      reload();
      // 处理后该状态少一条、目标状态多一条：角标同刻刷新；
      // 同时上报「已看过」，避免已退款/已拒绝这类可消状态挂回角标
      void dismissAfterHandled(handling.id);
    } catch (err) {
      // 422 的常见原因：订单已完成（钱已放款给卖家），系统退不了，需人工追偿
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  /**
   * 批量处理售后（2026-10-27 · 批量操作即已读）。
   *
   * <p>与商品管理同一闭环：逐条结果 → 成功条目服务端已写已读 →
   * 用返回的最新分桶覆盖本地角标（拿不到才回读）→ 清空选择 → 刷新并广播父级角标。
   */
  const submitBatch = async () => {
    if (!selection.count) return;
    setActionError(null);
    if (batchAction === 'REJECT' && !batchReply.trim()) {
      setActionError('拒绝售后必须填写说明原因');
      return;
    }
    setBusy(true);
    try {
      const result = await afterSaleApi.sellerBatchReply(
        selection.selected,
        batchAction,
        batchReply.trim() || undefined,
      );
      setBatchResult(result);
      if (result.counts) setCounts(toCountMap(result.counts));
      else void reloadCounts();
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

  const confirmReceived = async (item: AfterSaleView) => {
    setBusy(true);
    try {
      await afterSaleApi.receiveReturn(item.id);
      push('已确认收货并退款，款项退回买家', 'success');
      reload();
      // 签收即退款：RETURNING 减一、REFUNDED 加一；同时上报已读过，REFUNDED 不挂角标
      void dismissAfterHandled(item.id);
    } catch (err) {
      // 常见失败：订单已完成且已放款（需走平台垫付 / 追偿），后端会给具体原因
      push(toFriendlyMessage(err), 'error');
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
        「同意」对<b>仅退款</b>是<b>立刻执行退款</b>（钱退回买家、库存回补），不可撤销；
        对<b>退货退款</b>则是「同意退货」—— 要等买家寄回、你<b>确认收货</b>后才退款。
        「拒绝」必须写清原因，买家可以据此<b>申请平台仲裁</b>。
      </Alert>

      {/* D-05 · 退款商品 / SKU 统计：售后单是订单级的，拆不到单品；这里用订单明细的快照口径 */}
      <div className="card card-pad" style={{ margin: '14px 0' }}>
        <div className="row-between row-wrap" style={{ marginBottom: 8 }}>
          <h3 style={{ margin: 0, fontSize: 15 }}>退款商品 / SKU 统计</h3>
          <span className="small muted">
            合计退款 <strong>{formatPrice(refundTotalAmount)}</strong> · {refundTotalQty} 件
          </span>
        </div>
        <p className="small muted" style={{ margin: '0 0 8px' }}>
          口径：按下单时的订单明细快照汇总，退款中 / 已退款的明细计入商品规格；
          同一订单含多个规格时分别计入各自 SKU。
        </p>
        {refundStats.error ? (
          <Alert kind="warn">退款商品统计读取失败：{refundStats.error}（售后单列表仍可查看）</Alert>
        ) : refundStats.loading && !refundStats.data ? (
          <span className="small muted">正在汇总退款明细…</span>
        ) : refundRows.length === 0 ? (
          <span className="small muted">暂无退款明细；发生退款后会在这里按商品与规格汇总。</span>
        ) : (
          <div style={{ maxHeight: 300, overflow: 'auto' }}>
            <table className="table" style={{ width: '100%' }}>
              <thead>
                <tr>
                  <th>商品</th>
                  <th>规格</th>
                  <th style={{ textAlign: 'right' }}>退款件数</th>
                  <th style={{ textAlign: 'right' }}>退款金额</th>
                  <th style={{ textAlign: 'right' }}>涉及订单</th>
                </tr>
              </thead>
              <tbody>
                {refundRows.map((row) => (
                  <tr key={`${row.spuId}-${row.skuId}`}>
                    <td style={{ maxWidth: 280, wordBreak: 'break-all' }}>{row.name || `商品 ${row.spuId}`}</td>
                    <td>{row.specText || '默认规格'}</td>
                    <td style={{ textAlign: 'right' }}>{row.refundQty}</td>
                    <td style={{ textAlign: 'right' }}>{formatPrice(row.refundAmount)}</td>
                    <td style={{ textAlign: 'right' }}>{row.refundOrderCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

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
            <Badge count={counts[tab.value]} />
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
          placeholder="搜售后单号或订单号"
          width={300}
        />
        {keywordInput.trim() ? (
          <span className="small muted">匹配「{keywordInput.trim()}」的售后 {data ? data.total : 0} 条</span>
        ) : null}
        <button
          className="btn btn-ghost btn-sm"
          title="按当前状态与关键词导出本店售后（CSV）"
          onClick={() => {
            window.location.href = afterSaleApi.sellerExportUrl({
              status: status || undefined,
              keyword: keyword || undefined,
            });
          }}
        >
          导出 CSV
        </button>
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading ? <LoadingBlock text="正在加载售后单…" /> : null}

      {!loading && data && data.items.length === 0 ? (
        <Empty icon="🧾" title="当前没有售后单" desc="买家提起售后后会出现在这里" />
      ) : null}

      {!loading && data && data.items.length > 0 ? (
        <div>
          <BatchBar count={selection.count} onClear={selection.clear}>
            <button
              className="btn btn-primary btn-sm"
              disabled={busy}
              onClick={() => {
                setBatchAction('AGREE');
                setBatchReply('');
                setActionError(null);
                setBatchOpen(true);
              }}
            >
              批量同意
            </button>
            <button
              className="btn btn-ghost btn-sm"
              disabled={busy}
              onClick={() => {
                setBatchAction('REJECT');
                setBatchReply('');
                setActionError(null);
                setBatchOpen(true);
              }}
            >
              批量拒绝
            </button>
          </BatchBar>
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th style={{ width: 36 }}>
                  <input
                    type="checkbox"
                    aria-label="全选当前页售后单"
                    checked={selection.allSelected}
                    disabled={!pageIds.length}
                    onChange={selection.toggleAll}
                  />
                </th>
                <th>订单号</th>
                <th>类型</th>
                <th>状态</th>
                <th>诉求</th>
                <th>申请时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {[...data.items]
                .sort((a, b) => {
                  const byRead = Number(isRead(a)) - Number(isRead(b));
                  if (byRead !== 0) return byRead;
                  return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();
                })
                .map((item) => (
                <tr
                  key={item.id}
                  onClick={() => openPreview(item)}
                  style={{ cursor: 'pointer' }}
                  className={isRead(item) ? undefined : 'unread-card'}
                >
                  <td onClick={(e) => e.stopPropagation()}>
                    <div className="row" style={{ gap: 6, alignItems: 'center' }}>
                      <input
                        type="checkbox"
                        aria-label={`选择售后单 ${item.orderNo}`}
                        checked={selection.selectedSet.has(item.id)}
                        disabled={busy}
                        onChange={() => selection.toggle(item.id)}
                      />
                      {!isRead(item) ? <UnreadDot /> : null}
                    </div>
                  </td>
                  <td className="mono">{item.orderNo}</td>
                  <td>{item.typeLabel}</td>
                  <td>
                    <AfterSaleStatusTag status={item.status} label={item.statusLabel} />
                  </td>
                  <td style={{ maxWidth: 260 }}>{item.reason}</td>
                  <td>{formatDate(item.createdAt)}</td>
                  <td onClick={(e) => e.stopPropagation()}>
                    {item.status === 'PENDING' ? (
                      <button className="btn btn-sm btn-primary" onClick={() => openHandle(item)}>
                        处理
                      </button>
                    ) : item.status === 'RETURNING' ? (
                      // 买家已寄回：这一步才真正把钱退给买家，所以提示要写清后果
                      <button
                        className="btn btn-sm btn-primary"
                        disabled={busy}
                        onClick={() => confirmReceived(item)}
                      >
                        {busy ? '处理中…' : '确认收货并退款'}
                      </button>
                    ) : (
                      <span className="muted">
                        {item.status === 'WAITING_RETURN' ? '等待买家寄回' : '—'}
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        </div>
      ) : null}

      {/* 与商家端订单页、用户端售后页同一口径：只要有数据就渲染分页条，
          否则「共 0 条」时看不到任何页码信息，三端观感不一致 */}
      {data ? (
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
              {busy
                ? '处理中…'
                : action === 'AGREE'
                  ? handling?.type === 'RETURN_REFUND'
                    ? '同意退货（等寄回）'
                    : '同意并退款'
                  : '确认拒绝'}
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

      {/* 售后预览：点行打开即已读；执行处理仍走独立弹窗（预览不直接执行动作） */}
      <Modal
        open={preview !== null}
        title="售后预览"
        onClose={() => setPreview(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setPreview(null)}>
              关闭
            </button>
            {preview?.status === 'PENDING' ? (
              <button
                className="btn btn-primary"
                onClick={() => {
                  const item = preview;
                  setPreview(null);
                  if (item) openHandle(item);
                }}
              >
                去处理
              </button>
            ) : null}
          </>
        }
      >
        {preview ? (
          <div className="stack" style={{ gap: 10 }}>
            <div className="row row-wrap" style={{ gap: 8, alignItems: 'center' }}>
              <strong className="mono">订单 {preview.orderNo}</strong>
              <AfterSaleStatusTag status={preview.status} label={preview.statusLabel} />
              <span className="tag">{preview.typeLabel}</span>
            </div>
            <div className="card card-pad" style={{ padding: 12 }}>
              <div className="small muted">买家诉求</div>
              <div style={{ marginTop: 6 }}>{preview.reason || '—'}</div>
              {preview.images.length > 0 ? (
                <div className="row row-wrap" style={{ gap: 8, marginTop: 8 }}>
                  {preview.images.map((url) => (
                    <a key={url} href={url} target="_blank" rel="noreferrer">
                      <img
                        src={url}
                        alt="举证图"
                        style={{ width: 72, height: 72, borderRadius: 6, objectFit: 'cover' }}
                      />
                    </a>
                  ))}
                </div>
              ) : null}
            </div>
            {preview.sellerReply ? (
              <div className="small muted">卖家处理说明：{preview.sellerReply}</div>
            ) : null}
            {preview.arbitrateNote ? (
              <div className="small muted">平台裁定：{preview.arbitrateNote}</div>
            ) : null}
            {preview.refundAmount !== null ? (
              <div className="small muted">退款金额：{formatPrice(preview.refundAmount)}</div>
            ) : null}
            {preview.returnCourier || preview.returnTrackingNo ? (
              <div className="small muted">
                寄回物流：{preview.returnCourier ?? '—'} {preview.returnTrackingNo ?? ''}
              </div>
            ) : null}
            <div className="small muted">申请时间：{formatDate(preview.createdAt)}</div>
            <div className="small muted">
              打开预览即视为已读：橙点消失；已退款 / 已关闭 / 卖家已拒绝的角标同步 -1，
              待卖家处理 / 待买家寄回仍保留提醒（事情还没办完）。
            </div>
          </div>
        ) : null}
      </Modal>

      {/* 批量处理：动作与说明对选中单共用；只有「待卖家处理」会成功 */}
      <Modal
        open={batchOpen}
        title={batchAction === 'AGREE' ? '批量同意售后' : '批量拒绝售后'}
        onClose={() => setBatchOpen(false)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setBatchOpen(false)} disabled={busy}>
              取消
            </button>
            <button
              className={`btn ${batchAction === 'AGREE' ? 'btn-primary' : 'btn-danger'}`}
              disabled={busy}
              onClick={submitBatch}
            >
              {busy ? '处理中…' : `确认处理（${selection.count} 条）`}
            </button>
          </>
        }
      >
        <Alert kind="warn">
          将处理 <b>{selection.count}</b> 条售后（仅当前页），动作与说明对它们共用。
          只有<b>待卖家处理</b>的售后会成功；其余状态会逐条失败并给出原因。
          {batchAction === 'AGREE'
            ? '仅退款会立刻执行退款；退货退款只代表「同意退货」，钱要等买家寄回并签收后才动。'
            : '拒绝后买家可以据此申请平台仲裁。'}
        </Alert>
        <div className="stack-sm" style={{ marginTop: 12 }}>
          <label className="checkbox-row">
            <input
              type="radio"
              name="after-sale-batch-action"
              checked={batchAction === 'AGREE'}
              onChange={() => setBatchAction('AGREE')}
            />
            <span>同意（仅退款立刻退款 / 退货退款等寄回）</span>
          </label>
          <label className="checkbox-row">
            <input
              type="radio"
              name="after-sale-batch-action"
              checked={batchAction === 'REJECT'}
              onChange={() => setBatchAction('REJECT')}
            />
            <span>拒绝（必须填写说明原因）</span>
          </label>
        </div>
        <Field
          label={batchAction === 'REJECT' ? '拒绝原因（必填）' : '处理说明（可选）'}
          required={batchAction === 'REJECT'}
          hint="会展示给买家，并作为后续仲裁的依据"
        >
          <textarea
            className="textarea"
            rows={3}
            maxLength={500}
            value={batchReply}
            onChange={(e) => setBatchReply(e.target.value)}
            placeholder={
              batchAction === 'REJECT'
                ? '例如：商品已拆封使用，影响二次销售，不同意退款'
                : '例如：已核对举证，同意退款'
            }
          />
        </Field>
        {actionError ? <Alert kind="error">{actionError}</Alert> : null}
      </Modal>

      <BatchResultModal result={batchResult} onClose={() => setBatchResult(null)} idLabel="售后单" />
    </div>
  );
};

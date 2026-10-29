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

import React, { useCallback, useEffect, useState } from 'react';
import { adminApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import type { AfterSaleArbitrateResult, AfterSaleView, BatchResultView } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, SearchBar, formatDate, formatPrice, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { BatchBar, BatchResultModal, useBatchSelection } from '@duoduo/shared/components/batch';
import { useAuth } from '@duoduo/shared/store/auth';
import { AFTER_SALE_STATUS_TABS, AfterSaleStatusTag } from '@duoduo/shared/components/afterSale';
import { UnreadDot } from '@duoduo/shared/components/badge';
import { notifyBadgeChanged } from '@duoduo/shared/lib/badge-events';
import { AdminDenied, AdminTabs, isOperator } from './shared';

/** 可裁定的状态（终态不可再改判 —— 要改判只能新立案） */
const ARBITRABLE = ['PENDING', 'SELLER_REJECTED', 'ARBITRATING'];

export const AdminAfterSalesPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();

  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);

  const [target, setTarget] = useState<AfterSaleView | null>(null);
  const [result, setResult] = useState<AfterSaleArbitrateResult>('REJECT');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  /** 批量裁定（2026-10-27 · 批量操作即已读）：结论与说明对选中单共用 */
  const [batchOpen, setBatchOpen] = useState(false);
  const [batchResultChoice, setBatchResultChoice] = useState<AfterSaleArbitrateResult>('REJECT');
  const [batchNote, setBatchNote] = useState('');
  const [batchResult, setBatchResult] = useState<BatchResultView | null>(null);

  /** 逐条已读覆盖（2026-10-27 · 橙色未读印记） */
  const [readIds, setReadIds] = useState<Set<string>>(new Set());
  useEffect(() => {
    setReadIds(new Set());
  }, [status, keyword, page]);
  const isRead = (item: AfterSaleView) => item.read === true || readIds.has(item.id);
  const [preview, setPreview] = useState<AfterSaleView | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(
      () => adminApi.afterSales({ status: status || undefined, keyword: keyword || undefined, page, pageSize: 10 }),
      [status, keyword, page],
    ),
    operator,
    [status, keyword, page],
  );

  const pageIds = (data?.items ?? []).map((item) => item.id);
  const selection = useBatchSelection(pageIds, `${status}|${keyword}|${page}`);

  if (!operator) return <AdminDenied />;

  /**
   * 打开裁定弹窗 = 已读（2026-10-27 · 点击即消除）。
   *
   * 售后没有独立详情页，弹窗里就是买家诉求 / 证据 / 裁定表单。
   * 上报失败不本地减，等下一轮服务端值回填。
   */
  const markSeen = async (item: AfterSaleView) => {
    setReadIds((prev) => new Set(prev).add(item.id));
    try {
      await adminApi.markAfterSaleRead(item.id);
      notifyBadgeChanged();
    } catch {
      setReadIds((prev) => {
        const next = new Set(prev);
        next.delete(item.id);
        return next;
      });
    }
  };

  const openArbitrate = (item: AfterSaleView) => {
    setTarget(item);
    void markSeen(item);
    setResult('REJECT');
    setNote('');
    setActionError(null);
  };

  /** 点行预览 = 已读；裁定仍走独立弹窗 */
  const openPreview = (item: AfterSaleView) => {
    setPreview(item);
    void markSeen(item);
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

  const submitBatch = async () => {
    if (!selection.count || busy) return;
    setActionError(null);
    if (!batchNote.trim()) {
      setActionError('裁定必须填写说明');
      return;
    }
    setBusy(true);
    try {
      const result = await adminApi.batchArbitrateAfterSales(selection.selected, batchResultChoice, batchNote.trim());
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
          title="按当前状态与关键词导出全平台售后（CSV）"
          onClick={() => {
            window.location.href = adminApi.afterSaleExportUrl({
              status: status || undefined,
              keyword: keyword || undefined,
            });
          }}
        >
          导出 CSV
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
      {loading ? <LoadingBlock text="正在加载售后单…" /> : null}

      {!loading && data && data.items.length === 0 ? (
        <Empty
          icon="⚖️"
          title={keywordInput.trim() ? `没有匹配「${keywordInput.trim()}」的售后` : '没有符合条件的售后单'}
          desc="买家提起售后、或卖家拒绝后会出现在这里"
        />
      ) : null}

      {!loading && data && data.items.length > 0 ? (
        <div>
          <BatchBar count={selection.count} onClear={selection.clear}>
            <button
              className="btn btn-danger btn-sm"
              disabled={busy}
              onClick={() => {
                setBatchResultChoice('REJECT');
                setBatchNote('');
                setActionError(null);
                setBatchOpen(true);
              }}
            >
              批量裁定
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
                <th>买家诉求</th>
                <th>卖家说明</th>
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
                  <td style={{ maxWidth: 220 }}>{item.reason}</td>
                  <td style={{ maxWidth: 200 }} className="small muted">
                    {item.sellerReply ?? '—'}
                  </td>
                  <td>{formatDate(item.createdAt)}</td>
                  <td onClick={(e) => e.stopPropagation()}>
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
        </div>
      ) : null}

      {data ? (
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

      {/* 售后预览：点行打开即已读；裁定仍走独立弹窗 */}
      <Modal
        open={preview !== null}
        title="售后预览"
        onClose={() => setPreview(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setPreview(null)}>
              关闭
            </button>
            {preview && ARBITRABLE.includes(preview.status) ? (
              <button
                className="btn btn-primary"
                onClick={() => {
                  const item = preview;
                  setPreview(null);
                  if (item) openArbitrate(item);
                }}
              >
                去裁定
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
            <div className="small muted">申请时间：{formatDate(preview.createdAt)}</div>
          </div>
        ) : null}
      </Modal>

      {/* 批量裁定：结论与说明共用；已终结的单逐条失败 */}
      <Modal
        open={batchOpen}
        title="批量售后裁定"
        onClose={() => setBatchOpen(false)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setBatchOpen(false)} disabled={busy}>
              取消
            </button>
            <button
              className={`btn ${batchResultChoice === 'APPROVE_REFUND' ? 'btn-primary' : 'btn-danger'}`}
              disabled={busy}
              onClick={submitBatch}
            >
              {busy
                ? '提交中…'
                : batchResultChoice === 'APPROVE_REFUND'
                  ? `支持退款并执行（${selection.count} 条）`
                  : `确认驳回（${selection.count} 条）`}
            </button>
          </>
        }
      >
        <Alert kind="warn">
          将裁定 <b>{selection.count}</b> 条售后（仅当前页），结论与说明对它们共用。
          只有<b>待处理 / 卖家已拒绝 / 仲裁中</b>的单会成功，已终结的逐条失败。
          「支持退款」会真的执行退款，不可撤销；已完成订单若钱已放款，会因资金边界失败。
        </Alert>
        <Field label="裁定结论" required>
          <div className="stack-sm">
            <label className="checkbox-row">
              <input
                type="radio"
                name="arbitrate-batch-result"
                checked={batchResultChoice === 'APPROVE_REFUND'}
                onChange={() => setBatchResultChoice('APPROVE_REFUND')}
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
                name="arbitrate-batch-result"
                checked={batchResultChoice === 'REJECT'}
                onChange={() => setBatchResultChoice('REJECT')}
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
        <Field label="裁定说明（必填）" required hint="这是事后复盘与客诉处理的唯一凭据，请写清依据">
          <textarea
            className="textarea"
            rows={3}
            maxLength={500}
            placeholder="例如：买家举证显示外包装破损、内袋漏气，属发货包装不当，支持退款"
            value={batchNote}
            onChange={(e) => setBatchNote(e.target.value)}
          />
        </Field>
        {actionError ? <Alert kind="error">{actionError}</Alert> : null}
      </Modal>

      <BatchResultModal result={batchResult} onClose={() => setBatchResult(null)} idLabel="售后单" />
    </div>
  );
};

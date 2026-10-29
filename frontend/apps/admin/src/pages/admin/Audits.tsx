/**
 * 平台管理后台 · 闲置内容审核（M10）。
 *
 * 数据源：GET /api/admin/listings（含未审核通过的内容）+ POST /{id}/audit。
 *
 * 公开的 /api/listings 只暴露「已审核通过」；这里是审核工作台，
 * 把待审 / 已驳回 / 全部给运营看。驳回必须填原因 —— 不然卖家不知道为何被拒。
 */

import React, { useCallback, useState } from 'react';
import { adminApi } from '@duoduo/api-sdk/endpoints';
import type { BatchResultView, ListingAuditView } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, SearchBar, formatPrice, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { BatchBar, BatchResultModal, useBatchSelection } from '@duoduo/shared/components/batch';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { notifyBadgeChanged } from '@duoduo/shared/lib/badge-events';
import { useAuth } from '@duoduo/shared/store/auth';
import { AdminDenied, AdminTabs, isOperator } from './shared';

const AUDIT_TABS = [
  { value: 'PENDING', label: '待审核' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: '', label: '全部' },
];

const AUDIT_LABELS: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
};

export const AdminAuditsPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();

  const [auditStatus, setAuditStatus] = useState('PENDING');
  const [page, setPage] = useState(1);
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);
  const [rejecting, setRejecting] = useState<ListingAuditView | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  /** 批量审核（2026-10-27 · 批量操作即已读）：结论与原因共用 */
  const [batchOpen, setBatchOpen] = useState(false);
  const [batchAction, setBatchAction] = useState<'APPROVE' | 'REJECT'>('APPROVE');
  const [batchReason, setBatchReason] = useState('');
  const [batchResult, setBatchResult] = useState<BatchResultView | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(
      () => adminApi.listingsForAudit({ auditStatus, keyword: keyword || undefined, page, pageSize: 10 }),
      [auditStatus, keyword, page],
    ),
    operator,
    [auditStatus, keyword, page],
  );

  const pageIds = (data?.items ?? []).map((item) => item.id);
  const selection = useBatchSelection(pageIds, `${auditStatus}|${keyword}|${page}`);

  if (!operator) return <AdminDenied />;

  const runAudit = async (id: string, action: 'APPROVE' | 'REJECT', rejectReason?: string) => {
    setActionError(null);
    setBusy(true);
    try {
      await adminApi.auditListing(id, { action, reason: rejectReason });
      push(action === 'APPROVE' ? '已通过审核' : '已驳回', 'success');
      setRejecting(null);
      setReason('');
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  /** 批量审核：成功条目服务端已处理，返回结果逐条；角标审完自然减少 */
  const submitBatch = async () => {
    if (!selection.count || busy) return;
    setActionError(null);
    if (batchAction === 'REJECT' && !batchReason.trim()) {
      setActionError('驳回闲置必须填写原因');
      return;
    }
    setBusy(true);
    try {
      const result = await adminApi.batchAuditListings(
        selection.selected,
        batchAction,
        batchAction === 'REJECT' ? batchReason.trim() : undefined,
      );
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

  const items = data?.items ?? [];
  const pageCount = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 内容审核</h2>
      <AdminTabs />

      <div className="row row-wrap" style={{ gap: 8, marginBottom: 14 }}>
        {AUDIT_TABS.map((tab) => (
          <button
            key={tab.value}
            className={`btn btn-sm ${auditStatus === tab.value ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => {
              setAuditStatus(tab.value);
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
          placeholder="搜标题、品种或城市"
          width={300}
        />
        {keywordInput.trim() ? (
          <span className="small muted">匹配「{keywordInput.trim()}」的闲置 {data ? data.total : 0} 条</span>
        ) : null}
        <label className="checkbox-row small" style={{ marginLeft: 'auto' }}>
          <input
            type="checkbox"
            checked={selection.allSelected}
            disabled={!pageIds.length}
            onChange={selection.toggleAll}
          />
          全选当前页
        </label>
        <a
          className="btn btn-sm btn-ghost"
          href={adminApi.listingAuditExportUrl({
            auditStatus: auditStatus || undefined,
            keyword: keywordInput.trim() || undefined,
          })}
        >
          导出 CSV
        </a>
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {actionError ? <Alert kind="error">{actionError}</Alert> : null}

      {loading ? (
        <LoadingBlock text="正在拉取审核队列…" />
      ) : items.length === 0 ? (
        <Empty title={keywordInput.trim() ? `没有匹配「${keywordInput.trim()}」的闲置` : '当前没有该状态的闲置内容'} />
      ) : (
        <div>
          <BatchBar count={selection.count} onClear={selection.clear}>
            <button
              className="btn btn-primary btn-sm"
              disabled={busy}
              onClick={() => {
                setBatchAction('APPROVE');
                setBatchReason('');
                setActionError(null);
                setBatchOpen(true);
              }}
            >
              批量通过
            </button>
            <button
              className="btn btn-ghost btn-sm"
              disabled={busy}
              onClick={() => {
                setBatchAction('REJECT');
                setBatchReason('');
                setActionError(null);
                setBatchOpen(true);
              }}
            >
              批量驳回
            </button>
          </BatchBar>
        <div className="stack">
          {items.map((item) => (
            <div className="card card-pad" key={item.id}>
              <div className="row-between row-wrap">
                <input
                  type="checkbox"
                  aria-label={`选择闲置 ${item.title}`}
                  checked={selection.selectedSet.has(item.id)}
                  disabled={busy}
                  onChange={() => selection.toggle(item.id)}
                  style={{ marginRight: 8, marginTop: 3 }}
                />
                <div style={{ flex: 1, minWidth: 220 }}>
                  <div style={{ fontWeight: 600 }}>{item.title}</div>
                  <div className="small muted" style={{ marginTop: 4 }}>
                    卖家 #{item.sellerId} · {formatPrice(item.price)} · {item.city ?? '—'} · 成色 {item.conditionLevel} · 提交于 {item.createdAt?.slice(0, 19).replace('T', ' ') ?? '—'}
                  </div>
                  <div className="small" style={{ marginTop: 4 }}>
                    状态：<b>{AUDIT_LABELS[item.auditStatus] ?? item.auditStatus}</b>
                    {item.auditReason ? <span className="muted">（原因：{item.auditReason}）</span> : null}
                  </div>
                </div>
                {item.auditStatus === 'PENDING' ? (
                  <div className="row">
                    <button
                      className="btn btn-primary btn-sm"
                      disabled={busy}
                      onClick={() => runAudit(item.id, 'APPROVE')}
                    >
                      通过
                    </button>
                    <button
                      className="btn btn-ghost btn-sm"
                      disabled={busy}
                      onClick={() => {
                        setRejecting(item);
                        setActionError(null);
                      }}
                    >
                      驳回
                    </button>
                  </div>
                ) : null}
              </div>
            </div>
          ))}
        </div>
        </div>
      )}

      {data ? (
        <Pagination page={page} pageCount={pageCount} total={data.total} onChange={setPage} />
      ) : null}

      <Modal
        open={rejecting != null}
        title="驳回闲置内容"
        onClose={() => setRejecting(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setRejecting(null)}>取消</button>
            <button
              className="btn btn-primary"
              disabled={busy || !reason.trim()}
              onClick={() => rejecting && runAudit(rejecting.id, 'REJECT', reason)}
            >
              {busy ? '处理中…' : '确认驳回'}
            </button>
          </>
        }
      >
        <Alert kind="info">
          驳回后该闲置将标记为「已驳回」，<b>不会对外展示</b>。必须填写原因，便于卖家知晓并整改。
        </Alert>
        <Field label="驳回原因" required>
          <textarea
            className="input"
            rows={3}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="如：图片涉嫌违规 / 描述含违禁信息 / 类目不合规"
            maxLength={200}
          />
        </Field>
      </Modal>

      {/* 批量审核：结论与原因共用；已处理的条目按幂等成功 */}
      <Modal
        open={batchOpen}
        title={batchAction === 'APPROVE' ? '批量通过闲置' : '批量驳回闲置'}
        onClose={() => setBatchOpen(false)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setBatchOpen(false)} disabled={busy}>
              取消
            </button>
            <button
              className={`btn ${batchAction === 'APPROVE' ? 'btn-primary' : 'btn-danger'}`}
              disabled={busy}
              onClick={submitBatch}
            >
              {busy
                ? '处理中…'
                : batchAction === 'APPROVE'
                  ? `确认通过（${selection.count} 条）`
                  : `确认驳回（${selection.count} 条）`}
            </button>
          </>
        }
      >
        <Alert kind="warn">
          将处理 <b>{selection.count}</b> 条闲置内容（仅当前页）。
          {batchAction === 'APPROVE'
            ? '通过后它们会出现在公开闲置列表；已通过的按幂等成功处理。'
            : '驳回后它们不会对外展示，必须填写原因，便于卖家知晓并整改。'}
        </Alert>
        {batchAction === 'REJECT' ? (
          <Field label="驳回原因（必填，对全部选中内容共用）" required>
            <textarea
              className="input"
              rows={3}
              value={batchReason}
              onChange={(e) => setBatchReason(e.target.value)}
              placeholder="如：图片涉嫌违规 / 描述含违禁信息 / 类目不合规"
              maxLength={200}
            />
          </Field>
        ) : null}
        {actionError ? <Alert kind="error">{actionError}</Alert> : null}
      </Modal>

      <BatchResultModal result={batchResult} onClose={() => setBatchResult(null)} idLabel="闲置" />
    </div>
  );
};

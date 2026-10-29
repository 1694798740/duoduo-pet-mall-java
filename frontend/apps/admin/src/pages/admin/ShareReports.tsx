/**
 * 平台管理后台 · 分享 / 评论举报处理台（第五十六轮）。
 *
 * 数据源：
 *   GET  /api/admin/share-reports                —— 全平台举报列表（按状态过滤）
 *   POST /api/admin/share-reports/{id}/handle    —— 处理：ACCEPT / REJECT，**说明必填**
 *
 * 这是分享圈的「秩序兜底」：用户可以举报垃圾广告、引战辱骂等，平台在这里给出结论。
 * 「ACCEPT」会**真的把内容下架**（分享 / 评论软删 + 回退计数），所以确认文案必须写清后果。
 *
 * 两条刻意的产品取舍：
 *  ① **列表里直接显示被举报内容的摘要**（服务端 targetExcerpt）——
 *     要运营逐条点开才知道在举报什么的话，这个入口迟早没人用；
 *  ② **不做自动判定**（关键词 / 模型）也不做「被举报 N 次自动下架」：
 *     那需要误伤评估与申诉链路，是另一个量级的产品决策，本轮明确不做。
 */

import React, { useCallback, useEffect, useState } from 'react';
import { adminApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import type { BatchResultView, ShareReportView } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, SearchBar, formatDate, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { BatchBar, BatchResultModal, useBatchSelection } from '@duoduo/shared/components/batch';
import { UnreadDot } from '@duoduo/shared/components/badge';
import { useAuth } from '@duoduo/shared/store/auth';
import { notifyBadgeChanged } from '@duoduo/shared/lib/badge-events';
import { AdminDenied, AdminTabs, isOperator } from './shared';

/** 状态筛选页签（'' = 全部） */
const STATUS_TABS = [
  { value: 'PENDING', label: '待处理' },
  { value: 'ACCEPTED', label: '已成立' },
  { value: 'REJECTED', label: '已驳回' },
  { value: '', label: '全部' },
];

const STATUS_LABEL: Record<string, string> = {
  PENDING: '待处理',
  ACCEPTED: '举报成立',
  REJECTED: '举报不成立',
};

const TARGET_LABEL: Record<string, string> = {
  SHARE: '分享',
  COMMENT: '评论',
};

const statusTagClass = (status: string): string => {
  if (status === 'PENDING') return 'tag tag-warn';
  if (status === 'ACCEPTED') return 'tag tag-danger';
  return 'tag';
};

export const AdminShareReportsPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();

  const [status, setStatus] = useState('PENDING');
  const [page, setPage] = useState(1);
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);

  const [target, setTarget] = useState<ShareReportView | null>(null);
  const [action, setAction] = useState<'ACCEPT' | 'REJECT'>('REJECT');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  /** 批量处理（2026-10-27 · 批量操作即已读）：结论与说明对选中举报共用 */
  const [batchOpen, setBatchOpen] = useState(false);
  const [batchAction, setBatchAction] = useState<'ACCEPT' | 'REJECT'>('REJECT');
  const [batchNote, setBatchNote] = useState('');
  const [batchResult, setBatchResult] = useState<BatchResultView | null>(null);

  /** 逐条已读覆盖（2026-10-27 · 橙色未读印记） */
  const [readIds, setReadIds] = useState<Set<string>>(new Set());
  useEffect(() => {
    setReadIds(new Set());
  }, [status, keyword, page]);
  const isRead = (item: ShareReportView) => item.read === true || readIds.has(item.id);

  const { data, loading, error, reload } = useAsync(
    useCallback(
      () => adminApi.shareReports({ status: status || undefined, keyword: keyword || undefined, page, pageSize: 10 }),
      [status, keyword, page],
    ),
    operator,
    [status, keyword, page],
  );

  const pageIds = (data?.items ?? []).map((item) => item.id);
  const selection = useBatchSelection(pageIds, `${status}|${keyword}|${page}`);

  if (!operator) return <AdminDenied />;

  /**
   * 打开处理弹窗 = 已读（2026-10-27 · 点击即消除）。
   *
   * 举报的「详情」就是这个弹窗（理由 / 摘要 / 处理表单）。
   * 上报失败不本地减：等下一轮服务端值回填，避免角标闪回。
   */
  const markSeen = async (item: ShareReportView) => {
    setReadIds((prev) => new Set(prev).add(item.id));
    try {
      await adminApi.markShareReportRead(item.id);
      notifyBadgeChanged();
    } catch {
      setReadIds((prev) => {
        const next = new Set(prev);
        next.delete(item.id);
        return next;
      });
    }
  };

  const openHandle = (item: ShareReportView) => {
    setTarget(item);
    void markSeen(item);
    // 默认选「驳回」：误点一次确认不会把别人的内容下架（与售后裁定弹窗同一取舍）
    setAction('REJECT');
    setNote('');
    setActionError(null);
  };

  const submit = async () => {
    if (!target) return;
    setActionError(null);
    if (!note.trim()) {
      setActionError('处理说明必填 —— 用户看到的就是它');
      return;
    }
    setBusy(true);
    try {
      await adminApi.handleShareReport(target.id, { action, note: note.trim() });
      push(action === 'ACCEPT' ? '举报成立，内容已下架' : '举报已驳回', 'success');
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
      setActionError('处理说明必填 —— 用户看到的就是它');
      return;
    }
    setBusy(true);
    try {
      const result = await adminApi.batchHandleShareReports(selection.selected, batchAction, batchNote.trim());
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
      <h2 className="section-title">平台管理后台 · 举报处理</h2>
      <AdminTabs />

      <Alert kind="info">
        判定「举报成立」会<b>立刻下架被举报的内容</b>（分享会从分享圈消失、评论不再展示），
        不可撤销。若内容<i>明显不构成违规</i>，选「举报不成立」并写清依据 ——
        处理说明是回应申诉与事后复盘的唯一凭据。
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
          placeholder="搜举报理由或被举报内容 ID"
          width={320}
        />
        {keywordInput.trim() ? (
          <span className="small muted">匹配「{keywordInput.trim()}」的举报 {data ? data.total : 0} 条</span>
        ) : null}
        <button
          className="btn btn-ghost btn-sm"
          title="按当前状态与关键词导出全平台举报（CSV）"
          onClick={() => {
            window.location.href = adminApi.shareReportExportUrl({
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
      {loading ? <LoadingBlock text="正在加载举报…" /> : null}

      {!loading && data && data.items.length === 0 ? (
        <Empty
          icon="🚩"
          title={keywordInput.trim() ? `没有匹配「${keywordInput.trim()}」的举报` : '没有符合条件的举报'}
          desc="用户举报分享或评论后会出现在这里"
        />
      ) : null}

      {!loading && data && data.items.length > 0 ? (
        <div>
          <BatchBar count={selection.count} onClear={selection.clear}>
            <button
              className="btn btn-danger btn-sm"
              disabled={busy}
              onClick={() => {
                setBatchAction('ACCEPT');
                setBatchNote('');
                setActionError(null);
                setBatchOpen(true);
              }}
            >
              批量成立（下架）
            </button>
            <button
              className="btn btn-primary btn-sm"
              disabled={busy}
              onClick={() => {
                setBatchAction('REJECT');
                setBatchNote('');
                setActionError(null);
                setBatchOpen(true);
              }}
            >
              批量驳回
            </button>
          </BatchBar>
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th style={{ width: 36 }}>
                  <input
                    type="checkbox"
                    aria-label="全选当前页举报"
                    checked={selection.allSelected}
                    disabled={!pageIds.length}
                    onChange={selection.toggleAll}
                  />
                </th>
                <th>类型</th>
                <th>被举报内容</th>
                <th>举报理由</th>
                <th>状态</th>
                <th>举报时间</th>
                <th>处理</th>
              </tr>
            </thead>
            <tbody>
              {[...data.items]
                .sort((a, b) => Number(isRead(a)) - Number(isRead(b)))
                .map((item) => (
                <tr key={item.id} className={isRead(item) ? undefined : 'unread-card'}>
                  <td>
                    <div className="row" style={{ gap: 6, alignItems: 'center' }}>
                      <input
                        type="checkbox"
                        aria-label={`选择举报 ${item.id}`}
                        checked={selection.selectedSet.has(item.id)}
                        disabled={busy}
                        onChange={() => selection.toggle(item.id)}
                      />
                      {!isRead(item) ? <UnreadDot /> : null}
                    </div>
                  </td>
                  <td>{TARGET_LABEL[item.targetType] ?? item.targetType}</td>
                  <td style={{ maxWidth: 260 }}>{item.targetExcerpt}</td>
                  <td style={{ maxWidth: 200 }}>{item.reason}</td>
                  <td>
                    <span className={statusTagClass(item.status)}>
                      {STATUS_LABEL[item.status] ?? item.status}
                    </span>
                    {item.handleNote ? (
                      <div className="small muted" style={{ marginTop: 4, maxWidth: 200 }}>
                        {item.handleNote}
                      </div>
                    ) : null}
                  </td>
                  <td>{formatDate(item.createdAt)}</td>
                  <td>
                    {item.status === 'PENDING' ? (
                      <button className="btn btn-sm btn-primary" onClick={() => openHandle(item)}>
                        处理
                      </button>
                    ) : (
                      <span className="muted">
                        {item.handledAt ? formatDate(item.handledAt) : '已处理'}
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
        title="处理举报"
        onClose={() => setTarget(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setTarget(null)}>
              取消
            </button>
            <button
              className={`btn ${action === 'ACCEPT' ? 'btn-danger' : 'btn-primary'}`}
              disabled={busy}
              onClick={submit}
            >
              {busy ? '提交中…' : action === 'ACCEPT' ? '下架该内容' : '驳回举报'}
            </button>
          </>
        }
      >
        {target ? (
          <div>
            <div className="card card-pad" style={{ marginBottom: 12 }}>
              <div className="small muted">
                {TARGET_LABEL[target.targetType] ?? target.targetType} · 被举报内容
              </div>
              <div style={{ marginTop: 6 }}>{target.targetExcerpt}</div>
              <div className="small muted" style={{ marginTop: 10 }}>
                举报理由
              </div>
              <div style={{ marginTop: 6 }}>{target.reason}</div>
              <div className="small muted" style={{ marginTop: 10 }}>
                举报人 {target.reporterId} · 内容作者 {target.targetAuthorId ?? '—'}
              </div>
            </div>

            <Field label="处理结论" required>
              <div className="stack-sm">
                <label className="checkbox-row">
                  <input
                    type="radio"
                    name="report-action"
                    checked={action === 'REJECT'}
                    onChange={() => setAction('REJECT')}
                  />
                  <span>
                    <b>举报不成立</b>
                    <span className="small muted" style={{ display: 'block' }}>
                      内容保留，不做改动
                    </span>
                  </span>
                </label>
                <label className="checkbox-row">
                  <input
                    type="radio"
                    name="report-action"
                    checked={action === 'ACCEPT'}
                    onChange={() => setAction('ACCEPT')}
                  />
                  <span>
                    <b>举报成立（下架内容）</b>
                    <span className="small muted" style={{ display: 'block' }}>
                      分享会从分享圈消失、评论不再展示，不可撤销
                    </span>
                  </span>
                </label>
              </div>
            </Field>

            <Field
              label="处理说明（必填）"
              required
              hint="用户看到的就是它；也是事后复盘的唯一凭据"
            >
              <textarea
                className="textarea"
                rows={3}
                maxLength={200}
                placeholder="例如：内容含引流二维码，属垃圾广告，予以下架"
                value={note}
                onChange={(e) => setNote(e.target.value)}
              />
            </Field>

            {actionError ? <Alert kind="error">{actionError}</Alert> : null}
          </div>
        ) : null}
      </Modal>

      {/* 批量处理：结论与说明共用；已处理过的一条逐条失败 */}
      <Modal
        open={batchOpen}
        title="批量处理举报"
        onClose={() => setBatchOpen(false)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setBatchOpen(false)} disabled={busy}>
              取消
            </button>
            <button
              className={`btn ${batchAction === 'ACCEPT' ? 'btn-danger' : 'btn-primary'}`}
              disabled={busy}
              onClick={submitBatch}
            >
              {busy
                ? '提交中…'
                : batchAction === 'ACCEPT'
                  ? `确认成立并下架（${selection.count} 条）`
                  : `确认驳回（${selection.count} 条）`}
            </button>
          </>
        }
      >
        <Alert kind="warn">
          将处理 <b>{selection.count}</b> 条举报（仅当前页），结论与说明对它们共用。
          已处理过的举报会逐条失败（409），不影响其它条。
          {batchAction === 'ACCEPT'
            ? '判定成立会立刻下架每一条被举报的内容，不可撤销。'
            : '判定不成立不改动内容，但仍会留下处理说明。'}
        </Alert>
        <Field label="处理结论" required>
          <div className="stack-sm">
            <label className="checkbox-row">
              <input
                type="radio"
                name="report-batch-action"
                checked={batchAction === 'REJECT'}
                onChange={() => setBatchAction('REJECT')}
              />
              <span>
                <b>举报不成立</b>
                <span className="small muted" style={{ display: 'block' }}>
                  内容保留，不做改动
                </span>
              </span>
            </label>
            <label className="checkbox-row">
              <input
                type="radio"
                name="report-batch-action"
                checked={batchAction === 'ACCEPT'}
                onChange={() => setBatchAction('ACCEPT')}
              />
              <span>
                <b>举报成立（下架内容）</b>
                <span className="small muted" style={{ display: 'block' }}>
                  分享会从分享圈消失、评论不再展示，不可撤销
                </span>
              </span>
            </label>
          </div>
        </Field>
        <Field label="处理说明（必填）" required hint="用户看到的就是它；也是事后复盘的唯一凭据">
          <textarea
            className="textarea"
            rows={3}
            maxLength={200}
            placeholder="例如：内容含引流二维码，属垃圾广告，予以下架"
            value={batchNote}
            onChange={(e) => setBatchNote(e.target.value)}
          />
        </Field>
        {actionError ? <Alert kind="error">{actionError}</Alert> : null}
      </Modal>

      <BatchResultModal result={batchResult} onClose={() => setBatchResult(null)} idLabel="举报" />
    </div>
  );
};

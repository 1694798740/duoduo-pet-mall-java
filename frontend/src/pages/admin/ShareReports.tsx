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

import React, { useCallback, useState } from 'react';
import { adminApi } from '../../api/endpoints';
import { toFriendlyMessage } from '../../api/client';
import type { ShareReportView } from '../../api/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, formatDate, useToast,
} from '../../components/ui';
import { useAsync } from '../../components/hooks';
import { useAuth } from '../../store/auth';
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

  const [target, setTarget] = useState<ShareReportView | null>(null);
  const [action, setAction] = useState<'ACCEPT' | 'REJECT'>('REJECT');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(
      () => adminApi.shareReports({ status: status || undefined, page, pageSize: 10 }),
      [status, page],
    ),
    operator,
    [status, page],
  );

  if (!operator) return <AdminDenied />;

  const openHandle = (item: ShareReportView) => {
    setTarget(item);
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

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading ? <LoadingBlock text="正在加载举报…" /> : null}

      {!loading && data && data.items.length === 0 ? (
        <Empty icon="🚩" title="没有符合条件的举报" desc="用户举报分享或评论后会出现在这里" />
      ) : null}

      {!loading && data && data.items.length > 0 ? (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>类型</th>
                <th>被举报内容</th>
                <th>举报理由</th>
                <th>状态</th>
                <th>举报时间</th>
                <th>处理</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((item) => (
                <tr key={item.id}>
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
    </div>
  );
};

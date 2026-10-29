/**
 * 平台管理后台 · 导出任务（矩阵第 9 项 · 2026-10-28）。
 *
 * <p>为什么要有这个页面：同步导出有 5 万行上限，超过后改走<b>异步任务</b> ——
 * 请求立刻返回，后台分页写 CSV，完成后推站内通知。任务与文件状态必须有一个
 * 可回来看的地方，否则「通知点完就找不到入口」。
 *
 * <p>两条产品口径：
 *  ① **只看自己的任务**：任务含筛选条件与生成的文件，跨人可见等于共享了别人的查询；
 *  ② **成功后 7 天内可重复下载**：不做一次性链接 —— 网络抖动导致下载失败时，
 *     一次性链接会把用户逼去重新生成一份几十万行的任务。
 */

import React, { useCallback, useEffect, useState } from 'react';
import { adminApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import type { ExportJobView, OrderStatus } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Pagination, formatDate, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';
import { useAuth } from '@duoduo/shared/store/auth';
import { AdminDenied, AdminTabs, isOperator } from './shared';

const STATUS_OPTIONS: { value: '' | OrderStatus; label: string }[] = [
  { value: '', label: '全部状态' },
  { value: 'PENDING_PAYMENT', label: '待付款' },
  { value: 'PAID', label: '已付款' },
  { value: 'SHIPPED', label: '已发货' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'CLOSED', label: '已关闭' },
];

/** 状态标签配色：进行中偏提示、失败偏危险、过期/完成走中性 */
const statusTagClass = (status: ExportJobView['status']): string => {
  if (status === 'PENDING') return 'tag tag-warn';
  if (status === 'RUNNING') return 'tag tag-success';
  if (status === 'FAILED') return 'tag tag-danger';
  return 'tag';
};

const fileSizeText = (bytes: number): string => {
  if (!bytes) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
};

export const AdminExportJobsPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();

  const [page, setPage] = useState(1);
  const [status, setStatus] = useState<'' | OrderStatus>('');
  const [keyword, setKeyword] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(() => adminApi.exportJobs({ page, pageSize: 10 }), [page]),
    operator,
    [page],
  );

  const jobs = data?.items ?? [];
  const running = jobs.some((job) => job.status === 'PENDING' || job.status === 'RUNNING');

  /**
   * 有进行中的任务时自动轮询（3 秒）。
   *
   * 依赖 running 而不是无条件轮询：全部结束后必须停 —— 一个永远在请求的页面
   * 会让「后台在跑任务」看起来像「这个页面自己有毛病」。
   */
  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => reload(), 3000);
    return () => window.clearInterval(timer);
  }, [running, reload]);

  if (!operator) return <AdminDenied />;

  const createJob = async () => {
    if (busy) return;
    setActionError(null);
    setBusy(true);
    try {
      const job = await adminApi.createOrderExportJob({
        status: status || undefined,
        keyword: keyword.trim() || undefined,
      });
      push(`导出任务已创建（预估 ${job.totalRows} 行），生成完成后可在此下载`, 'success');
      setPage(1);
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 导出任务</h2>
      <AdminTabs />

      <Alert kind="info">
        订单导出超过 <b>5 万行</b>时请用这里的「创建异步任务」：后台分页生成 CSV，
        完成后会收到站内通知。文件保留 <b>7 天</b>，期间可重复下载；只有<b>创建人</b>能看到与下载自己的任务。
      </Alert>

      <div className="card card-pad" style={{ margin: '14px 0' }}>
        <div className="row row-wrap" style={{ gap: 12, alignItems: 'flex-end' }}>
          <Field label="订单状态">
            <select
              className="input"
              value={status}
              onChange={(e) => setStatus(e.target.value as '' | OrderStatus)}
              style={{ minWidth: 140 }}
            >
              {STATUS_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="关键词（订单号 / 商品标题）">
            <input
              className="input"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="留空 = 全部订单"
              style={{ minWidth: 260 }}
            />
          </Field>
          <button className="btn btn-primary" disabled={busy} onClick={() => void createJob()}>
            {busy ? '创建中…' : '创建订单导出任务'}
          </button>
        </div>
        <div className="small muted" style={{ marginTop: 8 }}>
          与「订单干预」页的同步导出使用同一套筛选口径，行数不会对不上。
        </div>
      </div>

      {actionError ? <Alert kind="error">{actionError}</Alert> : null}
      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading ? <LoadingBlock text="正在加载导出任务…" /> : null}

      {!loading && data && jobs.length === 0 ? (
        <Empty
          icon="📄"
          title="还没有导出任务"
          desc="超过 5 万行的订单导出会生成任务，完成后在这里下载"
        />
      ) : null}

      {!loading && jobs.length > 0 ? (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>模块</th>
                <th>筛选</th>
                <th>状态</th>
                <th>进度</th>
                <th>创建时间</th>
                <th>文件</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((job) => {
                const percent = job.totalRows > 0
                  ? Math.min(100, Math.round((job.rowCount / job.totalRows) * 100))
                  : 0;
                return (
                  <tr key={job.id}>
                    <td>{job.moduleLabel}</td>
                    <td style={{ maxWidth: 260 }}>{job.filters}</td>
                    <td>
                      <span className={statusTagClass(job.status)}>{job.statusLabel}</span>
                      {job.errorMessage ? (
                        <div className="small muted" style={{ marginTop: 4, maxWidth: 240 }}>
                          {job.errorMessage}
                        </div>
                      ) : null}
                    </td>
                    <td style={{ minWidth: 160 }}>
                      {job.status === 'RUNNING' || job.status === 'PENDING' ? (
                        <div>
                          <div className="small muted">
                            {job.rowCount} / {job.totalRows} 行
                          </div>
                          <div
                            className="progress-track"
                            style={{ marginTop: 6, height: 6, borderRadius: 3, background: 'var(--border)' }}
                          >
                            <div
                              style={{
                                width: `${percent}%`,
                                height: '100%',
                                borderRadius: 3,
                                background: 'var(--primary)',
                                transition: 'width .3s ease',
                              }}
                            />
                          </div>
                        </div>
                      ) : (
                        <span className="small muted">
                          {job.status === 'SUCCESS' ? `${job.rowCount} 行` : '—'}
                        </span>
                      )}
                    </td>
                    <td>{formatDate(job.createdAt)}</td>
                    <td>
                      {job.status === 'SUCCESS' ? (
                        <div className="small">
                          <div>{fileSizeText(job.fileSize)}</div>
                          <div className="muted">有效至 {formatDate(job.expiresAt)}</div>
                        </div>
                      ) : (
                        <span className="small muted">—</span>
                      )}
                    </td>
                    <td>
                      {job.downloadable ? (
                        <button
                          className="btn btn-sm btn-primary"
                          onClick={() => {
                            window.location.href = adminApi.exportJobDownloadUrl(job.id);
                          }}
                        >
                          下载
                        </button>
                      ) : (
                        <span className="small muted">
                          {job.status === 'FAILED' ? '可重新创建任务' : job.status === 'EXPIRED' ? '已过期' : '生成中'}
                        </span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <Pagination
            page={page}
            pageCount={Math.max(1, data!.pageCount)}
            total={data!.total}
            onChange={setPage}
          />
        </div>
      ) : null}
    </div>
  );
};

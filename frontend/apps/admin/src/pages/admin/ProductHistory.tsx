/**
 * 平台管理后台 · 商品变更历史（H-03）。
 *
 * <p>与「商品审核」页的历史弹窗分工：那个是打开某件商品看它的时间线；
 * 这里是**先按条件找记录**，再点开看字段级改前改后 —— 运营复盘「昨天谁把哪件商品改成了什么」
 * 时不必先知道商品 id。
 *
 * <p>数据源：GET /api/admin/products/change-history（append-only 的 t_spu_audit_log）。
 * 自营商品免审、不进本表，因此这里只会有第三方商品。
 */

import React, { useCallback, useMemo, useState } from 'react';
import { adminApi } from '@duoduo/api-sdk/endpoints';
import type { SpuChangeHistoryItem } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, SearchBar, formatDate,
} from '@duoduo/shared/components/ui';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { useAuth } from '@duoduo/shared/store/auth';
import { AdminDenied, AdminTabs, isOperator } from './shared';

const EVENT_OPTIONS = [
  { value: '', label: '全部事件' },
  { value: 'SUBMIT', label: '提交' },
  { value: 'EDIT', label: '编辑内容' },
  { value: 'RESUBMIT', label: '重新提审' },
  { value: 'APPROVE', label: '审核通过' },
  { value: 'REJECT', label: '审核驳回' },
  { value: 'SAMPLE_REJECT', label: '抽检驳回' },
];

const EVENT_BADGE: Record<string, string> = {
  SUBMIT: 'tag',
  EDIT: 'tag tag-warn',
  RESUBMIT: 'tag tag-warn',
  APPROVE: 'tag tag-success',
  REJECT: 'tag tag-danger',
  SAMPLE_REJECT: 'tag tag-danger',
};

const PAGE_SIZE = 20;

const fmtTime = (value: string | null | undefined): string =>
  value ? value.slice(0, 19).replace('T', ' ') : '—';

export const AdminProductHistoryPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);

  const [spuId, setSpuId] = useState('');
  const [sellerId, setSellerId] = useState('');
  const [event, setEvent] = useState('');
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);
  const [page, setPage] = useState(1);
  const [detail, setDetail] = useState<SpuChangeHistoryItem | null>(null);

  const query = useMemo(() => ({
    spuId: spuId.trim() || undefined,
    sellerId: sellerId.trim() || undefined,
    event: event || undefined,
    keyword: keyword || undefined,
    page,
    pageSize: PAGE_SIZE,
  }), [spuId, sellerId, event, keyword, page]);

  const { data, loading, error, reload } = useAsync(
    useCallback(() => adminApi.productChangeHistory(query), [query]),
    operator,
    [query],
  );

  if (!operator) return <AdminDenied />;

  const items = data?.items ?? [];
  const total = data?.total ?? 0;
  const pageCount = data?.pageCount ?? Math.max(1, Math.ceil(total / PAGE_SIZE));

  const reset = () => {
    setSpuId('');
    setSellerId('');
    setEvent('');
    setKeywordInput('');
    setPage(1);
  };

  return (
    <div>
      <AdminTabs />
      <h2 className="section-title">商品变更历史</h2>

      <Alert kind="info">
        按商品 / 标题 / 事件 / 店铺查第三方商品的字段级改动。记录与业务写在同一事务里，
        <b>append-only</b>；自营商品免审，不进入本页。点「查看改动」可看每个字段的改前 / 改后。
      </Alert>

      <div className="card card-pad" style={{ margin: '14px 0' }}>
        <div className="row row-wrap" style={{ gap: 10, alignItems: 'flex-end' }}>
          <Field label="商品 ID">
            <input
              className="input"
              value={spuId}
              onChange={(e) => { setSpuId(e.target.value); setPage(1); }}
              placeholder="雪花 ID（可空）"
              style={{ width: 210 }}
            />
          </Field>
          <Field label="商品标题关键词">
            <input
              className="input"
              value={keywordInput}
              onChange={(e) => { setKeywordInput(e.target.value); setPage(1); }}
              placeholder="如：冻干 / 猫粮"
              style={{ width: 200 }}
            />
          </Field>
          <Field label="店铺主体 userId">
            <input
              className="input"
              value={sellerId}
              onChange={(e) => { setSellerId(e.target.value); setPage(1); }}
              placeholder="可空"
              style={{ width: 190 }}
            />
          </Field>
          <Field label="事件">
            <select
              className="input"
              value={event}
              onChange={(e) => { setEvent(e.target.value); setPage(1); }}
              style={{ width: 160 }}
            >
              {EVENT_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </Field>
          <button className="btn btn-ghost" onClick={reset}>重置</button>
          <button className="btn btn-primary" onClick={reload} disabled={loading}>查询</button>
        </div>
      </div>

      <div className="list-toolbar">
        <SearchBar
          value={keywordInput}
          onChange={(v) => { setKeywordInput(v); setPage(1); }}
          busy={loading && Boolean(keywordInput.trim())}
          placeholder="搜商品标题"
          width={260}
        />
        <span className="small muted">共 {total} 条记录</span>
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading ? <LoadingBlock text="正在加载变更历史…" /> : null}
      {!loading && !error && items.length === 0 ? (
        <Empty icon="🧾" title="没有匹配的变更记录" desc="换个商品 ID、标题关键词或事件再试" />
      ) : null}
      {!loading && items.length > 0 ? (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>时间</th>
                <th>商品</th>
                <th>事件</th>
                <th>操作人</th>
                <th>版本</th>
                <th>改动</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map((row) => (
                <tr key={row.id}>
                  <td className="small">{fmtTime(row.createdAt)}</td>
                  <td>
                    <div>{row.spuTitle ?? '—'}</div>
                    <div className="small muted">#{row.spuId ?? '—'} · 店铺 {row.sellerId ?? '—'}</div>
                  </td>
                  <td><span className={EVENT_BADGE[row.event] ?? 'tag'}>{row.eventLabel}</span></td>
                  <td className="small">
                    {row.actorLabel}
                    {row.actorId ? ` #${row.actorId}` : ''}
                    {row.actorPhone ? <div className="small muted">{row.actorPhone}</div> : null}
                  </td>
                  <td className="small">{row.versionBefore ?? '—'} → {row.versionAfter ?? '—'}</td>
                  <td className="small">
                    {row.changes.length > 0
                      ? `${row.changes.length} 个字段`
                      : (row.reason ? '仅结论 / 原因' : '无字段变化')}
                  </td>
                  <td>
                    <button className="btn btn-sm btn-ghost" onClick={() => setDetail(row)}>
                      查看改动
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pagination page={page} pageCount={Math.max(1, pageCount)} total={total} onChange={setPage} />
        </div>
      ) : null}

      <Modal
        open={detail != null}
        title={`变更详情 · ${detail?.spuTitle ?? ''}`}
        onClose={() => setDetail(null)}
        wide
      >
        {detail ? (
          <div>
            <div className="small muted" style={{ marginBottom: 10 }}>
              商品 #{detail.spuId} · 店铺 {detail.sellerId ?? '—'} · {fmtTime(detail.createdAt)}
              {' · '}{detail.eventLabel}{detail.actorId ? ` · ${detail.actorLabel} #${detail.actorId}` : ''}
              <br />
              审核态：{detail.fromAuditStatus ?? '—'} → {detail.toAuditStatus ?? '—'}
              {' · '}版本 {detail.versionBefore ?? '—'} → {detail.versionAfter ?? '—'}
              {detail.reason ? <><br />原因：{detail.reason}</> : null}
            </div>
            {detail.changes.length === 0 ? (
              <Empty icon="📄" title="本条没有字段级改动" desc="提交 / 审核结论等事件只记录状态与版本" />
            ) : (
              <div style={{ display: 'grid', gap: 10 }}>
                {detail.changes.map((change) => (
                  <div key={change.field} className="card card-pad" style={{ padding: 12 }}>
                    <div style={{ fontWeight: 600, marginBottom: 6 }}>{change.label}</div>
                    <div className="row row-wrap" style={{ gap: 8, alignItems: 'flex-start' }}>
                      <div style={{ flex: 1, minWidth: 240 }}>
                        <div className="small muted">改前</div>
                        <div className="small" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                          {change.before ?? '（空）'}
                        </div>
                      </div>
                      <div style={{ flex: 1, minWidth: 240 }}>
                        <div className="small muted">改后</div>
                        <div className="small" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                          {change.after ?? '（空）'}
                        </div>
                      </div>
                    </div>
                    {change.truncated ? <div className="small muted">超长值已截断（最多 2000 字）</div> : null}
                  </div>
                ))}
              </div>
            )}
            <div className="small muted" style={{ marginTop: 12 }}>
              记录时间：{detail.createdAt ? formatDate(detail.createdAt) : '—'}
            </div>
          </div>
        ) : null}
      </Modal>
    </div>
  );
};

/**
 * 平台管理后台 · 第三方商品上架审核（E14）。
 *
 * 数据源：GET /api/admin/products（只含**第三方**商品）+ POST /{spuId}/audit。
 *
 * 公开的商品接口（/api/products 系列）只暴露「在售 **且** 已过审」的商品；
 * 这里是审核工作台，把待审 / 已驳回 / 已通过 / 全部给运营看。
 *
 * 与闲置内容审核（Audits.tsx）刻意保持同一套交互：同样的四标签、同样的
 * 「驳回必须填原因」、同样的一次性卡片流 —— 运营在两处不必学两套规则。
 * 差别在于商品要核对的东西更多（主图 / 详情 / 规格价格 / 类目品牌），
 * 所以卡片里直接摊开背审材料，避免「点进二级页才发现缺图」。
 *
 * 审核**可重入**（后端允许改判）：已通过的商品仍给出「驳回改判」入口 ——
 * 把改判逼成改库，才会留下无法追溯的结论。
 */

import React, { useCallback, useState } from 'react';
import { adminApi } from '@duoduo/api-sdk/endpoints';
import type { AdminProductAuditView, BatchResultView, SpuAuditLogView } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, SearchBar, SupplyTag, formatPrice, useToast,
} from '@duoduo/shared/components/ui';
import { RichTextView } from '@duoduo/shared/components/RichText';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { BatchBar, BatchResultModal, useBatchSelection } from '@duoduo/shared/components/batch';
import { ApiError, toFriendlyMessage } from '@duoduo/api-sdk/client';
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

/** 审核态徽标配色（复用全局 .tag-* 语义色，与商家端「我的商品」一致） */
const AUDIT_BADGE: Record<string, string> = {
  PENDING: 'tag tag-warn',
  APPROVED: 'tag tag-success',
  REJECTED: 'tag tag-danger',
};

/** 审核来源（A-08）：历史行要能回答「这条结论是怎么来的」 */
const SOURCE_LABELS: Record<string, string> = {
  AUTO: '规则自动放行',
  MANUAL: '人工审核',
  SELF: '自营免审',
  SAMPLE: '事后抽查',
};

const EVENT_BADGE: Record<string, string> = {
  SUBMIT: 'tag',
  EDIT: 'tag tag-warn',
  RESUBMIT: 'tag tag-warn',
  APPROVE: 'tag tag-success',
  REJECT: 'tag tag-danger',
  SAMPLE_REJECT: 'tag tag-danger',
};

const fmtTime = (value: string | null | undefined): string =>
  value ? value.slice(0, 19).replace('T', ' ') : '—';

/** 规格行摘要：规格名 · 价格 · 库存 */
const skuSummary = (sku: AdminProductAuditView['skus'][number]): string => {
  const parts: string[] = [];
  if (sku.specName) parts.push(sku.specName);
  parts.push(formatPrice(sku.price));
  parts.push(`库存 ${sku.stock}`);
  return parts.join(' · ');
};

export const AdminProductAuditsPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();

  const [auditStatus, setAuditStatus] = useState('PENDING');
  const [sellerInput, setSellerInput] = useState('');
  const [sellerId, setSellerId] = useState<number | undefined>(undefined);
  const [page, setPage] = useState(1);
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);

  const [rejecting, setRejecting] = useState<AdminProductAuditView | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  /** 批量审核（2026-10-27 · 批量操作即已读）：逐件带 version，结论与原因共用 */
  const [batchOpen, setBatchOpen] = useState(false);
  const [batchAction, setBatchAction] = useState<'APPROVE' | 'REJECT'>('APPROVE');
  const [batchReason, setBatchReason] = useState('');
  const [batchResult, setBatchResult] = useState<BatchResultView | null>(null);

  // A-08：审核历史（按需拉取，不跟着列表翻页反复请求）
  const [historyOf, setHistoryOf] = useState<AdminProductAuditView | null>(null);
  const [history, setHistory] = useState<SpuAuditLogView[] | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(
      () => adminApi.productsForAudit({
        auditStatus, sellerId, keyword: keyword || undefined, page, pageSize: 10,
      }),
      [auditStatus, sellerId, keyword, page],
    ),
    operator,
    [auditStatus, sellerId, keyword, page],
  );

  const pageIds = (data?.items ?? []).map((item) => item.id);
  const selection = useBatchSelection(pageIds, `${auditStatus}|${sellerId ?? ''}|${keyword}|${page}`);

  if (!operator) return <AdminDenied />;

  const applySellerFilter = () => {
    const trimmed = sellerInput.trim();
    if (trimmed === '') {
      setSellerId(undefined);
      setPage(1);
      return;
    }
    const parsed = Number(trimmed);
    if (!Number.isFinite(parsed) || parsed <= 0) {
      setActionError('卖家 ID 必须是正整数');
      return;
    }
    setActionError(null);
    setSellerId(parsed);
    setPage(1);
  };

  const runAudit = async (
    spuId: string,
    version: number,
    action: 'APPROVE' | 'REJECT',
    rejectReason?: string,
  ) => {
    setActionError(null);
    setBusy(true);
    try {
      await adminApi.auditProduct(spuId, { action, reason: rejectReason, version });
      push(action === 'APPROVE' ? '已通过审核' : '已驳回', 'success');
      setRejecting(null);
      setReason('');
      reload();
    } catch (err) {
      // 409 = 卖家在运营核对期间改了内容（或已重提审）。此时手上的结论对应的是旧内容，
      // 不能静默重试，也不能留在弹窗里「再点一次」—— 关掉并刷新，逼运营重新看一遍。
      if (err instanceof ApiError && err.status === 409) {
        setActionError('商品内容已被卖家修改，列表已刷新，请重新核对后再审核');
        setRejecting(null);
        setReason('');
        reload();
      } else {
        setActionError(toFriendlyMessage(err));
      }
    } finally {
      setBusy(false);
    }
  };

  /**
   * 批量审核商品（2026-10-27 · 批量操作即已读）。
   *
   * <p>逐件把当前列表里的 version 带上（乐观锁，不能共用一个）；
   * 卖家在此期间改了内容的那件会以 409 失败并出现在结果里，其余继续。
   */
  const submitBatch = async () => {
    if (!selection.count || busy) return;
    setActionError(null);
    if (batchAction === 'REJECT' && !batchReason.trim()) {
      setActionError('驳回商品必须填写原因');
      return;
    }
    const byId = new Map((data?.items ?? []).map((item) => [item.id, item.version]));
    const targets = selection.selected
      .map((id) => ({ spuId: id, version: byId.get(id) ?? 0 }))
      .filter((item) => item.version > 0);
    if (!targets.length) {
      setActionError('选中的商品缺少版本号，请刷新列表后重试');
      return;
    }
    setBusy(true);
    try {
      const result = await adminApi.batchAuditProducts(
        targets,
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

  const openHistory = async (item: AdminProductAuditView) => {
    setHistoryOf(item);
    setHistory(null);
    setHistoryError(null);
    setHistoryLoading(true);
    try {
      setHistory(await adminApi.productAuditHistory(item.id, 50));
    } catch (err) {
      setHistoryError(toFriendlyMessage(err));
    } finally {
      setHistoryLoading(false);
    }
  };

  const items = data?.items ?? [];
  const pageCount = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 商品上架审核</h2>
      <AdminTabs />

      <div className="list-toolbar">
        <SearchBar
          value={keywordInput}
          onChange={(v) => {
            setKeywordInput(v);
            setPage(1);
          }}
          busy={loading && Boolean(keywordInput.trim())}
          placeholder="搜商品标题"
          width={280}
        />
        {keywordInput.trim() ? (
          <span className="small muted">匹配「{keywordInput.trim()}」的商品 {data ? data.total : 0} 件</span>
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
          href={adminApi.productAuditExportUrl({
            auditStatus: auditStatus || undefined,
            sellerId: sellerId == null ? undefined : String(sellerId),
            keyword: keywordInput.trim() || undefined,
          })}
        >
          导出 CSV
        </a>
      </div>

      <div className="row row-wrap row-between" style={{ gap: 12, marginBottom: 14, alignItems: 'flex-end' }}>
        <div className="row row-wrap" style={{ gap: 8 }}>
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
        <div className="row" style={{ gap: 6, alignItems: 'flex-end' }}>
          <Field label="按卖家筛选">
            <input
              className="input"
              style={{ width: 140 }}
              value={sellerInput}
              inputMode="numeric"
              placeholder="卖家 ID"
              onChange={(e) => setSellerInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') applySellerFilter();
              }}
            />
          </Field>
          <button className="btn btn-sm btn-ghost" onClick={applySellerFilter}>筛选</button>
          {sellerId != null ? (
            <button
              className="btn btn-sm btn-ghost"
              onClick={() => {
                setSellerInput('');
                setSellerId(undefined);
                setPage(1);
              }}
            >
              清除
            </button>
          ) : null}
        </div>
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {actionError ? <Alert kind="error">{actionError}</Alert> : null}

      {loading ? (
        <LoadingBlock text="正在拉取商品审核队列…" />
      ) : items.length === 0 ? (
        <Empty
          title={keywordInput.trim() ? `没有匹配「${keywordInput.trim()}」的商品` : '当前没有该状态的商品'}
          desc="第三方卖家提交商品后，会先进入「待审核」"
        />
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
          {items.map((item) => {
            const approved = item.auditStatus === 'APPROVED';
            return (
              <div className="card card-pad" key={item.id}>
                <div className="row row-wrap" style={{ gap: 14, alignItems: 'flex-start' }}>
                  <input
                    type="checkbox"
                    aria-label={`选择商品 ${item.title}`}
                    checked={selection.selectedSet.has(item.id)}
                    disabled={busy}
                    onChange={() => selection.toggle(item.id)}
                    style={{ marginTop: 4 }}
                  />
                  {item.mainImage ? (
                    <img
                      src={item.mainImage}
                      alt="主图"
                      style={{
                        width: 92, height: 92, objectFit: 'cover',
                        borderRadius: 8, border: '1px solid var(--border, #e5e7eb)', flex: 'none',
                      }}
                      loading="lazy"
                      decoding="async"
                    />
                  ) : (
                    <div
                      className="small muted"
                      style={{
                        width: 92, height: 92, borderRadius: 8, flex: 'none',
                        border: '1px dashed var(--border, #e5e7eb)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                      }}
                    >
                      无主图
                    </div>
                  )}

                  <div style={{ flex: 1, minWidth: 260 }}>
                    <div className="row row-wrap" style={{ gap: 8, alignItems: 'center' }}>
                      <span style={{ fontWeight: 600 }}>{item.title}</span>
                      <span className={AUDIT_BADGE[item.auditStatus] ?? 'tag'}>
                        {AUDIT_LABELS[item.auditStatus] ?? item.auditStatus}
                      </span>
                      <SupplyTag supplyType={item.supplyType} />
                    </div>

                    {item.subtitle ? (
                      <div className="small muted" style={{ marginTop: 4 }}>{item.subtitle}</div>
                    ) : null}

                    <div className="small muted" style={{ marginTop: 6 }}>
                      卖家 #{item.sellerId} · {formatPrice(item.minPrice)}
                      {item.maxPrice !== item.minPrice ? ` ~ ${formatPrice(item.maxPrice)}` : ''}
                      {' · '}{item.categoryName ?? `类目 #${item.categoryId ?? '—'}`}
                      {' · '}{item.brandName ?? '无品牌'}
                      {' · '}提交于 {fmtTime(item.createdAt)}
                    </div>

                    {/* 背审材料：规格价格一目了然，不必点进二级页 */}
                    {item.skus.length > 0 ? (
                      <div className="small muted" style={{ marginTop: 6 }}>
                        规格（{item.skus.length}）：
                        {item.skus.slice(0, 4).map((sku) => skuSummary(sku)).join(' ｜ ')}
                        {item.skus.length > 4 ? ` … 等 ${item.skus.length} 项` : ''}
                      </div>
                    ) : null}

                    {/* 2026-10-24：审核台必须看到买家将看到的全部内容。
                        此前只显示主图 1 张与纯文本详情，详情图与富文本在审核时是"不可见"的。 */}
                    {(item.images ?? []).length > 0 ? (
                      <div className="row row-wrap" style={{ gap: 6, marginTop: 8 }}>
                        {(item.images ?? []).map((img, idx) => (
                          <a key={`${img}-${idx}`} href={img} target="_blank" rel="noreferrer">
                            <img
                              src={img}
                              alt={`详情图 ${idx + 1}`}
                              style={{ width: 56, height: 56, objectFit: 'cover', borderRadius: 6, border: '1px solid var(--border, #e5e7eb)' }}
                              loading="lazy"
                              decoding="async"
                            />
                          </a>
                        ))}
                      </div>
                    ) : null}

                    {item.intro ? (
                      <div className="small" style={{ marginTop: 8 }}>
                        <b>商品介绍：</b>{item.intro}
                      </div>
                    ) : null}

                    {item.detailHtml ? (
                      <details style={{ marginTop: 8 }}>
                        <summary className="small" style={{ cursor: 'pointer' }}>
                          富文本详情（点击展开，共 {item.detailHtml.length} 字符）
                        </summary>
                        <div style={{ maxHeight: 260, overflow: 'auto', marginTop: 6, padding: '6px 8px', border: '1px solid var(--border, #e5e7eb)', borderRadius: 8 }}>
                          <RichTextView html={item.detailHtml} />
                        </div>
                      </details>
                    ) : null}

                    {(item.specParams ?? []).length > 0 || (item.packaging ?? []).length > 0 ? (
                      <div className="small muted" style={{ marginTop: 8 }}>
                        {(item.specParams ?? []).length > 0 ? (
                          <div>规格参数：{(item.specParams ?? []).map((x) => `${x.name}=${x.value}`).join('；')}</div>
                        ) : null}
                        {(item.packaging ?? []).length > 0 ? (
                          <div>包装规格：{(item.packaging ?? []).map((x) => `${x.name}=${x.value}`).join('；')}</div>
                        ) : null}
                      </div>
                    ) : null}

                    {item.detail ? (
                      <div
                        className="small muted"
                        style={{
                          marginTop: 6, whiteSpace: 'pre-wrap',
                          display: '-webkit-box', WebkitLineClamp: 3, WebkitBoxOrient: 'vertical',
                          overflow: 'hidden',
                        }}
                      >
                        详情：{item.detail}
                      </div>
                    ) : null}

                    {item.auditReason ? (
                      <div className="small" style={{ marginTop: 6 }}>
                        <b>驳回原因：</b>{item.auditReason}
                      </div>
                    ) : null}

                    {item.auditAt ? (
                      <div className="small muted" style={{ marginTop: 4 }}>
                        上次审核于 {fmtTime(item.auditAt)}
                      </div>
                    ) : null}
                  </div>

                  <div className="row" style={{ gap: 8, flex: 'none' }}>
                    <button
                      className="btn btn-ghost btn-sm"
                      disabled={busy}
                      onClick={() => void openHistory(item)}
                    >
                      审核历史
                    </button>
                    {approved ? (
                      // 已通过仍给改判入口：审核可重入，堵掉只会把改判逼成改库
                      <button
                        className="btn btn-ghost btn-sm"
                        disabled={busy}
                        onClick={() => {
                          setRejecting(item);
                          setActionError(null);
                        }}
                      >
                        驳回改判
                      </button>
                    ) : (
                      <>
                        <button
                          className="btn btn-primary btn-sm"
                          disabled={busy}
                          onClick={() => runAudit(item.id, item.version, 'APPROVE')}
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
                      </>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
        </div>
      )}

      {data ? (
        <Pagination page={page} pageCount={pageCount} total={data.total} onChange={setPage} />
      ) : null}

      <Modal
        open={rejecting != null}
        title="驳回商品上架"
        onClose={() => setRejecting(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setRejecting(null)}>取消</button>
            <button
              className="btn btn-primary"
              disabled={busy || !reason.trim()}
              onClick={() => rejecting && runAudit(rejecting.id, rejecting.version, 'REJECT', reason)}
            >
              {busy ? '处理中…' : '确认驳回'}
            </button>
          </>
        }
      >
        <Alert kind="info">
          驳回后该商品<b>立即从前台撤下</b>，并标记为「已驳回」。必须填写原因，
          便于卖家知晓并整改；卖家修改内容后可重新进入审核队列。
        </Alert>
        <Field label="驳回原因" required>
          <textarea
            className="input"
            rows={3}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="如：主图涉嫌违禁内容 / 标题含夸大宣传 / 类目选择错误 / 资质不匹配"
            maxLength={200}
          />
        </Field>
      </Modal>

      {/* 批量审核：逐件带 version；卖家改过内容的那件会 409，其余继续 */}
      <Modal
        open={batchOpen}
        title={batchAction === 'APPROVE' ? '批量通过商品' : '批量驳回商品'}
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
                  ? `确认通过（${selection.count} 件）`
                  : `确认驳回（${selection.count} 件）`}
            </button>
          </>
        }
      >
        <Alert kind="warn">
          将处理 <b>{selection.count}</b> 件商品（仅当前页）。
          {batchAction === 'APPROVE'
            ? '通过后商品即刻在前台可见；已通过的商品按幂等成功处理。'
            : '驳回后商品立即从前台撤下，必须填写原因；卖家修改内容后可重新进入审核队列。'}
          若卖家在核对期间改了内容，该件会因版本冲突失败并出现在结果里。
        </Alert>
        {batchAction === 'REJECT' ? (
          <Field label="驳回原因（必填，对全部选中商品共用）" required>
            <textarea
              className="input"
              rows={3}
              value={batchReason}
              onChange={(e) => setBatchReason(e.target.value)}
              placeholder="如：主图涉嫌违禁内容 / 标题含夸大宣传 / 类目选择错误 / 资质不匹配"
              maxLength={200}
            />
          </Field>
        ) : null}
        {actionError ? <Alert kind="error">{actionError}</Alert> : null}
      </Modal>

      <BatchResultModal result={batchResult} onClose={() => setBatchResult(null)} idLabel="商品" />

      <Modal
        open={historyOf != null}
        title="商品审核历史"
        onClose={() => setHistoryOf(null)}
        footer={<button className="btn btn-ghost" onClick={() => setHistoryOf(null)}>关闭</button>}
      >
        {historyOf ? (
          <div className="small muted" style={{ marginBottom: 10 }}>
            商品：<b>{historyOf.title}</b>
            {' · '}#{historyOf.id}
            {' · '}当前版本 v{historyOf.version}
          </div>
        ) : null}
        {historyLoading ? (
          <LoadingBlock text="正在拉取审核历史…" />
        ) : historyError ? (
          <Alert kind="error">{historyError}</Alert>
        ) : (history ?? []).length === 0 ? (
          <Empty
            title="没有审核历史"
            desc="自营商品免审、不进审核时间线；第三方商品从第一次提交起记录"
          />
        ) : (
          <div className="stack" style={{ gap: 12, maxHeight: 460, overflow: 'auto' }}>
            {(history ?? []).map((log) => (
              <div key={log.id} style={{ borderLeft: '3px solid var(--border, #e5e7eb)', paddingLeft: 10 }}>
                <div className="row row-wrap" style={{ gap: 8, alignItems: 'center' }}>
                  <span className={EVENT_BADGE[log.event] ?? 'tag'}>{log.eventLabel}</span>
                  <span className="small muted">
                    {fmtTime(log.createdAt)}
                    {' · '}{log.actorLabel}{log.actorId ? ` #${log.actorId}` : ''}
                  </span>
                </div>
                <div className="small muted" style={{ marginTop: 4 }}>
                  {log.fromAuditStatus ?? '—'} → {log.toAuditStatus ?? '—'}
                  {log.auditSource ? ` · 来源：${SOURCE_LABELS[log.auditSource] ?? log.auditSource}` : ''}
                  {' · '}版本 {log.versionBefore ?? '—'} → {log.versionAfter}
                  {log.sampledVersion != null ? ` · 抽中版本 v${log.sampledVersion}` : ''}
                </div>
                {log.reason ? (
                  <div className="small" style={{ marginTop: 4 }}>
                    <b>原因：</b>{log.reason}
                  </div>
                ) : null}
                {log.changes.length > 0 ? (
                  <ul className="small" style={{ margin: '6px 0 0', paddingLeft: 18 }}>
                    {log.changes.map((change) => (
                      <li key={change.field} style={{ marginBottom: 4 }}>
                        <b>{change.label}</b>：
                        <span className="muted">{change.before ?? '（空）'}</span>
                        {' → '}
                        <span>{change.after ?? '（空）'}</span>
                        {change.truncated ? <span className="muted">（超长截断）</span> : null}
                      </li>
                    ))}
                  </ul>
                ) : null}
              </div>
            ))}
          </div>
        )}
      </Modal>
    </div>
  );
};

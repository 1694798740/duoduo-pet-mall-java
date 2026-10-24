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
import type { AdminProductAuditView } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, SearchBar, SupplyTag, formatPrice, useToast,
} from '@duoduo/shared/components/ui';
import { RichTextView } from '@duoduo/shared/components/RichText';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
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

  const runAudit = async (spuId: string, action: 'APPROVE' | 'REJECT', rejectReason?: string) => {
    setActionError(null);
    setBusy(true);
    try {
      await adminApi.auditProduct(spuId, { action, reason: rejectReason });
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
        <div className="stack">
          {items.map((item) => {
            const approved = item.auditStatus === 'APPROVED';
            return (
              <div className="card card-pad" key={item.id}>
                <div className="row row-wrap" style={{ gap: 14, alignItems: 'flex-start' }}>
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
                      </>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
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
              onClick={() => rejecting && runAudit(rejecting.id, 'REJECT', reason)}
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
    </div>
  );
};

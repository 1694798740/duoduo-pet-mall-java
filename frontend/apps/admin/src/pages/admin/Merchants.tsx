/**
 * 平台管理后台 · 商家入驻审核台（E11）。
 *
 * 数据源（E10 已交付的端点，均在 E8 的 admin 专属路径族内）：
 *   GET  /api/admin/merchants?status=&page=        —— 申请列表
 *   GET  /api/admin/merchants/{id}                 —— 详情（含材料与驳回原因）
 *   POST /api/admin/merchants/{id}/start-review    —— SUBMITTED → REVIEWING
 *   POST /api/admin/merchants/{id}/approve         —— 通过并开店（幂等）
 *   POST /api/admin/merchants/{id}/reject          —— 驳回（结构化原因必填）
 *
 * 两条刻意的产品取舍：
 *  ① **材料在详情里直接大图预览**，且把「店名与执照名称是否一致」作为固定核对提示 ——
 *     名称不一致是入驻材料最常见的真实问题，与其靠运营记性，不如把它钉在眼前；
 *  ② **驳回必须逐条给原因**（scope 指到具体材料 / 店名 / 整体）——
 *     商家看到"驳回"两个字却不知道改什么，只会重复提交原样材料，审核台迟早被重提淹没。
 */

import React, { useCallback, useState } from 'react';
import { adminMerchantApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import {
  MERCHANT_MATERIAL_TYPES,
  MerchantApplicationView,
  MerchantRejectReason,
} from '@duoduo/api-sdk/types';
import {
  Alert, Empty, LoadingBlock, Modal, Pagination, formatDate, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';
import { useAuth } from '@duoduo/shared/store/auth';
import { AdminDenied, isOperator } from './shared';

const STATUS_TABS = [
  { value: 'SUBMITTED', label: '待审核' },
  { value: 'REVIEWING', label: '审核中' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: '', label: '全部' },
];

const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '待审核',
  REVIEWING: '审核中',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  SUSPENDED: '已暂停',
};

const statusTagClass = (status: string): string => {
  if (status === 'SUBMITTED') return 'tag tag-warn';
  if (status === 'REVIEWING') return 'tag';
  if (status === 'APPROVED') return 'tag';
  if (status === 'REJECTED') return 'tag tag-danger';
  return 'tag';
};

const REJECT_CODE_OPTIONS = [
  { value: 'MATERIAL_ILLEGIBLE', label: '材料模糊/无法辨认' },
  { value: 'INFO_MISMATCH', label: '信息与执照不一致' },
  { value: 'INDUSTRY_NOT_ALLOWED', label: '行业类目不在准入范围' },
  { value: 'MATERIAL_EXPIRED', label: '证照过期' },
  { value: 'OTHER', label: '其他（请在说明中写清）' },
];

/** 驳回对象的 scope：整体 / 店名 / 某类材料 */
const SCOPE_OPTIONS = [
  { value: 'GENERAL', label: '整体' },
  { value: 'STORE_NAME', label: '店铺名称' },
  ...MERCHANT_MATERIAL_TYPES.map((t) => ({ value: t.type, label: t.label })),
];

export const AdminMerchantsPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();

  const [status, setStatus] = useState('SUBMITTED');
  const [page, setPage] = useState(1);

  const [detail, setDetail] = useState<MerchantApplicationView | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [reasons, setReasons] = useState<MerchantRejectReason[]>([
    { scope: 'GENERAL', code: 'MATERIAL_ILLEGIBLE', note: '' },
  ]);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(
      () => adminMerchantApi.page({ status: status || undefined, page, pageSize: 10 }),
      [status, page],
    ),
    operator,
    [status, page],
  );

  if (!operator) return <AdminDenied />;

  const openDetail = async (id: string) => {
    setActionError(null);
    try {
      setDetail(await adminMerchantApi.detail(id));
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    }
  };

  const act = async (fn: () => Promise<MerchantApplicationView>, okMsg: string) => {
    setBusy(true);
    setActionError(null);
    try {
      const view = await fn();
      setDetail(view);
      push(okMsg, 'success');
      reload();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const submitReject = async () => {
    if (!detail) return;
    const cleaned = reasons
      .map((r) => ({ scope: r.scope.trim(), code: r.code, note: r.note.trim() }))
      .filter((r) => r.note.length > 0);
    if (cleaned.length === 0) {
      setActionError('至少写一条驳回说明 —— 商家看到的就是它');
      return;
    }
    await act(() => adminMerchantApi.reject(detail.merchantId, cleaned), '已驳回，商家可修改后重提');
    setRejecting(false);
  };

  const st = detail?.status ?? '';

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 入驻审核</h2>

      <Alert kind="info">
        通过审核即<b>开店</b>：商家立刻能上架商品、接单收款。材料核对要点 ——
        营业执照在有效期内、<b>店铺名称与执照名称一致</b>、法人身份证两面齐全、
        行业许可证在准入范围。驳回时请逐条写明原因与修改建议。
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
      {loading ? <LoadingBlock text="正在加载申请…" /> : null}

      {!loading && data && data.items.length === 0 ? (
        <Empty icon="🏪" title="没有符合条件的申请" desc="商家提交入驻材料后会出现在这里" />
      ) : null}

      {!loading && data && data.items.length > 0 ? (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>店铺名称</th>
                <th>店主</th>
                <th>状态</th>
                <th>最近提交</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((item) => (
                <tr key={item.merchantId}>
                  <td>{item.storeName}</td>
                  <td>{item.ownerPhoneMasked || item.ownerUserId}</td>
                  <td><span className={statusTagClass(item.status)}>{STATUS_LABEL[item.status] ?? item.status}</span></td>
                  <td>{item.appliedAt ? formatDate(item.appliedAt) : '—'}</td>
                  <td>
                    <button className="btn btn-sm btn-ghost" onClick={() => void openDetail(item.merchantId)}>
                      查看
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pagination page={page} pageCount={Math.max(1, Math.ceil(data.total / 10))} total={data.total} onChange={setPage} />
        </div>
      ) : null}

      {detail ? (
        <Modal open title={`入驻申请 · ${detail.storeName}`} onClose={() => setDetail(null)}>
          {actionError ? (
            <div style={{ marginBottom: 12 }}>
              <Alert kind="error">{actionError}</Alert>
            </div>
          ) : null}

          {detail.rejectReasons.length > 0 ? (
            <div style={{ marginBottom: 12 }}>
              <Alert kind="error">
                驳回原因（最近一次）：
                <ul style={{ margin: '8px 0 0 18px' }}>
                  {detail.rejectReasons.map((r, i) => (
                    <li key={i}>
                      <b>{SCOPE_OPTIONS.find((s) => s.value === r.scope)?.label ?? r.scope}</b>
                      ：{r.note}（{r.code}）
                    </li>
                  ))}
                </ul>
              </Alert>
            </div>
          ) : null}

          <div style={{ marginBottom: 10 }}>
            <span className="tag">{STATUS_LABEL[st] ?? st}</span>
            <span className="small muted" style={{ marginLeft: 8 }}>
              店主 ID {detail.ownerUserId} ｜ 最近提交 {detail.appliedAt ? formatDate(detail.appliedAt) : '—'}
              {detail.reviewedAt ? ` ｜ 审核时间 ${formatDate(detail.reviewedAt)}` : ''}
            </span>
          </div>

          {/* 一致性核对提示：店名与执照名称不一致是入驻材料最高频的真实问题 */}
          <Alert kind="info">
            核对提示：店铺名称「<b>{detail.storeName}</b>」与营业执照上的名称是否一致？
            法人身份证是否两面齐全且在有效期？行业许可证是否属于平台准入类目？
          </Alert>

          <div className="small muted" style={{ margin: '12px 0 6px' }}>资质材料（点击图片可放大）</div>
          <div className="row row-wrap" style={{ gap: 12 }}>
            {MERCHANT_MATERIAL_TYPES.map((t) => {
              const url = detail.materials[t.type];
              return (
                <div key={t.type} style={{ width: 150 }}>
                  <div className="small" style={{ marginBottom: 4 }}>{t.label}</div>
                  {url ? (
                    url.toLowerCase().endsWith('.pdf') ? (
                      <a
                        className="btn btn-sm btn-ghost"
                        href={url}
                        target="_blank"
                        rel="noreferrer"
                        style={{ width: 150, height: 100, display: 'flex', alignItems: 'center', justifyContent: 'center', border: '1px solid var(--border, #ddd)', borderRadius: 6 }}
                      >
                        📄 PDF 文档
                      </a>
                    ) : (
                      <a href={url} target="_blank" rel="noreferrer">
                        <img
                          src={url}
                          alt={t.label}
                          style={{ width: 150, height: 100, objectFit: 'cover', border: '1px solid var(--border, #ddd)', borderRadius: 6 }}
                        />
                      </a>
                    )
                  ) : (
                    <div className="small muted" style={{ width: 150, height: 100, display: 'flex', alignItems: 'center', justifyContent: 'center', border: '1px dashed var(--border, #ccc)', borderRadius: 6 }}>
                      缺失
                    </div>
                  )}
                </div>
              );
            })}
          </div>

          <div className="row row-wrap" style={{ marginTop: 16 }}>
            {st === 'SUBMITTED' ? (
              <button
                className="btn btn-primary"
                disabled={busy}
                onClick={() => void act(() => adminMerchantApi.startReview(detail.merchantId), '已开始审核')}
              >
                开始审核
              </button>
            ) : null}
            {st === 'REVIEWING' ? (
              <>
                <button
                  className="btn btn-primary"
                  disabled={busy}
                  onClick={() => void act(() => adminMerchantApi.approve(detail.merchantId), '已通过并开店')}
                >
                  通过并开店
                </button>
                <button className="btn btn-danger" disabled={busy} onClick={() => setRejecting(true)}>
                  驳回
                </button>
              </>
            ) : null}
            {st === 'APPROVED' ? <Alert kind="success">已通过，店铺已开通。</Alert> : null}
          </div>

          {rejecting && st === 'REVIEWING' ? (
            <div style={{ marginTop: 14, borderTop: '1px solid var(--border, #ddd)', paddingTop: 12 }}>
              <div className="small muted" style={{ marginBottom: 6 }}>
                驳回原因（逐条填写；scope 指明驳回对象，说明会原样展示给商家）
              </div>
              {reasons.map((r, i) => (
                <div key={i} className="row" style={{ gap: 8, marginBottom: 8 }}>
                  <select
                    className="input"
                    style={{ width: 170 }}
                    value={r.scope}
                    onChange={(e) => setReasons((prev) => prev.map((x, j) => (j === i ? { ...x, scope: e.target.value } : x)))}
                  >
                    {SCOPE_OPTIONS.map((o) => (
                      <option key={o.value} value={o.value}>{o.label}</option>
                    ))}
                  </select>
                  <select
                    className="input"
                    style={{ width: 190 }}
                    value={r.code}
                    onChange={(e) => setReasons((prev) => prev.map((x, j) => (j === i ? { ...x, code: e.target.value } : x)))}
                  >
                    {REJECT_CODE_OPTIONS.map((o) => (
                      <option key={o.value} value={o.value}>{o.label}</option>
                    ))}
                  </select>
                  <input
                    className="input"
                    style={{ flex: 1 }}
                    placeholder="给商家看的说明（必填）"
                    value={r.note}
                    onChange={(e) => setReasons((prev) => prev.map((x, j) => (j === i ? { ...x, note: e.target.value } : x)))}
                  />
                  {reasons.length > 1 ? (
                    <button className="btn btn-sm btn-ghost" onClick={() => setReasons((prev) => prev.filter((_, j) => j !== i))}>
                      删
                    </button>
                  ) : null}
                </div>
              ))}
              <div className="row row-wrap" style={{ marginTop: 8 }}>
                <button
                  className="btn btn-sm btn-ghost"
                  onClick={() => setReasons((prev) => [...prev, { scope: 'GENERAL', code: 'OTHER', note: '' }])}
                >
                  + 加一条
                </button>
                <button className="btn btn-danger" disabled={busy} onClick={() => void submitReject()}>
                  确认驳回
                </button>
              </div>
            </div>
          ) : null}
        </Modal>
      ) : null}
    </div>
  );
};

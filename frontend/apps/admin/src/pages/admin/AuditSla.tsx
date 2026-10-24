/**
 * 平台管理后台 · 审核 SLA 事后抽查（E28）。
 *
 * <p>这个页面服务的制度是「默认放行 + 事后抽查」：第三方商品与入驻申请不再逐条等人工审，
 * 系统按风险规则自动放行，平台再按比例回头抽查。所以这里的语义与「内容审核 / 商品审核」
 * 两个页面**根本不同** —— 那边是「还没上，等我放行」，这边是「已经在卖/已经在营业，
 * 我回头看放行判得对不对」。判违规是真处置（商品强制下架 / 店铺直接停业），
 * 比待审队列更紧急，也因此单独一个页面、单独一套状态词（待复核 / 复核通过 / 判违规）。
 *
 * <p>页面同时是这套制度的**自证面板**：抽中违规率（放行是否过松）、平均复核时长
 * （处置是否及时）、误判率（抽查是否在制造噪音）、当前抽样比例（是否已被系统自动收紧）。
 * 没有这几个数，运营只能"感觉在抽查"；有了它们，制度才谈得上能评估、能收紧、能放松。
 *
 * <p>两个域（商品 / 入驻）共用一套交互与一套指标口径，切换只在顶部一行 ——
 * 两边各写一套页面，最后必然出现"商品侧能看误判率、入驻侧看不到"这种不一致。
 */

import React, { useCallback, useState } from 'react';
import { adminApi, adminMerchantApi } from '@duoduo/api-sdk/endpoints';
import type { AuditSampleView, MerchantAuditSampleView, PageResult } from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Modal, Pagination, SearchBar, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync, useDebounced } from '@duoduo/shared/components/hooks';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { useAuth } from '@duoduo/shared/store/auth';
import { AdminDenied, AdminStat, AdminTabs, isOperator } from './shared';

type Domain = 'product' | 'merchant';

const DOMAINS: { value: Domain; label: string; target: string }[] = [
  { value: 'product', label: '第三方商品', target: '自动放行的第三方商品' },
  { value: 'merchant', label: '入驻店铺', target: '自动放行的入驻店铺' },
];

const STATUS_TABS = [
  { value: 'PENDING', label: '待复核' },
  { value: 'PASSED', label: '复核通过' },
  { value: 'REJECTED', label: '判违规' },
  { value: 'ALL', label: '全部' },
];

/** 处置结果徽标：与「审核状态」区分开，避免运营把「复核通过」读成「审核通过」 */
const STATUS_BADGE: Record<string, string> = {
  PENDING: 'tag tag-warn',
  PASSED: 'tag tag-success',
  REJECTED: 'tag tag-danger',
};

const STATUS_TEXT: Record<string, string> = {
  PENDING: '待复核',
  PASSED: '复核通过',
  REJECTED: '判违规',
};

const fmtTime = (value: string | null | undefined): string =>
  value ? value.slice(0, 19).replace('T', ' ') : '—';

const fmtHours = (hours: number | null | undefined): string => {
  if (hours == null) return '—';
  if (hours < 24) return `${hours} 小时`;
  return `${(hours / 24).toFixed(1)} 天`;
};

/** 两域的列表行归一化后的形状（渲染只认这个，域差异在 normalize 里消化） */
interface Row {
  id: string;
  status: string;
  title: string;
  meta: string;
  reasonLabel: string;
  sampleDate: string | null;
  createdAt: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  note: string | null;
  hours: number | null;
  /** 抽中时的版本 / 当前版本（A-08，仅商品侧有）；旧任务为 null */
  sampledVersion: number | null;
  currentVersion: number | null;
  /** 抽中后卖家又改过内容（A-08）：提示运营眼前内容已不是抽中那一版 */
  drift: boolean;
  /** 商品侧有商城详情深链（复核必须能看见实物）；入驻侧无对应前台页，为 null */
  detailUrl: string | null;
}

type AnySample = Partial<AuditSampleView> &
  Partial<MerchantAuditSampleView> & {
    id: string;
    status: string;
    reasonLabel: string;
    bizId: string;
  };

const MALL_URL = (import.meta.env.VITE_MALL_URL as string | undefined) ?? 'http://localhost:8088';

const normalize = (domain: Domain, s: AnySample): Row => ({
  id: String(s.id),
  status: String(s.status ?? 'PENDING'),
  title:
    domain === 'product'
      ? s.spuTitle || `商品 #${s.bizId}`
      : s.storeName || `店铺 #${s.bizId}`,
  meta:
    domain === 'product'
      ? `商品 #${s.bizId} · 卖家 #${s.sellerId}`
      : `店铺 #${s.bizId}`,
  reasonLabel: s.reasonLabel || s.reason || '',
  sampleDate: s.sampleDate ?? null,
  createdAt: s.createdAt ?? null,
  reviewedBy: s.reviewedBy ?? null,
  reviewedAt: s.reviewedAt ?? null,
  note: s.reviewNote ?? null,
  hours: s.responseHours ?? null,
  sampledVersion: s.sampledVersion ?? null,
  currentVersion: s.currentVersion ?? null,
  drift: Boolean(s.drift),
  detailUrl: domain === 'product' ? `${MALL_URL}/product/${s.bizId}` : null,
});

export const AdminAuditSlaPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();

  const [domain, setDomain] = useState<Domain>('product');
  const [status, setStatus] = useState('PENDING');
  const [page, setPage] = useState(1);
  const [keywordInput, setKeywordInput] = useState('');
  const keyword = useDebounced(keywordInput.trim(), 350);

  const [reviewing, setReviewing] = useState<Row | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [sweeping, setSweeping] = useState(false);

  // 泛型显式写成 AnySample：两个域的返回类型不同，不显式指定时 T 会被推成其中一个域，
  // 另一个域的 Promise 就赋不进去（编译报 storeName 缺失）。归一化在 normalize 里做。
  const { data, loading, error, reload } = useAsync<PageResult<AnySample>>(
    useCallback(() => {
      const query = { status, keyword: keyword || undefined, page, pageSize: 10 };
      return domain === 'product'
        ? adminApi.auditSlaSamples(query)
        : adminMerchantApi.auditSamples(query);
    }, [domain, status, keyword, page]),
    operator,
    [domain, status, keyword, page],
  );

  // 指标与队列分开取：读指标会**顺带触发自动收紧评估**，不该跟着翻页反复执行
  const {
    data: metrics, loading: metricsLoading, error: metricsError, reload: reloadMetrics,
  } = useAsync(
    useCallback(
      () => (domain === 'product' ? adminApi.auditSlaMetrics() : adminMerchantApi.auditMetrics()),
      [domain],
    ),
    operator,
    [domain],
  );

  if (!operator) return <AdminDenied />;

  const switchDomain = (next: Domain) => {
    setDomain(next);
    setStatus('PENDING');
    setPage(1);
    setActionError(null);
  };

  const runSweep = async () => {
    setSweeping(true);
    setActionError(null);
    try {
      const r = domain === 'product'
        ? await adminApi.auditSlaSweep()
        : await adminMerchantApi.auditSweep();
      push(
        `抽查完成：候选 ${r.candidates} 条、新建 ${r.created} 条、幂等跳过 ${r.skipped} 条`
          + `（本轮比例 ${r.ratePercent}%）`,
        'success',
      );
      reload();
      reloadMetrics();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setSweeping(false);
    }
  };

  const submitReview = async (action: 'PASS' | 'REJECT') => {
    if (!reviewing) return;
    setActionError(null);
    setBusy(true);
    try {
      const body = { action, note: note.trim() || undefined };
      if (domain === 'product') {
        await adminApi.reviewAuditSlaSample(reviewing.id, body);
      } else {
        await adminMerchantApi.reviewAuditSample(reviewing.id, body);
      }
      push(action === 'PASS' ? '已判定合规' : '已判定违规并执行处置', 'success');
      setReviewing(null);
      setNote('');
      reload();
      reloadMetrics();
    } catch (err) {
      setActionError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const rows: Row[] = (data?.items ?? []).map((item) => normalize(domain, item));
  const pageCount = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;
  const domainLabel = DOMAINS.find((d) => d.value === domain)?.target ?? '';

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 审核事后抽查</h2>
      <AdminTabs />

      <Alert kind="info">
        <b>这套制度的定位：默认放行 + 事后抽查。</b>
        平台不再逐条等人工审：命中风险规则（违禁类目 / 联系方式 / 价格异常 / 曾经被驳回过）
        的一律转人工，其余自动放行并**已在售 / 已营业**；系统随后按比例回头抽查。
        复核结论里只有「判违规」是真处置 —— 商品会被驳回并下架，店铺会被停业并联动下架其商品。
      </Alert>

      <div className="row row-wrap" style={{ gap: 8, margin: '14px 0' }}>
        {DOMAINS.map((d) => (
          <button
            key={d.value}
            className={`btn btn-sm ${domain === d.value ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => switchDomain(d.value)}
          >
            {d.label}
          </button>
        ))}
      </div>

      {/* ---- 自证指标：没有这几个数，抽查制度无法评估、无法自动收紧 ---- */}
      {metricsError ? <Alert kind="error">{metricsError}</Alert> : null}
      {metricsLoading ? (
        <LoadingBlock text="正在计算 SLA 指标…" />
      ) : metrics ? (
        <>
          <div className="grid grid-3" style={{ gap: 12, marginBottom: 12 }}>
            <AdminStat
              label="当前抽样比例"
              value={`${metrics.currentRatePercent}%`}
              hint={metrics.tightened ? '已自动收紧（近 3 天抽中违规率超标）' : '基础比例 5%'}
            />
            <AdminStat
              label={`近 ${metrics.windowDays} 天已复核`}
              value={metrics.sampled}
              hint={`违规 ${metrics.violated} · 合规 ${metrics.passed}`}
            />
            <AdminStat
              label="抽中违规率"
              value={`${metrics.violationPercent}%`}
              hint="放行是否过得松"
            />
          </div>
          <div className="grid grid-3" style={{ gap: 12, marginBottom: 16 }}>
            <AdminStat
              label="平均复核时长"
              value={`${metrics.avgResponseHours} 小时`}
              hint="从抽中到出结论"
            />
            <AdminStat
              label="误判率"
              value={`${metrics.falsePositivePercent}%`}
              hint="结论反复 = 抽查在制造噪音"
            />
            <AdminStat
              label="待复核"
              value={status === 'PENDING' && data ? data.total : '—'}
              hint={status === 'PENDING' ? '当前是运营的待办量' : '切到「待复核」查看'}
            />
          </div>
        </>
      ) : null}

      <div className="list-toolbar">
        <SearchBar
          value={keywordInput}
          onChange={(v) => {
            setKeywordInput(v);
            setPage(1);
          }}
          busy={loading && Boolean(keywordInput.trim())}
          placeholder={domain === 'product' ? '搜商品标题' : '搜店铺名'}
          width={280}
        />
        {keywordInput.trim() ? (
          <span className="small muted">匹配「{keywordInput.trim()}」的抽查 {data ? data.total : 0} 条</span>
        ) : null}
      </div>

      <div className="row row-wrap row-between" style={{ gap: 12, marginBottom: 14, alignItems: 'center' }}>
        <div className="row row-wrap" style={{ gap: 8 }}>
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
        <button
          className="btn btn-sm btn-ghost"
          disabled={sweeping}
          title={`按当前库内数据立即对「昨天自动放行的${domainLabel}」跑一轮抽查（唯一键幂等，可重复点击）`}
          onClick={() => void runSweep()}
        >
          {sweeping ? '抽查中…' : '立即抽查（昨天那批）'}
        </button>
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}
      {actionError ? <Alert kind="error">{actionError}</Alert> : null}

      {loading ? (
        <LoadingBlock text="正在拉取抽查队列…" />
      ) : rows.length === 0 ? (
        <Empty
          icon="🔍"
          title={
            keywordInput.trim()
              ? `没有匹配「${keywordInput.trim()}」的抽查`
              : status === 'PENDING'
                ? '当前没有待复核的抽查任务'
                : '这个状态下没有记录'
          }
          desc={
            status === 'PENDING'
              ? '自动放行的内容会被按比例抽中；也可以点右上角「立即抽查」对昨天那批跑一轮'
              : '换个状态看看'
          }
        />
      ) : (
        <div className="stack">
          {rows.map((row) => (
            <div className="card card-pad" key={row.id}>
              <div className="row row-wrap row-between" style={{ gap: 12, alignItems: 'flex-start' }}>
                <div style={{ flex: 1, minWidth: 260 }}>
                  <div className="row row-wrap" style={{ gap: 8, alignItems: 'center' }}>
                    <span style={{ fontWeight: 600 }}>{row.title}</span>
                    <span className={STATUS_BADGE[row.status] ?? 'tag'}>
                      {STATUS_TEXT[row.status] ?? row.status}
                    </span>
                    {row.reasonLabel ? <span className="tag">{row.reasonLabel}</span> : null}
                    {row.drift ? (
                      <span
                        className="tag tag-warn"
                        title="抽中后卖家又改过内容：请以当前商品详情为准，不要对着旧截图下结论"
                      >
                        内容已变更 v{row.sampledVersion} → v{row.currentVersion}
                      </span>
                    ) : null}
                  </div>

                  <div className="small muted" style={{ marginTop: 6 }}>
                    {row.meta}
                    {row.sampleDate ? ` · 抽中批次 ${row.sampleDate}` : ''}
                    {' · '}建单 {fmtTime(row.createdAt)}
                  </div>

                  {row.status !== 'PENDING' ? (
                    <div className="small muted" style={{ marginTop: 4 }}>
                      复核于 {fmtTime(row.reviewedAt)}
                      {row.reviewedBy ? ` · 处理人 #${row.reviewedBy}` : ''}
                      {' · '}耗时 {fmtHours(row.hours)}
                    </div>
                  ) : null}

                  {row.note ? (
                    <div className="small" style={{ marginTop: 6 }}>
                      <b>{row.status === 'REJECTED' ? '判违规原因：' : '复核备注：'}</b>
                      {row.note}
                    </div>
                  ) : null}
                </div>

                <div className="row" style={{ gap: 8, flex: 'none' }}>
                  {row.detailUrl ? (
                    <a className="btn btn-sm btn-ghost" href={row.detailUrl} target="_blank" rel="noreferrer">
                      查看商品
                    </a>
                  ) : null}
                  {row.status === 'PENDING' ? (
                    <button
                      className="btn btn-sm btn-primary"
                      disabled={busy}
                      onClick={() => {
                        setReviewing(row);
                        setNote('');
                        setActionError(null);
                      }}
                    >
                      复核
                    </button>
                  ) : null}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {data ? (
        <Pagination page={page} pageCount={pageCount} total={data.total} onChange={setPage} />
      ) : null}

      <Modal
        open={reviewing != null}
        title="复核抽查任务"
        onClose={() => setReviewing(null)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setReviewing(null)}>取消</button>
            <button
              className="btn btn-ghost"
              disabled={busy}
              title="判定合规：只记结论，不改动商品/店铺状态"
              onClick={() => void submitReview('PASS')}
            >
              {busy ? '处理中…' : '判定合规'}
            </button>
            <button
              className="btn btn-primary"
              disabled={busy || !note.trim()}
              onClick={() => void submitReview('REJECT')}
            >
              判定违规并处置
            </button>
          </>
        }
      >
        <Alert kind="warn">
          {domain === 'product'
            ? '判定违规会立即驳回该商品并强制下架（下架动作不依赖卖家）。'
            : '判定违规会直接停业该店铺并联动下架其全部商品（走条件更新，不会写回已变更的店铺状态）。'}
          「判定违规」必须写明依据 —— 这是事后申诉时唯一的书面凭证；
          「判定合规」只记结论、不动任何业务数据，备注可选。
        </Alert>
        {reviewing ? (
          <div className="small muted" style={{ margin: '10px 0' }}>
            复核对象：<b>{reviewing.title}</b>（{reviewing.meta}）
            {reviewing.reasonLabel ? ` · 抽中理由：${reviewing.reasonLabel}` : ''}
          </div>
        ) : null}
        {reviewing?.drift ? (
          <Alert kind="warn">
            该商品抽中后已被卖家修改（抽中版本 v{reviewing.sampledVersion}，当前版本 v
            {reviewing.currentVersion}）。复核依据是「抽中时那一版」还是「当前内容」，
            请在处置原因里写明；本提示不阻断复核。
          </Alert>
        ) : null}
        <Field label="复核依据（判定违规必填）">
          <textarea
            className="input"
            rows={3}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder={
              domain === 'product'
                ? '如：商品与描述相符，价格与类目均值一致 / 主图含站外联系方式，属违禁'
                : '如：材料齐备且店名与执照一致 / 店名含「官方」误导表述，属违规'
            }
            maxLength={200}
          />
        </Field>
      </Modal>
    </div>
  );
};

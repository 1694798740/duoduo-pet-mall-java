/**
 * 平台管理后台 · 操作审计（E18）。
 *
 * 数据源：`GET /api/admin/audit-logs`（分页 + 六种过滤）。
 *
 * 关键口径（与后端一致，前端不要另立一套）：
 *
 *  1. **本页只有读**。服务端刻意不提供任何修改 / 删除入口 —— 审计的完整性靠
 *     「根本没有修改路径」来保证，而不是靠「没人会去改」。所以这张表里**没有操作列**；
 *     若将来要加归档，必须先改服务端的档位判据，并让「允许删什么」变成一次显式决定。
 *
 *  2. **`immutable` 由服务端下发**，本页只负责把它显示出来（资金与权限类打红标）。
 *     前端不自行判断 `category === 'SECURITY'` —— 那等于再抄一份规则，两份规则迟早分叉，
 *     而分叉的后果是「某天清理把追责材料删了」，不可逆。
 *
 *  3. **失败记录同样重要**。审计的一半价值在「谁试过但没成功」：一次成功的角色变更
 *     说明流程正常；连续多次失败的提权尝试才是信号。所以**默认不做结果过滤**（两端都看），
 *     失败行用显眼的红标，而不是靠运营自己去切「失败」标签 —— 那会让失败被埋在默认视图之外。
 *
 *  4. **时间范围止端按「含」**：运营选「截止到今天」时期望看到今天的记录。
 *     传 `2026-10-09` 服务端会补到当天 23:59:59.999999999。
 */
import React, { useCallback, useState } from 'react';
import { adminApi } from '@duoduo/api-sdk/endpoints';
import type { AuditLogView } from '@duoduo/api-sdk/types';
import { Alert, Empty, LoadingBlock, Pagination } from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';
import { useAuth } from '@duoduo/shared/store/auth';
import { AdminDenied, AdminTabs, isOperator } from './shared';

const PAGE_SIZE = 20;

/** 类目中文标签：枚举名是给程序看的，运营要看人话 */
const CATEGORY_LABELS: Record<string, string> = {
  SECURITY: '权限 / 身份',
  FUNDS: '资金',
  TENANT: '租户 / 店铺',
  CONTENT: '内容 / 主数据',
  OTHER: '运维',
};

/** 常用动作的短标签；未收录的直接显示原始标识（宁可显示英文，也不显示错的翻译） */
const ACTION_LABELS: Record<string, string> = {
  USER_ROLE_CHANGE: '变更用户角色',
  MERCHANT_AUDIT_START: '开始审核入驻',
  MERCHANT_APPROVE: '通过入驻',
  MERCHANT_REJECT: '驳回入驻',
  MERCHANT_LICENSE_SWEEP: '资质巡检',
  MERCHANT_LICENSE_SUSPEND: '资质过期停业',
  MERCHANT_VIOLATION_SUSPEND: '违规停业',
  MERCHANT_MATERIAL_READ: '读取资质材料',
  INVITE_STAFF: '邀请店员',
  ENABLE_STAFF: '启用店员',
  DISABLE_STAFF: '停用店员',
  ADMIN_ENABLE_STAFF: '平台启用店员',
  ADMIN_DISABLE_STAFF: '平台停用店员',
  PRODUCT_AUDIT: '商品上架审核',
  LISTING_AUDIT: '闲置内容审核',
  CATEGORY_CREATE: '新建类目',
  CATEGORY_UPDATE: '修改类目',
  CATEGORY_STATUS: '类目启停',
  CATEGORY_DELETE: '删除类目',
  CATEGORY_ATTR_CREATE: '新建类目属性',
  CATEGORY_ATTR_UPDATE: '修改类目属性',
  CATEGORY_ATTR_STATUS: '类目属性启停',
  CATEGORY_ATTR_DELETE: '删除类目属性',
  BRAND_CREATE: '新建品牌',
  BRAND_UPDATE: '修改品牌',
  BRAND_STATUS: '品牌启停',
  BRAND_DELETE: '删除品牌',
  SEARCH_REINDEX: '重建搜索索引',
  AUDIT_SLA_REVIEW: '抽查复核',
  AUDIT_SLA_SWEEP: '抽查抽样',
  SHARE_REPORT_HANDLE: '处置分享举报',
  REVIEW_AUDIT: '评价审核',
  REVIEW_REPLY: '商家回复评价',
  ORDER_FORCE_CLOSE: '强制关闭订单',
  SECKILL_CREATE: '新建秒杀',
  SECKILL_UPDATE: '编辑秒杀',
  SECKILL_STATUS: '秒杀上下架',
  SECKILL_DELETE: '删除秒杀',
  AFTERSALE_ARBITRATE: '售后仲裁',
  SETTLEMENT_BACKFILL: '结算回补',
  SETTLEMENT_COMPENSATE: '平台垫付',
  SETTLEMENT_DEBT_SETTLE: '核销欠款',
  SETTLEMENT_DEBT_WRITE_OFF: '欠款豁免',
  MQ_DLQ_REPLAY: '死信重放',
  UNKNOWN: '未登记动作',
};

/** 平台级操作的 target_id 约定值：0 = 无单一目标（全站动作） */
const PLATFORM_TARGET = '0';

const shortId = (id: string) => (id && id.length > 10 ? `${id.slice(0, 6)}…${id.slice(-4)}` : id);

export const AdminOperationAuditsPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);

  const [actorInput, setActorInput] = useState('');
  const [actionInput, setActionInput] = useState('');
  const [categoryInput, setCategoryInput] = useState('');
  const [outcomeInput, setOutcomeInput] = useState('');
  const [fromInput, setFromInput] = useState('');
  const [toInput, setToInput] = useState('');

  const [query, setQuery] = useState<{
    actorId?: string; action?: string; category?: string;
    outcome?: string; from?: string; to?: string; page: number;
  }>({ page: 1 });

  const [expanded, setExpanded] = useState<string | null>(null);

  const { data, loading, error, reload } = useAsync(
    useCallback(() => adminApi.auditLogs({ ...query, pageSize: PAGE_SIZE }), [query]),
    operator,
    [operator, query],
  );

  if (!operator) return <AdminDenied />;

  const items = data?.items ?? [];

  const search = () => {
    setQuery({
      actorId: actorInput.trim() || undefined,
      action: actionInput.trim() || undefined,
      category: categoryInput || undefined,
      outcome: outcomeInput || undefined,
      from: fromInput.trim() || undefined,
      to: toInput.trim() || undefined,
      page: 1,
    });
  };

  const reset = () => {
    setActorInput(''); setActionInput(''); setCategoryInput('');
    setOutcomeInput(''); setFromInput(''); setToInput('');
    setQuery({ page: 1 });
  };

  const goPage = (page: number) => setQuery({ ...query, page });

  if (loading) return <LoadingBlock text="正在加载审计记录…" />;

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 操作审计</h2>
      <AdminTabs />

      <Alert kind="info">
        这里记录<b>后台的全部写操作</b>：谁、什么时候、从哪个 IP、做了什么、成功还是失败。
        <b>失败的尝试同样留痕</b> —— 连续失败的提权尝试比一次成功更值得看。
        {' '}
        <b>资金与权限类（标红）不可删改</b>，本页只提供查询，没有任何修改入口。
      </Alert>

      <div className="row row-wrap" style={{ margin: '14px 0', gap: 8 }}>
        <input
          className="input"
          style={{ width: 180 }}
          placeholder="操作人 ID（精确）"
          value={actorInput}
          onChange={(e) => setActorInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') search(); }}
          maxLength={24}
        />
        <input
          className="input"
          style={{ width: 200 }}
          placeholder="动作标识，如 USER_ROLE_CHANGE"
          value={actionInput}
          onChange={(e) => setActionInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') search(); }}
          maxLength={50}
        />
        <select
          className="input"
          style={{ width: 150 }}
          value={categoryInput}
          onChange={(e) => setCategoryInput(e.target.value)}
        >
          <option value="">全部类目</option>
          {Object.entries(CATEGORY_LABELS).map(([k, v]) => (
            <option key={k} value={k}>{v}（{k}）</option>
          ))}
        </select>
        {/* 默认「全部结果」而不是「只看成功」：失败被埋在默认视图之外就失去了它的价值 */}
        <select
          className="input"
          style={{ width: 130 }}
          value={outcomeInput}
          onChange={(e) => setOutcomeInput(e.target.value)}
        >
          <option value="">全部结果</option>
          <option value="SUCCESS">成功</option>
          <option value="FAILED">失败</option>
        </select>
        <input
          className="input"
          style={{ width: 150 }}
          type="date"
          value={fromInput}
          onChange={(e) => setFromInput(e.target.value)}
        />
        <span className="muted">至</span>
        <input
          className="input"
          style={{ width: 150 }}
          type="date"
          value={toInput}
          onChange={(e) => setToInput(e.target.value)}
        />
        <button className="btn btn-primary btn-sm" onClick={search}>查询</button>
        <button className="btn btn-ghost btn-sm" onClick={reset}>重置</button>
        <button className="btn btn-ghost btn-sm" onClick={reload}>刷新</button>
        <a
          className="btn btn-ghost btn-sm"
          href={adminApi.auditLogExportUrl({
            actorId: query.actorId,
            action: query.action,
            category: query.category,
            outcome: query.outcome,
            from: query.from,
            to: query.to,
          })}
        >
          导出 CSV
        </a>
      </div>

      {error ? <Alert kind="error">{error}</Alert> : null}

      {items.length === 0 ? (
        <Empty title="没有匹配的审计记录" />
      ) : (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th style={{ width: 160 }}>时间（UTC）</th>
                <th style={{ width: 150 }}>操作人</th>
                <th>动作</th>
                <th style={{ width: 130 }}>类目</th>
                <th style={{ width: 80 }}>结果</th>
                <th style={{ width: 150 }}>目标</th>
                <th style={{ width: 130 }}>租户</th>
                <th style={{ width: 60 }} />
              </tr>
            </thead>
            <tbody>
              {items.map((item: AuditLogView) => (
                <React.Fragment key={item.id}>
                  <tr>
                    <td className="mono small">{item.createdAt?.replace('T', ' ').slice(0, 19) ?? '—'}</td>
                    <td className="mono small" title={item.actorId ?? ''}>
                      {item.actorPhone || '—'}
                      {item.actorId ? <div className="muted">{shortId(item.actorId)}</div> : null}
                    </td>
                    <td>
                      {ACTION_LABELS[item.action] ?? item.action}
                      <div className="muted small mono">{item.action}</div>
                    </td>
                    <td>
                      {CATEGORY_LABELS[item.category] ?? item.category}
                      {/* 不可删改档打红：判据来自服务端下发的 immutable，前端不自行判断 */}
                      {item.immutable ? (
                        <div><span className="tag tag-danger">不可删改</span></div>
                      ) : null}
                    </td>
                    <td>
                      {item.outcome === 'FAILED'
                        ? <span className="tag tag-danger">失败</span>
                        : <span className="tag tag-success">成功</span>}
                    </td>
                    <td className="mono small">
                      {item.targetType || '—'}
                      {item.targetId === PLATFORM_TARGET
                        ? <div className="muted">平台级</div>
                        : <div className="muted">{shortId(item.targetId)}</div>}
                    </td>
                    <td className="mono small">
                      {item.tenantId === PLATFORM_TARGET
                        ? <span className="muted">平台 / 自营</span>
                        : shortId(item.tenantId)}
                    </td>
                    <td>
                      <button
                        className="btn btn-ghost btn-sm"
                        onClick={() => setExpanded(expanded === item.id ? null : item.id)}
                      >
                        {expanded === item.id ? '收起' : '详情'}
                      </button>
                    </td>
                  </tr>
                  {expanded === item.id ? (
                    <tr>
                      <td colSpan={8}>
                        <div className="small" style={{ lineHeight: 1.9 }}>
                          <div><b>摘要：</b>{item.detail || '—'}</div>
                          <div>
                            <b>变更前：</b><span className="mono">{item.beforeValue ?? '—'}</span>
                            {'  '}<b>变更后：</b><span className="mono">{item.afterValue ?? '—'}</span>
                          </div>
                          <div>
                            <b>目标 ID：</b><span className="mono">{item.targetId}</span>
                            {item.targetId === PLATFORM_TARGET ? <span className="muted">（平台级操作，无单一目标）</span> : null}
                          </div>
                          <div><b>来源 IP：</b><span className="mono">{item.ip ?? '—'}</span></div>
                          {/* 链路 ID 能把这条记录与访问日志对上 —— 出事时这是唯一能把两边串起来的东西 */}
                          <div><b>链路 ID：</b><span className="mono">{item.requestId ?? '—'}</span></div>
                        </div>
                      </td>
                    </tr>
                  ) : null}
                </React.Fragment>
              ))}
            </tbody>
          </table>

          <Pagination
            page={data?.page ?? 1}
            pageCount={data?.pageCount ?? 1}
            total={data?.total ?? 0}
            onChange={goPage}
          />
          <div className="small muted" style={{ textAlign: 'center', marginTop: 6 }}>
            时间按 UTC 显示
          </div>
        </div>
      )}
    </div>
  );
};

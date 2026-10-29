/**
 * 平台管理后台 · 多多币（R-P1 第一轮）。
 *
 * 数据源：
 *   GET  /api/admin/coins/summary   —— 全站汇总（负债余额 / 累计发放 / 消耗 / 过期 / 开户数 / 30 天到期量）
 *   GET  /api/admin/coins/ledger    —— 全站流水（可按 userId / direction 过滤）
 *   POST /api/admin/coins/adjust    —— 人工调整（正=加发，负=回收；原因必填）
 *
 * 两条必须让运营看懂的口径：
 *  1. **币是平台负债**：总负债余额 = 所有账户当前余额之和，每一枚都是平台未来要兑付的
 *     商品抵扣额度。加发要克制（发放即负债），回收上限 = 该用户当前未过期批次可扣量
 *     —— 已过期的币收不回来（那部分负债已经自然消失）。
 *  2. **调整归 SECURITY 档审计**：动币等于动钱，原因必填且进操作审计；
 *     页面上把这条规矩显式说出来，避免运营把「调整」当成随手补偿的快捷键。
 *
 * 展示换算：1 币 = ¥0.01（结算抵扣口径），汇总卡与流水都同时给「币数」与折合金额。
 */

import React, { useCallback, useState } from 'react';
import { adminCoinsApi } from '@duoduo/api-sdk/endpoints';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import type {
  CoinAdminSummary,
  CoinCampaignView,
  CoinLedgerView,
  CoinTaskDefAdminView,
} from '@duoduo/api-sdk/types';
import {
  Alert, Empty, Field, LoadingBlock, Pagination, SearchBar, formatDate, useToast,
} from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';
import { useAuth } from '@duoduo/shared/store/auth';
import { AdminDenied, AdminStat, AdminTabs, isOperator } from './shared';

/** 流水方向 → 中文 + 配色（与全局 tag 样式对齐） */
const DIRECTION_LABELS: Record<string, { text: string; cls: string }> = {
  EARN: { text: '获得', cls: 'tag tag-success' },
  SPEND: { text: '消费', cls: 'tag tag-warn' },
  REFUND: { text: '退款返还', cls: 'tag tag-success' },
  EXPIRE: { text: '过期', cls: 'tag' },
  CLAWBACK: { text: '回收', cls: 'tag tag-danger' },
  ADJUST: { text: '人工调整', cls: 'tag tag-second' },
};

const DIRECTION_FILTERS: Array<{ value: string; label: string }> = [
  { value: '', label: '全部' },
  { value: 'EARN', label: '获得' },
  { value: 'SPEND', label: '消费' },
  { value: 'REFUND', label: '退款返还' },
  { value: 'EXPIRE', label: '过期' },
  { value: 'CLAWBACK', label: '回收' },
  { value: 'ADJUST', label: '人工调整' },
];

/** 业务场景 → 中文（服务端 bizType 全集） */
const BIZ_LABELS: Record<string, string> = {
  ORDER_EARN: '订单返币（T+7）',
  ORDER_FREEZE: '下单冻结',
  ORDER_RELEASE: '关单释放',
  ORDER_REFUND: '退款返还',
  LOT_EXPIRE: '批次过期',
  ADMIN_ADJUST: '人工调整',
  COIN_TASK: '任务奖励',
  SIGNIN_EARN: '每日签到',
};

/** 币数 → 折合金额展示（1 币 = 1 分） */
const coinsToYuan = (coins: number): string => `¥${(coins / 100).toFixed(2)}`;

const PAGE_SIZE = 15;

export const AdminCoinsPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);
  const { push } = useToast();

  // ---------------- 全站汇总 ----------------
  const summaryFetcher = useCallback(() => adminCoinsApi.summary(), []);
  const { data: summary, loading: summaryLoading, error: summaryError, reload: reloadSummary } =
    useAsync<CoinAdminSummary>(summaryFetcher, operator);

  // ---------------- 流水查询 ----------------
  const [direction, setDirection] = useState('');
  const [page, setPage] = useState(1);
  const [userIdInput, setUserIdInput] = useState('');
  const userId = userIdInput.trim();

  const ledgerFetcher = useCallback(
    () =>
      adminCoinsApi.ledger({
        userId: userId || undefined,
        direction: direction || undefined,
        page,
        pageSize: PAGE_SIZE,
      }),
    [userId, direction, page],
  );
  const { data: ledger, loading: ledgerLoading, error: ledgerError, reload: reloadLedger } =
    useAsync(ledgerFetcher, operator, [userId, direction, page]);

  // ---------------- 任务定义 ----------------
  const tasksFetcher = useCallback(() => adminCoinsApi.tasks(), []);
  const { data: tasks, reload: reloadTasks } = useAsync<CoinTaskDefAdminView[]>(tasksFetcher, operator);

  // ---------------- 节日活动 ----------------
  const campaignsFetcher = useCallback(() => adminCoinsApi.campaigns(), []);
  const { data: campaigns, reload: reloadCampaigns } = useAsync<CoinCampaignView[]>(campaignsFetcher, operator);
  const [campaignBusy, setCampaignBusy] = useState(false);
  const [campaignError, setCampaignError] = useState<string | null>(null);
  const [campaignForm, setCampaignForm] = useState({
    code: '',
    name: '',
    startAt: '',
    endAt: '',
    signinMultiplier: '1',
    earnMultiplier: '1',
    budgetTotal: '0',
    perUserCap: '0',
  });

  const submitCampaign = async (e: React.FormEvent) => {
    e.preventDefault();
    setCampaignError(null);
    if (!campaignForm.code.trim() || !campaignForm.name.trim()) {
      setCampaignError('活动编码与名称必填');
      return;
    }
    if (!campaignForm.startAt || !campaignForm.endAt) {
      setCampaignError('请填写活动开始与结束时间');
      return;
    }
    setCampaignBusy(true);
    try {
      await adminCoinsApi.createCampaign({
        code: campaignForm.code.trim(),
        name: campaignForm.name.trim(),
        startAt: campaignForm.startAt,
        endAt: campaignForm.endAt,
        signinMultiplier: Number(campaignForm.signinMultiplier) || 1,
        earnMultiplier: Number(campaignForm.earnMultiplier) || 1,
        budgetTotal: Math.max(0, Number(campaignForm.budgetTotal) || 0),
        perUserCap: Math.max(0, Number(campaignForm.perUserCap) || 0),
      });
      push('活动已创建', 'success');
      setCampaignForm({ code: '', name: '', startAt: '', endAt: '', signinMultiplier: '1', earnMultiplier: '1', budgetTotal: '0', perUserCap: '0' });
      reloadCampaigns();
    } catch (err) {
      setCampaignError(toFriendlyMessage(err));
    } finally {
      setCampaignBusy(false);
    }
  };

  const toggleCampaign = async (item: CoinCampaignView) => {
    try {
      await adminCoinsApi.setCampaignStatus(item.id, item.status !== 'ENABLED');
      push(item.status === 'ENABLED' ? '活动已停用' : '活动已启用', 'success');
      reloadCampaigns();
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    }
  };

  const toggleTask = async (task: CoinTaskDefAdminView) => {
    try {
      await adminCoinsApi.updateTask(task.code, { enabled: !task.enabled });
      push(task.enabled ? '任务已停用' : '任务已启用', 'success');
      reloadTasks();
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    }
  };

  // ---------------- 人工调整 ----------------
  const [adjustUserId, setAdjustUserId] = useState('');
  const [amountText, setAmountText] = useState('');
  const [reason, setReason] = useState('');
  const [adjustBusy, setAdjustBusy] = useState(false);
  const [adjustError, setAdjustError] = useState<string | null>(null);
  const [adjustResult, setAdjustResult] = useState<{ applied: boolean; balance: number } | null>(null);

  const submitAdjust = async (e: React.FormEvent) => {
    e.preventDefault();
    setAdjustError(null);
    setAdjustResult(null);

    const trimmedUserId = adjustUserId.trim();
    if (!/^\d+$/.test(trimmedUserId)) {
      setAdjustError('请输入合法的用户 ID（纯数字）');
      return;
    }
    const amount = Number(amountText.trim());
    if (!Number.isInteger(amount) || amount === 0) {
      setAdjustError('调整数量必须是非 0 整数（正数加发、负数回收）');
      return;
    }
    if (!reason.trim()) {
      setAdjustError('调整原因必填 —— 动币等于动钱，原因进 SECURITY 档审计');
      return;
    }

    setAdjustBusy(true);
    try {
      const result = await adminCoinsApi.adjust({
        userId: trimmedUserId,
        amount,
        reason: reason.trim(),
      });
      setAdjustResult(result);
      push(
        result.applied
          ? `调整完成，该用户当前余额 ${result.balance} 币`
          : '调整未生效（服务端幂等拦截），当前余额不变',
        result.applied ? 'success' : 'info',
      );
      setAmountText('');
      setReason('');
      // 调整会同时改变汇总与流水：两处都刷
      reloadSummary();
      reloadLedger();
    } catch (err) {
      // 404（用户不存在）/ 422（回收超量）等业务原因原样透出
      setAdjustError(toFriendlyMessage(err));
    } finally {
      setAdjustBusy(false);
    }
  };

  if (!operator) return <AdminDenied />;

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 多多币</h2>
      <AdminTabs />

      <Alert kind="info">
        多多币是平台<b>单币种积分</b>：1 币 = ¥0.01，下单可抵扣券后金额的 50%，订单完成后 7 天返币，
        币自获得日起 12 个月有效。总负债余额是平台要兑付的总额度 —— 加发前先看「30 天内到期」，
        避免集中到期冲击。人工调整动币等于动钱，<b>原因必填</b>并归 SECURITY 档审计。
      </Alert>

      {/* ---------- 全站汇总 ---------- */}
      {summaryError ? <Alert kind="error">汇总加载失败：{summaryError}</Alert> : null}
      {summaryLoading && !summary ? <LoadingBlock text="正在加载全站汇总…" /> : null}
      {summary ? (
        <div className="row row-wrap" style={{ gap: 12, margin: '14px 0' }}>
          <AdminStat
            label="总负债余额"
            value={`${summary.totalBalance.toLocaleString()} 币`}
            hint={`折合 ${coinsToYuan(summary.totalBalance)}`}
          />
          <AdminStat
            label="累计发放"
            value={`${summary.totalEarned.toLocaleString()} 币`}
            hint={`折合 ${coinsToYuan(summary.totalEarned)}`}
          />
          <AdminStat
            label="累计消耗"
            value={`${summary.totalSpent.toLocaleString()} 币`}
            hint={`折合 ${coinsToYuan(summary.totalSpent)}`}
          />
          <AdminStat
            label="累计过期"
            value={`${summary.totalExpired.toLocaleString()} 币`}
            hint="过期即负债自然消失"
          />
          <AdminStat label="开户数" value={summary.accounts.toLocaleString()} hint="有币账户的用户数" />
          <AdminStat
            label="30 天内到期"
            value={`${summary.expiringIn30dCoins.toLocaleString()} 币`}
            hint={`折合 ${coinsToYuan(summary.expiringIn30dCoins)} · 关注集中到期`}
          />
        </div>
      ) : null}

      {/* ---------- 任务定义（R-P1 第三轮） ---------- */}
      <div className="card card-pad" style={{ margin: '14px 0' }}>
        <div className="row-between row-wrap">
          <h3 style={{ margin: 0 }}>任务定义</h3>
          <span className="small muted">停用后该任务不再计进度；奖励与每日上限沿用当前值</span>
        </div>
        {tasks && tasks.length > 0 ? (
          <div style={{ overflow: 'auto', marginTop: 10 }}>
            <table className="table">
              <thead>
                <tr>
                  <th>任务</th>
                  <th>类型</th>
                  <th>目标</th>
                  <th>奖励</th>
                  <th>每日上限</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {tasks.map((task) => (
                  <tr key={task.code}>
                    <td>
                      <div>{task.name}</div>
                      <div className="small muted mono">{task.code}</div>
                    </td>
                    <td>{task.taskType === 'NEWCOMER' ? '新人' : '每日'}</td>
                    <td>{task.targetCount}</td>
                    <td>{task.rewardCoins} 币</td>
                    <td>{task.dailyCap > 0 ? `${task.dailyCap} 币` : '不限'}</td>
                    <td>
                      <span className={task.enabled ? 'tag tag-success' : 'tag'}>
                        {task.enabled ? '启用' : '停用'}
                      </span>
                    </td>
                    <td>
                      <button className="btn btn-sm btn-ghost" onClick={() => void toggleTask(task)}>
                        {task.enabled ? '停用' : '启用'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="small muted" style={{ marginTop: 10 }}>暂无任务定义</div>
        )}
      </div>

      {/* ---------- 节日活动 ---------- */}
      <div className="card card-pad" style={{ margin: '14px 0' }}>
        <div className="row-between row-wrap">
          <h3 style={{ margin: 0 }}>节日活动</h3>
          <span className="small muted">签到 / 返币倍率与预算；预算用完自动退回基础值</span>
        </div>
        {campaigns && campaigns.length > 0 ? (
          <div className="stack" style={{ gap: 8, marginTop: 10 }}>
            {campaigns.map((item) => {
              const percent = item.budgetTotal > 0
                ? Math.min(100, Math.round((item.budgetUsed / item.budgetTotal) * 100))
                : 0;
              return (
                <div key={item.id} className="card card-pad" style={{ padding: 12 }}>
                  <div className="row-between row-wrap" style={{ gap: 8 }}>
                    <div>
                      <div style={{ fontWeight: 600 }}>
                        {item.name}
                        <span className={item.status === 'ENABLED' ? 'tag tag-success' : 'tag'} style={{ marginLeft: 8 }}>
                          {item.status === 'ENABLED' ? '进行中' : '已停用'}
                        </span>
                      </div>
                      <div className="small muted" style={{ marginTop: 4 }}>
                        {formatDate(item.startAt)} ~ {formatDate(item.endAt)}
                        {' · '}签到 {item.signinMultiplier}x / 返币 {item.earnMultiplier}x
                        {item.perUserCap > 0 ? ` · 每人上限 ${item.perUserCap} 币` : ''}
                      </div>
                      {item.budgetTotal > 0 ? (
                        <div className="small muted" style={{ marginTop: 4 }}>
                          预算 {item.budgetUsed} / {item.budgetTotal} 币（{percent}%）
                        </div>
                      ) : (
                        <div className="small muted" style={{ marginTop: 4 }}>预算不限</div>
                      )}
                    </div>
                    <button className="btn btn-sm btn-ghost" onClick={() => void toggleCampaign(item)}>
                      {item.status === 'ENABLED' ? '停用' : '启用'}
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        ) : (
          <div className="small muted" style={{ marginTop: 10 }}>还没有活动</div>
        )}

        <form onSubmit={submitCampaign} style={{ marginTop: 14, borderTop: '1px solid var(--line, #eee)', paddingTop: 12 }}>
          <div className="row row-wrap" style={{ gap: 10, alignItems: 'flex-end' }}>
            <Field label="编码" required>
              <input className="input" style={{ width: 140 }} value={campaignForm.code}
                onChange={(e) => setCampaignForm({ ...campaignForm, code: e.target.value })} placeholder="如 618" />
            </Field>
            <Field label="名称" required>
              <input className="input" style={{ width: 180 }} value={campaignForm.name}
                onChange={(e) => setCampaignForm({ ...campaignForm, name: e.target.value })} placeholder="如 618 宠物节" />
            </Field>
            <Field label="开始" required>
              <input className="input" type="datetime-local" value={campaignForm.startAt}
                onChange={(e) => setCampaignForm({ ...campaignForm, startAt: e.target.value })} />
            </Field>
            <Field label="结束" required>
              <input className="input" type="datetime-local" value={campaignForm.endAt}
                onChange={(e) => setCampaignForm({ ...campaignForm, endAt: e.target.value })} />
            </Field>
            <Field label="签到倍率">
              <input className="input" style={{ width: 90 }} type="number" step="0.1" min="1" value={campaignForm.signinMultiplier}
                onChange={(e) => setCampaignForm({ ...campaignForm, signinMultiplier: e.target.value })} />
            </Field>
            <Field label="返币倍率">
              <input className="input" style={{ width: 90 }} type="number" step="0.1" min="1" value={campaignForm.earnMultiplier}
                onChange={(e) => setCampaignForm({ ...campaignForm, earnMultiplier: e.target.value })} />
            </Field>
            <Field label="总预算（币）">
              <input className="input" style={{ width: 130 }} type="number" min="0" value={campaignForm.budgetTotal}
                onChange={(e) => setCampaignForm({ ...campaignForm, budgetTotal: e.target.value })} />
            </Field>
            <Field label="每人上限（币）">
              <input className="input" style={{ width: 130 }} type="number" min="0" value={campaignForm.perUserCap}
                onChange={(e) => setCampaignForm({ ...campaignForm, perUserCap: e.target.value })} />
            </Field>
            <button className="btn btn-primary" type="submit" disabled={campaignBusy}>
              {campaignBusy ? '创建中…' : '创建活动'}
            </button>
          </div>
          {campaignError ? <Alert kind="error">{campaignError}</Alert> : null}
        </form>
      </div>

      {/* ---------- 人工调整 ---------- */}
      <div className="card card-pad" style={{ margin: '14px 0' }}>
        <h3 style={{ marginBottom: 10 }}>人工调整</h3>
        <form onSubmit={submitAdjust}>
          <div className="row row-wrap" style={{ gap: 12, alignItems: 'flex-end' }}>
            <Field label="用户 ID" required>
              <input
                className="input"
                style={{ width: 220 }}
                value={adjustUserId}
                onChange={(e) => setAdjustUserId(e.target.value.replace(/\D/g, ''))}
                placeholder="18 位用户 ID（纯数字）"
                inputMode="numeric"
              />
            </Field>
            <Field label="数量（正=加发 / 负=回收）" required>
              <input
                className="input"
                style={{ width: 180 }}
                value={amountText}
                onChange={(e) => setAmountText(e.target.value.replace(/[^\d-]/g, '').replace(/(?!^)-/g, ''))}
                placeholder="如 100 或 -50"
              />
            </Field>
            <Field label="原因（必填，进 SECURITY 审计）" required>
              <input
                className="input"
                style={{ width: 320 }}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                maxLength={200}
                placeholder="如：客诉补偿单 20261026-001"
              />
            </Field>
            <button className="btn btn-primary" type="submit" disabled={adjustBusy}>
              {adjustBusy ? '提交中…' : '提交调整'}
            </button>
          </div>
        </form>
        {adjustError ? (
          <div style={{ marginTop: 10 }}>
            <Alert kind="error">{adjustError}</Alert>
          </div>
        ) : null}
        {adjustResult ? (
          <div style={{ marginTop: 10 }}>
            <Alert kind="success">
              {adjustResult.applied
                ? `调整已生效，该用户当前余额 ${adjustResult.balance.toLocaleString()} 币。`
                : '调整未生效（幂等拦截），余额不变。'}
              回收时上限为该用户<b>未过期批次</b>可扣量 —— 超出会被服务端拒绝（422）。
            </Alert>
          </div>
        ) : null}
      </div>

      {/* ---------- 全站流水 ---------- */}
      <div className="row row-wrap" style={{ gap: 8, margin: '14px 0' }}>
        {DIRECTION_FILTERS.map((opt) => (
          <button
            key={opt.value}
            className={`btn btn-sm ${direction === opt.value ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => {
              setDirection(opt.value);
              setPage(1);
            }}
          >
            {opt.label}
          </button>
        ))}
      </div>

      <div className="list-toolbar">
        <SearchBar
          value={userIdInput}
          onChange={(v) => {
            setUserIdInput(v.replace(/\D/g, ''));
            setPage(1);
          }}
          busy={ledgerLoading && Boolean(userIdInput.trim())}
          placeholder="按用户 ID 过滤"
          width={280}
        />
        {userId ? <span className="small muted">用户 {userId} 的币流水</span> : null}
        <a
          className="btn btn-sm btn-ghost"
          style={{ marginLeft: 'auto' }}
          href={adminCoinsApi.ledgerExportUrl({ userId: userId || undefined, direction: direction || undefined })}
        >
          导出 CSV
        </a>
      </div>

      {ledgerError ? <Alert kind="error">{ledgerError}</Alert> : null}
      {ledgerLoading && !ledger ? <LoadingBlock text="正在加载币流水…" /> : null}

      {!ledgerLoading && ledger && ledger.items.length === 0 ? (
        <Empty
          icon="🪙"
          title="没有符合条件的币流水"
          desc="下单抵扣、完成返币、退款返还、过期清算都会在这里留痕"
        />
      ) : null}

      {!ledgerLoading && ledger && ledger.items.length > 0 ? (
        <div className="card card-pad">
          <table className="table">
            <thead>
              <tr>
                <th>用户</th>
                <th>方向</th>
                <th>数量（币）</th>
                <th>记账后余额</th>
                <th>场景</th>
                <th>关联单号</th>
                <th>备注</th>
                <th>时间</th>
              </tr>
            </thead>
            <tbody>
              {ledger.items.map((item: CoinLedgerView) => {
                const dir = DIRECTION_LABELS[item.direction];
                return (
                  <tr key={item.id}>
                    <td className="mono small">{item.userId}</td>
                    <td>
                      <span className={dir?.cls ?? 'tag'}>{dir?.text ?? item.direction}</span>
                    </td>
                    <td>
                      <strong>{item.amount.toLocaleString()}</strong>
                      <span className="small muted">（{coinsToYuan(item.amount)}）</span>
                    </td>
                    <td>{item.balanceAfter.toLocaleString()}</td>
                    <td>
                      {BIZ_LABELS[item.bizType] ?? item.bizType}
                      <div className="small muted mono">{item.bizType}</div>
                    </td>
                    <td className="mono small">{item.bizNo ?? '—'}</td>
                    <td className="small" style={{ maxWidth: 220 }}>
                      {item.remark ?? '—'}
                    </td>
                    <td className="small">{formatDate(item.createdAt)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : null}

      {ledger ? (
        <Pagination
          page={page}
          pageCount={Math.max(1, ledger.pageCount)}
          total={ledger.total}
          onChange={setPage}
        />
      ) : null}
    </div>
  );
};

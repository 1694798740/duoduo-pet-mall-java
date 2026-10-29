/**
 * 我的多多币（R-P1 · 第一百二十六轮；R-P2 签到同轮追加）。
 *
 * 四块信息各答一个问题：
 *   1. 账户概览 —— 「我现在有多少币、来龙去脉对不对」（余额 / 累计获得 / 累计消耗）；
 *   2. 签到 —— 「今天领了吗」（每日 5 币，连签 3/7 天当天加发，服务端唯一判定）；
 *   3. 到期提醒 —— 「哪些币要先花掉」（30 天内到期批次，服务端只回即将到期的）；
 *   4. 流水 —— 「每一笔怎么变的」（direction + 场景 + 记账后余额快照，出问题可对账）。
 *
 * 金额口径：币是整数「个」，1 币 = 0.01 元。余额与累计值都是雪花级安全的长整型个数，
 * 直接展示数字即可，不做 Number() 转换以外的加工（id 同理必须保持字符串）。
 */

import React, { useCallback, useState } from 'react';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { coinsApi } from '@duoduo/api-sdk/endpoints';
import type {
  CoinBalanceView,
  CoinCampaignView,
  CoinLedgerView,
  CoinSigninMonthView,
  CoinTaskView,
  PageResult,
} from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import { Alert, LoadingBlock, useToast } from '@duoduo/shared/components/ui';

/** 流水方向的中文与配色：进出语义看 direction，不看 amount 正负（amount 一律为正） */
const DIRECTION_LABEL: Record<CoinLedgerView['direction'], { text: string; color?: string }> = {
  EARN: { text: '获得', color: 'var(--success)' },
  REFUND: { text: '退回', color: 'var(--success)' },
  ADJUST: { text: '调整', color: 'var(--success)' },
  SPEND: { text: '使用', color: 'var(--danger)' },
  EXPIRE: { text: '过期', color: 'var(--muted, #999)' },
  CLAWBACK: { text: '扣回', color: 'var(--danger)' },
};

/** 场景翻译成用户语言：bizType 是服务端口径，直接展示对用户没有意义 */
const BIZ_LABEL: Record<string, string> = {
  ORDER_EARN: '订单完成返币',
  ORDER_FREEZE: '下单抵扣（冻结）',
  ORDER_RELEASE: '订单取消退回',
  ORDER_REFUND: '订单退款退回',
  LOT_EXPIRE: '批次到期作废',
  ADMIN_ADJUST: '平台调整',
  SIGNIN_EARN: '每日签到',
  COIN_TASK: '任务奖励',
};

const formatDate = (iso: string) => (iso || '').slice(0, 16).replace('T', ' ');

/** 月历格：1 号起排到月末，周一开头；已签的日期点亮 */
const SignInCalendar: React.FC<{ view: CoinSigninMonthView }> = ({ view }) => {
  const [year, month] = view.month.split('-').map(Number);
  if (!year || !month) return null;
  const firstDay = new Date(year, month - 1, 1);
  // getDay(): 0=周日；日历按「一二三四五六日」排，周一开头
  const offset = (firstDay.getDay() + 6) % 7;
  const daysInMonth = new Date(year, month, 0).getDate();
  const signed = new Set(view.signedDates);
  const cells: Array<{ day: number | null; date: string | null }> = [];
  for (let i = 0; i < offset; i++) cells.push({ day: null, date: null });
  for (let d = 1; d <= daysInMonth; d++) {
    const date = `${view.month}-${String(d).padStart(2, '0')}`;
    cells.push({ day: d, date });
  }
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 4, marginTop: 10 }}>
      {['一', '二', '三', '四', '五', '六', '日'].map((w, wi) => (
        <div key={`w-${wi}`} className="small muted" style={{ textAlign: 'center' }}>
          {w}
        </div>
      ))}
      {cells.map((cell, index) =>
        cell.day === null ? (
          <div key={`blank-${index}`} />
        ) : (
          (() => {
            // day 非 null 时 date 必非 null；TS 推不出跨字段约束，这里显式收窄
            const date = cell.date ?? '';
            const hit = signed.has(date);
            return (
              <div
                key={date}
                className="small"
                style={{
                  textAlign: 'center',
                  padding: '6px 0',
                  borderRadius: 6,
                  background: hit ? 'var(--brand, #ff5c5c)' : 'transparent',
                  color: hit ? '#fff' : 'inherit',
                  fontWeight: hit ? 700 : 400,
                }}
              >
                {cell.day}
              </div>
            );
          })()
        ),
      )}
    </div>
  );
};

/** 任务行：进度条 + 奖励状态；没有「领取」按钮 —— 达成时服务端已自动发币 */
const TaskRow: React.FC<{ task: CoinTaskView }> = ({ task }) => {
  const percent = task.targetCount > 0 ? Math.min(100, Math.round((task.progress / task.targetCount) * 100)) : 100;
  return (
    <div style={{ padding: '10px 0', borderBottom: '1px solid var(--line, #eee)' }}>
      <div className="row-between row-wrap" style={{ gap: 8 }}>
        <div style={{ minWidth: 0 }}>
          <div className="small" style={{ fontWeight: 600 }}>
            {task.name}
            <span className="small muted" style={{ marginLeft: 6 }}>
              +{task.rewardCoins} 币
            </span>
          </div>
          <div className="small muted" style={{ marginTop: 2 }}>
            {task.description ?? ''}
          </div>
        </div>
        <span className={task.rewarded ? 'tag tag-success' : task.completed ? 'tag tag-success' : 'tag'}>
          {task.rewarded ? '已发放' : task.completed ? '已达成' : `${task.progress}/${task.targetCount}`}
        </span>
      </div>
      <div
        style={{
          height: 6,
          borderRadius: 3,
          background: 'var(--line, #eee)',
          marginTop: 8,
          overflow: 'hidden',
        }}
      >
        <div
          style={{
            width: `${task.rewarded ? 100 : percent}%`,
            height: '100%',
            background: task.rewarded ? 'var(--success, #16a34a)' : 'var(--brand, #ff5c5c)',
          }}
        />
      </div>
    </div>
  );
};

export const MyCoinsPage: React.FC = () => {
  const { push } = useToast();
  const meFetcher = useCallback(() => coinsApi.me(), []);
  const { data: account, loading, error, reload } = useAsync<CoinBalanceView>(meFetcher);

  const [page, setPage] = useState(1);
  const ledgerFetcher = useCallback(() => coinsApi.ledger({ page, pageSize: 10 }), [page]);
  const { data: ledger, loading: ledgerLoading } = useAsync<PageResult<CoinLedgerView>>(ledgerFetcher, true, [page]);

  // 签到月历（R-P2）：本月已签日期 + 连签天数 + 今日是否已签
  const monthFetcher = useCallback(() => coinsApi.signinMonth(), []);
  const { data: signinMonthView, reload: reloadSignin } = useAsync<CoinSigninMonthView>(monthFetcher);

  /** R-P1 第三轮：任务中心 + 进行中的节日活动 */
  const tasksFetcher = useCallback(() => coinsApi.tasks(), []);
  const { data: tasks, reload: reloadTasks } = useAsync<CoinTaskView[]>(tasksFetcher);
  const campaignFetcher = useCallback(() => coinsApi.activeCampaign(), []);
  const { data: campaign } = useAsync<CoinCampaignView | null>(campaignFetcher);

  const [signinBusy, setSigninBusy] = useState(false);
  const signedToday = signinMonthView?.signedToday ?? false;

  const doSignin = async () => {
    if (signinBusy || signedToday) return;
    setSigninBusy(true);
    try {
      const result = await coinsApi.signin();
      const bonus =
        result.continuousDays === 7 ? '，连签 7 天额外 +30' : result.continuousDays === 3 ? '，连签 3 天额外 +10' : '';
      push(`签到成功 +${result.coins} 币${bonus}`, 'success');
      // 签到影响账户余额与当月月历：两处都刷
      await Promise.all([reload(), reloadSignin(), reloadTasks()]);
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setSigninBusy(false);
    }
  };

  if (loading && !account) return <LoadingBlock text="正在加载多多币账户…" />;
  if (error && !account) {
    return (
      <Alert kind="error">
        多多币账户加载失败：{error}
        <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={reload}>
          重试
        </button>
      </Alert>
    );
  }

  const expiring = account?.expiringLots ?? [];

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">我的多多币</h2>
        <span className="section-sub">1 币 = 0.01 元，下单结算时可直接抵扣</span>
      </div>

      {campaign ? (
        <Alert kind="success">
          <b>{campaign.name}</b> 进行中：签到 {campaign.signinMultiplier} 倍、购物返币 {campaign.earnMultiplier} 倍
          {campaign.perUserCap > 0 ? `，每人最多加成 ${campaign.perUserCap} 币` : ''}
          ；活动至 {formatDate(campaign.endAt)}。
        </Alert>
      ) : null}

      <div className="card card-pad" style={{ textAlign: 'center', padding: '28px 16px', marginTop: campaign ? 12 : 0 }}>
        <div className="small muted">当前可用</div>
        <div style={{ fontSize: 40, fontWeight: 700, color: 'var(--brand)', lineHeight: 1.2 }}>
          {account?.balance ?? 0}
          <span className="small muted" style={{ fontSize: 14, marginLeft: 6 }}>币</span>
        </div>
        <div className="row row-wrap" style={{ gap: 24, justifyContent: 'center', marginTop: 12 }}>
          <span className="small">
            累计获得 <strong>{account?.totalEarned ?? 0}</strong>
          </span>
          <span className="small">
            累计消耗 <strong>{account?.totalSpent ?? 0}</strong>
          </span>
          {account && account.expiringSoonCoins > 0 ? (
            <span className="small" style={{ color: 'var(--danger)' }}>
              即将到期 <strong>{account.expiringSoonCoins}</strong> 币
            </span>
          ) : null}
        </div>
      </div>

      {/* ---------- 签到（R-P2） ---------- */}
      <div className="card card-pad" style={{ marginTop: 16 }}>
        <div className="row-between">
          <h3 style={{ margin: 0 }}>每日签到</h3>
          <span className="small muted">
            {signinMonthView && signinMonthView.continuousDays > 0
              ? `已连签 ${signinMonthView.continuousDays} 天`
              : '每天签到领 5 币'}
          </span>
        </div>
        <button
          className={`btn ${signedToday ? 'btn-ghost' : 'btn-primary'}`}
          style={{ marginTop: 12, width: '100%' }}
          disabled={signinBusy || signedToday}
          onClick={doSignin}
        >
          {signedToday ? '今日已签到' : signinBusy ? '签到中…' : '签到领币 +5'}
        </button>
        {signinMonthView ? (
          <>
            <SignInCalendar view={signinMonthView} />
            <div className="small muted" style={{ marginTop: 8 }}>
              连签 3 天当天额外 +10 币，连签 7 天当天额外 +30 币；断签后连签天数重新计算。
            </div>
          </>
        ) : null}
      </div>

      {/* ---------- 任务中心（R-P1 第三轮） ---------- */}
      <div className="card card-pad" style={{ marginTop: 16 }}>
        <div className="row-between">
          <h3 style={{ margin: 0 }}>任务中心</h3>
          <span className="small muted">达成即自动发币，无需手动领取</span>
        </div>
        {!tasks ? (
          <div className="small muted" style={{ marginTop: 10 }}>正在加载任务…</div>
        ) : tasks.length === 0 ? (
          <div className="small muted" style={{ marginTop: 10 }}>当前没有可做的任务。</div>
        ) : (
          <>
            <div className="small muted" style={{ marginTop: 12, fontWeight: 600 }}>每日任务</div>
            {tasks.filter((task) => task.taskType === 'DAILY').map((task) => (
              <TaskRow key={task.code} task={task} />
            ))}
            {tasks.some((task) => task.taskType === 'NEWCOMER') ? (
              <>
                <div className="small muted" style={{ marginTop: 14, fontWeight: 600 }}>新人任务（一次性）</div>
                {tasks.filter((task) => task.taskType === 'NEWCOMER').map((task) => (
                  <TaskRow key={task.code} task={task} />
                ))}
              </>
            ) : null}
          </>
        )}
        <div className="small muted" style={{ marginTop: 10 }}>
          浏览任务会在商品详情 / 分享内容停留满 15 / 10 秒后自动计数；
          收藏、加购、发布、评价等动作由对应页面触发，每日只算一次。
        </div>
      </div>

      {expiring.length > 0 ? (
        <div className="card card-pad" style={{ marginTop: 16, borderColor: 'var(--warning, #f0a020)' }}>
          <h3 style={{ marginBottom: 10 }}>30 天内到期</h3>
          <div className="stack" style={{ gap: 6 }}>
            {expiring.map((lot) => (
              <div className="row-between" key={lot.lotId}>
                <span className="small muted">{formatDate(lot.expireAt)} 到期</span>
                <strong style={{ color: 'var(--danger)' }}>{lot.coins} 币</strong>
              </div>
            ))}
          </div>
          <div className="small muted" style={{ marginTop: 8 }}>
            到期未使用的币会自动作废；下单时系统会优先消耗先到期的批次。
          </div>
        </div>
      ) : null}

      <div className="card card-pad" style={{ marginTop: 16 }}>
        <div className="row-between row-wrap" style={{ marginBottom: 12 }}>
          <h3 style={{ margin: 0 }}>币流水</h3>
          {/* 矩阵 · 买家侧「我的多多币」导出：仅本人，方向翻成中文，最多 5 万条 */}
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            title="导出本人全部多多币流水 CSV"
            onClick={() => {
              window.location.href = coinsApi.ledgerExportUrl();
            }}
          >
            导出流水
          </button>
        </div>
        {ledgerLoading && !ledger ? <div className="small muted">正在加载流水…</div> : null}
        {ledger && ledger.items.length === 0 ? (
          <div className="small muted">还没有流水记录。</div>
        ) : null}
        {ledger && ledger.items.length > 0 ? (
          <div className="stack" style={{ gap: 0 }}>
            {ledger.items.map((row) => {
              const dir = DIRECTION_LABEL[row.direction] ?? { text: row.direction };
              return (
                <div
                  key={row.id}
                  className="row-between row-wrap"
                  style={{ padding: '10px 0', borderBottom: '1px solid var(--line, #eee)' }}
                >
                  <div style={{ minWidth: 0 }}>
                    <div className="small" style={{ fontWeight: 600 }}>
                      {BIZ_LABEL[row.bizType] ?? row.bizType}
                    </div>
                    <div className="small muted" style={{ marginTop: 2 }}>
                      {formatDate(row.createdAt)}
                      {row.remark ? ` · ${row.remark}` : ''}
                    </div>
                  </div>
                  <div style={{ textAlign: 'right' }}>
                    <div className="small" style={{ color: dir.color, fontWeight: 700 }}>
                      {row.direction === 'SPEND' || row.direction === 'EXPIRE' || row.direction === 'CLAWBACK'
                        ? '-'
                        : '+'}
                      {Math.abs(row.amount)}
                    </div>
                    <div className="small muted">余额 {row.balanceAfter}</div>
                  </div>
                </div>
              );
            })}
          </div>
        ) : null}

        {ledger && ledger.pageCount > 1 ? (
          <div className="row row-wrap" style={{ gap: 10, marginTop: 14, justifyContent: 'center' }}>
            <button className="btn btn-ghost btn-sm" disabled={page <= 1} onClick={() => setPage(page - 1)}>
              上一页
            </button>
            <span className="small muted">
              第 {ledger.page} / {ledger.pageCount} 页
            </span>
            <button
              className="btn btn-ghost btn-sm"
              disabled={page >= ledger.pageCount}
              onClick={() => setPage(page + 1)}
            >
              下一页
            </button>
          </div>
        ) : null}
      </div>
    </div>
  );
};

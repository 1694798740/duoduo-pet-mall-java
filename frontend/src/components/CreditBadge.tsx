/**
 * 信用分展示件（A4）—— 商品详情 / 闲置详情 / 「我的」三处**共用同一份口径**。
 *
 * 为什么抽成共用件：同一个卖家的信用分会在「商品详情」「闲置详情」「我的」三处出现，
 * 各写一份的结果必然是「这里显示优秀、那里显示良好」——
 * 而这种不一致在用户眼里就是系统坏了。评分与等级的**中文文案一律用服务端下发的**，
 * 本组件只维护「等级 → 配色 / 图标」这一份纯装饰映射。
 *
 * 两个刻意的取舍：
 *   1. **失败不打断页面**：信用分是有信息量的附加信息，不是页面的主体。
 *      取不到时只显示一行说明（而不是整页报错），商品该看什么还看什么。
 *   2. **明细只做「分数的拆解」**：四个因子的 points 之和恒等于总分（服务端在模型层保证），
 *      因此进度条的宽度直接用 points / weight 计算即可，不需要在前端做任何归一或补偿。
 *
 * 数据来源：`GET /api/reviews/seller/{sellerId}/credit`（履约域，公开只读）。
 */

import React, { useCallback } from 'react';
import { creditApi } from '../api/endpoints';
import type { CreditView } from '../api/types';
import { useAsync } from './hooks';
import { Alert, LoadingBlock, formatDate } from './ui';

/**
 * 等级 → 配色与图标。中文文案**不在这里**维护（用服务端的 levelLabel）。
 * `FALLBACK_TONE` 单独拎出来是有意的：本项目开启 noUncheckedIndexedAccess，
 * `Record<string, T>[k]` 的类型是 `T | undefined`，只有指向一个确定存在的常量，
 * `toneOf` 的返回类型才不是可空的（否则每个用到 tone.color 的地方都要加一次判空）。
 */
const FALLBACK_TONE = { color: 'var(--text-3)', soft: 'var(--surface-2)', icon: '🌱' };

const LEVEL_TONE: Record<string, { color: string; soft: string; icon: string }> = {
  EXCELLENT: { color: 'var(--success)', soft: 'var(--success-soft)', icon: '🏅' },
  GOOD: { color: 'var(--brand)', soft: 'var(--brand-soft)', icon: '👍' },
  FAIR: { color: 'var(--warn)', soft: 'var(--warn-soft)', icon: '🙂' },
  POOR: FALLBACK_TONE,
};

function toneOf(level: string) {
  return LEVEL_TONE[level] ?? FALLBACK_TONE;
}

/** 一行式的信用徽标（卖家侧）：`🏅 信用 98 · 优秀`。加载中 / 失败都不占据视觉重心。 */
export const CreditBadge: React.FC<{ sellerId: string; label?: string }> = ({
  sellerId,
  label = '卖家信用',
}) => {
  const fetcher = useCallback(
    (signal: AbortSignal) => creditApi.seller(sellerId, { signal }),
    [sellerId],
  );
  const { data, loading, error } = useAsync(fetcher, sellerId !== '', [sellerId]);

  if (loading) {
    return <span className="small muted">{label} 加载中…</span>;
  }
  if (error || !data) {
    // 取不到就不显示（而不是把错误顶到页面上）：信用分缺失不影响商品本身可看
    return null;
  }
  const tone = toneOf(data.level);
  return (
    <span
      className="tag"
      data-testid="credit-badge"
      data-credit-score={data.score}
      data-credit-level={data.level}
      title={`${label}：${data.levelLabel}（${data.score} 分）`}
      style={{ background: tone.soft, color: tone.color, border: 'none' }}
    >
      {tone.icon} {label} {data.score} · {data.levelLabel}
    </span>
  );
};

/** 因子进度条：宽度 = 得分 / 满分。四项之和恒等于总分，所以它同时就是「分数的拆解」。 */
const FactorRow: React.FC<{ factor: { code: string; label: string; weight: number; rawValue: string; points: number } }> = ({
  factor,
}) => {
  const pct = factor.weight > 0 ? Math.round((factor.points / factor.weight) * 100) : 0;
  return (
    <div
      data-testid="credit-factor-row"
      data-factor-code={factor.code}
      style={{ padding: '10px 0', borderTop: '1px solid var(--border)' }}
    >
      <div className="row-between row-wrap" style={{ alignItems: 'baseline' }}>
        <strong style={{ fontSize: 14 }}>{factor.label}</strong>
        <span className="small muted">
          {factor.points} / {factor.weight}
        </span>
      </div>
      <div
        style={{
          height: 6,
          borderRadius: 3,
          background: 'var(--border)',
          marginTop: 6,
          overflow: 'hidden',
        }}
      >
        <div
          style={{
            width: `${pct}%`,
            height: '100%',
            background: 'var(--brand)',
            borderRadius: 3,
          }}
        />
      </div>
      <div className="small muted" style={{ marginTop: 4 }}>
        {factor.rawValue}
      </div>
    </div>
  );
};

/**
 * 完整信用分面板（「我的」页用）：大号分数 + 明细 + 原始输入 + 计算时刻。
 *
 * 为什么把 `inputs` 也展示出来：信用分是个「凭几个计数算出来的数」，
 * 用户看到 94 分而不知道为什么时，唯一能建立信任的做法就是把依据摊开 ——
 * 完成了几单、均分多少、有几笔被裁定退款。这些都不是隐私字段（只有计数与评分）。
 */
export const CreditPanel: React.FC<{ userId: string }> = ({ userId }) => {
  const fetcher = useCallback(
    (signal: AbortSignal) => creditApi.seller(userId, { signal }),
    [userId],
  );
  const { data, loading, error } = useAsync(fetcher, userId !== '', [userId]);

  if (loading) return <LoadingBlock text="正在加载信用分…" />;
  if (error) return <Alert kind="error">{error}</Alert>;
  if (!data) return null;

  const tone = toneOf(data.level);
  const view: CreditView = data;

  return (
    <div data-testid="credit-panel" data-credit-score={view.score}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 14,
          padding: '4px 0 12px',
        }}
      >
        <div
          style={{
            width: 64,
            height: 64,
            borderRadius: '50%',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 24,
            fontWeight: 700,
            color: tone.color,
            background: tone.soft,
            flex: '0 0 auto',
          }}
        >
          {view.score}
        </div>
        <div>
          <div style={{ fontSize: 16, fontWeight: 600 }}>
            {tone.icon} {view.levelLabel}
          </div>
          <div className="small muted">满分 100 · 信用分体现的是交易履约的可信度</div>
        </div>
      </div>

      {view.factors.map((f) => (
        <FactorRow key={f.code} factor={f} />
      ))}

      <div className="small muted" style={{ marginTop: 12 }}>
        依据：已完成 {view.inputs.completedOrders} 单 · 已关闭 {view.inputs.closedOrders} 单 ·
        已退款 {view.inputs.refundedOrders} 单 · 收到评价 {view.inputs.reviewCount} 条（均分{' '}
        {view.inputs.avgRating}）· 被裁定退款 {view.inputs.adverseAfterSales} 笔
      </div>
      <div className="small muted" style={{ marginTop: 4 }}>
        计算于 {formatDate(view.generatedAt)}（实时计算，不是缓存值）
      </div>
    </div>
  );
};

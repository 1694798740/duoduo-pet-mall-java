/**
 * 秒杀展示组件（全站统一视觉）。
 *
 * <h2>为什么集中在这里</h2>
 * 「秒杀」标识与「现价 + 划线原价」必须**长得一样**：首页卡片、搜索结果、商品详情、
 * 秒杀列表页共用同一批组件，避免每个页面各写一套颜色和字号 ——
 * 那样用户会看到四种「秒杀」，反而降低可信度。
 *
 * <h2>视觉口径</h2>
 * <ul>
 *   <li>标签：进行中＝红橙实心（最醒目），即将开始＝琥珀描边（区别于已结束的灰）。</li>
 *   <li>价格：现价用品牌红加粗放大；原价用 `line-through` + 灰色小字，并显式带「原价」二字
 *       （只靠划线在小字号下容易被看漏）。</li>
 *   <li>倒计时：等宽数字，避免每秒跳动时宽度抖动。</li>
 * </ul>
 */

import React, { useEffect, useRef } from 'react';
import { formatPrice, parseServerTime } from '@duoduo/api-sdk/client';
import { useNow, useSeckill } from '../store/seckill';
import type { SeckillPhase } from '../store/seckill';

/** 「秒杀」标签 */
export const SeckillTag: React.FC<{ phase: SeckillPhase; text?: string }> = ({ phase, text }) => {
  if (phase === 'ENDED') return null;
  const label = text ?? (phase === 'ONGOING' ? '秒杀' : '即将开抢');
  return (
    <span className={`sk-tag${phase === 'ONGOING' ? ' sk-tag-live' : ' sk-tag-soon'}`}>
      <span className="sk-tag-dot" aria-hidden="true" />
      {label}
    </span>
  );
};

/** 秒杀价 + 划线原价 */
export const SeckillPrice: React.FC<{
  price: number;
  original: number;
  /** md = 商品卡；lg = 详情页主价格区 */
  size?: 'sm' | 'md' | 'lg';
  /** 同 SPU 多个秒杀规格价不同时，价格后加「起」 */
  from?: boolean;
}> = ({ price, original, size = 'md', from = false }) => (
  <span className={`sk-price sk-price-${size}`}>
    <span className="sk-price-now">{formatPrice(price)}</span>
    {from ? <span className="sk-price-from">起</span> : null}
    {original > price ? (
      <span className="sk-price-was">
        <span className="sk-price-was-label">原价</span>
        <s>{formatPrice(original)}</s>
      </span>
    ) : null}
  </span>
);

function formatRemain(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  if (days > 0) return `${days}天 ${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
}

/**
 * 倒计时（进行中＝距结束，即将开始＝距开始）。
 *
 * 到点后会触发一次全站刷新：活动的 `phase` 是服务端算的，
 * 本地只能靠「重新拉一次」把它从 ONGOING 变成 ENDED，
 * 否则标签会一直停在「秒杀」而按钮已不可用。
 */
export const SeckillCountdown: React.FC<{
  startAt: string;
  endAt: string;
  phase: SeckillPhase;
  /** 紧凑模式：首页卡片内用，不带“距结束”三个字 */
  compact?: boolean;
}> = ({ startAt, endAt, phase, compact = false }) => {
  const now = useNow(phase !== 'ENDED');
  const { refresh } = useSeckill();
  const target = phase === 'UPCOMING' ? parseServerTime(startAt) : parseServerTime(endAt);
  const remain = target - now;
  const flipped = useRef(false);

  useEffect(() => {
    if (phase === 'ENDED' || Number.isNaN(target)) return;
    if (remain > 0 || flipped.current) return;
    flipped.current = true;
    refresh();
  }, [remain, phase, target, refresh]);

  if (phase === 'ENDED') return <span className="sk-countdown sk-countdown-ended">已结束</span>;
  if (Number.isNaN(target)) return null;

  return (
    <span className={`sk-countdown${compact ? ' sk-countdown-compact' : ''}`}>
      {compact ? null : <span className="sk-countdown-label">{phase === 'UPCOMING' ? '距开始' : '距结束'}</span>}
      <span className="sk-countdown-clock">{formatRemain(remain)}</span>
    </span>
  );
};

/** 库存进度（已抢 N%），只做紧迫感提示，不参与任何「能不能买」的判断 */
export const SeckillProgress: React.FC<{ available: number; total: number }> = ({
  available,
  total,
}) => {
  if (total <= 0) return null;
  const sold = Math.min(total, Math.max(0, total - available));
  const percent = Math.round((sold / total) * 100);
  return (
    <div className="sk-progress" title={`已抢 ${sold} / ${total} 件`}>
      <div className="sk-progress-bar">
        <span className="sk-progress-fill" style={{ width: `${percent}%` }} />
      </div>
      <span className="sk-progress-text">
        {available <= 0 ? '已抢完' : `剩 ${available} 件`}
      </span>
    </div>
  );
};

/**
 * **活动余量**（2026-10-19 统一口径）。
 *
 * <p>「余量」全站只有一个含义：<b>这场秒杀活动自身还剩多少件可抢</b>，
 * 即活动配额的剩余（`totalStock - 已下单件数`），**不是商品的 SKU 库存**。
 * 两者完全不同：SKU 库存 500 的活动，配额可能只配了 50；把 SKU 库存当余量展示，
 * 用户会以为「还剩 500 件」，点进去却提示抢完了。</p>
 *
 * <p>用户端、商家端、平台后台共用本组件，避免三处各算一遍、各写一种样式。
 * 数字放在最前并加粗，是因为运营在后台列表里扫一眼要看到的就是它。</p>
 */
export const SeckillRemainStat: React.FC<{
  available: number;
  total: number;
  /** sm = 表格行内；md = 活动卡片头部 */
  size?: 'sm' | 'md';
  /** 是否显示「活动余量」四个字（表格列头已有时可省） */
  showLabel?: boolean;
}> = ({ available, total, size = 'sm', showLabel = false }) => {
  const safeTotal = Math.max(0, total);
  const safeAvailable = Math.max(0, Math.min(available, safeTotal));
  const percent = safeTotal > 0 ? Math.round((safeAvailable / safeTotal) * 100) : 0;
  return (
    <div
      className={`sk-remain sk-remain-${size}${safeAvailable <= 0 ? ' is-empty' : ''}`}
      title={`活动余量：本场活动还剩 ${safeAvailable} 件可抢（活动配额 ${safeTotal} 件，不是商品 SKU 库存）`}
    >
      <div className="sk-remain-line">
        {showLabel ? <span className="sk-remain-label">活动余量</span> : null}
        <b className="sk-remain-num">{safeAvailable}</b>
        <span className="sk-remain-unit">件</span>
        <span className="sk-remain-total">/ 共 {safeTotal}</span>
      </div>
      <div className="sk-remain-bar">
        <span className="sk-remain-fill" style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
};

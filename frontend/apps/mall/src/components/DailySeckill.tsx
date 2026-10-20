/**
 * 首页「每日秒杀」栏目（2026-10-19）。
 *
 * <h2>为什么单独做一个栏目，而不是只给商品卡加标签</h2>
 * 标签解决「这件商品在秒杀」，但用户不知道「今天有哪些、什么时候结束」。
 * 首页需要一块**集中入口**：商品 + 秒杀价/划线原价 + 活动时间 + 实时倒计时 + 余量进度，
 * 把「现在不抢就没了」这件事说清楚。
 *
 * <h2>数据口径</h2>
 * 只读 `useSeckill()`（全局唯一数据源）—— 与商品卡、详情页同一份价格，
 * 不会出现「首页 1 元、详情 12.9 元」。已结束的活动不出现；没有活动时整块**不渲染**，
 * 而不是留一个空盒子占位。
 */

import React from 'react';
import { Link } from 'react-router-dom';
import { skuImage } from '@duoduo/api-sdk/images';
import { parseServerTime } from '@duoduo/api-sdk/client';
import { useSeckill } from '@duoduo/shared/store/seckill';
import {
  SeckillCountdown,
  SeckillPrice,
  SeckillProgress,
  SeckillTag,
} from '@duoduo/shared/components/seckill';

const pad = (n: number) => String(n).padStart(2, '0');

/** 卡片里的短时间：`10-19 10:00`（完整年份对「今天抢什么」没有信息量） */
function shortTime(raw: string): string {
  const ms = parseServerTime(raw);
  if (Number.isNaN(ms)) return '—';
  const d = new Date(ms);
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 同一天则省略结束日期：`10-19 10:00 ~ 12:00` */
function timeRange(startAt: string, endAt: string): string {
  const start = new Date(parseServerTime(startAt));
  const end = new Date(parseServerTime(endAt));
  const sameDay =
    start.getFullYear() === end.getFullYear() &&
    start.getMonth() === end.getMonth() &&
    start.getDate() === end.getDate();
  return sameDay
    ? `${shortTime(startAt)} ~ ${pad(end.getHours())}:${pad(end.getMinutes())}`
    : `${shortTime(startAt)} ~ ${shortTime(endAt)}`;
}

export const DailySeckill: React.FC = () => {
  const { activities } = useSeckill();

  // 一个活动多个 SKU 就展示多个商品；上限 12 个，避免首屏被秒杀占满
  const items = activities
    .flatMap((activity) => activity.skus.filter((sku) => sku.spuId).map((sku) => ({ activity, sku })))
    .slice(0, 12);

  if (items.length === 0) return null;

  return (
    <section className="daily-seckill" aria-label="每日秒杀">
      <div className="daily-seckill-head">
        <h2>
          ⚡ 每日秒杀
          <span className="sk-tag sk-tag-live">
            <span className="sk-tag-dot" aria-hidden="true" />
            {items.some((i) => i.activity.phase === 'ONGOING') ? '抢购中' : '即将开始'}
          </span>
        </h2>
        <span className="daily-seckill-sub">限时特价 · 售完即止</span>
        <Link className="small muted" to="/seckill" style={{ marginLeft: 'auto' }}>
          查看全部 →
        </Link>
      </div>

      <div className="daily-seckill-track">
        {items.map(({ activity, sku }) => (
          <Link key={sku.skuId} className="daily-seckill-card" to={`/product/${sku.spuId}`}>
            <div className="daily-seckill-media">
              <img
                src={skuImage(sku.skuId, sku.spuId, sku.image)}
                alt={sku.spuTitle}
                loading="lazy"
                decoding="async"
              />
              <SeckillTag phase={activity.phase} />
            </div>
            <div className="daily-seckill-title">{sku.spuTitle}</div>
            <div className="daily-seckill-time">{timeRange(activity.startAt, activity.endAt)}</div>
            <SeckillPrice
              price={sku.seckillPrice}
              original={sku.originalPrice}
              size="md"
            />
            <SeckillProgress available={sku.availableStock} total={sku.totalStock} />
            <div className="daily-seckill-foot">
              <SeckillCountdown
                startAt={activity.startAt}
                endAt={activity.endAt}
                phase={activity.phase}
                compact
              />
              <span className="small muted">
                {sku.perLimit ? `限购 ${sku.perLimit} 件` : ''}
              </span>
            </div>
          </Link>
        ))}
      </div>
    </section>
  );
};

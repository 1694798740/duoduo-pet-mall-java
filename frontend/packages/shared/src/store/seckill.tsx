/**
 * 全站秒杀信息中心（2026-10-19）。
 *
 * <h2>为什么要有这一层</h2>
 * 「哪些商品在秒杀、秒杀价多少、还剩多久」会被**首页 / 搜索 / 推荐 / 详情 / 秒杀页**同时用到。
 * 如果各页面自己拉一遍活动列表、自己算「该 SPU 的秒杀价」，迟早出现
 * 「首页卡片显示 1 元、点进详情显示 12.9 元」这种自相矛盾 —— 用户不会怀疑哪个页面错了，
 * 只会认为秒杀是假的。
 *
 * 所以这里**只保留一个数据源**：`GET /api/seckill/activities`（公开只读，未登录也能拉），
 * 一次拉全量活动 → 按 SPU 建索引 → 所有展示点读同一份派生结果。
 *
 * <h2>几个刻意的口径</h2>
 * <ul>
 *   <li><b>已结束的活动不进索引</b>：结束了还挂「秒杀」标签属于虚假宣传。</li>
 *   <li><b>同一 SPU 有多个秒杀 SKU 时取最低价</b>：商品卡是 SPU 粒度，展示「¥x 起」的语义；
 *       详情页选中具体 SKU 时再用该 SKU 自己的秒杀价（见 {@link SpuSeckill.bySku}）。</li>
 *   <li><b>拉取失败保持旧值、不报错</b>：秒杀标识是增强信息，不是购物主链路；
 *       网络抖一下就让整页商品卡报错，代价远大于「少一个标签」。</li>
 *   <li><b>时间一律按 UTC 解析</b>：服务端 `LocalDateTime` 输出不带时区后缀（DB 存 UTC），
 *       浏览器 `new Date('2026-10-19T01:48:28')` 会按**本地时区**解析 —— 直接早 8 小时。
 *       倒计时与活动时间必须走 {@link parseServerTime}。</li>
 * </ul>
 */

import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { seckillApi } from '@duoduo/api-sdk/endpoints';
import { parseServerTime } from '@duoduo/api-sdk/client';
import { subscribeDataChanged } from '../lib/dataBus';
import type { SeckillActivityView, SeckillSkuView } from '@duoduo/api-sdk/types';

export type SeckillPhase = 'ONGOING' | 'UPCOMING' | 'ENDED';

/** 一个 SPU 的秒杀视图（商品卡 / 首页栏目用这份） */
export interface SpuSeckill {
  activityId: string;
  activityTitle: string;
  startAt: string;
  endAt: string;
  phase: SeckillPhase;
  /** 该 SPU 下**最低价**的秒杀 SKU（详情页默认选中它） */
  skuId: string;
  sku: SeckillSkuView;
  /** 该 SPU 全部参与秒杀的 SKU，按 skuId 索引（详情页按选中规格取值） */
  bySku: Record<string, SeckillSkuView>;
  /** 同一 SPU 的多个秒杀 SKU 价格不同时，卡片价格带「起」 */
  multiPrice: boolean;
}

interface SeckillState {
  /** 未结束的活动（ONGOING 在前，其次按结束时间升序） */
  activities: SeckillActivityView[];
  bySpu: Record<string, SpuSeckill>;
  loading: boolean;
  /** 手动刷新（下单后调一次，余量立刻更新） */
  refresh: () => void;
}

const EMPTY: SeckillState = {
  activities: [],
  bySpu: {},
  loading: false,
  refresh: () => undefined,
};

/**
 * Provider 的默认值**不能抛错**：`ProductCardView` 在三端共用，
 * 后台 / 商家端没有挂本 Provider，抛错会让那些页面整页崩掉。
 */
const SeckillContext = createContext<SeckillState>(EMPTY);

/** 兜底轮询：活动上下架 / 余量变化不需要秒级，60s 足够；倒计时是本地每秒算的 */
const POLL_MS = 60 * 1000;

function buildIndex(activities: SeckillActivityView[]): {
  activities: SeckillActivityView[];
  bySpu: Record<string, SpuSeckill>;
} {
  const live = activities.filter((a) => a.phase !== 'ENDED' && a.skus.length > 0);
  // ONGOING 先展示（正在抢的紧迫感更强），同相位按结束时间升序（最快结束的在前）
  live.sort((a, b) => {
    if (a.phase !== b.phase) return a.phase === 'ONGOING' ? -1 : 1;
    return parseServerTime(a.endAt) - parseServerTime(b.endAt);
  });

  const bySpu: Record<string, SpuSeckill> = {};
  for (const act of live) {
    const grouped: Record<string, SeckillSkuView[]> = {};
    for (const sku of act.skus) {
      // spuId 缺失（商品服务查不到该 SKU）的商品不挂秒杀标识 —— 点了也进不去
      if (!sku.spuId) continue;
      (grouped[sku.spuId] ??= []).push(sku);
    }
    for (const [spuId, list] of Object.entries(grouped)) {
      const sorted = [...list].sort((a, b) => a.seckillPrice - b.seckillPrice);
      const cheapest = sorted[0];
      if (!cheapest) continue;
      const bySku: Record<string, SeckillSkuView> = {};
      for (const s of list) bySku[s.skuId] = s;
      const existing = bySpu[spuId];
      const candidate: SpuSeckill = {
        activityId: act.id,
        activityTitle: act.title,
        startAt: act.startAt,
        endAt: act.endAt,
        phase: act.phase,
        skuId: cheapest.skuId,
        sku: cheapest,
        bySku,
        multiPrice: cheapest.seckillPrice !== sorted[sorted.length - 1]?.seckillPrice,
      };
      // 同一 SPU 出现在多场活动里：优先「进行中」，其次价格更低的
      if (!existing) {
        bySpu[spuId] = candidate;
      } else if (existing.phase !== 'ONGOING' && candidate.phase === 'ONGOING') {
        bySpu[spuId] = candidate;
      } else if (existing.phase === candidate.phase && candidate.sku.seckillPrice < existing.sku.seckillPrice) {
        bySpu[spuId] = candidate;
      }
    }
  }
  return { activities: live, bySpu };
}

export const SeckillProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [activities, setActivities] = useState<SeckillActivityView[]>([]);
  const [loading, setLoading] = useState(true);
  const alive = useRef(true);
  const [tick, setTick] = useState(0);

  const refresh = useCallback(() => setTick((v) => v + 1), []);

  useEffect(() => {
    alive.current = true;
    const load = () => {
      seckillApi
        .activities()
        .then((list) => {
          if (!alive.current) return;
          setActivities(list);
        })
        // 秒杀信息是增强项：失败静默保留旧值，绝不打断购物主链路
        .catch(() => undefined)
        .finally(() => {
          if (alive.current) setLoading(false);
        });
    };
    load();

    const timer = window.setInterval(load, POLL_MS);
    const onVisible = () => {
      if (document.visibilityState === 'visible') load();
    };
    document.addEventListener('visibilitychange', onVisible);
    // 全站数据变更广播（2026-10-19）：取消秒杀订单 / 抢购成功等写操作会改变余量，
    // 写方 notifyDataChanged() 喊一嗓子，这里听到立刻重拉 —— 不用等 60s 轮询或用户手动刷新。
    const offDataChanged = subscribeDataChanged(load);

    return () => {
      alive.current = false;
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
      offDataChanged();
    };
  }, [tick]);

  const value = useMemo<SeckillState>(() => {
    const indexed = buildIndex(activities);
    return { ...indexed, loading, refresh };
  }, [activities, loading, refresh]);

  return <SeckillContext.Provider value={value}>{children}</SeckillContext.Provider>;
};

export function useSeckill(): SeckillState {
  return useContext(SeckillContext);
}

/** 某个 SPU 的秒杀信息；不参与秒杀返回 null（商品卡据此决定是否挂标签/换价） */
export function useSpuSeckill(spuId: string | null | undefined): SpuSeckill | null {
  const { bySpu } = useContext(SeckillContext);
  if (!spuId) return null;
  return bySpu[spuId] ?? null;
}

/**
 * 每秒走一格的“现在”。只在真正展示倒计时的组件里启用，
 * 避免给商品卡列表挂上几百个定时器。
 */
export function useNow(enabled = true, intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!enabled) return undefined;
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(timer);
  }, [enabled, intervalMs]);
  return now;
}

/**
 * 图表组件（ECharts 6 封装）。
 *
 * <p><b>为什么换掉原来手写的 div 柱状图</b>：
 * 原实现用 HTML 原生的 {@code title} 属性做悬停提示 —— 而原生 title 的**显示延迟由操作系统决定
 * （Windows 上约 1–2 秒）且无法通过 CSS/JS 配置**，这就是「鼠标停了三四秒才弹出一行字」的来源。
 * ECharts 的 tooltip 是自绘浮层：鼠标进入图形**即刻**显示，还能一次列出该分类的多个系列，
 * 不再是一行纯文本。
 *
 * <p>三条实现纪律：
 * <ol>
 *   <li><b>按需引入</b>：只 use 用到的图表与组件。全量 import echarts 会把 4MB 的源码图
 *       全打进来（含 map/geo/3D 那些永远用不到的模块）。</li>
 *   <li><b>动画关闭</b>（{@code animation: false}）：仪表盘不是演示动画的地方 ——
 *       首屏那 1 秒的生长动画期间悬停是无反馈的，反而显得卡。</li>
 *   <li><b>空数据显式说「暂无数据」</b>：一张全是 0 的图会让人以为坏了（这正是上一轮
 *       「柱状图全平」报障的心理来源）。宁可明确告诉用户「这段时间没有成交」。</li>
 * </ol>
 */

import React, { useEffect, useMemo, useRef } from 'react';
import * as echarts from 'echarts/core';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { formatPrice } from '../api/client';
import type { AnalyticsSlice, AnalyticsTrendPoint } from '../api/types';

echarts.use([
  BarChart,
  LineChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer,
]);

/** 配色沿用品牌橙 + 一组能区分但不相冲的辅助色；顺序固定，避免同一维度两次刷新换色 */
const PALETTE = ['#ff7a45', '#4f6ef7', '#36cfc9', '#f7b84b', '#a855f7', '#f759ab', '#52c41a', '#8c8c8c'];

const AXIS_LABEL = { color: '#8b8b94', fontSize: 11 } as const;
const SPLIT_LINE = { lineStyle: { color: 'rgba(0,0,0,.06)' } } as const;

/**
 * tooltip 公共配置。
 *
 * <p>{@code transitionDuration: 0} 是关键：ECharts 的浮层默认自带一段淡入，
 * 鼠标快速划过多个柱子时会出现「提示框还在追鼠标」的滞后感。设为 0 后它跟着指针就位。
 */
const TOOLTIP = {
  transitionDuration: 0,
  backgroundColor: 'rgba(255,255,255,.98)',
  borderColor: '#e9e9ef',
  borderWidth: 1,
  padding: [8, 12],
  textStyle: { color: '#2b2b33', fontSize: 12 },
  extraCssText: 'border-radius:8px;box-shadow:0 6px 18px rgba(0,0,0,.08);',
} as const;

type Slice = AnalyticsSlice;
type Trend = AnalyticsTrendPoint;

const asSlices = (data: Slice[] | Trend[]): Slice[] =>
  (data as Slice[]).map((d) => ({ name: d.name, value: d.value }));

const asTrend = (data: Slice[] | Trend[]): Trend[] => data as Trend[];

/** 数值展示：金额走 formatPrice（分→¥），计数加千分位 + 单位 */
const fmt = (v: number, money?: boolean, unit?: string): string =>
  money ? formatPrice(v) : `${v.toLocaleString('zh-CN')}${unit ?? ''}`;

export interface ChartProps {
  /** bar 纵向柱状 / hbar 横向条形（适合类目名较长的分布图）/ line 折线 / pie 环形饼图 */
  type: 'bar' | 'hbar' | 'line' | 'pie';
  data: Slice[] | Trend[];
  height?: number;
  /** 数值是否为金额（单位「分」）—— 决定提示框与坐标轴如何格式化 */
  money?: boolean;
  /** 计数单位，如「笔」「件」 */
  unit?: string;
}

const Chart: React.FC<ChartProps> = ({ type, data, height = 240, money, unit }) => {
  const boxRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);

  /** 全为 0 或没有数据 —— 显式出空态，不画一张「看起来坏了」的平图 */
  const empty = useMemo(() => {
    if (!data || data.length === 0) return true;
    if (type === 'line') return asTrend(data).every((d) => d.orderCount === 0 && d.gmv === 0);
    return asSlices(data).every((d) => d.value === 0);
  }, [data, type]);

  const option = useMemo<echarts.EChartsCoreOption>(() => {
    if (type === 'pie') {
      return {
        animation: false,
        color: PALETTE,
        tooltip: { ...TOOLTIP, trigger: 'item', formatter: (p: { name: string; value: number; percent: number }) => `${p.name}<br/><b>${fmt(p.value, money, unit)}</b>（${p.percent}%）` },
        legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: '#6b6b73', fontSize: 11 } },
        series: [{
          type: 'pie',
          radius: ['46%', '68%'],
          center: ['50%', '44%'],
          avoidLabelOverlap: true,
          itemStyle: { borderColor: '#fff', borderWidth: 2 },
          label: { show: false },
          labelLine: { show: false },
          data: asSlices(data).map((d) => ({ name: d.name, value: d.value })),
        }],
      };
    }

    if (type === 'line') {
      const points = asTrend(data);
      return {
        animation: false,
        color: ['#4f6ef7', '#ff7a45'],
        tooltip: {
          ...TOOLTIP,
          trigger: 'axis',
          axisPointer: { type: 'line', lineStyle: { color: 'rgba(79,110,247,.4)' } },
          formatter: (ps: { seriesName: string; value: number; axisValue: string }[]) => {
            const day = ps[0]?.axisValue ?? '';
            const cnt = ps.find((p) => p.seriesName === '订单数')?.value ?? 0;
            const gmv = ps.find((p) => p.seriesName === '成交额')?.value ?? 0;
            return `${day}<br/>订单数 <b>${cnt} 笔</b><br/>成交额 <b>${formatPrice(gmv)}</b>`;
          },
        },
        legend: { top: 0, right: 0, icon: 'roundRect', itemWidth: 10, itemHeight: 6, textStyle: { color: '#6b6b73', fontSize: 11 } },
        grid: { left: 4, right: 4, top: 28, bottom: 2, containLabel: true },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: points.map((p) => p.date.slice(5)),
          axisLabel: AXIS_LABEL,
          axisLine: { lineStyle: { color: 'rgba(0,0,0,.1)' } },
          axisTick: { show: false },
        },
        yAxis: [
          { type: 'value', name: '笔', nameTextStyle: AXIS_LABEL, axisLabel: AXIS_LABEL, splitLine: SPLIT_LINE },
          { type: 'value', name: '元', nameTextStyle: AXIS_LABEL, axisLabel: { ...AXIS_LABEL, formatter: (v: number) => (v / 100).toFixed(0) }, splitLine: { show: false } },
        ],
        series: [
          { name: '订单数', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5, lineStyle: { width: 2 }, areaStyle: { opacity: 0.08 }, data: points.map((p) => p.orderCount) },
          { name: '成交额', type: 'line', yAxisIndex: 1, smooth: true, symbol: 'circle', symbolSize: 5, lineStyle: { width: 2 }, areaStyle: { opacity: 0.08 }, data: points.map((p) => p.gmv) },
        ],
      };
    }

    const slices = asSlices(data);
    const names = slices.map((d) => d.name);
    const values = slices.map((d) => d.value);
    const horizontal = type === 'hbar';

    return {
      animation: false,
      color: PALETTE,
      tooltip: {
        ...TOOLTIP,
        trigger: 'axis',
        axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(255,122,69,.08)' } },
        formatter: (ps: { name: string; value: number }[]) =>
          `${ps[0]?.name ?? ''}<br/><b>${fmt(ps[0]?.value ?? 0, money, unit)}</b>`,
      },
      grid: { left: 4, right: 12, top: 16, bottom: 2, containLabel: true },
      xAxis: horizontal
        ? { type: 'value', axisLabel: AXIS_LABEL, splitLine: SPLIT_LINE }
        : { type: 'category', data: names, axisLabel: AXIS_LABEL, axisLine: { lineStyle: { color: 'rgba(0,0,0,.1)' } }, axisTick: { show: false } },
      yAxis: horizontal
        ? {
          type: 'category',
          data: names,
          inverse: true,
          axisLabel: { ...AXIS_LABEL, width: 120, overflow: 'truncate' },
          axisLine: { show: false },
          axisTick: { show: false },
        }
        : { type: 'value', axisLabel: AXIS_LABEL, splitLine: SPLIT_LINE },
      series: [{
        type: 'bar',
        barMaxWidth: 34,
        itemStyle: { borderRadius: horizontal ? [0, 6, 6, 0] : [6, 6, 0, 0] },
        label: { show: false },
        data: values,
      }],
    };
  }, [type, data, money, unit]);

  useEffect(() => {
    const el = boxRef.current;
    if (!el || empty) return;
    const chart = echarts.init(el);
    chartRef.current = chart;
    // 容器宽度随布局变化（栅格换行 / 侧栏折叠）时必须 resize，否则画布会被拉花
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(el);
    return () => {
      observer.disconnect();
      chart.dispose();
      chartRef.current = null;
    };
  }, [empty]);

  useEffect(() => {
    chartRef.current?.setOption(option, true);
  }, [option]);

  /** 屏幕阅读器看不到 canvas —— 给容器一句可读的摘要，别让图表对无障碍完全黑箱 */
  const summary = useMemo(() => {
    if (empty) return '暂无数据';
    const slices = type === 'line'
      ? asTrend(data).map((d) => ({ name: d.date, value: d.orderCount }))
      : asSlices(data);
    return `${type === 'line' ? '趋势' : '分布'}：`
      + slices.slice(0, 6).map((d) => `${d.name} ${fmt(d.value, type === 'line' ? false : money, type === 'line' ? '笔' : unit)}`).join('，');
  }, [data, type, money, unit, empty]);

  return (
    <div
      ref={boxRef}
      role="img"
      aria-label={summary}
      style={{ width: '100%', height, position: 'relative' }}
    >
      {empty ? (
        <div
          style={{
            position: 'absolute', inset: 0, display: 'grid', placeItems: 'center',
            color: 'var(--text-3)', fontSize: 12,
          }}
        >
          暂无数据
        </div>
      ) : null}
    </div>
  );
};

export default Chart;

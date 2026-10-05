/**
 * 图表的**懒加载边界**。
 *
 * <p>ECharts 是本项目第一个运行时第三方 UI 依赖，压缩后仍有数百 KB。而它只被
 * **商家端与平台后台**两个页面用到 —— 买家逛商品、下单、看订单都不需要它。
 * 若在页面上直接 `import Chart from './charts'`，Vite 会把它挂进主依赖图，
 * 首屏包体白白变大几百 KB（买家为运营的页面买单）。
 *
 * <p>所以这里做一个 `React.lazy` 边界：页面只引本文件（几百字节），
 * ECharts 被拆成独立 chunk，**只有真的打开商家端/后台才会下载**。
 * 打包结果可用 `npm run build` 的产物核对：应存在独立的 echarts chunk，
 * 且主包体积与加图表之前基本一致。
 */

import React, { Suspense } from 'react';
import type { AnalyticsSlice, AnalyticsTrendPoint } from '../api/types';

const Inner = React.lazy(() => import('./charts'));

export interface LazyChartProps {
  /** bar 纵向柱状 / hbar 横向条形 / line 折线 / pie 环形饼图 */
  type: 'bar' | 'hbar' | 'line' | 'pie';
  data: AnalyticsSlice[] | AnalyticsTrendPoint[];
  height?: number;
  /** 数值是否为金额（单位「分」） */
  money?: boolean;
  /** 计数单位，如「笔」「件」 */
  unit?: string;
}

/** 加载骨架：与图表同高，避免 chunk 到达时页面跳动 */
const Skeleton: React.FC<{ height?: number }> = ({ height = 240 }) => (
  <div
    style={{
      height,
      display: 'grid',
      placeItems: 'center',
      color: 'var(--text-3)',
      fontSize: 12,
    }}
  >
    图表加载中…
  </div>
);

export const LazyChart: React.FC<LazyChartProps> = (props) => (
  <Suspense fallback={<Skeleton height={props.height} />}>
    <Inner {...props} />
  </Suspense>
);

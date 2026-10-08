/**
 * 物流轨迹时间线（A2）—— 买家订单详情与卖家订单详情**共用同一个组件**。
 *
 * 为什么抽成共用件：同一张订单的轨迹，买家看到「我的货到哪了」，卖家看到「客户问起来我答什么」——
 * 两边的数据完全相同，只有空态文案不同。各写一份的结果必然是「一边显示已签收、另一边还停在派送中」，
 * 而这种不一致在用户眼里就是系统坏了。
 *
 * 数据来源：`GET /api/logistics/orders/{orderNo}/tracks`（履约域）。
 * 路径**不挂在 /api/orders 下** —— 那一段整段路由给交易域，轨迹数据却在履约域。
 *
 * 三个刻意的取舍：
 *   1. **未发货不请求**：服务端对没有轨迹的订单回 200 + 空列表（这是正常状态，不是错误），
 *      但未发货时我们**已经知道**结果必然为空，多发一次请求只会在网络面板里留一条无意义的记录。
 *      所以由调用方给出 `shipped`，未发货直接渲染说明性空态。
 *   2. **空态要说人话**：区分「卖家还没发货」与「已发货但轨迹还没同步」——
 *      两句都是「暂无信息」，但对用户意味着完全不同的事，前者要等，后者要等快递推送。
 *   3. **正文按发生时间正序**（服务端已排好），并在卡片头部单独提示**最新一条**：
 *      用户来看轨迹，最关心的永远是「现在到哪了」。若只按正序排，
 *      节点一多就要滚到底部才能看到答案。
 *
 * 节点中文文案一律用服务端下发的 `nodeLabel`，本组件只维护「图标」这一份纯装饰映射。
 */

import React, { useCallback } from 'react';
import { logisticsApi } from '@duoduo/api-sdk/endpoints';
import type { TrackView } from '@duoduo/api-sdk/types';
import { useAsync } from './hooks';
import { Alert, LoadingBlock, formatDate } from './ui';

/** 节点码 → 图标。纯装饰，取不到就退回包裹图标；中文文案不在这里维护。 */
const NODE_ICON: Record<string, string> = {
  PICKED_UP: '📦',
  IN_TRANSIT: '🚚',
  ARRIVED_HUB: '🏭',
  OUT_FOR_DELIVERY: '🛵',
  SIGNED: '✅',
  REJECTED: '↩️',
  EXCEPTION: '⚠️',
};

/** 节点圆点颜色：异常优先（红），其次签收（绿），最新一条用品牌色突出，其余中性。 */
function dotColor(code: string, isLatest: boolean): string {
  if (code === 'EXCEPTION') return 'var(--danger)';
  if (code === 'REJECTED') return 'var(--warn)';
  if (isLatest) return 'var(--brand)';
  if (code === 'SIGNED') return 'var(--success)';
  return 'var(--border-strong)';
}

export const LogisticsTimeline: React.FC<{
  orderNo: string;
  /** 订单是否已进入「已发货 / 已完成」。未发货时不必请求，见文件头取舍 1 */
  shipped: boolean;
}> = ({ orderNo, shipped }) => {
  const fetcher = useCallback(
    (signal: AbortSignal) => logisticsApi.tracks(orderNo, { signal }),
    [orderNo],
  );
  const { data, loading, error } = useAsync(fetcher, shipped && Boolean(orderNo), [
    orderNo,
    shipped,
  ]);

  const tracks: TrackView[] = data?.tracks ?? [];
  const latest = tracks.length > 0 ? tracks[tracks.length - 1] : null;

  return (
    <div className="card card-pad" style={{ marginTop: 16 }}>
      <div className="row-between row-wrap" style={{ alignItems: 'baseline' }}>
        <h3>物流轨迹</h3>
        {latest ? (
          <span className="tag tag-active">
            {NODE_ICON[latest.nodeCode] ?? '📦'} 当前：{latest.nodeLabel}
          </span>
        ) : null}
      </div>

      {!shipped ? (
        <div className="small muted" style={{ marginTop: 10 }}>
          卖家尚未发货。发货后，这里会按时间列出包裹的揽收、运输、派送与签收节点。
        </div>
      ) : loading ? (
        <LoadingBlock text="正在加载物流轨迹…" />
      ) : error ? (
        <div style={{ marginTop: 12 }}>
          {/* 轨迹加载失败不该让整个订单页看不下去：只在这里提示，订单信息照常可用 */}
          <Alert kind="error">{error}</Alert>
        </div>
      ) : tracks.length === 0 ? (
        <div className="small muted" style={{ marginTop: 10 }}>
          已发货，但还没有物流节点同步进来。快递公司推送后会自动出现在这里。
        </div>
      ) : (
        <div style={{ marginTop: 14 }} data-testid="logistics-timeline">
          {tracks.map((t, i) => {
            const isLatest = i === tracks.length - 1;
            const isLastRow = isLatest;
            return (
              <div
                key={`${t.nodeCode}-${t.occurredAt}-${i}`}
                data-testid="logistics-track-row"
                data-node-code={t.nodeCode}
                style={{ display: 'flex', gap: 12 }}
              >
                {/* 左侧：圆点 + 连接竖线（最后一条不画竖线，视觉上收口） */}
                <div
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    width: 16,
                    flex: '0 0 auto',
                  }}
                >
                  <span
                    style={{
                      width: 10,
                      height: 10,
                      borderRadius: '50%',
                      background: dotColor(t.nodeCode, isLatest),
                      marginTop: 6,
                      boxShadow: isLatest ? '0 0 0 4px var(--brand-soft)' : undefined,
                    }}
                  />
                  {!isLastRow ? (
                    <span
                      style={{
                        flex: 1,
                        width: 2,
                        minHeight: 20,
                        background: 'var(--border)',
                        marginTop: 3,
                      }}
                    />
                  ) : null}
                </div>

                <div style={{ paddingBottom: isLastRow ? 0 : 16, minWidth: 0, flex: 1 }}>
                  <div
                    style={{
                      display: 'flex',
                      gap: 8,
                      alignItems: 'baseline',
                      flexWrap: 'wrap',
                    }}
                  >
                    <strong
                      style={{
                        fontSize: 14,
                        color: isLatest ? 'var(--brand-dark)' : 'var(--text)',
                      }}
                    >
                      {NODE_ICON[t.nodeCode] ?? '📦'} {t.nodeLabel}
                    </strong>
                    <span className="small muted">{formatDate(t.occurredAt)}</span>
                  </div>
                  <div
                    className="small"
                    style={{
                      color: 'var(--text-2)',
                      marginTop: 2,
                      wordBreak: 'break-word',
                    }}
                  >
                    {t.nodeText}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

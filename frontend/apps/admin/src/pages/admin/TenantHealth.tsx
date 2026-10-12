/**
 * 平台管理后台 · 租户健康度面板（E24）。
 *
 * <h2>它回答的问题</h2>
 * 「这家店正不正常」。四个维度横跨**三个存储**，服务端一次聚合返回：
 * <pre>
 *   订单量 / 成交额    交易库 t_order
 *   售后率             履约库 t_after_sale（走服务间内部接口，不经网关）
 *   结算异常           交易库 t_settlement / t_seller_debt / t_order（三类存量异常）
 *   接口错误率         Redis（网关对「带店铺身份的请求」逐日计数）
 * </pre>
 * 之所以不做成四个前端各拉各的接口：那会出现「四个数字来自四个时刻」，
 * 以及「其中一格加载失败但页面看起来完好」——后者是这一整类页面的通病。
 *
 * <h2>三态纪律在这个页面上的落地（最容易做错的地方）</h2>
 * <ul>
 *   <li>比例字段为 {@code null} ⇒ 渲染「—」而**不是** {@code 0.00%}：
 *       「这段时间一单没成交」与「成交 100 单、零售后」是两个完全不同的结论；
 *   <li>档位 {@code NO_DATA}（无样本）与 {@code OK}（健康）**分开渲染、分开配色** ——
 *       把「无数据」画成绿色等于替它担保；
 *   <li>**未知档位**（后端新增了档位、前端还没跟上）按「未知」灰显并原样带出档位名，
 *       绝不静默当成「正常」——这是本页最容易腐烂、且腐烂后完全看不出来的地方。
 * </ul>
 *
 * <h2>阈值一律来自服务端</h2>
 * 判级规则是从 {@code /limits} 读出来**渲染**的，前端不抄一份：
 * 抄一份意味着政策调整后页面仍在按旧数解释数据 —— 不报错、也不空，只是结论悄悄变错。
 */

import React, { useCallback, useMemo, useState } from 'react';
import { adminApi } from '@duoduo/api-sdk/endpoints';
import type { TenantHealthRow } from '@duoduo/api-sdk/types';
import { Alert, Empty, LoadingBlock, formatPrice } from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';
import { useAuth } from '@duoduo/shared/store/auth';
import { AdminDenied, AdminStat, AdminTabs, isOperator } from './shared';

/** 档位展示元数据；key 与后端 `TenantHealthJudgement.Level` 一一对应。 */
const LEVEL_META: Record<string, { label: string; cls: string; hint: string }> = {
  RISK: { label: '风险', cls: 'tag tag-danger', hint: '有结算异常，或接口 5xx 率超风险线' },
  WATCH: { label: '关注', cls: 'tag tag-warn', hint: '售后率或接口 5xx 率偏高' },
  NO_DATA: { label: '无数据', cls: 'tag', hint: '窗口内既无成交也无接口调用 —— 无从判断，不等于健康' },
  OK: { label: '正常', cls: 'tag tag-success', hint: '各维度都在阈值内' },
};

const levelMeta = (level: string) =>
  LEVEL_META[level] ?? {
    label: `未知(${level})`,
    cls: 'tag',
    hint: '后端返回了本页还不认识的档位 —— 前端未同步，请勿按「正常」理解',
  };

/**
 * 比例字段是服务端算好的两位小数字符串；`null` 表示**分母为 0（无样本）**。
 * ★ 显式渲染「—」：写成 `0.00%` 会把「没有样本」伪装成「零问题」。
 */
const fmtPct = (v: string | null | undefined): string => (v == null ? '—' : v);

/** 店铺显示名：取不到名字时退化成 `#id`（整体降级提示在页面顶部给） */
const storeLabel = (row: TenantHealthRow): string => {
  if (row.tenantId === '0') return row.tenantName ?? '平台自营';
  return row.tenantName && row.tenantName.trim() ? row.tenantName : `#${row.tenantId}`;
};

/** 一行明细里的小标签（标签名 + 值 + 可选脚注） */
const Cell: React.FC<{ label: string; value: React.ReactNode; hint?: string }> = ({
  label, value, hint,
}) => (
  <div>
    <div className="small muted">{label}</div>
    <div>{value}</div>
    {hint ? <div className="small muted">{hint}</div> : null}
  </div>
);

export const AdminTenantHealthPage: React.FC = () => {
  const { user } = useAuth();
  const operator = isOperator(user?.role);

  // days=undefined 表示「用服务端默认窗口」——不在这里写死 30
  const [days, setDays] = useState<number | undefined>(undefined);
  const [expanded, setExpanded] = useState<string | null>(null);

  const { data: limits } = useAsync(
    useCallback(() => adminApi.tenantHealthLimits(), []),
    operator,
    [],
  );

  const { data: panel, loading, error, reload } = useAsync(
    useCallback(() => adminApi.tenantHealth(days == null ? {} : { days }), [days]),
    operator,
    [days],
  );

  // 窗口按钮：7 / 服务端默认 / 服务端上限 —— 全部从 limits 推导，不写死
  const windowOptions = useMemo(() => {
    if (!limits) return [] as number[];
    const set = new Set<number>([7, limits.defaultDays, limits.maxDays]);
    return [...set].filter((d) => d > 0 && d <= limits.maxDays).sort((a, b) => a - b);
  }, [limits]);

  if (!operator) return <AdminDenied />;

  const rows = panel?.tenants ?? [];
  const plat = panel?.platform;

  return (
    <div>
      <h2 className="section-title">平台管理后台 · 租户健康度</h2>
      <AdminTabs />

      <Alert kind="info">
        <b>这一个面板横跨三个存储。</b>
        订单量与成交额来自交易库订单、售后率来自履约库（走服务间内部接口）、
        结算异常来自交易库的三类存量异常、接口错误率来自网关对
        <b>「带店铺身份的请求」</b>的逐日计数。
        服务端一次聚合返回，所以下面的每个数字都来自<b>同一个时刻</b>。
      </Alert>

      {/* ---- 窗口切换 ---- */}
      <div className="row row-wrap" style={{ gap: 8, margin: '14px 0 10px' }}>
        <span className="small muted" style={{ alignSelf: 'center' }}>统计窗口</span>
        {windowOptions.map((d) => (
          <button
            key={d}
            className={`btn btn-sm ${(days ?? limits?.defaultDays) === d ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => setDays(d)}
          >
            近 {d} 天
          </button>
        ))}
        <button className="btn btn-sm btn-ghost" onClick={reload} disabled={loading}>
          {loading ? '刷新中…' : '刷新'}
        </button>
        {panel ? (
          <span className="small muted" style={{ alignSelf: 'center' }}>
            窗口 {panel.from} ~ {panel.to}（UTC 自然日，含首含尾）
            {panel.truncated ? ` · 共 ${panel.totalTenants} 家，本页按档位截断展示 ${rows.length} 家` : ''}
          </span>
        ) : null}
      </div>

      {/* ---- 店铺名降级：允许，但必须说出来。判据四维不受影响 ---- */}
      {panel && panel.nameResolved === false ? (
        <Alert kind="warn">
          店铺名暂时取不到（用户域不可达），下面用 <b>#租户号</b> 代替。
          这只影响显示 —— <b>四个维度的判据与档位仍然有效</b>。
        </Alert>
      ) : null}

      {error ? <Alert kind="error">{error}</Alert> : null}
      {loading && !panel ? <LoadingBlock text="正在汇总租户健康度…" /> : null}

      {plat ? (
        <>
          <div className="grid grid-4" style={{ gap: 12, marginBottom: 12 }}>
            <AdminStat
              label="风险 / 关注"
              value={`${plat.riskyTenants} / ${plat.watchTenants}`}
              hint={plat.riskyTenants > 0 ? '风险家数需优先处理' : '当前无风险租户'}
            />
            <AdminStat
              label="窗口内下单"
              value={plat.orderCount}
              hint={`成交额 ${formatPrice(plat.gmv)}`}
            />
            <AdminStat
              label="平台售后率"
              value={fmtPct(plat.afterSaleRate)}
              hint={plat.afterSaleRate == null ? '窗口内无成交，无法计算' : `${plat.afterSaleCount} 笔售后`}
            />
            <AdminStat
              label="结算异常"
              value={plat.settlementAbnormalCount}
              hint="欠款 + 超期托管 + 缺托管单"
            />
          </div>
          <div className="grid grid-4" style={{ gap: 12, marginBottom: 16 }}>
            <AdminStat label="店铺侧接口调用" value={plat.apiTotal} hint="仅统计带店铺身份的请求" />
            <AdminStat
              label="接口错误率"
              value={fmtPct(plat.apiErrorRate)}
              hint={plat.apiErrorRate == null ? '窗口内无调用' : '只看 5xx；4xx 不参与判级'}
            />
            <AdminStat label="可见租户" value={plat.tenantCount} hint={`命中 ${panel?.totalTenants ?? '—'} 家`} />
            <AdminStat
              label="生成时刻"
              value={panel?.generatedAt ? panel.generatedAt.slice(11, 19) : '—'}
              hint="UTC（面板所有数字同源同一时刻）"
            />
          </div>
        </>
      ) : null}

      {/* ---- 明细表 ---- */}
      {loading && !panel ? null : rows.length === 0 ? (
        <Empty
          title="窗口内没有可展示的租户"
          desc="面板只列这几天「有业务动静」的店：既没有成交、也没有接口调用的租户不会出现在这里 —— 那不等于它健康，只是无从判断。"
        />
      ) : (
        <div className="card" style={{ padding: 0, overflowX: 'auto' }}>
          <table className="table">
            <thead>
              <tr>
                <th>档位</th>
                <th>店铺</th>
                <th>下单 / 成交</th>
                <th>成交额</th>
                <th>售后率</th>
                <th>结算异常</th>
                <th>接口错误率</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                const meta = levelMeta(row.level);
                const open = expanded === row.tenantId;
                return (
                  <React.Fragment key={row.tenantId}>
                    <tr>
                      <td>
                        <span className={meta.cls} title={meta.hint}>
                          {meta.label}
                        </span>
                      </td>
                      <td>
                        <div>{storeLabel(row)}</div>
                        <div className="small muted" style={{ fontFamily: 'ui-monospace, monospace' }}>
                          {row.tenantId}
                        </div>
                      </td>
                      <td>
                        {row.orderCount} / {row.paidCount}
                      </td>
                      <td>{formatPrice(row.gmv)}</td>
                      <td>
                        {fmtPct(row.afterSaleRate)}
                        <div className="small muted">{row.afterSaleCount} 笔</div>
                      </td>
                      <td>
                        {row.settlementAbnormalCount > 0 ? (
                          <b className="text-danger">{row.settlementAbnormalCount} 笔</b>
                        ) : (
                          <span className="muted">—</span>
                        )}
                      </td>
                      <td>
                        {fmtPct(row.apiErrorRate)}
                        <div className="small muted">{row.apiTotal} 次调用</div>
                      </td>
                      <td>
                        <button
                          className="btn btn-sm btn-ghost"
                          onClick={() => setExpanded(open ? null : row.tenantId)}
                        >
                          {open ? '收起' : '明细'}
                        </button>
                      </td>
                    </tr>
                    {open ? (
                      <tr>
                        <td colSpan={8} style={{ background: 'var(--bg-soft, #fafafa)' }}>
                          <div className="grid grid-4" style={{ gap: 12 }}>
                            <Cell
                              label="成交"
                              value={`${row.paidCount} 单`}
                              hint={`下单 ${row.orderCount} 单 · 成交额 ${formatPrice(row.gmv)}`}
                            />
                            <Cell
                              label="售后"
                              value={`${row.afterSaleCount} 笔`}
                              hint={`已退款 ${row.afterSaleRefundedCount} · 未结案 ${row.afterSaleOpenCount} · 卖家败诉 ${row.afterSaleAdverseCount}`}
                            />
                            <Cell
                              label="未收回欠款"
                              value={`${row.debtOutstandingCount} 笔`}
                              hint={formatPrice(row.debtOutstandingAmount)}
                            />
                            <Cell
                              label="超期仍托管"
                              value={`${row.staleCustodyCount} 笔`}
                              hint={`${formatPrice(row.staleCustodyAmount)} · 超 ${limits?.staleCustodyDays ?? '—'} 天`}
                            />
                            <Cell
                              label="缺托管单"
                              value={`${row.missingSettlementCount} 笔`}
                              hint="已付款却没有账本，放款时会失败"
                            />
                            <Cell
                              label="结算异常合计"
                              value={row.settlementAbnormalCount}
                              hint="＝ 欠款 + 超期托管 + 缺托管单"
                            />
                            <Cell
                              label="接口调用"
                              value={`${row.apiTotal} 次`}
                              hint={`5xx ${row.apiServerError} · 4xx ${row.apiClientError}`}
                            />
                            <Cell
                              label="接口错误率"
                              value={fmtPct(row.apiErrorRate)}
                              hint={row.apiErrorRate == null ? '无调用样本' : '只看 5xx'}
                            />
                          </div>
                        </td>
                      </tr>
                    ) : null}
                  </React.Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* ---- 判级规则：从服务端阈值渲染，前端不抄一份 ---- */}
      {limits ? (
        <div className="card card-pad" style={{ marginTop: 16 }}>
          <h4 style={{ marginBottom: 6 }}>判级规则（阈值实时取自服务端）</h4>
          <ul className="small muted" style={{ paddingLeft: 18, lineHeight: 1.9, margin: 0 }}>
            <li>
              结算异常 &gt; 0（欠款 / 超期托管 / 缺托管单，<b>一笔也算</b>）→ <b>风险</b>
            </li>
            <li>
              接口 5xx 率 ≥ {limits.apiErrorRiskPercent}%，且调用 ≥ {limits.apiMinBase} 次 → <b>风险</b>
            </li>
            <li>
              售后率 ≥ {limits.afterSaleWatchPercent}%，且成交 ≥ {limits.afterSaleMinBase} 单 → <b>关注</b>
            </li>
            <li>
              接口 5xx 率 ≥ {limits.apiErrorWatchPercent}%，且调用 ≥ {limits.apiMinBase} 次 → <b>关注</b>
            </li>
            <li>
              窗口内既无成交也无接口调用 → <b>无数据</b>（刻意与「正常」分开：没有样本时不给结论）
            </li>
            <li>
              其余 → 正常。<b>4xx 不参与判级</b> —— 调用方传错参数不是这家店不健康
            </li>
            <li>
              <b>样本不足时不给档位、但计数照常展示</b>：例如一家店 4 单里出 4 笔售后（100%），
              未达 {limits.afterSaleMinBase} 单门槛，不会被标成关注 ——
              一个「1 次失败就变红」的面板会在几天内被彻底无视，那比漏报更糟。
            </li>
          </ul>
          <div className="small muted" style={{ marginTop: 8 }}>
            判级档位：{limits.levels.join(' / ')} · 结算异常来源：
            {limits.settlementAbnormalSources.join(' / ')}
            <br />
            超期托管阈值 {limits.staleCustodyDays} 天 · 接口计数保留 {limits.metricKeepDays} 天 ·
            窗口上限 {limits.maxDays} 天 · 行数上限 {limits.maxLimit}
          </div>
        </div>
      ) : null}
    </div>
  );
};

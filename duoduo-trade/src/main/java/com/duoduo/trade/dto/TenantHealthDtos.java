package com.duoduo.trade.dto;

import java.util.List;

/**
 * E24 · 租户健康度面板的对外契约（平台后台）。
 *
 * <h2>四个维度分别来自哪里（看清这张表才不会把口径讲错）</h2>
 * <pre>
 *   订单量 / 成交额      交易库 t_order            按 seller_id × 创建时间窗口 GROUP BY
 *   售后率              履约库 t_after_sale       服务间内部接口（不走网关）
 *   结算异常            交易库 t_settlement / t_seller_debt / t_order  三类存量异常
 *   接口错误率          Redis duoduo:api:stat:*   网关按"带店铺身份的请求"逐日计数
 * </pre>
 * 也就是说这一张面板**横跨三个存储**。把它做成一次请求是刻意的：
 * "这家店到底健不健康"是一个问题，答案必须来自同一个时间点；
 * 拆成四个接口让前端各拉各的，出现的是四个数字来自四个不同时刻、
 * 以及"其中一格加载失败但页面看起来完好"的老问题。
 *
 * <h2>★ 为什么比例字段是 {@code String} 且允许为 null</h2>
 * <ul>
 *   <li><b>String</b>：与 {@code AdminReport} 的 {@code refundRate} / {@code conversionRate}
 *       同一口径（前端不负责四舍五入，两个页面不会出现"12.3%"和"12.35%"两种写法）。
 *       本项目的金额一律是整数分，比例一律是服务端算好的两位小数字符串；</li>
 *   <li><b>允许 null</b>：分母为 0 时**不给 0.00%**。这是三态纪律的落地 ——
 *       "这段时间一单都没成交"与"成交了 100 单、一单售后都没有"是两个完全不同的结论，
 *       写成同一个 0.00% 会让前者看起来和后者一样健康。
 *       前端对 null 的渲染是「—」（无样本），与 0 明确区分。
 *       同理，判级里另有一个 {@code NO_DATA} 档位（见 {@code TenantHealthJudgement.Level}）。</li>
 * </ul>
 *
 * <h2>为什么金额/计数都是原始值而不是算好的展示串</h2>
 * 面板前端要排序、要按档位筛选、要画柱状图，都依赖数值本身。
 * 服务端只把**判断**（档位、比例）算好，数值原样给出去。
 */
public final class TenantHealthDtos {

    private TenantHealthDtos() {
    }

    /**
     * 一个租户的健康度行。字段较多但每一个都对应面板上的一格，不设"备用字段"。
     *
     * @param tenantId                租户标识（店铺主体 userId；{@code 0} = 平台自营）
     * @param tenantName              店铺主体昵称；取不到时为 null（见 {@code nameResolved}）
     * @param orderCount              窗口内下单数（含未付款）
     * @param paidCount               窗口内"成交单数"（已付款及之后，含已退款）
     * @param gmv                     窗口内成交额（分）
     * @param afterSaleCount          窗口内售后立案数
     * @param afterSaleRefundedCount  其中已退款终态
     * @param afterSaleOpenCount      其中未结案（有积压才有这个数）
     * @param afterSaleAdverseCount   其中运营裁定支持买家（卖家败诉）
     * @param afterSaleRate           售后率＝售后立案 / 成交单数；成交 0 单时为 null
     * @param debtOutstandingCount    未收回欠款笔数
     * @param debtOutstandingAmount   未收回欠款金额（分）
     * @param staleCustodyCount       超期仍托管的笔数（≥30 天，见 {@code SettlementMapper#staleCustodyBySeller}）
     * @param staleCustodyAmount      超期仍托管的金额（分）
     * @param missingSettlementCount  已付款却缺托管单的订单数
     * @param settlementAbnormalCount 结算异常合计＝欠款笔数 + 超期托管笔数 + 缺托管单数
     * @param apiTotal                窗口内该租户发起的接口调用数
     * @param apiServerError          其中 5xx
     * @param apiClientError          其中 4xx（**不参与判级**，理由见 {@code TenantHealthJudgement}）
     * @param apiErrorRate            接口错误率＝5xx / 总调用数；无调用时为 null
     * @param level                   档位：RISK / WATCH / NO_DATA / OK
     */
    public record TenantHealthRow(long tenantId,
                                  String tenantName,
                                  long orderCount,
                                  long paidCount,
                                  long gmv,
                                  long afterSaleCount,
                                  long afterSaleRefundedCount,
                                  long afterSaleOpenCount,
                                  long afterSaleAdverseCount,
                                  String afterSaleRate,
                                  long debtOutstandingCount,
                                  long debtOutstandingAmount,
                                  long staleCustodyCount,
                                  long staleCustodyAmount,
                                  long missingSettlementCount,
                                  long settlementAbnormalCount,
                                  long apiTotal,
                                  long apiServerError,
                                  long apiClientError,
                                  String apiErrorRate,
                                  String level) {
    }

    /**
     * 平台汇总（面板顶部的一排数）。**不是各行简单相加**的地方要说清：
     * 比例类由平台自身两个合计再算一次，而不是把各租户的百分比取平均 ——
     * 取平均会让一个只有 1 单的小店与一个 1 万单的大店权重相同。
     *
     * @param tenantCount           面板可见的租户行数（受 limit 影响，见 {@code TenantHealthPanel.truncated}）
     * @param orderCount            窗口内全平台下单数
     * @param gmv                   窗口内全平台成交额（分）
     * @param afterSaleCount        窗口内全平台售后立案数
     * @param afterSaleRate         平台售后率（按合计重算）
     * @param settlementAbnormalCount 全平台结算异常合计
     * @param apiTotal              全平台店铺侧接口调用数
     * @param apiErrorRate          平台口径接口错误率
     * @param riskyTenants          档位为 RISK 的租户数
     * @param watchTenants          档位为 WATCH 的租户数
     */
    public record TenantHealthPlatform(long tenantCount,
                                       long orderCount,
                                       long gmv,
                                       long afterSaleCount,
                                       String afterSaleRate,
                                       long settlementAbnormalCount,
                                       long apiTotal,
                                       String apiErrorRate,
                                       long riskyTenants,
                                       long watchTenants) {
    }

    /**
     * 面板响应。
     *
     * @param tenants      按档位排序后的租户行（RISK → WATCH → NO_DATA → OK，同级按成交量降序）
     * @param platform     平台汇总
     * @param days         实际生效的窗口天数（**回显服务端算的**，不是客户端传的，见下）
     * @param from         窗口起点日期（UTC，含）
     * @param to           窗口终点日期（UTC，含）
     * @param limit        本请求的租户行数上限
     * @param totalTenants 命中的租户总数（可能大于 {@code tenants.size()}）
     * @param truncated    是否因为 limit 被截断 —— 前端据此显示"还有 N 家未展示"。
     *                     没有这个标记时，被截断的列表与"平台只有这几家"长得完全一样
     * @param nameResolved 店铺名是否取到。false = 用户域不可达，此时 {@code tenantName} 全为 null，
     *                     但**其余四维判据仍然有效**（名字是展示装饰，不参与判级）。
     *                     之所以允许这一处降级而售后/接口指标必须硬失败：
     *                     名字缺了面板只是难看，判据缺了面板就会说谎
     * @param generatedAt  生成时刻（UTC ISO）
     */
    public record TenantHealthPanel(List<TenantHealthRow> tenants,
                                    TenantHealthPlatform platform,
                                    int days,
                                    String from,
                                    String to,
                                    int limit,
                                    int totalTenants,
                                    boolean truncated,
                                    boolean nameResolved,
                                    String generatedAt) {
    }
}

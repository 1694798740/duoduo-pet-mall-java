package com.duoduo.trade.client.dto;

import java.util.List;

/**
 * E24 · 履约域售后聚合的**调用侧**响应形状。
 *
 * <p>刻意与履约域的 {@code AfterSaleStatsDtos} 分开定义（而不是让交易域依赖履约域的模块）：
 * 两个域各自编译、各自发版，共享一个 DTO 类会把它们拧成"必须同时升级"。
 * 这与 E23 工单「共享契约不共享代码」是同一条决定。
 *
 * <p>代价是这个形状会漂移 —— 所以调用侧必须**按自己的需要容错**：
 * 字段缺失时给 0（见 {@code TenantHealthService}），而不是让整个面板 NPE。
 * 但这不等于"静默接受任何形状"：拿到的行里 {@code sellerId} 缺失 = 这份响应对不上契约，
 * 那种情况按硬失败处理（宁可报错，也不要把别人的售后算到 0 号租户头上）。
 */
public final class AfterSaleStatsCallDtos {

    private AfterSaleStatsCallDtos() {
    }

    /** 单卖家售后聚合（与履约域字段一一对应） */
    public record TenantAfterSaleStat(Long sellerId,
                                      Long total,
                                      Long refunded,
                                      Long openCnt,
                                      Long adverse,
                                      Long refundAmount) {
    }

    /** 批量响应；{@code from}/{@code to} 是**服务端实际生效**的窗口回显 */
    public record TenantAfterSaleStats(String from, String to, List<TenantAfterSaleStat> rows) {
    }
}

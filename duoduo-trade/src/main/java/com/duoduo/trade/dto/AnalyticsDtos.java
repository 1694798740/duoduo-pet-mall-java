package com.duoduo.trade.dto;

import java.util.List;

/**
 * 图表统计数据（商家端概览 / 平台后台运营概览共用）。
 *
 * <p><b>为什么统一成 {@link Slice} 一种形状</b>：柱状图、饼图、横向条形图在前端
 * 都是「一组 名称→数值」，若按图类型各定一套 DTO，前端就会为每种图各写一份组装逻辑，
 * 数据源一改要改多处。统一成切片后，换图类型只是换渲染器。
 *
 * <p><b>数值口径一律是原始值，不做格式化</b>：金额单位「分」、计数是「笔」，
 * 千分位与「¥」由前端在展示层处理。后端一旦下发 "¥1,234.00" 这类字符串，
 * 前端就无法做同比、占比、排序，等于把数据降级成文案。
 */
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    /** 趋势窗口：近 14 天（含今天）。选 14 而非 30，是因为一屏要放下多张图，横轴点太多会糊成一团 */
    public static final int TREND_DAYS = 14;

    /**
     * 销量榜取前 8。
     *
     * <p>不是「随便定的一个数」：超过 8 项后标签会互相压住，而「第 9 名」对决策
     * 没有增量信息 —— 榜单的用途是「看见头部」，不是「穷举」。
     */
    public static final int TOP_PRODUCT_LIMIT = 8;

    /**
     * 通用切片。
     *
     * @param name  展示名（后端已经本地化好，前端不再维护一份映射表）
     * @param value 原始数值：计数类为「笔」，金额类为「分」
     */
    public record Slice(String name, long value) {
    }

    /**
     * 趋势点。
     *
     * @param date       日期，格式 {@code yyyy-MM-dd}
     * @param orderCount 当日下单数（按**下单时间**归集）
     * @param gmv        当日成交额（分）：只累计「已付款及之后」的订单，
     *                   {@code PENDING_PAYMENT} / {@code CLOSED} 不计入 —— 钱从未收过
     */
    public record TrendPoint(String date, long orderCount, long gmv) {
    }

    /**
     * 商家端多维度统计。
     *
     * <p>刻意与 {@code /api/seller/summary} 分开：那种是「几张数字卡」，
     * 这种是「几组序列」，生命周期不同 —— 图表数据取不到时，
     * 上面的统计卡与资金三栏仍然应该正常显示，不该被一起拖垮。
     *
     * @param statusDistribution 维度一：订单状态分布（柱状 / 分布图）
     * @param trend              维度二：近 14 天成交趋势（折线）—— **含无成交的日期，补 0 而非断点**
     * @param topProducts        维度三：商品销量 Top N（柱状 / 横条），value 是**销量件数**
     * @param fundComposition    维度四：资金构成（饼图），value 是**金额（分）**
     * @param orderTypeMix       维度五：订单类型占比（饼图 / 环形），普通单 vs 秒杀单
     */
    public record SellerAnalytics(
            List<Slice> statusDistribution,
            List<TrendPoint> trend,
            List<Slice> topProducts,
            List<Slice> fundComposition,
            List<Slice> orderTypeMix,
            String generatedAt
    ) {
    }

    /**
     * 按商品的经营统计行（2026-10-24）。
     *
     * @param spuId     商品 id（与商品域、评价域关联的唯一键）
     * @param name      商品名（订单里的历史标题，改过名以最新成交单为准）
     * @param quantity  成交件数
     * @param amount    成交金额（分）
     * @param refundQty 其中退款件数（REFUNDING / REFUNDED）
     */
    public record ProductStat(Long spuId, String name, long quantity, long amount, long refundQty) {
    }

    /**
     * 平台后台图表数据（运营概览）。
     *
     * @param statusDistribution 全平台订单状态分布（分布图）
     * @param trend              全平台近 14 天成交趋势（折线）
     * @param topProducts        全平台商品销量 Top N（柱状图）
     * @param fundComposition    全平台资金构成（饼图），value 是金额（分）
     */
    public record AdminCharts(
            List<Slice> statusDistribution,
            List<TrendPoint> trend,
            List<Slice> topProducts,
            List<Slice> fundComposition,
            String generatedAt
    ) {
    }
}

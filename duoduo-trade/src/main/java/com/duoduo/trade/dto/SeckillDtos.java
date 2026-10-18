package com.duoduo.trade.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀域 DTO（A14）。字段口径与小程序端 pages/seckill 的渲染需求一一对应。
 */
public final class SeckillDtos {

    private SeckillDtos() {
    }

    /** 活动 + 秒杀 SKU 列表视图（C 端 / 运营端共用同一份装配口径） */
    public record ActivityView(
            Long id,
            String title,
            LocalDateTime startAt,
            LocalDateTime endAt,
            /** ONGOING / UPCOMING / ENDED（按当前时间计算，与运营开关正交） */
            String phase,
            /**
             * 运营开关（true=已上架）。
             *
             * <p>必须下发：运营端列表要显示「上架 / 下架」，且**下架的活动也要能列出来** ——
             * 否则一旦下架就从列表消失，运营再也点不到「上架」，活动等于被永久封印。
             */
            boolean enabled,
            List<SkuView> skus) {
    }

    public record SkuView(
            Long skuId,
            String spuTitle,
            String specText,
            String image,
            Long seckillPrice,
            Long originalPrice,
            Integer totalStock,
            Integer availableStock,
            Integer perLimit) {
    }

    /** 运营端创建活动 */
    public record CreateActivityRequest(
            @NotBlank(message = "活动标题不能为空") @Size(max = 100, message = "活动标题过长") String title,
            @NotBlank(message = "开始时间不能为空") String startAt,
            @NotBlank(message = "结束时间不能为空") String endAt,
            @NotEmpty(message = "至少配置一个秒杀商品") List<CreateSkuItem> skus) {

        public record CreateSkuItem(
                @NotNull(message = "skuId 不能为空") Long skuId,
                @NotNull(message = "秒杀价不能为空") @Min(value = 1, message = "秒杀价必须大于 0") Long seckillPrice,
                /** 可空：缺省取商品服务当前售价 */
                Long originalPrice,
                @NotNull(message = "活动配额不能为空") @Min(value = 1, message = "活动配额必须大于 0") Integer totalStock,
                @Min(value = 1, message = "限购件数至少为 1") @Max(value = 10, message = "限购件数最多 10") Integer perLimit) {
        }
    }

    /**
     * 运营端编辑活动（R74）。
     *
     * <p><b>刻意只开「标题 + 时间」两项</b>，不开 SKU / 价格 / 配额：
     * 那些字段背后是已经扣掉的余量与已成交的订单快照，改动会让「活动配置」与
     * 「已卖出去的事实」对不上（例如把配额从 10 调到 5，而已经卖出了 8 件）。
     * 要换商品请新建活动、把旧活动下架。
     */
    public record EditActivityRequest(
            @NotBlank(message = "活动标题不能为空") @Size(max = 100, message = "活动标题过长") String title,
            @NotBlank(message = "开始时间不能为空") String startAt,
            @NotBlank(message = "结束时间不能为空") String endAt) {
    }

    /** 运营端上下架 */
    public record ToggleRequest(@NotNull(message = "enabled 不能为空") Boolean enabled) {
    }

    /** C 端秒杀下单 */
    /**
     * C 端秒杀下单请求。
     *
     * <p>{@code couponCode} 可选（2026-10-18 放开）：秒杀价与优惠券**叠加**，
     * 券的门槛按秒杀价判定、抵扣封顶为秒杀价（营销域已封顶），实付不低于 0。
     * 不传即不使用券 —— 与普通下单同一套契约，避免两端各写一份「能不能用券」的规则。
     */
    public record PlaceSeckillRequest(
            @NotNull(message = "活动不能为空") Long activityId,
            @NotNull(message = "商品规格不能为空") Long skuId,
            @NotNull(message = "收货地址不能为空") Long addressId,
            @NotBlank(message = "幂等键不能为空") @Size(max = 64, message = "幂等键过长") String idempotentKey,
            @Size(max = 64, message = "券码过长") String couponCode) {
    }

    /** C 端秒杀下单结果（订单号 + 应付金额，前端拿去走沙箱支付） */
    public record PlaceSeckillResult(
            String orderNo,
            Long payAmount,
            Long seckillPrice,
            boolean replay) {
    }
}

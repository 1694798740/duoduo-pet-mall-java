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
            @NotNull(message = "开始时间不能为空") LocalDateTime startAt,
            @NotNull(message = "结束时间不能为空") LocalDateTime endAt,
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

    /** 运营端上下架 */
    public record ToggleRequest(@NotNull(message = "enabled 不能为空") Boolean enabled) {
    }

    /** C 端秒杀下单 */
    public record PlaceSeckillRequest(
            @NotNull(message = "活动不能为空") Long activityId,
            @NotNull(message = "商品规格不能为空") Long skuId,
            @NotNull(message = "收货地址不能为空") Long addressId,
            @NotBlank(message = "幂等键不能为空") @Size(max = 64, message = "幂等键过长") String idempotentKey) {
    }

    /** C 端秒杀下单结果（订单号 + 应付金额，前端拿去走沙箱支付） */
    public record PlaceSeckillResult(
            String orderNo,
            Long payAmount,
            Long seckillPrice,
            boolean replay) {
    }
}

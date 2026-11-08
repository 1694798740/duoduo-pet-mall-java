package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 商品域闲置客户端（B-11 闲置担保交易）：交易域 → 商品域的「释放闲置」回调。
 *
 * <p>唯一用途：C2C 订单关闭 / 退款时，把商品域里被 CAS 锁成 {@code SOLD} 的闲置
 * 回滚成 {@code ON_SALE} —— 锁是商品域下的（下单 CAS），钥匙也必须由商品域提供，
 * 交易域不跨库直接 UPDATE（硬规矩 4）。
 *
 * <p>{@code X-Internal-Token} 由 {@code InternalCallConfig} 的全局拦截器统一签发，
 * 与 {@code ProductClient} 同一道门；服务端由商品域的 {@code InternalTokenFilter}
 * 按 {@code /api/listings/internal/} 前缀校验。
 *
 * <p>调用是 <b>best-effort</b>：失败只记日志、靠补偿调度重试场景兜底 ——
 * 订单的钱货事实已在本域落定，不能因为一次跨域抖动把「退款成功」回报成失败，
 * 否则用户会看到「退款失败」但钱其实已经退了。
 */
@FeignClient(name = "duoduo-product", contextId = "listingRelease", path = "/api/listings")
public interface ListingOrderClient {

    /**
     * 释放闲置（SOLD → ON_SALE）：幂等，已不是 SOLD 时返回 {@code released=false} 不报错
     * —— 用户取消 / 超时关单 / 未发货退款三类钩子可能对同一单重复触发。
     */
    @PostMapping("/internal/{id}/release")
    ApiResponse<Boolean> releaseToOnSale(@PathVariable("id") Long listingId);
}

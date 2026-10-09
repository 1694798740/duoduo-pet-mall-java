package com.duoduo.user.client;

import com.duoduo.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 商品域停售客户端（E15）：资质过期被停业的店铺，其在售商品要一并下架。
 *
 * <h2>为什么要联动下架，而不是「只挡住上架」</h2>
 * E15 的需求口径里「限制上架、不影响已成交订单履约」。只挡住新的上架动作是不够的：
 * 一家执照过期的店，货还挂在架上被买家下单，平台就是在给一个无照经营者引流 ——
 * 而「已成交订单照常履约」这一条，指的是**已经产生的订单**要继续走完发货与售后，
 * 不是指过期之后还能继续产生新订单。所以口径落地为：
 * <ul>
 *   <li>过期 → 商品全部下架（不再产生新订单）；</li>
 *   <li>订单、售后、结算等履约数据<b>一律不动</b>，钱照结、货照发。</li>
 * </ul>
 *
 * <h2>为什么是「命令别人做」而不是直接改表</h2>
 * 商品表属于商品域（{@code duoduo_product}），用户域直连就是跨库写 ——
 * 本项目的硬约束是禁止跨库直连。走内部端点还多一层好处：
 * 商品域可以在自己的事务里顺带把搜索索引、缓存一起更新，不会出现
 * 「库改了、索引还是旧的」这种只有搜索时才暴露的半截状态。
 *
 * <p>调用方 {@code MerchantLicenseNotifier} 以 best-effort 调用：下架失败绝不能让
 * 「停业」这个判定失败（停业是平台合规动作，必须先成立），但必须吵到日志里 ——
 * 店停业了、货还在卖，是最需要人立刻知道的那种不一致。
 */
@FeignClient(name = "duoduo-product", contextId = "userProductOffShelf",
        path = "/api/products/internal")
public interface ProductOffShelfClient {

    /**
     * 批量下架某店铺的全部在售商品。
     *
     * @param sellerId 店铺主体（t_merchant.owner_user_id）—— 与商品归属同一口径
     * @return {@code data} 为实际被下架的商品数（幂等：已下架的不会被重复计入）
     */
    @PostMapping("/seller/{sellerId}/off-shelf")
    ApiResponse<Integer> offShelfBySeller(@PathVariable("sellerId") Long sellerId);
}

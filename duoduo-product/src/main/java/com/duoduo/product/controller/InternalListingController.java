package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.product.dto.ProductDtos.InternalListingView;
import com.duoduo.product.dto.ProductDtos.WantResult;
import com.duoduo.product.service.ListingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 闲置服务间接口（第五十三轮）：社交域「问一问」LISTING 消息的事实源。
 *
 * <p>路径落在 {@code /api/listings/internal/cards}：与 {@code ListingController} 的
 * 单段 {@code /{id}} 不冲突（两段路径 vs 单段变量，同 {@code /api/products/internal/cards}
 * 与 {@code /api/products/{spuId}} 共存的先例）。
 *
 * <p><b>内部接口</b>：{@code /api/listings/internal/**} 由 {@code InternalTokenFilter}
 * 校验 {@code X-Internal-Token} —— 返回值带 sellerId（内部口径字段），
 * 不能匿名枚举，否则「向谁咨询」可以被任意探测。
 */
@RestController
@RequestMapping("/api/listings")
public class InternalListingController {

    private final ListingService listingService;

    public InternalListingController(ListingService listingService) {
        this.listingService = listingService;
    }

    /** 批量取闲置卡事实（问一问定收件人 + 卡片展示）；幽灵 id 静默跳过 */
    @GetMapping("/internal/cards")
    public ApiResponse<List<InternalListingView>> cards(@RequestParam("ids") List<Long> ids) {
        return ApiResponse.ok(listingService.internalCards(ids));
    }

    /**
     * 「想要」上报（第五十四轮）：社交域在买家<b>首次</b>向闲置卖家发出咨询消息时调用。
     *
     * <p><b>为什么是 POST 而不是 GET</b>：这是一次<b>写</b>（去重登记 + 计数 +1）。
     * 用 GET 会允许被预取、被重试、被爬虫无意触发 —— 计数会被刷歪，而且错误很难归因。
     *
     * <p><b>幂等</b>：同一 {@code buyerId} 重复上报返回 {@code {"counted": false}}，
     * 不报错也不重复计数。调用方（发消息链路）是 best-effort 调用 ——
     * 统计口径重复绝不该让用户的消息发不出去。
     */
    @PostMapping("/internal/{id}/want")
    public ApiResponse<WantResult> markWant(@PathVariable Long id,
                                            @RequestParam("buyerId") Long buyerId) {
        return ApiResponse.ok(new WantResult(listingService.markWant(id, buyerId)));
    }
}

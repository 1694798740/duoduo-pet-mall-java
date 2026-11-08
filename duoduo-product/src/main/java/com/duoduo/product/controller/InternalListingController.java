package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.product.dto.PersonalDataCleanupResult;
import com.duoduo.product.dto.ProductDtos.InternalListingView;
import com.duoduo.product.dto.ProductDtos.WantResult;
import com.duoduo.product.service.ListingOrderService;
import com.duoduo.product.service.ListingService;
import com.duoduo.product.service.PersonalDataCleanupService;
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
    private final PersonalDataCleanupService personalDataCleanupService;
    private final ListingOrderService listingOrderService;

    public InternalListingController(ListingService listingService,
                                     PersonalDataCleanupService personalDataCleanupService,
                                     ListingOrderService listingOrderService) {
        this.listingService = listingService;
        this.personalDataCleanupService = personalDataCleanupService;
        this.listingOrderService = listingOrderService;
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

    /**
     * 释放闲置（B-11 · C2C 担保交易）：交易域在「用户取消 / 运营强关 / 超时关单 / 退款」
     * 四类钩子后回调，把下单时 CAS 锁成的 SOLD 回滚成 ON_SALE。
     *
     * <p><b>幂等</b>：同一单的多个钩子可能重复到达（或与下一单成交几乎同时），
     * 已不是 SOLD 时返回 {@code released=false}、不报错 —— 调用方（交易域事件监听）
     * 是 best-effort，绝不能让「重复释放」变成一次报错的噪音。
     *
     * <p><b>内部接口</b>：在 {@code /api/listings/internal/} 前缀下，凭据由
     * {@code InternalTokenFilter} 校验 —— 外部绝不可达，否则「帮任意闲置解锁」
     * 就是把防超卖闸门交给了调用方。
     */
    @PostMapping("/internal/{id}/release")
    public ApiResponse<Boolean> releaseToOnSale(@PathVariable Long id) {
        return ApiResponse.ok(listingOrderService.releaseToOnSale(id));
    }

    /**
     * C7 · 账号注销的个人数据清理（服务间调用，见 {@code docs/account-deletion-design.md} 4.4）。
     *
     * <p><b>内部接口</b>：路径以 {@code /api/listings/internal/} 开头，由本域
     * {@code InternalTokenFilter} 校验 {@code X-Internal-Token}（既有前缀已覆盖本路径，
     * 无需改 Filter）。调用方是用户域的注销编排，五域全部成功后才落
     * {@code t_user.account_status = DELETED}。
     *
     * <p>两种模式（查询串 {@code dryRun}）：
     * <ul>
     *   <li>{@code dryRun=true} —— 预检，只查不写，返回「将删多少行、有多少在售闲置」，
     *       供「申请注销」时判断有没有在售商品要挡下；</li>
     *   <li>缺省 / false —— 执行，删该用户已下架的闲置。
     *       <b>幂等可重入</b>：调度失败重试时重复调用不报错、不二次破坏。</li>
     * </ul>
     *
     * <p>在售（{@code ON_SALE}）与已售出（{@code SOLD}）的闲置刻意不删：
     * 前者被调用方预检挡下，后者关联真实订单（理由见 {@code PersonalDataCleanupService} 类注释）。
     */
    @PostMapping("/internal/users/{userId}/personal-data")
    public ApiResponse<PersonalDataCleanupResult> cleanupPersonalData(
            @PathVariable Long userId,
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun) {
        PersonalDataCleanupResult result = dryRun
                ? personalDataCleanupService.preview(userId)
                : personalDataCleanupService.execute(userId);
        return ApiResponse.ok(result);
    }
}

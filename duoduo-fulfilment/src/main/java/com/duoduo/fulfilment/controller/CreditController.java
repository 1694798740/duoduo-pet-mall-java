package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.fulfilment.dto.CreditDtos.CreditView;
import com.duoduo.fulfilment.service.CreditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 信用分公开查询接口（A4）。
 *
 * <p>挂 {@code /api/reviews} 前缀而不是另起一段：信用分的三类事实里两类（评价、售后）
 * 都在评价 / 履约语义之下，放同一前缀下网关路由与白名单都不用新增规则段。
 *
 * <p>与 {@link ReviewController} 共用 {@code /api/reviews} 前缀，但分属两个控制器：
 * 二者生命周期不同（评价是既有能力，信用分是新增能力），拆开后互不干扰。
 *
 * <p><b>公开只读</b>：信用分是「购物决策信息」（买家要看卖家靠不靠谱），
 * 与商品评价同一性质，未登录访客也应看得到；网关白名单只放行
 * {@code /api/reviews/seller/*&#47;credit} 这一条精确路径，
 * 发表评价等写接口仍在白名单之外。控制器只做「绑定参数 + 调服务 + 包响应」。
 */
@RestController
@RequestMapping("/api/reviews")
public class CreditController {

    private final CreditService creditService;

    public CreditController(CreditService creditService) {
        this.creditService = creditService;
    }

    /**
     * 某用户的信用分与构成明细（公开）。
     *
     * <p>路径沿用产品口径的 {@code seller} 段：它的展示用例是「商品 / 闲置页上的卖家信用」。
     * 信用分本身是**用户维度**属性，因此「我的信用」也复用同一端点（传自己的 userId）。
     *
     * @param sellerId 卖家（用户）标识；{@code 0} 表示平台自营
     */
    @GetMapping("/seller/{sellerId}/credit")
    public ApiResponse<CreditView> sellerCredit(@PathVariable Long sellerId) {
        return ApiResponse.ok(creditService.creditOf(sellerId));
    }
}

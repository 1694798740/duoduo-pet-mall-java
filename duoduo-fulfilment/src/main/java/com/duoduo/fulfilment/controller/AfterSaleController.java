package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.UserContext;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleCreateRequest;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleReplyRequest;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleReturnShipRequest;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleView;
import com.duoduo.fulfilment.service.AfterSaleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 售后接口（买家与卖家）。
 *
 * <p>整个前缀 {@code /api/aftersales/**} <b>不进网关白名单</b> ——
 * 售后单里含买家诉求、举证图与卖家处理说明，都是当事人信息，
 * 不登录拿不到 {@code X-User-*}，服务端也就无法判断「你有没有资格看这条」。
 *
 * <p>控制器只做「参数绑定 + 调服务 + 包响应」，归属校验全部在服务层。
 */
@RestController
@RequestMapping("/api/aftersales")
public class AfterSaleController {

    private final AfterSaleService afterSaleService;

    public AfterSaleController(AfterSaleService afterSaleService) {
        this.afterSaleService = afterSaleService;
    }

    /** 买家立案（仅本人订单、仅已发货 / 已完成订单） */
    @PostMapping
    public ApiResponse<AfterSaleView> create(@Valid @RequestBody AfterSaleCreateRequest request) {
        return ApiResponse.ok(afterSaleService.create(UserContext.requireUserId(), request));
    }

    /** 我提起的售后（买家视角，分页） */
    @GetMapping("/mine")
    public ApiResponse<PageResult<AfterSaleView>> mine(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(afterSaleService.listMine(UserContext.requireUserId(), status, page, pageSize));
    }

    /** 我店铺的售后（卖家视角；可见范围由登录态推导，接受不到 sellerId 参数） */
    @GetMapping("/seller")
    public ApiResponse<PageResult<AfterSaleView>> seller(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(afterSaleService.listForSeller(UserContext.requireUserId(), status, page, pageSize));
    }

    /** 售后单详情（当事人或运营） */
    @GetMapping("/{id:\\d+}")
    public ApiResponse<AfterSaleView> detail(@PathVariable Long id) {
        return ApiResponse.ok(afterSaleService.detail(UserContext.requireUserId(), id));
    }

    /** 卖家处理：AGREE（同意并退款）/ REJECT（拒绝，必须填说明） */
    @PostMapping("/{id}/reply")
    public ApiResponse<AfterSaleView> reply(@PathVariable Long id,
                                            @Valid @RequestBody AfterSaleReplyRequest request) {
        return ApiResponse.ok(afterSaleService.reply(UserContext.requireUserId(), id, request));
    }

    /**
     * 买家填写寄回物流（第五十八轮 P1-5）：仅「待买家寄回」的退货退款可提交。
     *
     * <p>路径是<b>三段</b>（{@code /{id}/return-shipment}）：网关白名单按路径匹配且不区分方法，
     * 而 {@code /api/aftersales/**} 整段都不在白名单里（售后含买家诉求与证据，必须登录），
     * 所以这里不存在「匿名可调」的风险；保持三段与既有 {@code /{id}/reply} 同形即可。
     */
    @PostMapping("/{id}/return-shipment")
    public ApiResponse<AfterSaleView> shipReturn(@PathVariable Long id,
                                                 @Valid @RequestBody AfterSaleReturnShipRequest request) {
        return ApiResponse.ok(afterSaleService.shipReturn(UserContext.requireUserId(), id, request));
    }

    /**
     * 卖家确认收货并退款（第五十八轮 P1-5）：仅「买家已寄回」的退货退款可提交，
     * 退款在这一刻才发生 —— 在此之前钱一直没动。
     */
    @PostMapping("/{id}/return-receipt")
    public ApiResponse<AfterSaleView> receiveReturn(@PathVariable Long id) {
        return ApiResponse.ok(afterSaleService.receiveReturn(UserContext.requireUserId(), id));
    }

    /** 买家申请平台仲裁（待卖家处理 / 卖家已拒绝 / 待寄回 / 待收货 时可进入） */
    @PostMapping("/{id}/escalate")
    public ApiResponse<AfterSaleView> escalate(@PathVariable Long id) {
        return ApiResponse.ok(afterSaleService.escalate(UserContext.requireUserId(), id));
    }
}

package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.product.dto.StockDtos.StockChangeRequest;
import com.duoduo.product.dto.StockDtos.StockChangeResponse;
import com.duoduo.product.service.StockService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存写接口（内部接口，只供交易服务调用）。
 *
 * <p><b>安全</b>：这四个接口会改变商品库存，属于写操作，因此由
 * {@code InternalTokenFilter} 强制校验 {@code X-Internal-Token} 请求头，
 * 校验不过直接 403。校验是**默认开启**的（配置项有开发默认值，生产必须用环境变量覆盖），
 * 避免「忘了配就没保护」这种失败方向错误的设计。
 *
 * <p>与只读的 {@code /api/products/skus/batch} 不同：只读接口返回的是与商品详情
 * 同级别的公开信息，可以对外；写接口不行。
 */
@RestController
@RequestMapping("/api/products/skus/stock")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    /** 冻结库存（下单时调用） */
    @PostMapping("/freeze")
    public ApiResponse<StockChangeResponse> freeze(@RequestBody StockChangeRequest request) {
        return ApiResponse.ok(stockService.apply(StockService.ACTION_FREEZE, request));
    }

    /** 释放冻结（取消订单 / 超时关单时调用） */
    @PostMapping("/release")
    public ApiResponse<StockChangeResponse> release(@RequestBody StockChangeRequest request) {
        return ApiResponse.ok(stockService.apply(StockService.ACTION_RELEASE, request));
    }

    /** 扣减库存（支付成功时调用） */
    @PostMapping("/deduct")
    public ApiResponse<StockChangeResponse> deduct(@RequestBody StockChangeRequest request) {
        return ApiResponse.ok(stockService.apply(StockService.ACTION_DEDUCT, request));
    }

    /** 退货入库（退款成功后调用）：库存加回，不校验在售状态 */
    @PostMapping("/restock")
    public ApiResponse<StockChangeResponse> restock(@RequestBody StockChangeRequest request) {
        return ApiResponse.ok(stockService.apply(StockService.ACTION_RESTOCK, request));
    }
}

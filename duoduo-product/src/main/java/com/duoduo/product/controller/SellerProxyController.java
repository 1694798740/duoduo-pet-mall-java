package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.product.dto.ProxyDtos.ProxyRequestCreate;
import com.duoduo.product.dto.ProxyDtos.ProxyWorkOrderPage;
import com.duoduo.product.dto.ProxyDtos.ProxyWorkOrderView;
import com.duoduo.product.service.ProxyOpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端 · 代运营申请（商品域，E23）。
 *
 * <p>路径取 {@code /api/seller/products/proxy-requests}：{@code /api/seller/products/**}
 * 已被网关路由到商品服务，所以**不需要改网关**。
 * 与交易域的同名入口（{@code /api/seller/proxy-requests}）只是路径不同，
 * 语义完全一致 —— 分属两个库，所以落两个服务。
 *
 * <p>与既有 {@code GET /api/seller/products/{spuId:\d+}} 不会互相截胡：
 * 本控制器的段是字面量 {@code proxy-requests}，而那个端点用 {@code \d+} 限定了必须是数字。
 */
@RestController
@RequestMapping("/api/seller/products/proxy-requests")
public class SellerProxyController {

    private final ProxyOpsService proxyOpsService;

    public SellerProxyController(ProxyOpsService proxyOpsService) {
        this.proxyOpsService = proxyOpsService;
    }

    /** 登记一条商品类代运营申请（授权凭证），返回工单号 */
    @PostMapping
    public ApiResponse<ProxyWorkOrderView> create(@RequestBody ProxyRequestCreate request) {
        return ApiResponse.ok(proxyOpsService.request(request));
    }

    /** 我（本店铺）的申请列表 */
    @GetMapping
    public ApiResponse<ProxyWorkOrderPage> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(proxyOpsService.listMine(status));
    }

    /** 撤销自己尚未被执行的申请（一份发出去就收不回来的授权不是授权） */
    @PostMapping("/{workOrderNo}/void")
    public ApiResponse<ProxyWorkOrderView> voidOne(@PathVariable String workOrderNo) {
        return ApiResponse.ok(proxyOpsService.voidMine(workOrderNo));
    }
}

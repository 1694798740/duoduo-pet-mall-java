package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.Audited;
import com.duoduo.product.dto.ProductDtos.SellerProductView;
import com.duoduo.product.dto.ProxyDtos.ProxyOffShelfRequest;
import com.duoduo.product.dto.ProxyDtos.ProxyWorkOrderPage;
import com.duoduo.product.service.ProxyOpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理后台 · 代运营（E23，商品域唯一的白名单动作：代下架）。
 *
 * <h2>为什么要有这个端点，而不是继续用商家端那个</h2>
 * 在 E23 之前，平台运营可以通过 {@code POST /api/seller/products/{spuId}/status}
 * 直接下架第三方店铺的商品（见 {@code SellerProductService.requireProxyForThirdParty}
 * 的说明）。那个行为已经收窄 —— 第三方商品必须走这里，
 * 并且必须带一份**该店铺店主本人给出的、与本商品本动作对得上的有效工单**。
 *
 * <h2>路径选择（零网关改动，刻意的）</h2>
 * 基础路径取 {@code /api/admin/products}（既有路由已覆盖），不新建
 * {@code /api/admin/proxy/**}：后者要在网关新增路由，而每新增一条路由就多一个
 * 「路由顺序 / 兜底规则」的出错面。语义上也说得通：代运营下架商品，本来就在商品管理之下。
 *
 * <h2>权限</h2>
 * 读取（待办列表）只要求「进后台」；执行动作在服务层要求 {@code tenant:proxy-ops}。
 * 审计动作标识与平台自家的处置**刻意分开** ——
 * 「平台处置自家商品」与「平台代第三方店铺处置」必须能分别检索。
 */
@RestController
@RequestMapping("/api/admin/products")
public class AdminProxyController {

    private final ProxyOpsService proxyOpsService;

    public AdminProxyController(ProxyOpsService proxyOpsService) {
        this.proxyOpsService = proxyOpsService;
    }

    /**
     * 商品类代运营工单待办（只读）。
     *
     * <p>{@code targetRef} 传 spuId 时走「这个商品上还有没有可用授权」——
     * 这是运营在被拒之后最需要的那个查询。
     */
    @GetMapping("/proxy-work-orders")
    public ApiResponse<ProxyWorkOrderPage> workOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String targetRef) {
        return ApiResponse.ok(proxyOpsService.listForPlatform(status, tenantId, targetRef));
    }

    /** 代下架：凭第三方店铺的有效工单，替它把商品下架 */
    @PostMapping("/{spuId}/proxy-off-shelf")
    @Audited(action = AuditAction.PRODUCT_PROXY_OFF_SHELF)
    public ApiResponse<SellerProductView> proxyOffShelf(@PathVariable Long spuId,
                                                        @RequestBody ProxyOffShelfRequest request) {
        return ApiResponse.ok(proxyOpsService.proxyOffShelf(spuId, request));
    }
}

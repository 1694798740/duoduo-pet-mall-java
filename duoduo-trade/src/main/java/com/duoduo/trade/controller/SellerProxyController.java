package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.dto.ProxyDtos.ProxyRequestCreate;
import com.duoduo.trade.dto.ProxyDtos.ProxyWorkOrderPage;
import com.duoduo.trade.dto.ProxyDtos.ProxyWorkOrderView;
import com.duoduo.trade.service.ProxyOpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端 · 代运营申请（{@code /api/seller/proxy-requests}，E23）。
 *
 * <p>这是「平台替第三方店铺操作」这条链路的**起点**：店主（或店员，以其所属店铺主体身份）
 * 在这里留下一份授权，平台才可能在后台执行代发货 / 代处理售后。
 * 没有这一步，平台侧的「代运营」就只是平台自己写的备注。
 *
 * <h2>为什么入口在商家端，而不是平台后台</h2>
 * 授权必须由被代理的一方给出。若平台既能授权又能执行，那这套机制
 * 只是给越权操作加了一道填写框 —— 审计记录会很完整，而它证明的事实并不存在。
 *
 * <h2>权限</h2>
 * 走商家端既有准入（卖家 / 店员），租户由登录态推导。**不要求任何平台权限点** ——
 * 商家给自己申请协助，不需要任何额外许可。
 *
 * <h2>路径选择</h2>
 * {@code /api/seller/**} 已被网关整体路由到交易服务，故本控制器**不需要改网关**。
 * 商品类的同名入口在商品域（{@code /api/seller/products/proxy-requests}），
 * 因为商品操作的数据在另一个库里。
 */
@RestController
@RequestMapping("/api/seller/proxy-requests")
public class SellerProxyController {

    private final ProxyOpsService proxyOpsService;

    public SellerProxyController(ProxyOpsService proxyOpsService) {
        this.proxyOpsService = proxyOpsService;
    }

    /** 登记一条代运营申请（授权凭证），返回工单号 */
    @PostMapping
    public ApiResponse<ProxyWorkOrderView> create(@RequestBody ProxyRequestCreate request) {
        return ApiResponse.ok(proxyOpsService.request(request));
    }

    /** 我（本店铺）的申请列表；{@code status} 可选，非法值不会被静默忽略 */
    @GetMapping
    public ApiResponse<ProxyWorkOrderPage> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(proxyOpsService.listMine(status));
    }

    /**
     * 撤销自己尚未被执行的申请。
     *
     * <p>撤销是这套机制能成立的前提之一：一份发出去就收不回来的授权不是授权。
     */
    @PostMapping("/{workOrderNo}/void")
    public ApiResponse<ProxyWorkOrderView> voidOne(@PathVariable String workOrderNo) {
        return ApiResponse.ok(proxyOpsService.voidMine(workOrderNo));
    }
}

package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.Audited;
import com.duoduo.trade.dto.OrderDtos.OrderView;
import com.duoduo.trade.dto.OrderDtos.RefundResult;
import com.duoduo.trade.dto.ProxyDtos.ProxyAftersaleRequest;
import com.duoduo.trade.dto.ProxyDtos.ProxyShipRequest;
import com.duoduo.trade.dto.ProxyDtos.ProxyWorkOrderPage;
import com.duoduo.trade.service.ProxyOpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理后台 · 代运营（E23，交易域两个白名单动作）。
 *
 * <h2>与 {@code AdminOrderController} 的分工</h2>
 * {@code AdminOrderController} 管的是平台**自己**的事务（强制关单）；
 * 这里管的是「平台替**第三方店铺**做事」—— 后者多一道前置条件：
 * 必须持有该店铺店主给出的、且与本动作/本目标完全对得上的有效工单。
 * 两者放在同一个类里会让人误以为准入条件相同，所以分开。
 *
 * <h2>路径选择（零网关改动，刻意的）</h2>
 * 基础路径取 {@code /api/admin/orders} 而不是新建 {@code /api/admin/proxy/**}：
 * 后者需要在网关新增两条路由（一个给交易、一个给商品），而每新增一条路由
 * 就多一个「路由顺序 / 兜底规则」的出错面（本项目的 Ant {@code /**} 不匹配无尾斜杠
 * 集合端点、新路径段必须排在兜底路由之前 —— 都踩过）。
 * 复用既有前缀则完全不需要动网关，且语义上说得通：代运营订单操作，本来就在订单管理之下。
 * 冲突风险已核对：本类的 {@code /proxy-work-orders} 是字面量，
 * 而 {@code AdminOrderController} 的详情端点是 {@code /{orderNo:\d+}}，两者不会互相截胡。
 *
 * <h2>权限</h2>
 * 读取（待办列表）只要求「进后台」；三个执行动作各自在服务层要求
 * {@code tenant:proxy-ops} —— 口径是「进门宽、动数严」。
 * 审计注解与动作一一对应，且刻意**不复用**平台自家事务的动作标识：
 * 「平台处置自家订单」与「平台代第三方店铺处置」是两类必须能分别检索的事实。
 */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminProxyController {

    private final ProxyOpsService proxyOpsService;

    public AdminProxyController(ProxyOpsService proxyOpsService) {
        this.proxyOpsService = proxyOpsService;
    }

    /**
     * 代运营工单待办（只读）。
     *
     * <p>{@code targetRef} 传订单号时走「这个订单上还有没有可用授权」，
     * 这是运营在被拒之后最需要的那个查询；不传则返回最近的待办队列。
     */
    @GetMapping("/proxy-work-orders")
    public ApiResponse<ProxyWorkOrderPage> workOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String targetRef) {
        return ApiResponse.ok(proxyOpsService.listForPlatform(status, tenantId, targetRef));
    }

    /** 代发货：凭第三方店铺的有效工单，替它把订单发出去 */
    @PostMapping("/{orderNo}/proxy-ship")
    @Audited(action = AuditAction.ORDER_PROXY_SHIP)
    public ApiResponse<OrderView> proxyShip(@PathVariable String orderNo,
                                            @RequestBody ProxyShipRequest request) {
        return ApiResponse.ok(proxyOpsService.proxyShip(orderNo, request));
    }

    /** 代处理售后（同意退款）：凭第三方店铺的有效工单，替它确认收到退货并退款 */
    @PostMapping("/{orderNo}/proxy-aftersale")
    @Audited(action = AuditAction.AFTERSALE_PROXY_PROCESS)
    public ApiResponse<RefundResult> proxyAftersale(@PathVariable String orderNo,
                                                    @RequestBody ProxyAftersaleRequest request) {
        return ApiResponse.ok(proxyOpsService.proxyAftersale(orderNo, request));
    }
}

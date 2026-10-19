package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.dto.OrderDtos.CancelRequest;
import com.duoduo.trade.dto.OrderDtos.OrderView;
import com.duoduo.trade.dto.OrderDtos.PayRequest;
import com.duoduo.trade.dto.OrderDtos.PayResult;
import com.duoduo.trade.dto.OrderDtos.PlaceOrderRequest;
import com.duoduo.trade.dto.OrderDtos.PlaceOrderResult;
import com.duoduo.trade.dto.OrderDtos.PlaceTaskView;
import com.duoduo.trade.dto.OrderDtos.PreviewOrderRequest;
import com.duoduo.trade.dto.OrderDtos.PreviewOrderResult;
import com.duoduo.trade.dto.OrderDtos.RefundRequest;
import com.duoduo.trade.dto.OrderDtos.RefundResult;
import com.duoduo.trade.dto.OrderDtos.SweepResult;
import com.duoduo.trade.dto.SettlementDtos.SettlementView;
import com.duoduo.trade.service.OrderPlaceService;
import com.duoduo.trade.service.OrderService;
import com.duoduo.trade.support.OrderPlaceGuard;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口（需登录）。
 *
 * <p>路径由网关按 {@code /api/orders/**} 路由到本服务；网关已完成鉴权，
 * 服务端从 {@code X-User-*} 取身份，<b>请求体与路径里都没有 userId</b>。
 *
 * <p>控制器只做「参数绑定 + 调服务 + 包响应」。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderPlaceGuard placeGuard;
    private final ObjectProvider<OrderPlaceService> placeServiceProvider;

    public OrderController(OrderService orderService,
                           OrderPlaceGuard placeGuard,
                           ObjectProvider<OrderPlaceService> placeServiceProvider) {
        this.orderService = orderService;
        this.placeGuard = placeGuard;
        this.placeServiceProvider = placeServiceProvider;
    }

    /**
     * 结算预览（只读）：算清「这一单要花多少钱」，供结算页展示。
     *
     * <p><b>为什么不能省掉这个端点：</b>金额取决于服务端独有的三件事 ——
     * 实时可售价、库存是否够、券能不能用。前端自己算一遍，迟早出现
     * 「结算页显示 199、付款时变成 219」，那是最伤信任的一类缺陷。
     *
     * <p><b>强制登录</b>（{@code /api/orders/**} 不在网关白名单）：
     * 预览要读「我的购物车勾选行」与「我的券」，这两个都是用户私有数据，
     * 匿名调用既无意义、也不该有一条公开通道去探测他人购物车金额。
     *
     * <p>金额口径与 {@link #place} 严格同源（同一个 {@code OrderService}），
     * 且<b>不写任何表</b>：不占库存、不占券、不建订单。
     */
    @PostMapping("/preview")
    public ApiResponse<PreviewOrderResult> preview(@Valid @RequestBody PreviewOrderRequest request) {
        return ApiResponse.ok(orderService.previewOrder(UserContext.requireUserId(), request));
    }

    /**
     * 提交订单：把购物车中**已勾选**的商品下单。
     *
     * <p>必须带 {@code idempotentKey}：同一用户用同一个键重复提交只会产生一个订单
     * （网络重试、用户连点两次都靠它兜住）。命中幂等键时返回首次的订单，
     * 响应里的 {@code idempotentReplay=true} 可用于区分。
     *
     * <p>A11：入口挂 Sentinel 限流（资源 order-place，与削峰端点共用），超限 429。
     */
    @PostMapping
    public ApiResponse<PlaceOrderResult> place(@Valid @RequestBody PlaceOrderRequest request) {
        return ApiResponse.ok(placeGuard.call(() -> orderService.placeOrder(request)));
    }

    /**
     * 削峰下单（A11）：受理即入队，消费者异步执行与同步端点<b>同一份</b>下单逻辑。
     *
     * <p>返回的是任务视图（PENDING）而非订单 —— 凭 {@code idempotentKey} 轮询
     * {@code GET /api/orders/placement/{idempotentKey}}，PLACED 后按常规订单接口查询；
     * FAILED 时 {@code failReason} 与同步端点的业务异常同文案。
     * 默认部署 order-mode=sync（本端点 503），生产按流量决策切换。
     */
    @PostMapping("/queued")
    public ApiResponse<PlaceTaskView> placeQueued(@Valid @RequestBody PlaceOrderRequest request) {
        Long userId = UserContext.requireUserId();
        return ApiResponse.ok(placeGuard.call(() -> placeService().enqueue(userId, request)));
    }

    /** 削峰下单任务查询（本人）：PENDING / PLACED / FAILED（带原因） */
    @GetMapping("/placement/{idempotentKey}")
    public ApiResponse<PlaceTaskView> placement(@PathVariable String idempotentKey) {
        Long userId = UserContext.requireUserId();
        PlaceTaskView view = placeService().placement(userId, idempotentKey);
        if (view == null) {
            throw BizException.notFound("下单任务");
        }
        return ApiResponse.ok(view);
    }

    /** 削峰服务仅在 order-mode=queue 时存在；sync 部署下调用按 503 如实告知 */
    private OrderPlaceService placeService() {
        OrderPlaceService service = placeServiceProvider.getIfAvailable();
        if (service == null) {
            throw new BizException(ErrorCode.DEGRADED, "当前部署未开启削峰队列（order-mode=sync）");
        }
        return service;
    }

    /**
     * 我的订单列表。
     *
     * @param status    状态过滤（PENDING_PAYMENT / PAID / SHIPPED / COMPLETED / CLOSED）
     * @param orderType NORMAL / SECKILL / C2C
     * @param keyword   订单号或商品标题（包含匹配；服务端筛，前端不做「当前页过滤」）
     */
    @GetMapping
    public ApiResponse<PageResult<OrderView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(orderService.listMine(status, orderType, keyword, page, pageSize));
    }

    /**
     * 订单详情（含明细快照）。
     *
     * <p><b>路径变量刻意加了 {@code \d{21}} 约束</b>（第五十八轮 P1-8）：
     * 订单号固定是「14 位时间戳 + 6 位随机 + 1 位校验位」共 21 位纯数字
     * （见 {@code OrderNoGenerator}）。不加约束时，{@code GET /api/orders/preview}
     * 会被这里的 {@code /{orderNo}} 当成订单号 "preview" 捕获，回 404「订单不存在」——
     * 状态码与真正的「订单号写错了」完全相同，排查时极易被误读成数据问题。
     * 加上形状约束后，拼错的 GET 落到 405（路径命中了只支持 POST 的 /preview），
     * 语义上「这个路径存在、但方法不对」，与「订单不存在」彻底分开。
     */
    @GetMapping("/{orderNo:\\d{21}}")
    public ApiResponse<OrderView> detail(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.detail(orderNo));
    }

    /**
     * 取消订单（仅待付款可取消），并释放已冻结的库存。
     *
     * <p>取消原因可选，请求体可为空 —— 用 {@code required=false} 允许前端不带 body 直接调用。
     */
    @PostMapping("/{orderNo}/cancel")
    public ApiResponse<OrderView> cancel(@PathVariable String orderNo,
                                         @Valid @RequestBody(required = false) CancelRequest request) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(orderService.cancel(orderNo, reason));
    }

    /**
     * 支付订单（本阶段为模拟渠道，即时成功）。
     *
     * <p>必须带 {@code idempotentKey}：同一渠道同一幂等键只会入账一次，
     * 重复提交返回首次结果（{@code idempotentReplay=true}）。
     */
    @PostMapping("/{orderNo}/pay")
    public ApiResponse<PayResult> pay(@PathVariable String orderNo,
                                      @Valid @RequestBody PayRequest request) {
        return ApiResponse.ok(orderService.pay(orderNo, request));
    }

    /**
     * 发货（已付款 → 已发货）：**只有卖家端这一个入口**（R60 收敛）。
     *
     * <p>这里原来有一个 {@code POST /api/orders/{orderNo}/ship}：它挂在买家侧的订单路径上，
     * 只校验内部凭据、**不校验调用者是不是这笔订单的卖家**，于是「给任意订单发货」对任何
     * 拿到凭据的人都成立。卖家端 {@code /api/seller/orders/{orderNo}/ship} 落地之后它就是
     * 一个纯冗余且更松的口子 —— 保留它的唯一代价是「哪天被人照着文档调一次」。
     * 已于 R60 删除，7 套依赖它的冒烟脚本同步迁移到卖家端（迁移记录见 docs/PLAN.md R60）。
     */

    /** 确认收货（已发货 → 已完成） */
    @PostMapping("/{orderNo}/confirm")
    public ApiResponse<OrderView> confirmReceipt(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.confirmReceipt(orderNo));
    }

    /**
     * 申请退款（买家自助）。
     *
     * <p>只允许「已付款且卖家未发货」的订单：未发货时钱退回去、卖家不发货，两清；
     * 一旦发货，货在买家手上，必须走售后流程（运营侧凭内部凭据执行退款）。
     * 这条界限在服务端强制，前端只显示正确按钮是挡不住直接调接口的。
     */
    @PostMapping("/{orderNo}/refund")
    public ApiResponse<RefundResult> refund(@PathVariable String orderNo,
                                            @Valid @RequestBody(required = false) RefundRequest request) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(orderService.refundByBuyer(orderNo, reason));
    }

    /**
     * 运营侧退款（售后审核通过后执行），已付款 / 已发货的订单都可。
     *
     * <p><b>内部接口</b>：路径以 {@code /internal/} 开头，由 {@code InternalTokenFilter}
     * 校验 {@code X-Internal-Token}。卖家账号体系落地后，这里会再加一层
     * 「调用者是否为该订单的卖家」的校验。
     */
    @PostMapping("/internal/{orderNo}/refund")
    public ApiResponse<RefundResult> refundByOperator(@PathVariable String orderNo,
                                                      @Valid @RequestBody(required = false) RefundRequest request) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(orderService.refundByOperator(orderNo, reason));
    }

    /**
     * 查询订单的资金托管状态（仅本人可见）。
     *
     * <p>买家详情页据此显示「资金托管中 / 已放款给卖家 / 已退回买家」——
     * C2C 担保交易里，买家最关心的就是「我的钱现在在哪」，这个必须能查到。
     */
    @GetMapping("/{orderNo}/settlement")
    public ApiResponse<SettlementView> settlement(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.settlementOf(orderNo));
    }

    /**
     * 手动触发一次补偿调度（超时关单 + 释放冻结 + 自动确认收货 + 入库补偿）。
     *
     * <p><b>内部接口</b>，同样需要内部凭据。
     * 定时任务每 {@code duoduo.trade.sweep-interval-ms} 自动跑一次；
     * 这个入口是给运维排障与自动化测试用的 —— 否则「等 7 天看自动收货」既不可测也不好排查。
     */
    @PostMapping("/internal/sweep")
    public ApiResponse<SweepResult> sweep() {
        return ApiResponse.ok(orderService.sweepExpiredOrders());
    }

    /**
     * 服务间只读：按订单号取订单事实（供履约域校验「能否评价」）。
     *
     * <p><b>内部接口</b>，由 {@code InternalTokenFilter} 校验 {@code X-Internal-Token}。
     * 返回的是裁剪过的 {@link com.duoduo.trade.dto.OrderDtos.OrderFactView}：
     * 只有归属 / 状态 / 时间 / 商品明细，<b>不含收货人信息</b>。
     */
    @GetMapping("/internal/{orderNo}/fact")
    public ApiResponse<com.duoduo.trade.dto.OrderDtos.OrderFactView> internalFact(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.internalOrderFact(orderNo));
    }

    /**
     * 服务间只读：某卖家的订单状态计数（供履约域算信用分的「履约完成率」因子）。
     *
     * <p><b>内部接口</b>，由 {@code InternalTokenFilter} 校验 {@code X-Internal-Token}。
     * 只回计数，不回订单 —— 不把收货人信息带进不需要它的服务（INV-9）。
     */
    @GetMapping("/internal/seller/{sellerId}/stats")
    public ApiResponse<com.duoduo.trade.dto.OrderDtos.SellerOrderStats> internalSellerStats(
            @PathVariable Long sellerId) {
        return ApiResponse.ok(orderService.internalSellerStats(sellerId));
    }
}

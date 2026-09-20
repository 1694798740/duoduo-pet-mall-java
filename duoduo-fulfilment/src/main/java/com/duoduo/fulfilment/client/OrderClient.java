package com.duoduo.fulfilment.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.fulfilment.client.dto.OrderFact;
import com.duoduo.fulfilment.client.dto.RefundRequest;
import com.duoduo.fulfilment.client.dto.RefundResult;
import com.duoduo.fulfilment.client.dto.SellerOrderStats;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 交易域客户端：取订单事实、执行退款（走 Nacos 服务发现 + 负载均衡，<b>不经网关</b>）。
 *
 * <p>为什么不走网关：网关承担「对外入口」职责（鉴权、限流、CORS）。
 * 服务间调用再绕一圈网关会平白多一跳，且网关故障会放大成全站故障。
 * 内部调用由 {@code X-Internal-Token} 保护（见 {@code InternalCallConfig}），
 * 交易域侧由它自己的 {@code InternalTokenFilter} 校验。
 */
@FeignClient(name = "duoduo-trade", path = "/api/orders")
public interface OrderClient {

    /**
     * 按订单号取订单事实；订单不存在由交易域返回 404。
     *
     * <p>返回的 {@code userId} 是**事实**，归属判断在本服务完成：拿它与登录态比对，
     * 不一致就 403。绝不把「订单属于谁」的判断权交给调用方传参。
     */
    @GetMapping("/internal/{orderNo}/fact")
    ApiResponse<OrderFact> fact(@PathVariable("orderNo") String orderNo);

    /**
     * 按裁定结果执行退款（售后单裁定通过时调用）。
     *
     * <p>复用交易域**既有的**运营侧退款入口，不新开一条资金通路 ——
     * 退款的状态机、托管 CAS、退货入库补偿在那边只有一份实现，
     * 这里再写一条「售后专用退款」等于给自己造第二个事实来源。
     *
     * <p>已知的失败语义（调用方必须区分对待，不允许「对端不可用就放行」）：
     * <ul>
     *   <li>订单不存在 → 交易域 404；</li>
     *   <li><b>已完成且已放款</b> → 交易域 422（钱已到卖家手里，需走追偿 / 平台垫付，
     *       属另一条资金流程，见 {@code PLAN.md} 的边界 B-01）；</li>
     *   <li>交易域不可用 → 5xx / 超时。</li>
     * </ul>
     */
    @PostMapping("/internal/{orderNo}/refund")
    ApiResponse<RefundResult> refund(@PathVariable("orderNo") String orderNo,
                                     @RequestBody RefundRequest request);

    /**
     * 服务间只读：某卖家的订单状态计数（信用分的「履约完成率」因子）。
     *
     * <p>刻意**不做**流式分页或明细拉取：要的只是几个整数。
     * 对端不可用时由调用方（{@code CreditService}）映射成 503，而不是当成「0 单」——
     * 把依赖故障算成「这个卖家没有完成过订单」，会凭空压低别人的信用分。
     */
    @GetMapping("/internal/seller/{sellerId}/stats")
    ApiResponse<SellerOrderStats> sellerStats(@PathVariable("sellerId") Long sellerId);
}

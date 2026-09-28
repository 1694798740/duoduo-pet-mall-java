package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.client.dto.CouponCallDtos.ConsumeRequest;
import com.duoduo.trade.client.dto.CouponCallDtos.ConsumeResult;
import com.duoduo.trade.client.dto.CouponCallDtos.LockRequest;
import com.duoduo.trade.client.dto.CouponCallDtos.LockResult;
import com.duoduo.trade.client.dto.CouponCallDtos.ReleaseRequest;
import com.duoduo.trade.client.dto.CouponCallDtos.ReleaseResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 营销服务客户端（服务间调用，走 Nacos 服务发现 + 负载均衡，不经网关）。
 *
 * <p>调用方向是<b>单向</b>的：只有交易域调营销域（下单锁券 / 支付核销 / 关单解锁），
 * 营销域不反向依赖交易域 —— 它连 openfeign 都不依赖。
 * 干净的单向依赖意味着营销域挂掉不会影响「订单已经建成」这件事的正确性
 * （只会让新订单用不了券），故障面是可控的。
 *
 * <p>三个写接口都由 {@code InternalCallConfig} 统一加 {@code X-Internal-Token}，
 * 营销域的 {@code InternalTokenFilter} 校验。
 */
@FeignClient(name = "duoduo-marketing", path = "/api/coupons/internal")
public interface MarketingClient {

    /** 锁券（下单时）。不可用返回 {@code success=false} 与原因文案，不抛异常 */
    @PostMapping("/lock")
    ApiResponse<LockResult> lock(@RequestBody LockRequest payload);

    /**
     * 券试算（结算预览时）。
     *
     * <p>复用 {@link LockRequest} 的请求形状，但语义是**只读** —— 营销域不会占用券。
     * 预览若直接调 {@link #lock}，「用户改数量看价格」这个纯浏览动作会把券锁住，
     * 最后没提交订单还得等 TTL 自动解锁。
     */
    @PostMapping("/trial")
    ApiResponse<LockResult> trial(@RequestBody LockRequest payload);

    /** 解锁（关单 / 取消 / 超时） */
    @PostMapping("/release")
    ApiResponse<ReleaseResult> release(@RequestBody ReleaseRequest payload);

    /** 核销（支付成功） */
    @PostMapping("/consume")
    ApiResponse<ConsumeResult> consume(@RequestBody ConsumeRequest payload);
}

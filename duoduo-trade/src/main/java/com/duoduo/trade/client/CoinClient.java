package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.client.dto.CoinCallDtos.CoinMutationResult;
import java.util.Map;
import com.duoduo.trade.client.dto.CoinCallDtos.CoinOrderRequest;
import com.duoduo.trade.client.dto.CoinCallDtos.CoinUserOrderRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 用户服务「多多币」内部客户端（R-P1，服务间调用，走 Nacos 发现，不经网关）。
 *
 * <p>币的<b>唯一权威在用户域</b>（账户 / 批次 / 流水四张表都在那边），
 * 交易域只持有订单上的币快照列（用了多少、抵了多少钱）。
 * 调用方向单向：交易域 → 用户域，用户域不反向依赖交易域，
 * 币的故障面因此与券完全同构 —— 只影响「能不能用币」，不影响「订单已建成」的正确性。
 *
 * <p>六个端点全部幂等：用户域以 {@code (user_id, biz_type, biz_no, direction)} 唯一索引
 * 与冻结单号唯一索引兜底，支付回调重放、调度重跑、超时重试都只会得到同一个结果。
 *
 * <p>内部凭据由 {@code InternalCallConfig} 统一注入 {@code X-Internal-Token}。
 */
@FeignClient(name = "duoduo-user", contextId = "coinClient", path = "/api/coins/internal")
public interface CoinClient {

    /** 可用余额（预览时只读；冻结前的最终校验由用户域扣减条件保证） */
    @GetMapping("/balance")
    ApiResponse<Long> balance(@RequestParam("userId") Long userId);

    /** 下单冻结（真实扣减余额，按批次先到期先扣） */
    @PostMapping("/freeze")
    ApiResponse<CoinMutationResult> freeze(@RequestBody CoinUserOrderRequest payload);

    /** 支付成功确认扣减 */
    @PostMapping("/deduct")
    ApiResponse<CoinMutationResult> deduct(@RequestBody CoinOrderRequest payload);

    /** 取消 / 超时关单释放 */
    @PostMapping("/release")
    ApiResponse<CoinMutationResult> release(@RequestBody CoinOrderRequest payload);

    /** 退款退币（按原批次退回，过期批次补发 30 天新批次） */
    @PostMapping("/refund")
    ApiResponse<CoinMutationResult> refund(@RequestBody CoinUserOrderRequest payload);

    /** 完成返币（T+7 调度调用） */
    @PostMapping("/earn")
    ApiResponse<CoinMutationResult> earn(@RequestBody CoinUserOrderRequest payload);

    /**
     * 站内任务事件（R-P1 第三轮）：加购 / 首单等事实由产生它的域上报。
     * 事件幂等，重复上报只返回当前进度。
     */
    @PostMapping("/task-event")
    ApiResponse<Object> taskEvent(@RequestBody Map<String, Object> body);
}

package com.duoduo.trade.client.dto;

/**
 * 多多币内部调用的请求 / 响应形状（R-P1）。
 *
 * <p>交易域<b>不共享</b>用户域的 DTO 类（各域的包互不依赖，跨域只认 JSON 契约），
 * 这里按用户域 {@code CoinInternalController} 的请求体与 {@code CoinMutationResult}
 * 响应逐字段对齐 —— 字段名就是契约，多传少传都不会被对方理解。
 *
 * <p>金额口径：币是「个」（整数），1 币 = 1 分钱；与分的换算只在交易域做，
 * 用户域只管账户加减，不感知人民币。
 */
public final class CoinCallDtos {

    private CoinCallDtos() {
    }

    /** 带用户与数量的命令（冻结 / 退款 / 返币） */
    public record CoinUserOrderRequest(Long userId, String orderNo, Long coins) {
    }

    /** 只认订单号的命令（支付确认扣减 / 取消释放）——幂等键就是订单号 */
    public record CoinOrderRequest(String orderNo) {
    }

    /**
     * 币变动的统一响应：{@code applied=false} 表示「这笔已经处理过了」（幂等重放），
     * 不是失败；{@code balance} 是本次变动后的可用余额（预览与冒烟断言用）。
     */
    public record CoinMutationResult(boolean applied, Long balance) {
    }
}

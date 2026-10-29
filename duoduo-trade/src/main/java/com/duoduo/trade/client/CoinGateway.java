package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.client.dto.CoinCallDtos.CoinMutationResult;
import com.duoduo.trade.client.dto.CoinCallDtos.CoinOrderRequest;
import com.duoduo.trade.client.dto.CoinCallDtos.CoinUserOrderRequest;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 多多币访问网关（R-P1）：把「怎么调用户域的币」与「失败怎么处理」收敛到一处。
 *
 * <p>失败语义按调用场景刻意分成三档，与券（{@link MarketingGateway}）同一套思路 ——
 * <b>可补偿的失败松、动真钱的失败紧</b>：
 * <ul>
 *   <li><b>查余额</b>：失败 → 返回 0 并只记日志。余额只是结算页的展示输入，
 *       拿不到就让「用币开关」显示为不可用，绝不能因此打不开结算页；
 *       真到冻结那一刻用户域还有一次硬校验（余额不足 409）。</li>
 *   <li><b>冻结 / 确认扣减 / 退款退币</b>：失败 → <b>必须抛</b>。
 *       币抵扣已经算进 {@code payAmount}：冻结失败还让订单建成，等于「订单标了抵 20 币、
 *       账上却一分没扣」，币就会被花两次 —— 与「券核销失败必须让支付回滚」是同一条铁律。
 *       退款退币失败则让整笔退款回滚，运营重试即可（退币幂等，重试不会退两次）。</li>
 *   <li><b>释放</b>：失败 → 只记 ERROR 日志、不阻断关单。
 *       用户取消 / 超时关单必须即时生效；释放失败留下的 FROZEN 冻结单由
 *       用户域的冻结 TTL 兜底任务（默认 2 小时）释放，晚点还币不影响正确性。</li>
 *   <li><b>返币</b>：失败 → 抛给调度逐单捕获，该订单 {@code coin_earned_at} 不落，
 *       下轮调度重试（返币幂等）。</li>
 * </ul>
 */
@Component
public class CoinGateway {

    private static final Logger log = LoggerFactory.getLogger(CoinGateway.class);

    private final CoinClient coinClient;

    public CoinGateway(CoinClient coinClient) {
        this.coinClient = coinClient;
    }

    /**
     * 上报站内任务事件（加购 / 首单）。任务奖励是附加激励，失败只记日志，
     * 绝不能让加购或支付因为币系统抖动而失败。
     */
    public void taskEvent(Long userId, String event, String bizId) {
        if (userId == null || event == null) {
            return;
        }
        try {
            coinClient.taskEvent(java.util.Map.of(
                    "userId", userId,
                    "event", event,
                    "bizId", bizId == null ? "" : bizId));
        } catch (RuntimeException e) {
            log.warn("上报多多币任务事件失败 userId={} event={} bizId={}：{}", userId, event, bizId, e.getMessage());
        }
    }

    /**
     * 查可用余额。失败降级为 0（展示用途，不值得打挂结算页）。
     */
    public long balanceOf(Long userId) {
        try {
            ApiResponse<Long> response = coinClient.balance(userId);
            Long data = response == null ? null : response.data();
            return data == null || data < 0 ? 0L : data;
        } catch (RuntimeException e) {
            log.warn("查询用户 {} 多多币余额失败，本次结算按 0 币展示：{}", userId, e.getMessage());
            return 0L;
        }
    }

    /**
     * 下单冻结。失败抛 {@link BizException}，订单不成立（币已算进应付金额）。
     */
    public void freeze(Long userId, String orderNo, long coins) {
        call(() -> coinClient.freeze(new CoinUserOrderRequest(userId, orderNo, coins)),
                "冻结多多币(用户 " + userId + "，订单 " + orderNo + "，" + coins + " 币)");
    }

    /**
     * 支付成功确认扣减。失败抛出，整笔支付回滚（渠道可重试，扣减幂等）。
     */
    public void deductConfirm(String orderNo) {
        call(() -> coinClient.deduct(new CoinOrderRequest(orderNo)),
                "确认扣减多多币(订单 " + orderNo + ")");
    }

    /**
     * 退款退币。失败抛出，整笔退款回滚 —— 钱与币要么一起退成，要么一起留给重试。
     */
    public void refundBack(Long userId, String orderNo, long coins) {
        call(() -> coinClient.refund(new CoinUserOrderRequest(userId, orderNo, coins)),
                "退款退回多多币(用户 " + userId + "，订单 " + orderNo + "，" + coins + " 币)");
    }

    /**
     * 取消 / 超时释放。best-effort：失败只记 ERROR，由用户域冻结 TTL 兜底任务最终释放。
     */
    public void release(String orderNo, String scene) {
        try {
            call(() -> coinClient.release(new CoinOrderRequest(orderNo)),
                    "释放多多币(订单 " + orderNo + "，" + scene + ")");
        } catch (RuntimeException e) {
            log.error("订单 {} 释放多多币失败（场景：{}），冻结单将由用户域 TTL 兜底任务释放",
                    orderNo, scene, e);
        }
    }

    /**
     * 完成返币。失败抛出，由调度逐单捕获（不落 coin_earned_at，下轮重试）。
     */
    public void earn(Long userId, String orderNo, long coins) {
        call(() -> coinClient.earn(new CoinUserOrderRequest(userId, orderNo, coins)),
                "购物返币(用户 " + userId + "，订单 " + orderNo + "，" + coins + " 币)");
    }

    /**
     * 统一异常转换：4xx（业务拒绝，如余额不足 / 状态冲突）解析报文里的 {@code message}
     * 原样转业务异常给人看（「多多币余额不足，请重新结算」比一句笼统的失败有用得多）；
     * 5xx 与网络故障转 503 {@code DEGRADED} —— 绝不伪装成「币没扣也一样能付」。
     */
    private void call(java.util.function.Supplier<ApiResponse<CoinMutationResult>> action, String what) {
        ApiResponse<CoinMutationResult> response;
        try {
            response = action.get();
        } catch (FeignException e) {
            if (e.status() >= 400 && e.status() < 500) {
                String message = extractMessage(e);
                log.warn("用户域返回 {}（{}）：{}", e.status(), what, message);
                throw new BizException(ErrorCode.BUSINESS_RULE,
                        message == null ? "多多币处理失败，请稍后重试" : message);
            }
            throw new BizException(ErrorCode.DEGRADED, "多多币服务暂时不可用，请稍后重试", e);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.DEGRADED, "多多币服务暂时不可用，请稍后重试", e);
        }
        if (response == null || response.data() == null) {
            throw new BizException(ErrorCode.DEGRADED, "多多币服务响应异常，请稍后重试");
        }
    }

    /** 从 Feign 异常体里取出 {@code message}；取不到返回 null，由调用方给兜底文案 */
    private String extractMessage(FeignException e) {
        try {
            String body = e.contentUTF8();
            if (body == null || body.isBlank()) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            com.fasterxml.jackson.databind.JsonNode message = node.get("message");
            return message == null || message.isNull() ? null : message.asText();
        } catch (RuntimeException | java.io.IOException ignored) {
            return null;
        }
    }
}

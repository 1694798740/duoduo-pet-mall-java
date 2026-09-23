package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.client.dto.CouponCallDtos.ConsumeRequest;
import com.duoduo.trade.client.dto.CouponCallDtos.ConsumeResult;
import com.duoduo.trade.client.dto.CouponCallDtos.LockRequest;
import com.duoduo.trade.client.dto.CouponCallDtos.LockResult;
import com.duoduo.trade.client.dto.CouponCallDtos.ReleaseRequest;
import com.duoduo.trade.client.dto.CouponCallDtos.ReleaseResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 营销服务访问网关（A5）：把「怎么调营销域」与「调用失败怎么处理」收敛到一处。
 *
 * <p>与 {@link ProductGateway} 的<b>关键差别</b>：
 * 商品域的 4xx 可以一律按「查不到」处理（调用方本来就要容错），
 * 但券的 4xx 里带着一句必须给用户看的话 ——「订单金额未达门槛，该券满 200 元可用」。
 * 因此这里<b>要解析错误报文里的 message</b> 并原样转成业务异常，
 * 不能像商品域那样把 4xx 一律吞成 null：吞掉之后用户只会看到「优惠券不可用」，
 * 不知道差多少钱，转化率与投诉量都会难看。
 *
 * <p>三种失败语义刻意不同：
 * <ul>
 *   <li><b>锁券</b>：不可用 → 抛 422（下单不成立）。券抵的是真金白银，
 *       绝不能「券用不了就按原价下单」—— 用户会以为自己享受了折扣。</li>
 *   <li><b>核销</b>：失败 → 抛（支付不成立）。核销失败意味着这张券没被真正用掉，
 *       若让它过去，补偿任务会在 35 分钟后把券解锁回用户手里，
 *       等于同一张券抵了两次钱。</li>
 *   <li><b>解锁</b>：失败 → 只记日志、不影响关单。券有 TTL 补偿任务兜底，
 *       拿它去卡住用户的取消操作是纯粹的得不偿失。</li>
 * </ul>
 */
@Component
public class MarketingGateway {

    private static final Logger log = LoggerFactory.getLogger(MarketingGateway.class);

    private final MarketingClient marketingClient;
    private final ObjectMapper objectMapper;

    public MarketingGateway(MarketingClient marketingClient, ObjectMapper objectMapper) {
        this.marketingClient = marketingClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 锁券（下单时调用）。
     *
     * <p>营销域对「券不可用」返回 200 + {@code success=false}，这里<b>不抛异常</b>，
     * 由调用方把 {@code reason} 转成给用户的提示；
     * 只有「营销域本身返回错误 / 不可用」才抛。这两种情况必须分开：
     * 前者是用户的券有问题（可重试别的券），后者是系统故障（重试也没用）。
     */
    public LockResult lock(String code, Long userId, String orderNo, long orderAmount) {
        ApiResponse<LockResult> response = call(
                () -> marketingClient.lock(new LockRequest(code, userId, orderNo, orderAmount)),
                "锁券(订单 " + orderNo + "，券 " + code + ")");
        return response == null ? null : response.data();
    }

    /**
     * 解锁（关单 / 取消 / 超时）。<b>best-effort</b>：失败只记日志，返回 0。
     *
     * <p>为什么可以这么松：券有按 TTL 扫描的兜底解锁（营销域 {@code CouponReleaseJob}），
     * 主动解锁只是为了让用户<b>立刻</b>能用回这张券，晚几十秒不影响正确性。
     * 反过来，若让解锁失败去阻断关单，用户点「取消订单」会一直转圈 ——
     * 用一个可后台补偿的动作去卡一个必须即时生效的动作，是本末倒置。
     */
    public int release(String orderNo, String scene) {
        try {
            ApiResponse<ReleaseResult> response = call(
                    () -> marketingClient.release(new ReleaseRequest(orderNo)),
                    "解锁券(订单 " + orderNo + "，" + scene + ")");
            return response == null || response.data() == null ? 0 : response.data().released();
        } catch (RuntimeException e) {
            log.error("订单 {} 解锁优惠券失败（场景：{}），券将由营销域 TTL 兜底任务自动解锁：{}",
                    orderNo, scene, e.getMessage());
            return 0;
        }
    }

    /**
     * 核销（支付成功时调用）。失败<b>必须抛</b>，让整笔支付回滚。
     *
     * <p>与库存扣减同一原则：券抵扣已经算进了 {@code payAmount}，
     * 核销不成功却让支付成立，等于这张券既没被标记为已用、又真的抵了钱，
     * 等 TTL 一到它还回用户手里，就能再抵一次。
     */
    public int consume(String orderNo) {
        ApiResponse<ConsumeResult> response = call(
                () -> marketingClient.consume(new ConsumeRequest(orderNo)),
                "核销券(订单 " + orderNo + ")");
        return response == null || response.data() == null ? 0 : response.data().consumed();
    }

    /**
     * 统一异常转换。
     *
     * <p>4xx：解析响应体里的 {@code message} 原样抛出（券不可用的原因要给人看）；
     * 5xx 与网络故障：转 503 {@code DEGRADED}，绝不伪装成「券没问题」。
     */
    private <T> ApiResponse<T> call(java.util.function.Supplier<ApiResponse<T>> action, String what) {
        try {
            return action.get();
        } catch (FeignException e) {
            if (e.status() >= 400 && e.status() < 500) {
                String message = extractMessage(e);
                log.warn("营销服务返回 {}（{}）：{}", e.status(), what, message);
                throw new BizException(ErrorCode.BUSINESS_RULE,
                        message == null ? "优惠券不可用" : message);
            }
            throw new BizException(ErrorCode.DEGRADED, "优惠券服务暂时不可用，请稍后重试", e);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.DEGRADED, "优惠券服务暂时不可用，请稍后重试", e);
        }
    }

    /** 从 Feign 异常体里取出 {@code message}；取不到返回 null，由调用方给兜底文案 */
    private String extractMessage(FeignException e) {
        try {
            String body = e.contentUTF8();
            if (body == null || body.isBlank()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(body);
            JsonNode message = node.get("message");
            return message == null || message.isNull() ? null : message.asText();
        } catch (RuntimeException | java.io.IOException ignored) {
            return null;
        }
    }
}

package com.duoduo.fulfilment.support;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.fulfilment.client.OrderClient;
import com.duoduo.fulfilment.client.dto.OrderFact;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 订单事实的统一取数口（履约域唯一的跨域读入口）。
 *
 * <p><b>为什么要有这一层</b>：评价域与售后域各写了一份「调交易域取订单事实 + 异常映射」，
 * 抽出来之前它们已经**悄悄漂移**了 —— 同一句「交易域不可用」，一处抛 422（业务规则），
 * 一处抛 503（依赖降级）。这不是风格问题：422 会被前端当成「你操作不合规」而提示用户改操作，
 * 503 才表示「不是你的错，稍后再试」。同一件事在两条业务线上给出两种用户可见的解释，
 * 排查时会被当成两个不同的故障。
 *
 * <p>统一后的口径（与交易域 {@code ProductGateway} 同一原则）：
 * <ul>
 *   <li>订单不存在 → 交易域 404 → 本域抛 <b>404</b>；</li>
 *   <li>交易域不可用 / 返回 5xx → <b>503 {@code DEGRADED}</b>，
 *       <b>绝不</b>当成「订单不存在」放行 —— 把依赖故障说成业务事实，
 *       会让用户以为自己的订单消失了，进而做出错误操作；</li>
 *   <li>{@code data} 为空同样按「不存在」处理（响应包络正常但没有内容）。</li>
 * </ul>
 *
 * <p>其他 4xx 没有被单独识别：目前交易域只对「不存在」返回 404，
 * 若将来出现别的 4xx，会落到通用分支当依赖异常 —— 宁可报 503 让人来查，
 * 也不要猜一个业务含义糊弄过去。
 */
@Component
public class OrderFacts {

    private static final Logger log = LoggerFactory.getLogger(OrderFacts.class);

    private final OrderClient orderClient;

    public OrderFacts(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    /**
     * 按订单号取订单事实；不存在抛 404，对端不可用抛 503。
     *
     * <p>取回的是**事实**：订单归属谁、当前什么状态。调用方据此做准入判断，
     * 判断权不放给请求体（INV-8）。
     */
    public OrderFact require(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw BizException.validation("订单号不能为空");
        }
        String trimmed = orderNo.trim();
        OrderFact fact;
        try {
            var response = orderClient.fact(trimmed);
            fact = response == null ? null : response.data();
        } catch (FeignException.NotFound e) {
            throw BizException.notFound("订单");
        } catch (FeignException e) {
            log.error("调用交易域取订单事实失败 orderNo={} status={}", trimmed, e.status(), e);
            throw new BizException(ErrorCode.DEGRADED, "订单服务暂时不可用，请稍后再试");
        }
        if (fact == null) {
            throw BizException.notFound("订单");
        }
        return fact;
    }
}

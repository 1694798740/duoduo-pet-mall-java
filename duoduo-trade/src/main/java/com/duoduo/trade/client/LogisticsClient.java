package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.client.dto.TrackIngestPayload;
import com.duoduo.trade.client.dto.TrackIngestPayload.IngestResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 履约域客户端：上报物流轨迹节点（走 Nacos 服务发现 + 负载均衡，<b>不经网关</b>）。
 *
 * <p>调用时机是「卖家发货成功后」—— 见 {@code OrderService} 里的
 * {@code OrderShippedEvent} 与它的 {@code AFTER_COMMIT} 监听器。
 *
 * <p><b>调用方向与「软循环」</b>：履约域也调交易域（取订单事实、执行退款），
 * 这里再加一条反向调用，两个服务之间就形成了双向依赖。
 * 之所以可以接受，是因为<b>两个方向都不承载正确性</b>：
 * <ul>
 *   <li>履约域 → 交易域：只读事实，对端不可用时履约域如实报 503，不影响交易域；</li>
 *   <li>交易域 → 履约域（本条）：<b>best-effort</b>，失败只记日志、绝不回滚发货
 *       （发货是否成功由订单状态机与买家能否收货决定，与轨迹写没写进去无关）。</li>
 * </ul>
 * 也就是说，没有哪一方「依赖对方活着才能正确工作」。等 M11 的 RocketMQ 落地后，
 * 这条调用应改为发一条 {@code OrderShipped} 事件，由履约域订阅 ——
 * 那时双向依赖会彻底消失。当前不引入 MQ 是为了不为一个节点写入背上整套消息中间件。
 */
@FeignClient(name = "duoduo-fulfilment", path = "/api/logistics")
public interface LogisticsClient {

    /**
     * 上报某订单的轨迹节点（幂等：重复节点由履约域的唯一索引跳过，回执里分开计数）。
     *
     * <p>内部凭据由 {@code InternalCallConfig} 统一注入 {@code X-Internal-Token}，
     * 履约域的 {@code InternalTokenFilter} 会校验。
     */
    @PostMapping("/internal/{orderNo}/tracks")
    ApiResponse<IngestResult> ingest(@PathVariable("orderNo") String orderNo,
                                     @RequestBody TrackIngestPayload.Payload payload);
}

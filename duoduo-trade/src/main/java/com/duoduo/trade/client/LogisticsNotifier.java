package com.duoduo.trade.client;

import com.duoduo.trade.client.dto.TrackIngestPayload;
import com.duoduo.trade.client.dto.TrackIngestPayload.IngestResult;
import com.duoduo.trade.client.dto.TrackIngestPayload.Node;
import com.duoduo.trade.client.dto.TrackIngestPayload.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 发货后向履约域补推物流首节点（best-effort）。
 *
 * <p><b>为什么单独一层而不是在 {@code OrderService} 里直接调 Feign</b>：
 * 这条调用的成败语义与其它跨服务调用**完全不同** ——
 * 库存冻结失败必须补偿、退款失败必须报错，而轨迹推送失败<b>什么也不该发生</b>。
 * 把「失败了怎么办」写在调用点旁边，下次有人在别处再调一次时很容易顺手改成抛异常，
 * 于是「履约域抖一下，卖家就发不了货」。策略收在这里，只有一个地方需要理解它。
 *
 * <p><b>为什么是 best-effort</b>：
 * <ul>
 *   <li>发货是否成功，由订单状态机和买家能否收到货决定，与轨迹写没写进去无关；</li>
 *   <li>失败了也不会「丢数据到永久」—— 节点是幂等上报的，
 *       履约域将来接上真实的快递推送后，揽收节点本来就会由快递公司补推一次；</li>
 *   <li>反过来，若让发货依赖它，就变成「履约域不可用 ⇒ 全平台发不出货」，
 *       把一个展示型功能的可用性抬到了主链路上。</li>
 * </ul>
 *
 * <p><b>绝不抛出</b>：本类所有方法都吞掉异常并记日志。调用方不需要 try/catch，
 * 也不该因为返回 false 去改业务流程（返回值仅用于日志与排查）。
 */
@Component
public class LogisticsNotifier {

    private static final Logger log = LoggerFactory.getLogger(LogisticsNotifier.class);

    /** 节点描述的长度上限（与履约域的 VARCHAR(200) 对齐）；超长只截断，不因此放弃上报 */
    private static final int MAX_TEXT = 200;

    private final LogisticsClient logisticsClient;

    public LogisticsNotifier(LogisticsClient logisticsClient) {
        this.logisticsClient = logisticsClient;
    }

    /**
     * 上报「已揽收」节点。
     *
     * <p>{@code occurredAt} 用订单的 {@code shippedAt}（UTC，ISO-8601）**而不是当前时间**：
     * 通知可能因为重试而晚到，用当前时间会让同一次发货在不同时刻重推时落成两条节点
     * （唯一索引里含发生时间，时间一变就挡不住重复）。用发货时间，重推永远撞唯一键。
     *
     * @param orderNo          订单号
     * @param logisticsCompany 物流公司（可空）
     * @param logisticsNo      物流单号（可空）
     * @param shippedAtIso     发货时间（UTC，ISO-8601）
     * @return 是否上报成功（仅供日志与排查使用，调用方无需据此改流程）
     */
    public boolean notifyPickedUp(String orderNo, String logisticsCompany, String logisticsNo,
                                  String shippedAtIso) {
        if (orderNo == null || orderNo.isBlank() || shippedAtIso == null) {
            // 没有发货时间就无法构成稳定的幂等键，宁可不推，也不要推一条将来必定重复的记录
            log.warn("订单 {} 缺少发货时间，跳过物流首节点上报", orderNo);
            return false;
        }
        Node node = new Node(
                TrackIngestPayload.NODE_PICKED_UP,
                describe(logisticsCompany, logisticsNo),
                shippedAtIso,
                describe(logisticsCompany, logisticsNo));
        try {
            var response = logisticsClient.ingest(orderNo,
                    Payload.single(TrackIngestPayload.SOURCE_SELLER, node));
            IngestResult result = response == null ? null : response.data();
            if (result == null) {
                log.warn("订单 {} 物流首节点上报未返回结果", orderNo);
                return false;
            }
            log.info("订单 {} 物流首节点上报完成：收到 {}，新增 {}，重复跳过 {}",
                    orderNo, result.received(), result.inserted(), result.skipped());
            return true;
        } catch (RuntimeException e) {
            // 只记日志：发货已经成功，轨迹缺失不该让卖家看到失败，也不该回滚已提交的事务
            log.error("订单 {} 物流首节点上报失败（不影响发货，待快递推送或重推补齐）", orderNo, e);
            return false;
        }
    }

    /** 节点描述：「顺丰速运 SF1234567890」；两者都空时返回 null，由履约域用节点名兜底 */
    private String describe(String company, String logisticsNo) {
        String c = trimToNull(company);
        String n = trimToNull(logisticsNo);
        String text;
        if (c == null && n == null) {
            return null;
        }
        text = c == null ? n : (n == null ? c : c + " " + n);
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }

    private String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

package com.duoduo.trade.client.dto;

import java.util.List;

/**
 * 物流轨迹上报的跨服务传输对象（与履约域 {@code com.duoduo.fulfilment.dto.LogisticsDtos}
 * 的 {@code TrackIngestRequest} / {@code TrackNode} 字段对齐）。
 *
 * <p>同 {@link SkuSnapshot} 与 {@code StockChangeDtos} 的理由：**不共享类，只对齐字段名**。
 * 服务端新增字段不会影响本服务的反序列化；服务端改名才是破坏性变更，
 * 所以契约在这里以注释形式写清，并由冒烟测试守住。
 *
 * <p>{@code occurredAt} 用 {@code String} 而不是 {@code LocalDateTime}：
 * 契约上它就是 ISO-8601 文本（履约域按 ISO-8601 解析，接受带时区与不带时区两种写法）。
 * 用 Java 时间类型会让两端的时区解释权落到各自的序列化配置上，
 * 「同一份报文在开发机与容器里落成相差 8 小时的两条记录」正是这么来的。
 */
public final class TrackIngestPayload {

    private TrackIngestPayload() {
    }

    /** 上报来源：SELLER / COURIER / SYSTEM（当前交易域只用 SELLER） */
    public static final String SOURCE_SELLER = "SELLER";

    /** 发货即揽收：包裹交给快递的第一个节点，节点码由履约域定义（交易域不自创） */
    public static final String NODE_PICKED_UP = "PICKED_UP";

    /**
     * 请求体：{@code {"source":"SELLER","nodes":[{"nodeCode":"PICKED_UP","nodeText":"…","occurredAt":"…Z","raw":"…"}]}}
     */
    public record Payload(String source, List<Node> nodes) {

        public static Payload single(String source, Node node) {
            return new Payload(source, List.of(node));
        }
    }

    /**
     * 单个节点。
     *
     * <p>{@code nodeText} 传的是**发货时的物流公司与单号**，例如
     * 「顺丰速运 SF1234567890」—— 买家在时间线的第一行就能看到用哪家快递发的，
     * 不用再往上滚去看订单信息。没填单号时传 {@code null}，由履约域用节点名兜底。
     */
    public record Node(String nodeCode, String nodeText, String occurredAt, String raw) {
    }

    /** 回执：{@code received} 收到几条、{@code inserted} 真新增几条、{@code skipped} 因重复跳过几条 */
    public record IngestResult(String orderNo, int received, int inserted, int skipped) {
    }
}

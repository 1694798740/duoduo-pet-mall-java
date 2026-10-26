package com.duoduo.fulfilment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 物流轨迹的请求与视图（A2）。
 *
 * <p>字段命名与交易域 / 前端逐字对齐（{@code orderNo} / {@code occurredAt}），
 * 不用 {@code order_no} 这类库内风格 —— 跨端契约以驼峰为准。
 */
public final class LogisticsDtos {

    private LogisticsDtos() {
    }

    /**
     * 轨迹上报（内部接口，{@code X-Internal-Token} 保护）。
     *
     * <p><b>为什么是批量</b>：真实快递推送与补推都是成批来的（一次回调带多个节点，
     * 或定时任务对齐一段时间的全部节点）。逐条接口会把一次同步变成 N 次跨服务往返，
     * 「节点多的时候反而更容易失败」是最糟的伸缩性。
     *
     * <p>{@code source} 描述**谁在上报**（SELLER / COURIER / SYSTEM）。
     * 它不进单独的列，而是写进 {@code raw} 报文 ——
     * 它只影响排查时的可读性，不参与任何业务判断，不值得为它加一列索引。
     */
    public record TrackIngestRequest(
            @Size(max = 20, message = "上报来源过长") String source,
            @NotEmpty(message = "节点列表不能为空") @Valid List<TrackNode> nodes) {
    }

    /**
     * 单个轨迹节点。
     *
     * <p>{@code occurredAt} 是节点**发生**时间，不是入库时间（延迟推送 / 补推很常见）。
     * 接受 ISO-8601：带时区的 {@code 2026-09-20T02:30:00Z} 与不带时区的
     * {@code 2026-09-20T10:30:00} 都收（后者按 UTC 解释），
     * 但**不接受**只有日期或本地化格式 —— 物流时间要精确到秒，否则同一节点会被判成两条。
     *
     * <p>{@code nodeText} 可空：推送方没给细节时，服务端用节点名的标准文案兜底。
     */
    public record TrackNode(
            @Size(max = 30, message = "节点码过长") String nodeCode,
            @Size(max = 200, message = "节点描述过长") String nodeText,
            @Size(max = 40, message = "节点时间格式不正确") String occurredAt,
            @Size(max = 1000, message = "原始报文过长") String raw) {
    }

    /**
     * 上报回执。
     *
     * <p>刻意分成三个数而不是只说「成功」：{@code inserted} 与 {@code skipped} 分开，
     * 调用方才能区分「全都重复（推送方重推了）」与「全都新增」这两种截然不同的情况。
     * 只回一个「成功」的话，重推与漏推在监控上看不出区别。
     */
    public record TrackIngestResult(String orderNo, int received, int inserted, int skipped) {
    }

    /**
     * 轨迹节点视图。
     *
     * <p>只回四样：节点码、节点标准文案、节点描述、发生时间。
     * <ul>
     *   <li>{@code nodeLabel} 由服务端给出（{@code LogisticsNode#labelOf}），前端不维护第二份映射；</li>
     *   <li>{@code occurredAt} 是 ISO-8601 字符串（UTC），前端只做展示不做计算；</li>
     *   <li><b>不回 {@code raw}</b>：那是上报方的原始报文，属内部留档，
     *       与「轨迹接口不返回收货人敏感字段」是同一条原则 —— 只给当事人看该看的（INV-9）。</li>
     * </ul>
     */
    public record TrackView(
            String nodeCode,
            String nodeLabel,
            String nodeText,
            String occurredAt) {
    }

    /**
     * 某个订单的轨迹。
     *
     * <p>不含订单状态、收货人、金额 —— 那些由交易域接口负责，
     * 前端本来就要调订单详情，这里再回一份就出现了两个事实来源。
     */
    public record TrackListView(String orderNo, int total, List<TrackView> tracks) {
    }

    // ---------------- B-05 · 电子面单 ----------------

    /**
     * 申请电子面单请求。
     *
     * <p>{@code courierCode} 为空 = 默认顺丰；只接受 {@code Couriers} 白名单里的编码，
     * 卖家不能自创 —— 自创编码会让真实渠道查不到这家快递，也会让两端叫法不一致。
     */
    public record WaybillCreateRequest(
            @NotBlank(message = "订单号不能为空") @Size(max = 32, message = "订单号过长") String orderNo,
            @Size(max = 20, message = "快递公司编码过长") String courierCode) {
    }

    /** 快递公司（前端下拉直接消费；中文名由服务端给出） */
    public record CourierView(String code, String name) {
    }

    /**
     * 电子面单视图。
     *
     * <p>{@code simulated=true} 表示当前没有接快递鸟/菜鸟，运单号与轨迹由本地模拟生成；
     * 前端必须明示这一点，不能让卖家以为已经对接了真实物流。
     *
     * <p>{@code tracks} 直接带上，前端拿到面单后可以立即渲染时间线；没有轨迹时是空列表。
     */
    public record WaybillView(
            String orderNo,
            String courierCode,
            String courierName,
            String waybillNo,
            /** SIMULATED / EXTERNAL */
            String mode,
            boolean simulated,
            String status,
            String statusLabel,
            String createdAt,
            List<TrackView> tracks) {
    }
}

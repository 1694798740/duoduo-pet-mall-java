package com.duoduo.fulfilment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 售后仲裁（A3）履约域 DTO。
 *
 * <p><b>关于「必填」用 400 还是 422 的分界</b>（这里刻意分两档，与 07 篇验收标准一致）：
 * <ul>
 *   <li>字段本身缺失 / 取值不合法 → {@code @NotBlank} 等注解拦在入口 → <b>400</b>。
 *       例如立案的 {@code orderNo}、{@code type}，或 {@code action} 传了未定义的取值；</li>
 *   <li>「某个动作必须附带说明」这类<b>流程要求</b> → 服务层校验 → <b>422</b>。
 *       例如卖家拒绝必须写原因、运营裁定必须填裁定说明 ——
 *       这不是「字段格式错」，而是「这个动作不允许这样提交」，
 *       前端提示与告警策略都不同，所以刻意不用注解拦成 400。</li>
 * </ul>
 */
public final class AfterSaleDtos {

    private AfterSaleDtos() {
    }

    /**
     * 买家立案请求。
     *
     * <p>请求体里**没有** buyerId / sellerId / status：归属与状态一律由服务层
     * 从订单事实（交易域内部接口）取，杜绝「换个 ID 给别人立案」。
     */
    public record AfterSaleCreateRequest(
            @NotBlank(message = "订单号不能为空") String orderNo,
            /** REFUND_ONLY / RETURN_REFUND */
            @NotBlank(message = "售后类型不能为空") String type,
            @NotBlank(message = "请填写售后诉求")
            @Size(max = 500, message = "售后诉求不能超过 500 字") String reason,
            List<String> images
    ) {
    }

    /**
     * 卖家处理请求。
     *
     * @param action AGREE（同意并退款）/ REJECT（拒绝，必须填 reply）
     * @param reply  处理说明
     */
    public record AfterSaleReplyRequest(
            @NotBlank(message = "处理动作不能为空") String action,
            @Size(max = 500, message = "处理说明不能超过 500 字") String reply
    ) {
    }

    /**
     * 运营裁定请求。
     *
     * @param result APPROVE_REFUND（支持退款）/ REJECT（驳回）
     * @param note   裁定说明 —— <b>必填</b>，但由服务层判成 422（见类注释）
     */
    public record AfterSaleArbitrateRequest(
            @NotBlank(message = "裁定结论不能为空") String result,
            @Size(max = 500, message = "裁定说明不能超过 500 字") String note
    ) {
    }

    /**
     * 卖家批量处理请求（2026-10-27 · 批量操作即已读）。
     *
     * <p>动作与说明对选中的全部售后单共用一份；逐条结果见 {@code BatchDtos.Result}。
     */
    public record AfterSaleBatchReplyRequest(
            @NotEmpty(message = "ids 不能为空") List<Long> ids,
            @NotBlank(message = "处理动作不能为空") String action,
            @Size(max = 500, message = "处理说明不能超过 500 字") String reply
    ) {
    }

    /** 运营批量裁定请求（2026-10-27）：结论与说明对全部单共用 */
    public record AfterSaleBatchArbitrateRequest(
            @NotEmpty(message = "ids 不能为空") List<Long> ids,
            @NotBlank(message = "裁定结论不能为空") String result,
            @Size(max = 500, message = "裁定说明不能超过 500 字") String note
    ) {
    }

    /**
     * 买家填写寄回物流请求（第五十八轮 P1-5）。
     *
     * <p>只收「快递公司 + 运单号」两个字段，不收买家自己输入的退货地址：
     * 退货地址由卖家 / 平台给出，让买家手填等于把「寄错地方」的责任与纠纷留给了系统之外。
     * 本阶段退货地址沿用卖家处理说明里的约定（seller_reply），后续若要有结构化地址，
     * 应当加在售后单上由服务端给出，而不是让买家侧提供。
     */
    public record AfterSaleReturnShipRequest(
            @NotBlank(message = "请填写快递公司")
            @Size(max = 64, message = "快递公司不能超过 64 字") String courierCompany,
            @NotBlank(message = "请填写运单号")
            @Size(max = 64, message = "运单号不能超过 64 字") String trackingNo
    ) {
    }

    /**
     * 售后单视图（买家 / 卖家 / 运营三端共用一份，可见范围由各自查询条件决定）。
     *
     * <p>刻意<b>不返回</b> {@code arbitratedBy}（运营人员 id）：对买卖双方而言
     * 「是谁裁定的」属于平台内部信息，需要时由运营侧台账查，不该扩散到用户端。
     *
     * <p><b>{@code id} 为什么是 String 而不是 Long</b>：主键是 18 位雪花值
     * （如 {@code 359291401479008256}），远超 JS 的 {@code Number.MAX_SAFE_INTEGER}
     * （9007199254740991）。序列化成 JSON 数字后，前端 {@code JSON.parse} 会把它
     * **静默四舍五入到另一个整数**，于是「前端拿列表里的 id 去调详情 / 回复 / 申请仲裁」
     * 会打到一条不存在的单子上，报 404 而看不出原因。
     * 64 位标识对外一律以字符串传输，是这类系统的标准做法 ——
     * 服务端入参仍是 {@code Long}，前端原样回传字符串即可（不经任何数值运算）。
     */
    public record AfterSaleView(
            String id,
            String orderNo,
            Long buyerId,
            Long sellerId,
            String type,
            String typeLabel,
            String reason,
            List<String> images,
            String status,
            String statusLabel,
            String sellerReply,
            String arbitrateResult,
            String arbitrateNote,
            Long refundAmount,
            /** 买家寄回的快递公司（仅退货退款，未寄回时为 null） */
            String returnCourier,
            /** 买家寄回的运单号 */
            String returnTrackingNo,
            /** 已寄回时间（ISO-8601 UTC，未寄回时为 null） */
            String returnShippedAt,
            /** 卖家确认收货时间（退款在这一刻发生，未签收时为 null） */
            String returnReceivedAt,
            String createdAt,
            String updatedAt,
            /** 当前查看者是否已看过（列表逐条橙点用；详情不参与） */
            boolean read
    ) {
    }
}

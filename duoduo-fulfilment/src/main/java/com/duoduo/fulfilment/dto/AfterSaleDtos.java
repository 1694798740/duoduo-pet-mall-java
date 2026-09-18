package com.duoduo.fulfilment.dto;

import jakarta.validation.constraints.NotBlank;
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
     * 售后单视图（买家 / 卖家 / 运营三端共用一份，可见范围由各自查询条件决定）。
     *
     * <p>刻意<b>不返回</b> {@code arbitratedBy}（运营人员 id）：对买卖双方而言
     * 「是谁裁定的」属于平台内部信息，需要时由运营侧台账查，不该扩散到用户端。
     */
    public record AfterSaleView(
            Long id,
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
            String createdAt,
            String updatedAt
    ) {
    }
}

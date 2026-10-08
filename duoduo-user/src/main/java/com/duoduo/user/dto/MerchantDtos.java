package com.duoduo.user.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 入驻申请相关 DTO（E10）。
 *
 * <p>驳回原因<b>结构化</b>存 JSON：{@code [{scope, code, note}]}。
 * scope 指明驳回对象（GENERAL = 整体 / 材料类型名 = 某份材料 / STORE_NAME = 店名），
 * code 是机器可读的驳回类别（审核台按 code 聚类统计），note 是给人看的话。
 * 存结构化而不是一段文本，是因为 E11 的审核台要把原因<b>按材料逐条展示</b>、
 * E15 的续期要把原因<b>按类目复核</b> —— 一段自由文本两者都做不到。
 */
public final class MerchantDtos {

    private MerchantDtos() {
    }

    /** 商家侧 / 审核台共用的申请视图 */
    public record ApplicationView(
            String merchantId,
            String storeName,
            String status,
            String isolationLevel,
            List<RejectReason> rejectReasons,
            /** 材料类型名 → 文件 URL（未上传的类不出现在 map 里） */
            Map<String, String> materials,
            String ownerUserId,
            LocalDateTime appliedAt,
            LocalDateTime reviewedAt,
            LocalDateTime createdAt
    ) {
    }

    /** 保存草稿（店名 + 材料引用）。材料引用来自 POST /api/uploads 返回的 url */
    public record SaveDraftRequest(
            String storeName,
            /** 类型名 → 文件 URL；未出现的类型 = 不修改已有（全量 upsert 语义见服务层） */
            Map<String, String> materials
    ) {
    }

    /** 结构化驳回原因 */
    public record RejectReason(
            /** GENERAL / STORE_NAME / 材料类型名 */
            String scope,
            /** 机器可读类别，如 MATERIAL_ILLEGIBLE / INFO_MISMATCH / INDUSTRY_NOT_ALLOWED */
            String code,
            /** 给商家看的人话 */
            String note
    ) {
    }

    /** 运营驳回请求 */
    public record RejectRequest(List<RejectReason> reasons) {
    }

    /** 审核台分页项 */
    public record ApplicationSummary(
            String merchantId,
            String storeName,
            String status,
            String ownerUserId,
            String ownerPhoneMasked,
            LocalDateTime appliedAt,
            LocalDateTime reviewedAt
    ) {
    }

    /** 商家侧激活（审核通过后取新令牌用） */
    public record MerchantTokenResponse(String accessToken, long expiresInSeconds) {
    }
}

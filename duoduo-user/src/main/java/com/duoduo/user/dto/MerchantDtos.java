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

    /**
     * 商家侧 / 审核台共用的申请视图。
     *
     * <p>E15 增补四个资质有效期相关字段，全部为**追加**（老前端不读即忽略，
     * 请求契约一点没动）：{@code licenseExpireAt} 判断「有没有到期概念」、
     * {@code licenseStage} 判断「该不该催」、{@code licenseRemainingDays} 用于文案、
     * {@code materialExpiry} 用于把日期填回编辑框。
     */
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
            LocalDateTime createdAt,
            /** E15：店铺资质最早到期日；null = 长期有效（无到期概念） */
            LocalDateTime licenseExpireAt,
            /** E15：NONE / T30 / T7 / EXPIRED（见 LicenseExpiry.Stage） */
            String licenseStage,
            /** E15：剩余日历天数；**null = 无到期概念**（刻意不用 0 —— 0 读起来像「今天到期」） */
            Long licenseRemainingDays,
            /** E15：停业原因（SUSPENDED 时有值，如「经营资质已过期」） */
            String suspendedReason,
            /** E15：材料类型名 → 有效期至（{@code yyyy-MM-dd}，仅含**已填写**的类） */
            Map<String, String> materialExpiry
    ) {
    }

    /**
     * 保存草稿（店名 + 材料引用）。材料引用来自 POST /api/uploads 返回的 url。
     *
     * <p>E15 增补 {@code materialExpiry}：材料类型名 → 有效期（{@code yyyy-MM-dd}）。
     * 只传 key 不传空的语义与 {@code materials} 一致 ——
     * <b>没出现的类型表示「不修改」</b>，传空串表示「改为长期有效」。
     * 新增字段而非改造 {@code materials} 的形状，是为了不动已有前端与 E12 的调用方。
     */
    public record SaveDraftRequest(
            String storeName,
            /** 类型名 → 文件 URL；未出现的类型 = 不修改已有（全量 upsert 语义见服务层） */
            Map<String, String> materials,
            /** E15：类型名 → 有效期至 {@code yyyy-MM-dd}；未出现 = 不修改，空串 = 长期有效 */
            Map<String, String> materialExpiry
    ) {
        /**
         * E12 及更早的调用方用的两参形态（不带有效期）。
         *
         * <p>保留它而不是让所有调用点改成三参：E15 只是**追加**了一个可选维度，
         * 「不传有效期」是完全合法的用法。少这一个构造器，每个既有调用方都得加一个
         * 写死的 {@code null} —— 那种改动没有任何信息量，却要在 code review 里
         * 逐个确认「这里为什么传 null」。
         */
        public SaveDraftRequest(String storeName, Map<String, String> materials) {
            this(storeName, materials, null);
        }
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

    /** 审核台分页项（E15 追加资质到期三字段，供审核台标记临期/过期店铺） */
    public record ApplicationSummary(
            String merchantId,
            String storeName,
            String status,
            String ownerUserId,
            String ownerPhoneMasked,
            LocalDateTime appliedAt,
            LocalDateTime reviewedAt,
            /** E15：资质最早到期日；null = 长期有效 */
            LocalDateTime licenseExpireAt,
            /** E15：NONE / T30 / T7 / EXPIRED */
            String licenseStage,
            /** E15：停业原因（SUSPENDED 时有值） */
            String suspendedReason
    ) {
    }

    /** 商家侧激活（审核通过后取新令牌用） */
    public record MerchantTokenResponse(String accessToken, long expiresInSeconds) {
    }

    // ---------------- E13 · 子账号体系 ----------------

    /** 成员视图（店主管理页） */
    public record StaffView(
            String memberId,
            String userId,
            String phoneMasked,
            String nickname,
            String role,
            String status,
            String invitedBy,
            String createdAt
    ) {
    }

    /** 平台侧成员总览项（跨租户） */
    public record StaffAdminView(
            String memberId,
            String merchantId,
            String storeName,
            String userId,
            String phoneMasked,
            String role,
            String status,
            String createdAt
    ) {
    }

    /**
     * 成员身份（内部端点下发，供交易/商品域做归属判定）。
     *
     * <p>刻意**只回四个字段**：交易域需要的是「这个人以哪家店的身份行动」，
     * 不是这个人是谁 —— 手机号、实名等不跨域扩散（与 briefs 同一裁剪纪律）。
     */
    public record MembershipInfo(
            String merchantId,
            String ownerUserId,
            String memberRole,
            String status
    ) {
        public boolean activeStaff() {
            return "STAFF".equals(memberRole) && "ACTIVE".equals(status);
        }
    }

    /**
     * 店铺资质与营业状态（E15，内部端点下发，供商品域做上架门禁）。
     *
     * <p>与 {@link MembershipInfo} 是两个不同的问题：
     * <ul>
     *   <li>{@code MembershipInfo} 回答「<b>这个人</b>是不是这家店的店员」（按 userId 查，只服务店员）；</li>
     *   <li>本记录回答「<b>这家店</b>现在能不能卖东西」（按店主 userId 查，店主与店员都要过这一关）。</li>
     * </ul>
     * 合成一个会让「查店员」的调用方平白依赖资质字段，而 {@code membershipOf} 里
     * 那条「店铺非 APPROVED 就查不到店员」的既有规则反而会把两件事搅在一起。
     *
     * <p>刻意<b>只回状态与到期信息</b>，不回材料、不回联系方式 ——
     * 商品域判断「能不能上架」不需要知道店主是谁。
     */
    public record MerchantLicenseInfo(
            String merchantId,
            /** 店铺状态：APPROVED / SUBMITTED / REVIEWING / SUSPENDED / … */
            String status,
            /** 资质档位 NONE / T30 / T7 / EXPIRED（前端提示用，门禁判定只看 status） */
            String licenseStage,
            /** 资质最早到期日；null = 长期有效 */
            LocalDateTime licenseExpireAt,
            /** 停业原因（SUSPENDED 时有值） */
            String suspendedReason
    ) {
        /**
         * 是否可以对外销售。
         *
         * <p>★ 判据是 {@code status == APPROVED}，<b>而不是「没有过期」</b>：
         * 续期审核中（SUBMITTED）、被驳回（REJECTED）、首次入驻未过审（SUBMITTED）
         * 这些都是「还不能卖」的状态。用「没过期」当判据会让这三种店都能上架。
         */
        public boolean canSell() {
            return "APPROVED".equals(status);
        }
    }
}

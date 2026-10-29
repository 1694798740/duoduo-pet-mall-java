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

    /**
     * 入驻批量操作请求（2026-10-28 · 批量操作第一批）。
     *
     * <p>{@code ids} 一律用字符串：商户 id 是 18 位雪花主键，经 JSON number 会被
     * JS 静默截断，批量操作就成了「批量操作不存在的商户」。
     */
    public record BatchIdsRequest(List<String> ids) {
    }

    /** 批量驳回：结构化的驳回原因对全部选中商户共用 */
    public record BatchRejectRequest(List<String> ids, List<RejectReason> reasons) {
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
            /** F-04 · 操作权限键（店主恒为全量；店员按配置） */
            List<String> permissions,
            String invitedBy,
            String createdAt
    ) {
    }

    /**
     * 员工批量导入的一行（2026-10-28 · 导入导出第一批）。
     *
     * <p>前端把 CSV 解析成行后提交，{@code rowNo} 用于把失败原因定位回用户看到的行号。
     * 姓名刻意不在列里：邀请按手机号找到已注册账号，昵称以那个账号自己的资料为准 ——
     * 让店主在导入表里「给别人改名」既没依据，也会让被邀请人看到与自己资料不一致的显示名。
     * {@code permissions} 为空 = 使用默认全量权限（与单条邀请一致）。
     */
    public record StaffImportRow(Integer rowNo, String phone, List<String> permissions) {
    }

    /** 员工批量导入请求：单次最多 200 行（同步处理的上限，见导入导出矩阵） */
    public record StaffImportRequest(List<StaffImportRow> rows) {
    }

    /** 员工批量启用 / 停用请求 */
    public record StaffBatchStatusRequest(List<String> memberIds, Boolean enabled) {
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
     * <p>刻意**只回身份与权限**：交易 / 商品 / 社交域需要的是「这个人以哪家店的身份行动、
     * 能做什么」，不是这个人是谁 —— 手机号、实名等不跨域扩散（与 briefs 同一裁剪纪律）。
     */
    public record MembershipInfo(
            String merchantId,
            String ownerUserId,
            String memberRole,
            String status,
            /**
             * F-04 · 该店员的生效权限键（全量 / 精确清单）。
             *
             * <p>店主不走本接口（role=SELLER 自有通道），因此这里只承载 STAFF 的配置；
             * 下游按 {@code StaffPermission} 判定，不自行解释角色。
             */
            List<String> permissions
    ) {
        public boolean activeStaff() {
            return "STAFF".equals(memberRole) && "ACTIVE".equals(status);
        }

        /** 是否具备某项操作权限（null 清单按无权限处理，防下游空指针）。 */
        public boolean hasPermission(com.duoduo.common.seller.StaffPermission permission) {
            return com.duoduo.common.seller.StaffPermissions.has(permissions, permission);
        }
    }

    /**
     * 工单转交候选人（C-04，内部端点下发）。
     *
     * <p>只回展示与选择所需的四个字段；客服工单的候选人是**本店在职成员**
     * （店主 + 店员），不包含平台运营与店外账号。
     */
    public record TicketMemberInfo(
            String userId,
            String nickname,
            String avatar,
            String role
    ) {
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

    /**
     * 商家风险画像（E28）：商品域判「这件商品能不能自动放行」时用。
     *
     * <p>与 {@link MerchantLicenseInfo} 分开而不是复用：那个回答「这家店现在能不能卖」，
     * 这个回答「这家店值不值得重点看」。两件事的生命周期不同 ——
     * 续期通过后 {@code status} 立刻变 APPROVED，但违规计数不会因此清零。
     *
     * <p>{@code hasRejectHistory} 是**可观测近似**：系统没有单独的「历史驳回」表，
     * 唯一能读到的是「当前是否处于被驳回 / 被停业状态」加「违规计数」。
     * 一个曾经被驳回、后来又通过的商家在这里会读成「没有历史」——
     * 这是刻意的取舍：为此单独加一张历史表，收益远小于它带来的写路径复杂度。
     */
    public record MerchantRiskInfo(
            String merchantId,
            /** 商家级违规累计次数（只增不减） */
            int violationCount,
            /** 当前是否被停业 */
            boolean suspended,
            /** 当前是否处于「申请被驳回」状态 */
            boolean rejected,
            /**
             * 是否处于「重新开业观察期」（最近一次审核通过 / 恢复营业在 7 天内）。
             *
             * <p>传布尔而不是传时间：商品域要用它做「恢复营业 7 天内发布的商品必抽」，
             * 而那是个**时间窗口判定** —— 把时间传过去，两个服务就得各自维护同一个窗口长度，
             * 改的时候必然漏一边。窗口的定义留在用户域这一处。
             */
            boolean recentlyResumed
    ) {
        /** 是否命中「此前有驳回或停业记录」这条风险规则 */
        public boolean flagged() {
            return violationCount > 0 || suspended || rejected;
        }
    }

    /**
     * 违规上报的结果（E28）：商品域判违规后回传，告知累计次数与是否触发停业。
     */
    public record MerchantViolationInfo(
            String merchantId,
            /** 累计违规次数（含本次） */
            int violationCount,
            /** 本次上报是否触发了商家级停业 */
            boolean suspended
    ) {
    }

    /**
     * 店铺名简称（内部端点批量下发）：商品域组装「购物车按店铺分组 / 商品卡显示自有店名」用。
     *
     * <p>键是<b>店主 userId</b>（{@code t_merchant.owner_user_id}），与商品归属
     * {@code t_spu.seller_id} 同一个键 —— 店铺主键不是 {@code t_merchant.id}（E20 口径）。
     * 查不到的 id 直接不返回，调用方按「未知店铺」降级，不抛异常。
     */
    public record StoreBrief(
            Long ownerUserId,
            /** 店铺名（t_merchant.name，全局唯一） */
            String name
    ) {
    }

    /** 营业中店铺改名请求（第八十一轮） */
    public record RenameStoreRequest(
            @jakarta.validation.constraints.NotBlank(message = "店铺名称不能为空")
            String storeName
    ) {
    }

    /**
     * 店铺公开主页信息（第八十二轮）：店铺主页页头用。
     *
     * <p>只暴露可公开的字段——不含资质材料、违规计数、停业原因等运营侧信息。
     * 仅「营业中（APPROVED）」的店铺可被查到，其余状态一律 404（与「未过审商品不可见」同口径）。
     */
    public record StorePublicView(
            /** 店主 userId（路由参数，与 t_spu.seller_id 同键） */
            Long ownerUserId,
            /** 店铺名（t_merchant.name，全局唯一） */
            String name,
            /** 开店时间（审核通过时刻，ISO-8601 UTC） */
            String openedAt
    ) {
    }
}

package com.duoduo.common.audit;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 后台动作清单（E18）：全项目审计动作标识的**唯一口径**。
 *
 * <p><b>为什么是枚举而不是散在各处的字符串常量</b>：E18 之前，动作标识是 5 个服务里
 * 各自 private 的 {@code String} 常量（{@code "USER_ROLE_CHANGE"} / {@code "INVITE_STAFF"} …），
 * 既没有清单也不知道总共有几个。后果有两类，都不报错：
 * <ul>
 *   <li><b>拼写漂移</b>：一处写 {@code "ADMIN_DISABLE_STAFF"}、另一处 {@code "ADMIN_STAFF_DISABLE"}，
 *       查询「某类动作」时漏一半，而两处代码都「看起来是对的」。</li>
 *   <li><b>类目漏判</b>：新增一个资金动作时没人想起来它属于不可删改档 ——
 *       因为「类目」这个概念当时还不存在（它只写在文档里）。</li>
 * </ul>
 * 放进枚举之后，「新增动作必须同时回答：它属于哪个类目、目标是哪类对象」成了一件
 * **编译期必须做完的事**。
 *
 * <p><b>★ 枚举名即落库值</b>（{@link #code()} 返回 {@link #name()}）：E18 之前已经写进
 * {@code t_audit_log} 的 9 个动作标识**必须逐字不变**，否则历史记录会与新记录分叉成两套
 * 口径（{@code USER_ROLE_CHANGE} 换成 {@code USER_ROLE_CHANGED} 之后，
 * 「这个人被改过几次角色」的查询会少一半）。因此这里刻意<b>不改名、不加前缀</b>。
 *
 * <p><b>分类规则</b>见 {@link AuditCategory} 的类注释（含「拿不准往更严的一档放」的理由）。
 *
 * <p><b>刻意不做</b>：不建 {@code t_audit_action} 字典表。动作是代码行为，
 * 不是运营可配置项；放进库表只会引入「代码加了一个动作、表里忘了加一行」这类
 * 只在运行时才暴露的问题，而它换不来任何能力。
 */
public enum AuditAction {

    // ---------------- 用户与权限 ----------------

    /** 变更用户的系统角色。E18 之前已有（{@code AdminUserService}）。 */
    USER_ROLE_CHANGE(AuditCategory.SECURITY, "USER"),

    /**
     * E21 · 启用两步验证（TOTP）。
     *
     * <p>归 SECURITY：它改变的是"这个账号需要几把钥匙才能进"，与权限变更同性质 ——
     * 追责时「某人的 2FA 是什么时候被打开的」往往正是判断"这次入侵从哪一步开始"的关键材料。
     */
    USER_TOTP_ENABLE(AuditCategory.SECURITY, "USER"),

    /** E21 · 关闭两步验证（含"用恢复码关闭"那条路径 —— 它更需要留痕）。 */
    USER_TOTP_DISABLE(AuditCategory.SECURITY, "USER"),

    /**
     * E21 · 由平台管理员重置某账号的两步验证（两个因子都丢失时的最后手段）。
     *
     * <p>这是唯一一个"能替别人解开第二把锁"的动作，必须留痕 ——
     * 否则一个被滥用的管理员可以悄无声息地摘掉任意账号的 2FA，再从容撞库。
     */
    USER_TOTP_RESET(AuditCategory.SECURITY, "USER"),

    // ---------------- 商家入驻 / 资质（租户生命周期） ----------------

    /** 开始审核入驻申请。 */
    MERCHANT_AUDIT_START(AuditCategory.TENANT, "MERCHANT"),

    /** 通过入驻申请并开店。 */
    MERCHANT_APPROVE(AuditCategory.TENANT, "MERCHANT"),

    /** 驳回入驻申请（必须带结构化原因）。 */
    MERCHANT_REJECT(AuditCategory.TENANT, "MERCHANT"),

    /** 手动触发资质有效期巡检（与定时任务同口径）。 */
    MERCHANT_LICENSE_SWEEP(AuditCategory.TENANT, "MERCHANT"),

    /** 资质过期导致的自动停业。E18 之前已有（{@code MerchantLicenseJob}，定时任务入口）。 */
    MERCHANT_LICENSE_SUSPEND(AuditCategory.TENANT, "MERCHANT"),

    /** 商品违规累计导致的停业。E18 之前已有（{@code MerchantViolationService}）。 */
    MERCHANT_VIOLATION_SUSPEND(AuditCategory.TENANT, "MERCHANT"),

    /**
     * 读取商家资质材料。
     *
     * <p>归入 {@link AuditCategory#SECURITY} 而非 TENANT：它是**敏感数据访问**留痕。
     * 「谁在什么时候看过别人的营业执照与身份证」一旦丢了，事后无法重建 ——
     * 这与「权限变更」是同一类不可逆的追责材料。
     */
    MERCHANT_MATERIAL_READ(AuditCategory.SECURITY, "MATERIAL"),

    // ---------------- 商家成员（身份变更 → SECURITY） ----------------

    /** 店主邀请店员。E18 之前已有（{@code MerchantStaffService}）。 */
    INVITE_STAFF(AuditCategory.SECURITY, "STAFF"),

    /** 店主启用店员。E18 之前已有。 */
    ENABLE_STAFF(AuditCategory.SECURITY, "STAFF"),

    /** 店主停用店员。E18 之前已有。 */
    DISABLE_STAFF(AuditCategory.SECURITY, "STAFF"),

    /**
     * F-04 · 店主修改店员操作权限。
     *
     * <p>与停用/启用同归 SECURITY：权限变更直接改变"这个账号能对店铺资产做什么"，
     * 是最需要事后追溯的一类操作（「这笔退款是谁被放开的」往往要回到这条记录）。
     */
    UPDATE_STAFF_PERMISSIONS(AuditCategory.SECURITY, "STAFF"),

    /** 平台启用店员（跨租户处置）。E18 之前已有。 */
    ADMIN_ENABLE_STAFF(AuditCategory.SECURITY, "STAFF"),

    /** 平台停用店员（跨租户处置）。E18 之前已有。 */
    ADMIN_DISABLE_STAFF(AuditCategory.SECURITY, "STAFF"),

    // ---------------- 商品、类目、品牌、审核（内容与主数据） ----------------

    /** 第三方商品上架审核结论（通过 / 驳回）。 */
    PRODUCT_AUDIT(AuditCategory.CONTENT, "PRODUCT"),

    /** 闲置内容审核结论。 */
    LISTING_AUDIT(AuditCategory.CONTENT, "LISTING"),

    /** 新建类目。 */
    CATEGORY_CREATE(AuditCategory.CONTENT, "CATEGORY"),

    /** 修改类目。 */
    CATEGORY_UPDATE(AuditCategory.CONTENT, "CATEGORY"),

    /** 启用 / 停用类目。 */
    CATEGORY_STATUS(AuditCategory.CONTENT, "CATEGORY"),

    /** 删除类目。 */
    CATEGORY_DELETE(AuditCategory.CONTENT, "CATEGORY"),

    /**
     * A-10 · 新建类目属性模板项。
     *
     * <p>归入 CONTENT：模板决定「这个类目的商品能不能发布」，改动会立即影响所有商家的发布闸门，
     * 与类目本身的增删改同性质。目标类型用 {@code CATEGORY_ATTR} 而非 {@code CATEGORY}，
     * 因为追责时要回答的是「谁改了哪个属性」，而不是「谁动了这个类目」。
     */
    CATEGORY_ATTR_CREATE(AuditCategory.CONTENT, "CATEGORY_ATTR"),

    /** A-10 · 修改类目属性（类型 / 必填 / 选项），会改变既有商家的发布校验结果。 */
    CATEGORY_ATTR_UPDATE(AuditCategory.CONTENT, "CATEGORY_ATTR"),

    /** A-10 · 停用 / 启用类目属性（停用后不再参与发布校验，历史值保留）。 */
    CATEGORY_ATTR_STATUS(AuditCategory.CONTENT, "CATEGORY_ATTR"),

    /** A-10 · 删除类目属性模板项。已发布商品的历史属性值不受影响。 */
    CATEGORY_ATTR_DELETE(AuditCategory.CONTENT, "CATEGORY_ATTR"),

    /** 新建品牌。 */
    BRAND_CREATE(AuditCategory.CONTENT, "BRAND"),

    /** 修改品牌。 */
    BRAND_UPDATE(AuditCategory.CONTENT, "BRAND"),

    /** 启用 / 停用品牌。 */
    BRAND_STATUS(AuditCategory.CONTENT, "BRAND"),

    /** 删除品牌。 */
    BRAND_DELETE(AuditCategory.CONTENT, "BRAND"),

    /** 重建商品搜索索引。目标为平台级（{@code target_id = 0}）。 */
    SEARCH_REINDEX(AuditCategory.CONTENT, "SEARCH"),

    /** 审核 SLA 抽查复核（单条）。 */
    AUDIT_SLA_REVIEW(AuditCategory.CONTENT, "AUDIT_SAMPLE"),

    /** 手动触发抽查抽样。目标为平台级。 */
    AUDIT_SLA_SWEEP(AuditCategory.CONTENT, "AUDIT_SAMPLE"),

    /** 处置分享举报（通过 / 驳回 / 下架）。 */
    SHARE_REPORT_HANDLE(AuditCategory.CONTENT, "SHARE_REPORT"),

    /**
     * H-02 · 人工审核评价（下架 / 恢复）。
     *
     * <p>归入 CONTENT：它改变的是公开可见内容，不动资金；但下架会让商品评分与好评率
     * 立即重算，因此审计要同时回答「谁把哪条评价下架了、理由是什么」。
     */
    REVIEW_AUDIT(AuditCategory.CONTENT, "REVIEW"),

    /**
     * A-16 · 商家回复评价。
     *
     * <p>与 {@link #REVIEW_AUDIT} 同属 CONTENT：回复是公开可见内容，但它是商家的
     * **一次性承诺**（回复后不可修改），审计要能回答「哪家店、谁、何时回复了哪条评价」。
     */
    REVIEW_REPLY(AuditCategory.CONTENT, "REVIEW"),

    // ---------------- 交易、营销、履约 ----------------

    /**
     * 强制关闭订单。
     *
     * <p>归入 {@link AuditCategory#FUNDS}：已付款订单被强制关闭会触发退款 ——
     * 它改变的是资金事实，不只是订单状态。拿不准的档位往更严的一档放（见 AuditCategory）。
     */
    ORDER_FORCE_CLOSE(AuditCategory.FUNDS, "ORDER"),

    /** 新建秒杀活动。 */
    SECKILL_CREATE(AuditCategory.CONTENT, "SECKILL"),

    /** 编辑秒杀活动（仅标题与时间）。 */
    SECKILL_UPDATE(AuditCategory.CONTENT, "SECKILL"),

    /** 上架 / 下架秒杀活动。 */
    SECKILL_STATUS(AuditCategory.CONTENT, "SECKILL"),

    /** 删除秒杀活动（判据：无未关闭订单）。 */
    SECKILL_DELETE(AuditCategory.CONTENT, "SECKILL"),

    /** 售后仲裁裁定。归入 FUNDS：裁定结果决定退款与责任归属。 */
    AFTERSALE_ARBITRATE(AuditCategory.FUNDS, "AFTERSALE"),

    // ---------------- 代运营（E23 · 三个白名单动作） ----------------

    /**
     * E23 · 平台代第三方店铺发货。
     *
     * <p>归入 {@link AuditCategory#CONTENT} 而不是 FUNDS：发货本身不动钱
     * （资金仍在托管，放款要等确认收货）。它的敏感点在**身份** ——
     * 平台以店铺的名义对一个不属于平台的订单做了处置，
     * 审计要回答的是「谁替谁、凭什么（工单）、发了什么」。
     * （归档判据：动钱 → FUNDS；动的是别人的经营资产 → CONTENT。）
     */
    ORDER_PROXY_SHIP(AuditCategory.CONTENT, "ORDER"),

    /**
     * E23 · 平台代第三方店铺处理售后（同意退款 / 驳回）。
     *
     * <p>归入 FUNDS：与 {@link #AFTERSALE_ARBITRATE} 同理，裁定结果直接决定退款。
     * 刻意**不**复用同一个动作标识 —— 「平台替自己的售后做裁定」与
     * 「平台替第三方店铺做裁定」是两类需要分开检索与追责的事实，
     * 合成一个标识后「代运营都干了什么」就再也查不出来了。
     */
    AFTERSALE_PROXY_PROCESS(AuditCategory.FUNDS, "AFTERSALE"),

    /** E23 · 平台代第三方店铺下架商品。 */
    PRODUCT_PROXY_OFF_SHELF(AuditCategory.CONTENT, "PRODUCT"),

    // ---------------- 资金 ----------------

    /** 结算单回补。 */
    SETTLEMENT_BACKFILL(AuditCategory.FUNDS, "SETTLEMENT"),

    /** 平台垫付补偿（先垫给买家、再向卖家记账）。 */
    SETTLEMENT_COMPENSATE(AuditCategory.FUNDS, "SETTLEMENT"),

    /** 核销卖家欠款。 */
    SETTLEMENT_DEBT_SETTLE(AuditCategory.FUNDS, "SETTLEMENT"),

    /** 欠款豁免 / 核销（write-off）。 */
    SETTLEMENT_DEBT_WRITE_OFF(AuditCategory.FUNDS, "SETTLEMENT"),

    // ---------------- 对账（S3） ----------------

    /**
     * S3 · 手动触发一轮对账。
     *
     * <p><b>为什么"只是重新算一遍"也要留痕</b>：它对业务数据完全只读，但它会**覆盖**
     * 当日已落库的那轮结论（同一天同一范围只保留一轮，见 {@code ReconService}）。
     * 于是「昨天报表上那批差异怎么没了」这个问题，唯一能回答它的就是这条记录 ——
     * 没有它，结论被重算掉之后无从解释，而"对账结果可信"正是这套功能唯一的产出。
     *
     * <p>归入 {@link AuditCategory#OTHER}：它既不动钱也不动内容，
     * 动的是一份巡检结论，因此不属于 FUNDS / CONTENT 任何一档。
     */
    RECON_RUN_MANUAL(AuditCategory.OTHER, "RECON"),

    // ---------------- 运维 ----------------

    /** 消息队列死信重放。 */
    MQ_DLQ_REPLAY(AuditCategory.OTHER, "MQ"),

    /**
     * 兜底动作：注解上未指定、或来自其它服务但未登记的动作。
     *
     * <p>归入 {@link AuditCategory#SECURITY}（不可删改档）—— 见 {@link #parse} 的说明：
     * <b>「看不懂的动作」不放行到「可删除」那一侧。</b>
     */
    UNKNOWN(AuditCategory.SECURITY, "");

    private final AuditCategory category;
    private final String targetType;

    AuditAction(AuditCategory category, String targetType) {
        this.category = category;
        this.targetType = targetType;
    }

    /** 落库用的动作标识（= 枚举名，见类注释「枚举名即落库值」）。 */
    public String code() {
        return name();
    }

    /** 该动作的类目（决定「能否被清理」，见 {@link AuditCategory#immutable()}）。 */
    public AuditCategory category() {
        return category;
    }

    /** 默认目标类型（如 {@code MERCHANT} / {@code PRODUCT}）；空串表示未细分。 */
    public String targetType() {
        return targetType;
    }

    /** 全部动作（顺序固定，供测试遍历与诊断输出）。 */
    public static final Set<AuditAction> ALL = all();

    private static Set<AuditAction> all() {
        Set<AuditAction> set = new LinkedHashSet<>();
        Collections.addAll(set, values());
        return Collections.unmodifiableSet(set);
    }

    /**
     * 宽容解析动作标识。
     *
     * <p>未知值返回 {@link #UNKNOWN}（对应 {@link AuditCategory#SECURITY}，不可删改档），
     * <b>不返回 null、不抛异常</b>：调用方来自注解反射与数据库读取两条路径，
     * 两条都不该因为一个读不懂的值 500；而「读不懂」这件事本身必须往保守的一侧走。
     */
    public static AuditAction parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}

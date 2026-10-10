package com.duoduo.common.security.perm;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 权限点清单（E16 · RBAC 的「点」）。
 *
 * <p><b>为什么要有这一层</b>：E16 之前，全项目的判权是「裸角色字符串比较」——
 * 后端 {@code LoginUser.isOperator()}、{@code AdminUserService} 手拼的
 * {@code "OPERATOR".equals(role)}、履约域 {@code AfterSaleService} 里的
 * {@code !isOperator()}，前端 {@code roles.ts} 再硬编码一份。同一个问题
 * 「这个人能不能做这件事」在 6 个模块里有 6 种写法，改一处口径必然漏掉另一处。
 *
 * <p>权限点把「能不能做这件事」变成一个**名词**，然后由
 * {@link RolePermissionMatrix} 唯一地回答「谁有它」。判权点只认权限点，
 * 不再认角色名 —— 角色是「人的属性」，权限点是「事的属性」，两者不该在判权处混用。
 *
 * <p><b>命名规范</b>：{@code 域:对象:动作}，全小写冒号分隔。域与现有的服务边界对齐
 * （platform / user / merchant / product / order / aftersale / coupon / audit / seller / finance）。
 * 动作按危险度递进：{@code view} &lt; {@code manage} &lt; {@code dispose}。
 *
 * <p><b>刻意不做</b>：不做「权限点继承 / 权限组 / 动态角色」。当前只有 4 个系统角色、
 * 没有自定义角色需求，动态 RBAC 的复杂度（缓存失效、越权矩阵爆炸）换不来任何收益。
 * 需要时（真出现自定义角色）再升级，见《开发执行总纲》§9「刻意不做」。
 */
public final class Permission {

    private Permission() {
    }

    // ---------------- 平台后台（进门的钥匙） ----------------

    /**
     * 进入平台管理后台。
     *
     * <p>这是「能不能进后台」的**唯一**判据，等价于 E16 之前的
     * {@code LoginUser.isOperator()}（OPERATOR / ADMIN）。
     * 它管的是「进门」，不管「进去之后这一行数据能不能动」——
     * 后者由下面各业务权限点回答（「进门与动数分开」的既有原则，见 OperatorGuard 注释）。
     */
    public static final String PLATFORM_CONSOLE = "platform:console";

    // ---------------- 用户与权限 ----------------

    /** 变更用户的系统角色。高危：给自己提权 / 给他人降权都从这里走。历史上**仅 ADMIN** 可用。 */
    public static final String USER_ROLE_MANAGE = "user:role:manage";

    // ---------------- 商家（入驻 / 资质 / 成员） ----------------

    /** 入驻审核台：查看申请、通过开店、驳回。 */
    public static final String MERCHANT_AUDIT = "merchant:audit";

    /** 读取商家资质材料（营业执照 / 身份证等）。每次读取都留痕（E12）。 */
    public static final String MERCHANT_MATERIAL_READ = "merchant:material:read";

    /** 平台侧跨租户处置商家成员（停用 / 启用店员），与店主自管分开。 */
    public static final String MERCHANT_STAFF_DISPOSE = "merchant:staff:dispose";

    /** 手动触发资质有效期巡检（与定时任务同口径，仅用于验收与应急）。 */
    public static final String MERCHANT_LICENSE_SWEEP = "merchant:license:sweep";

    // ---------------- 商品 ----------------

    /** 第三方商品上架审核台：通过 / 驳回。 */
    public static final String PRODUCT_AUDIT = "product:audit";

    /** 类目与品牌主数据维护。 */
    public static final String PRODUCT_CATALOG_MANAGE = "product:catalog:manage";

    /** 商品搜索索引重建 / 对账。 */
    public static final String PRODUCT_SEARCH_REINDEX = "product:search:reindex";

    /** 闲置（跳蚤市场）内容的平台侧处置。 */
    public static final String PRODUCT_LISTING_ADMIN = "product:listing:admin";

    /** 审核 SLA 抽查队列与复核（E28）。 */
    public static final String AUDIT_SLA = "audit:sla";

    // ---------------- 交易与履约 ----------------

    /** 订单运营：改地址 / 代发货 / 强制关闭等。 */
    public static final String ORDER_OPS = "order:ops";

    /** 秒杀活动管理（创建 / 编辑 / 删除）。 */
    public static final String ORDER_SECKILL_MANAGE = "order:seckill:manage";

    /** 下单消息队列运维：死信查看与重放。 */
    public static final String ORDER_MQ_MANAGE = "order:mq:manage";

    /** 售后仲裁：运营裁定退款 / 责任归属。 */
    public static final String AFTERSALE_ARBITRATE = "aftersale:arbitrate";

    /** 券模板管理。 */
    public static final String COUPON_TEMPLATE_MANAGE = "coupon:template:manage";

    // ---------------- 审计 ----------------

    /**
     * 查看后台操作审计日志（E18）。
     *
     * <p><b>为什么 OPERATOR 也有</b>：运营正是后台操作的主体。审计日志对运营的价值是
     * 「自查与互查」——「刚才那个商品是谁驳回的」「这条停业记录的依据是什么」，
     * 这些问题的答案本来就在页面里；把运营挡在外面，只会让每一件小事都去找管理员口头确认，
     * 而口头确认不留痕。真正的边界不在这里，而在**审计记录不可删改**
     * （任何角色都没有修改入口，见 {@code AuditCategory.immutable()}）。
     *
     * <p>与 {@code FINANCE_SETTLEMENT} 的区别：那个点管的是「动钱」，
     * 这个点管的是「看记录」。看记录不改变任何事实，因此不按资金档收紧。
     */
    public static final String AUDIT_LOG_READ = "audit:log:read";

    // ---------------- 财务 ----------------

    /**
     * 结算与资金流水（E22 落地）。
     *
     * <p>当前**仅 ADMIN**：真正的「财务角色」尚未引入（全仓无 FINANCE 取值）。
     * 先把这个点立起来并只给最高权限，避免 E22 落地时再改判权点 —— 那时改的是
     * 已经跑起来的资金链路，风险远高于现在预留一个点。
     */
    public static final String FINANCE_SETTLEMENT = "finance:settlement";

    // ---------------- 商家端（卖家的门） ----------------

    /**
     * 进入商家工作台（发布商品 / 处理订单 / 售后）。
     *
     * <p>等价于 E16 之前的 {@code LoginUser.canAccessSellerConsole()}
     * （SELLER 本人 + OPERATOR/ADMIN 代运营）。注意店员（{@code t_merchant_member}
     * 的 STAFF）**不在**角色矩阵里 —— 他们的可见性由成员身份实时决定（E13），
     * 角色仍是 USER，走 {@code requireSellerScope()} 的成员分支，不经此点。
     */
    public static final String SELLER_CONSOLE = "seller:console";

    /**
     * 全部权限点（供矩阵测试与「ADMIN 拥有全部」的实现使用）。
     *
     * <p>用 {@link LinkedHashSet} 而不是 {@code Set.of(...)}：后者的迭代顺序
     * **不保证稳定**，而权限点清单会被测试逐条遍历，顺序稳定才能让失败信息可读
     * （第 N 条挂掉 = 对应哪个点）。同理这里用 {@code unmodifiableSet} 包一层，
     * 不用 {@code Set.copyOf} —— 后者同样不保证顺序。
     */
    public static final Set<String> ALL = all();

    private static Set<String> all() {
        Set<String> set = new LinkedHashSet<>();
        set.add(PLATFORM_CONSOLE);
        set.add(USER_ROLE_MANAGE);
        set.add(MERCHANT_AUDIT);
        set.add(MERCHANT_MATERIAL_READ);
        set.add(MERCHANT_STAFF_DISPOSE);
        set.add(MERCHANT_LICENSE_SWEEP);
        set.add(PRODUCT_AUDIT);
        set.add(PRODUCT_CATALOG_MANAGE);
        set.add(PRODUCT_SEARCH_REINDEX);
        set.add(PRODUCT_LISTING_ADMIN);
        set.add(AUDIT_SLA);
        set.add(ORDER_OPS);
        set.add(ORDER_SECKILL_MANAGE);
        set.add(ORDER_MQ_MANAGE);
        set.add(AFTERSALE_ARBITRATE);
        set.add(COUPON_TEMPLATE_MANAGE);
        set.add(AUDIT_LOG_READ);
        set.add(FINANCE_SETTLEMENT);
        set.add(SELLER_CONSOLE);
        return java.util.Collections.unmodifiableSet(set);
    }
}

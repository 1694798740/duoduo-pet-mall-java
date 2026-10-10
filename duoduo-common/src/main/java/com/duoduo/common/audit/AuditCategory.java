package com.duoduo.common.audit;

/**
 * 审计动作类目（E18）。
 *
 * <p>它存在的唯一理由是回答一个问题：<b>这条审计记录将来能不能被清理掉？</b>
 * 《开发执行总纲》§2 E18 的原文是「资金与权限类不可删改」——
 * 这就需要一个**把动作分成档位**的判据，而且只能有一处实现
 * （{@link #immutable()}）。散在各服务里写 {@code if (category == FUNDS)} 迟早分叉。
 *
 * <p><b>分类规则（拿不准时往更严的一档放）</b>：
 * <ul>
 *   <li>{@link #SECURITY} —— 权限、身份、敏感数据访问。改角色、读资质材料、成员启停。
 *       「谁在什么时候看过别人的身份证」这件事一旦丢了，事后无法重建。</li>
 *   <li>{@link #FUNDS} —— <b>任何可能改变资金事实</b>的动作。不只是结算与提现，
 *       还包括强制关闭订单、平台垫付、欠款核销：它们都会让某个人账户上的数字变化。</li>
 *   <li>{@link #TENANT} —— 租户 / 店铺的生命周期与归属（入驻审核结论、停业、资质巡检）。</li>
 *   <li>{@link #CONTENT} —— 内容与主数据（商品、闲置、类目、品牌、营销活动、举报处置、索引）。</li>
 *   <li>{@link #OTHER} —— 运维类（消息队列死信重放、一次性对账脚本）。</li>
 * </ul>
 *
 * <p><b>★ 为什么「拿不准往更严的放」</b>：把 SECURITY/FUNDS 误判成 CONTENT，
 * 代价是「将来某次清理把追责材料删了」，且<b>不可逆</b>；反过来误判成更严的档，
 * 代价只是「这条记录多留了几年」。两个方向的代价严重不对称，所以标准不该对称。
 */
public enum AuditCategory {

    /** 权限 / 身份 / 敏感数据访问。不可删改。 */
    SECURITY,

    /** 资金事实。不可删改。 */
    FUNDS,

    /** 租户与店铺生命周期。 */
    TENANT,

    /** 内容与主数据。 */
    CONTENT,

    /** 运维类。 */
    OTHER;

    /**
     * 是否属于「不可删改」档（资金与权限类）。
     *
     * <p><b>这是全项目唯一的判据</b>。当前阶段应用层根本没有任何删除 / 修改入口
     * （见 {@code AuditLogMapper} 的注释与架构断言），本方法是为将来的
     * 「归档 / 清理」准备的：那时必须只删 {@code !immutable()} 的档位。
     * 现在就写下来，是因为等到真要清理时才定义判据，就已经太晚了。
     */
    public boolean immutable() {
        return this == SECURITY || this == FUNDS;
    }

    /**
     * 宽容解析：未知 / 空白一律折叠为 {@link #SECURITY}（**最严档**），不抛异常。
     *
     * <p><b>★ 为什么未知值选「最严」而不是 {@code OTHER}</b>：本方法的结果会被用来判断
     * 「这条记录能不能被清理」。解析失败时返回 {@code OTHER} 意味着<b>一条读不懂的记录
     * 自动获得了「可删除」资格</b> —— 失败方向指向了不可逆的一侧。
     * 返回 SECURITY 的代价只是「一条看不懂的记录显示成权限类、多留几年」。
     * 与 {@code RolePermissionMatrix} 未知角色折叠为 USER 是同一条原则：
     * <b>解析失败的走向必须是「更保守」，而不是「更松」。</b>
     */
    public static AuditCategory parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SECURITY;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return SECURITY;
        }
    }
}

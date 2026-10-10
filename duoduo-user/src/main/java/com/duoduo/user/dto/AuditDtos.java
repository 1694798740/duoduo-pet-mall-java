package com.duoduo.user.dto;

/**
 * 操作审计的接口契约（E18）。
 */
public final class AuditDtos {

    private AuditDtos() {
    }

    /**
     * 审计记录视图（只读）。
     *
     * <p><b>★ 为什么 {@code actorPhone} 在这里不脱敏</b>：账号管理列表
     * （{@code AdminUserView}）刻意把手机号脱敏成 {@code 139****1111}，因为那是
     * 「浏览用户列表」的场景。审计页不同 —— 它的用途是**追责**：
     * 「到底是哪个运营账号做的这件事」。手机号是本系统里定位到具体账号的唯一稳定标识
     * （昵称可改、角色可变），脱敏之后这份记录就失去了追责能力。
     *
     * <p>风险与代价是对称的：能看这个页面的人本来就持有后台最高权限，且**每一次查看
     * 后台接口的行为都会进访问日志**。用「脱敏」换取「查不出是谁干的」，
     * 是拿审计的核心价值去换一个它本来就不缺的保护。
     *
     * <p>{@code id} 与目标 ID 一律按字符串下发（雪花 ID 超出 JS 安全整数范围，
     * 见 {@code LongToStringJacksonModule}）；这里显式声明成 {@code String} 是为了让
     * 契约在 DTO 上就看得见，而不是依赖全局模块的隐式转换。
     */
    public record AuditLogView(
            String id,
            String actorId,
            String actorPhone,
            String action,
            String category,
            /** 类目是否属「不可删改」档（SECURITY / FUNDS）—— 由服务端按唯一判据算出，前端不自行判断 */
            boolean immutable,
            String outcome,
            String targetId,
            String targetType,
            String tenantId,
            String beforeValue,
            String afterValue,
            String ip,
            String requestId,
            String detail,
            String createdAt
    ) {
    }

    /**
     * 检索条件（全部可空 = 不加该条件）。
     *
     * <p>只提供「按人 / 按租户 / 按时间 / 按类目 / 按动作 / 按目标 / 按结果」七种过滤，
     * 不做全文搜索：审计记录的量级是「后台写操作条数」，七种精确条件足够定位，
     * 而全文检索会让人以为「没搜到 = 没发生过」（它其实只是没匹配上关键词）。
     */
    public record AuditQuery(
            Long actorId,
            Long tenantId,
            String category,
            String action,
            String outcome,
            Long targetId,
            /** 起（含），ISO-8601 本地日期时间；服务端按 UTC 解释 */
            String from,
            /** 止（含） */
            String to
    ) {
    }
}

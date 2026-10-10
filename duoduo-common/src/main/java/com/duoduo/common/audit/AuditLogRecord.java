package com.duoduo.common.audit;

/**
 * 一条审计记录（E18）。
 *
 * <p>它同时承担两个角色：<b>落库载荷</b>（{@code duoduo-user} 的 {@code t_audit_log}）
 * 与<b>跨服务传输载荷</b>（其它域的后台写操作经 {@code /api/users/internal/audit-logs} 汇总到同一张表）。
 * 两者用同一个结构，是为了避免「本地写一份、远程同步一份、字段慢慢对不上」——
 * 那类问题的表现是「跨域的动作在审计页上看不全」，很难被发现。
 *
 * <p><b>★ 为什么 {@code createdAt} 是字符串而不是 {@code LocalDateTime}</b>：
 * 它要跨服务走 JSON。{@code LocalDateTime} 的序列化形态取决于各自的 Jackson 配置
 * （数组 / 带 Z 的字符串 / 无 Z 的字符串），两个服务配置稍有差异就会得到
 * 「时间差 8 小时」或反序列化失败 —— 而这类错误只在跨域操作时才出现。
 * 统一成 ISO-8601 字符串、由接收方宽容解析，把差异挡在一个地方。
 *
 * <p><b>★ 字段可空性口径</b>：{@code targetId} / {@code tenantId} 用 {@code 0} 表示
 * 「平台级操作、无具体目标」，不用 {@code null} —— 落库列是 {@code NOT NULL}，
 * 让「无目标」同时有 null 与 0 两种表示会让所有查询语义分叉（MySQL 里
 * {@code <>} 与 {@code =} 对 NULL 都不匹配，这是项目既有的教训）。
 */
public record AuditLogRecord(
        Long actorId,
        String actorPhone,
        String action,
        String category,
        String outcome,
        long targetId,
        String targetType,
        long tenantId,
        String beforeValue,
        String afterValue,
        String ip,
        String requestId,
        String detail,
        String createdAt
) {

    /** 落库列宽上限：{@code detail(500)} / {@code before_value(500)} / {@code after_value(500)} / {@code ip(64)} / {@code request_id(64)}。 */
    public static final int MAX_TEXT = 500;
    public static final int MAX_SHORT = 64;

    /**
     * 收敛到列宽之内。
     *
     * <p><b>为什么在写入前截断而不是靠数据库报错</b>：审计写入是**旁路**，
     * 它绝不该因为「某个字段太长」把业务请求搞成 500。超长时截断并附省略标记，
     * 保留前 500 字符——审计要的是「能看出发生了什么」，不是完整报文。
     * 截断用的是按码点计数，避免把一个汉字劈成两半变成乱码。
     */
    public AuditLogRecord sanitized() {
        return new AuditLogRecord(
                actorId,
                cut(actorPhone, 20),
                cut(action, 50),
                cut(category, 20),
                cut(outcome, 16),
                targetId,
                cut(targetType, 32),
                tenantId,
                cut(beforeValue, MAX_TEXT),
                cut(afterValue, MAX_TEXT),
                cut(ip, MAX_SHORT),
                cut(requestId, MAX_SHORT),
                cut(detail, MAX_TEXT),
                createdAt
        );
    }

    private static String cut(String raw, int max) {
        if (raw == null) {
            return null;
        }
        int points = raw.codePointCount(0, raw.length());
        if (points <= max) {
            return raw;
        }
        int end = raw.offsetByCodePoints(0, max - 1);
        return raw.substring(0, end) + "…";
    }

    /** 类目（宽容解析；读不懂时按最严档 SECURITY 处理）。 */
    public AuditCategory categoryEnum() {
        return AuditCategory.parse(category);
    }

    /** 结果（宽容解析；读不懂时按 FAILED 处理）。 */
    public AuditOutcome outcomeEnum() {
        return AuditOutcome.parse(outcome);
    }
}

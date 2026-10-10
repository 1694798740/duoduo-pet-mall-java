package com.duoduo.common.audit;

/**
 * 当前请求的审计上下文（E18）：让业务代码补充「注解采集不到」的三件事。
 *
 * <p>拦截器能自动拿到：谁（{@code UserContext}）、何时、从哪个 IP 与哪条链路
 * （网关透传的 {@code X-Real-IP} / {@code X-Request-Id}）、什么动作（注解）、
 * 成功还是失败。它<b>拿不到</b>的是：
 * <ul>
 *   <li>{@code targetId} —— 目标在各端点里叫法不同（{@code id} / {@code spuId} /
 *       {@code memberId} / {@code orderNo}），从参数名猜一定会猜错；</li>
 *   <li>{@code tenantId} —— 租户要从业务对象上推（这家店是谁的），只有业务知道；</li>
 *   <li>{@code beforeValue / afterValue} —— 变更前的值只存在于业务代码读过的那一瞬。</li>
 * </ul>
 * 这三件事由业务代码显式补充，其余由框架补齐。这样「覆盖全部后台写操作」
 * （不会漏）与「关键动作有前后值」（有质量）两件事可以分开做，不必二选一。
 *
 * <p><b>★ ThreadLocal 不跨线程</b>：本上下文与 {@code UserContext} 同一性质。
 * 在异步线程 / 线程池里调用这里的方法会是**静默 no-op**（拿不到就跳过），
 * 不会抛异常、也不会写错行 —— 但也不会补上那份信息。需要跨线程时请显式传递。
 *
 * <p><b>★ 没有活动上下文时全部方法是 no-op，不抛异常</b>：定时任务、MQ 消费、
 * 内部调用都会调用到同一段业务代码（例如资质过期停业既可能由定时器触发、
 * 也可能由运营手点兜底端点触发）。让「没有 HTTP 请求」变成异常，
 * 等于强迫业务代码到处判断自己是不是在 HTTP 线程里 —— 那正是这类工具最容易出事的地方。
 * 非 HTTP 入口的留痕请显式构造 {@link AuditLogRecord} 交给 sink（见 user 域的用法）。
 */
public final class AuditTrail {

    private static final ThreadLocal<AuditTrail> HOLDER = new ThreadLocal<>();

    private final AuditAction action;
    private long targetId;
    private String targetType;
    private long tenantId;
    private String beforeValue;
    private String afterValue;
    private String detail;
    /** 幂等确认（无事实变化）时的抑制标记，见 {@link #skip(String)} */
    private boolean skipped;
    private String skipReason;

    private AuditTrail(AuditAction action) {
        this.action = action;
        this.targetType = action == null ? "" : action.targetType();
        this.detail = "";
    }

    // ---------------- 生命周期（拦截器使用） ----------------

    /**
     * 开始一次审计上下文。
     *
     * <p>若当前线程已有未清理的上下文（理论上不该发生：上次请求的 afterCompletion 一定会清理），
     * <b>覆盖而不是叠加</b> —— 叠加会让上一条记录的内容泄漏到这一条里，
     * 那会让审计记录张冠李戴，比丢一条更糟。
     */
    public static AuditTrail start(AuditAction action) {
        AuditTrail trail = new AuditTrail(action);
        HOLDER.set(trail);
        return trail;
    }

    /** 当前上下文；没有则返回 {@code null}（调用方自行决定，公开方法都不依赖它）。 */
    public static AuditTrail current() {
        return HOLDER.get();
    }

    /** 结束并清理。**必须在 finally 里调用**，否则线程复用会把上下文带到下一个请求。 */
    public static void clear() {
        HOLDER.remove();
    }

    // ---------------- 业务侧补充入口（无上下文时静默跳过） ----------------

    /**
     * 记录目标对象。
     *
     * <p>{@code id <= 0} 视为「无具体目标」，忽略 —— 平台级动作（全站索引重建、
     * 资质巡检）本就没有单一目标，此时 {@code targetId} 保持 0，
     * 由数据库列语义解释为「平台级」，不需要业务代码显式写 0。
     */
    public static void target(long id) {
        AuditTrail trail = HOLDER.get();
        if (trail != null && id > 0) {
            trail.targetId = id;
        }
    }

    /** 记录目标对象并覆盖默认目标类型（例如同一个动作可能作用在店铺或成员上）。 */
    public static void target(long id, String type) {
        AuditTrail trail = HOLDER.get();
        if (trail != null && id > 0) {
            trail.targetId = id;
            if (type != null && !type.isBlank()) {
                trail.targetType = type;
            }
        }
    }

    /**
     * 记录所属租户（店铺主体 userId）。
     *
     * <p>{@code id <= 0} 表示平台级 / 自营，忽略写入（列默认即 0）。
     * <b>自营不要传 0 以外的东西</b> —— 自营的 {@code seller_id} 恒为 0，
     * 若这里塞了「当前运营的 userId」，审计页上就会显示成「这个运营自己的店」。
     */
    public static void tenant(long id) {
        AuditTrail trail = HOLDER.get();
        if (trail != null && id > 0) {
            trail.tenantId = id;
        }
    }

    /**
     * 记录变更前后值（机读口径）。
     *
     * <p>同时把人读摘要 {@code detail} 补成 {@code before -> after}（仅当 detail 还空着）——
     * 这样业务代码只调一次就同时满足了「机读比对」与「人读摘要」两个需求，
     * 不必两处都写、也就不用担心两处写得不一致。
     */
    public static void change(String before, String after) {
        AuditTrail trail = HOLDER.get();
        if (trail == null) {
            return;
        }
        trail.beforeValue = before;
        trail.afterValue = after;
        if (trail.detail == null || trail.detail.isBlank()) {
            trail.detail = text(before) + " -> " + text(after);
        }
    }

    /** 追加一句人读说明（多次调用按 {@code ; } 串接）。 */
    public static void note(String text) {
        AuditTrail trail = HOLDER.get();
        if (trail == null || text == null || text.isBlank()) {
            return;
        }
        trail.detail = (trail.detail == null || trail.detail.isBlank())
                ? text : trail.detail + "; " + text;
    }

    private static String text(String value) {
        return value == null ? "(空)" : value;
    }

    /**
     * 声明「这次请求成功了，但没有产生任何事实变化」，因此不留痕。
     *
     * <p><b>★ 这是一个刻意保留的、唯一的抑制口，用途被限制得非常窄</b>：
     * 只用于「幂等确认」—— 调用了接口、接口正常返回，但目标状态本来就是它要变成的样子
     * （例如把一个已经是 OPERATOR 的账号再设成 OPERATOR）。
     *
     * <p><b>为什么需要它</b>：这类调用不构成事件。每天定时任务、前端重试、运营手滑重复点，
     * 都会产生它；写进审计表只会稀释信噪比，让真正的变更淹没在「无变化的确认」里。
     *
     * <p><b>为什么它是危险的口子，以及如何限制</b>：任何抑制机制都可能被用来藏事。
     * 因此这里只做两件事：① 只在 `AuditTrail` 有活动上下文时生效；
     * ② 抑制**不会静默** —— 拦截器会打一条 DEBUG 日志（打开 DEBUG 就能看到
     * 「某个动作用了抑制」）。当前全项目只有一处使用它
     * （{@code AdminUserService.changeRole} 的幂等分支），
     * 且该分支的判据是「目标已是该角色」这种可被独立复核的事实。
     * 若将来要新增使用点，先问一句：**这件事真的「什么都没发生」吗？**
     *
     * @param reason 抑制原因（写进 DEBUG 日志，便于事后复核）
     */
    public static void skip(String reason) {
        AuditTrail trail = HOLDER.get();
        if (trail != null) {
            trail.skipped = true;
            trail.skipReason = reason;
        }
    }

    // ---------------- 读取（拦截器组装记录时使用） ----------------

    /** 是否已被显式抑制（见 {@link #skip(String)}）。 */
    public boolean skipped() {
        return skipped;
    }

    /** 抑制原因（仅用于日志）。 */
    public String skipReason() {
        return skipReason;
    }

    public AuditAction action() {
        return action;
    }

    public long targetId() {
        return targetId;
    }

    public String targetType() {
        return targetType;
    }

    public long tenantId() {
        return tenantId;
    }

    public String beforeValue() {
        return beforeValue;
    }

    public String afterValue() {
        return afterValue;
    }

    public String detail() {
        return detail;
    }
}

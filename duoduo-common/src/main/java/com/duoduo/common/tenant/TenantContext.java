package com.duoduo.common.tenant;

import com.duoduo.common.seller.SellerOwnership;

import java.util.Optional;

/**
 * 当前请求的**租户上下文**（E20 租户隔离硬化）。
 *
 * <h2>租户是谁（口径唯一来源）</h2>
 * 租户键 = **店铺主体 userId**（= {@code t_merchant.owner_user_id}），
 * 与 {@code t_spu.seller_id} / {@code t_order.seller_id} 等租户列**同一口径**。
 * <b>不是 {@code t_merchant.id}</b> —— 这两者都是雪花 id、都是 bigint，
 * 写错了不会报任何错，只会表现为"过滤条件过滤掉了不该过滤的东西"或"什么都没过滤掉"。
 * 实测口径（2026-10-10 抽样 8 条非 0 的 {@code t_audit_log.tenant_id}）：
 * 命中 {@code t_user.id} 8/8、命中 {@code t_merchant.id} 0/8。
 *
 * <p>平台自营的租户标识是 {@link SellerOwnership#SELF_SELLER_ID}（= 0）——
 * 它是一个**有效租户**，不是"没有租户"。
 *
 * <h2>为什么用 {@code Optional} 而不是"null 当未设置"</h2>
 * 因为 **0 是有效值**。若用 {@code ThreadLocal<Long>} + 「null 表示未设置」，
 * 自营（0）与未设置（null）还算能分开；但只要有人图省事写成
 * {@code long tenantId = 0} 当默认值，两者就再也分不开 —— 这正是本项目反复出现的
 * "缺省值被当成真实值"陷阱（参见 {@code SellerOwnership} 的类注释）。
 * 用 {@code Optional} 把"未设置"变成**类型上的真实状态**，调用方无法用 0 冒充。
 *
 * <h2>未设置意味着什么</h2>
 * <b>不追加任何租户条件</b>，而不是"拒绝访问"。理由：本上下文是**纵深防御的第二层**，
 * 不是唯一防线（第一层是各服务自己的归属校验 + 网关入口 + 角色权限）。
 * 未设置的出现场景全部是合理的：平台运营/管理员跨租户巡查、
 * 服务间内部调用（{@code /internal/**}）、定时任务、老令牌（没有 {@code tid} 声明）、
 * 商城端买家请求（买家不属于任何店铺）。
 * 若在这里选择"拒绝"，等于给上述所有正常链路加一道 500/403，
 * 而收益是零 —— 攻击者伪造不出网关写的头。
 *
 * <h2>红线</h2>
 * <ul>
 *   <li><b>只能由网关写入的值来决定</b>：请求头 {@link TenantTables#HEADER} 由网关
 *       先剥离同名字段再写入（与 {@code X-User-Id} 同一姿势）。
 *       服务端**绝不**读客户端查询参数 / 请求体里的租户字段。</li>
 *   <li><b>必须清理</b>：Servlet 容器线程是复用的，忘记清理等于把上一个请求的租户
 *       带进下一个请求。{@code TenantContextInterceptor} 在 {@code preHandle} 先清、
 *       {@code afterCompletion} 再清，两处都要（只清一次会在异常路径漏掉）。</li>
 * </ul>
 *
 * <p>本类<b>只有读写能力，不含判定</b>：要不要过滤、按哪一列过滤，由
 * {@link TenantTables} 与数据层拦截器决定。口径集中在本类，是为了保证
 * "设置的地方"和"消费的地方"永远说的是同一件事。
 */
public final class TenantContext {

    /**
     * {@code null} = 未设置（不追加条件）；非 null（含 0）= 有效租户。
     *
     * <p>刻意用 {@code ThreadLocal<Long>} 而不是 {@code ThreadLocal<Optional<Long>>}：
     * {@code ThreadLocal} 的 {@code remove()} 才是释放语义，包一层 Optional
     * 会让人误以为"set(Optional.empty())"也是一种清理 —— 那样 ThreadLocal 里的
     * 引用仍然挂着，在线程池里会一直泄漏下去。对外仍然只暴露 {@link Optional}。
     */
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 设置当前租户。传 {@code null} 等价于 {@link #clear()}（把"未设置"写成一句话）。
     */
    public static void set(Long tenantId) {
        if (tenantId == null) {
            clear();
            return;
        }
        CURRENT.set(tenantId);
    }

    /** 清理。**必须在请求结束时调用**（见类注释的"红线"）。 */
    public static void clear() {
        CURRENT.remove();
    }

    /** 当前租户；空表示"本请求未声明租户"（不是"没有权限"）。 */
    public static Optional<Long> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** 是否已声明租户。数据层拦截器用它决定"要不要追加条件"。 */
    public static boolean isPresent() {
        return CURRENT.get() != null;
    }
}

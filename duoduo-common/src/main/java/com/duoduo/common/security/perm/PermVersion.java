package com.duoduo.common.security.perm;

import java.time.Duration;

/**
 * E17 · 权限版本（permVersion）的**共享口径**：键名、存活时间、解析规则。
 *
 * <h2>它解决什么问题</h2>
 * 访问令牌里带的是「签发那一刻的角色快照」。改完库里的角色之后，
 * 对方手上的旧令牌**仍然写着旧角色**，直到自然过期（15 分钟）才收敛。
 * 升级场景下这是「不方便」，<b>降级场景下这是「权限收不回来」</b> ——
 * 一个刚被撤销运营权限的账号，还能继续以更高角色调后台接口。
 *
 * <p>做法：令牌里多带一个数字 {@code pv}（权限版本）。任何权限相关的变更
 * 都让该用户的版本号 +1，签发令牌时把当前版本写进去，校验时比对。
 * 不一致即说明「令牌是旧权限的快照」。
 *
 * <h2>权威值在库里，Redis 只是"感知副本"</h2>
 * 落库的是 {@code t_user.perm_version}（权威、可审计、重启不丢）；
 * 但校验发生在<b>网关上</b>，而网关不连业务库 —— 所以签发/变更时把当前版本
 * 复制一份到 Redis，网关读它做比对。
 *
 * <p>由此产生一条必须写明的取舍：<b>Redis 里查不到版本时放行，不放拒绝</b>。
 * 理由是失败方向 —— Redis 不可用 / 键自然过期这类情况随时会发生，
 * 若一律拒绝，一次缓存抖动就等于<b>全站已登录用户集体掉线</b>；
 * 而放行的后果只是「退回 E17 之前的行为」（旧令牌最多再多活 15 分钟）。
 * 安全边界因此是「Redis 可用时精确、不可用时退化为旧行为」，而不是「不可用时更危险」。
 *
 * <h2>为什么键名与 TTL 放在 common 而不是各写一份</h2>
 * 写的一方在用户服务、读的一方在网关。键名写错、TTL 给短，两种情况下
 * <b>都不会报错</b>，只会表现为「权限变更没有即时生效」——正是本机制要解决的问题本身，
 * 却比不引入它更难排查。所以键与解析规则只允许有一处定义。
 */
public final class PermVersion {

    /** Redis 键前缀：{@code duoduo:perm:ver:{userId}} */
    public static final String KEY_PREFIX = "duoduo:perm:ver:";

    /**
     * 感知副本的存活时间。
     *
     * <p>取得与刷新令牌同量级（7 天）：副本的唯一作用是「让下一个请求能比对上」，
     * 它过期得越早，越容易出现「令牌带 pv 而 Redis 无记录 → 放行」的窗口。
     * 反过来，过期不清理也无害 —— 用户重新登录/刷新时会按库里的最新值覆盖写回。
     */
    public static final Duration TTL = Duration.ofDays(7);

    /** 初始版本（新建账号 / DDL 默认值）。刻意用 0 而不是 1：与 {@code DEFAULT 0} 对齐，避免两套起点 */
    public static final long INITIAL = 0L;

    private PermVersion() {
    }

    /**
     * 该用户在 Redis 中的版本键。
     *
     * @param userId 用户 id；为 null 时返回 null（调用方应据此跳过读写，而不是拼出一个 "null" 键）
     */
    public static String key(Long userId) {
        return userId == null ? null : KEY_PREFIX + userId;
    }

    /** 库内版本 → Redis 值 / 令牌声明值 */
    public static String format(Long version) {
        return version == null ? null : Long.toString(version);
    }

    /**
     * Redis 值 → 版本号。
     *
     * <p>非数字、空串一律返回 {@code null}（= 视为"未知"→ 放行），不抛异常：
     * 这个值来自缓存，被人手工改过、或残留了别的格式都不该让鉴权链路崩掉。
     */
    public static Long parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 库内取值 → 非空版本号：老数据（NULL）按 {@link #INITIAL} 处理，避免每个调用点各写一次判空 */
    public static long orInitial(Long version) {
        return version == null ? INITIAL : version;
    }
}

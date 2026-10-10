package com.duoduo.common.tenant;

/**
 * 租户级 Redis 键的**唯一拼接口径**（E20）。
 *
 * <h2>本轮的实际结论：现有键一个都不用改</h2>
 * 盘点全项目（2026-10-10，grep 全仓 `duoduo:*` 与 `KEY_*` 常量）一共只有 4 个键，**没有一个是租户级缓存**：
 * <pre>
 *   duoduo:perm:ver:{userId}            用户级 —— 权限版本副本（E17）
 *   duoduo:ai:rl:{minute}:{key}         IP 级  —— AI 导购限流（第五十七轮）
 *   seckill:stock:{activityId}:{skuId}  活动级 —— 秒杀库存
 *   seckill:bought:{activityId}:{skuId} 活动级 —— 秒杀已购
 * </pre>
 * 所以本轮"Redis 键带 {@code tenant:{id}}"落地为**收口**而不是**改名**：
 * 既有的键语义本来就是正确的（秒杀是平台自营活动、限流按 IP、权限版本按用户），
 * 为了"看起来做了"而给它们硬塞一个租户维度，只会让键的含义变模糊、还得清一次缓存。
 * 真正的风险在**将来**：某天有人加了「店铺概览缓存」而忘了把租户写进键里，
 * 表现是 A 店看到 B 店的数据、接口 200、日志干净 —— 这类错在本项目已经出现过很多次，
 * 共同点是**没有一个地方强制你写对**。
 *
 * <h2>所以要的是这个类：把"必须带租户"变成无法绕过</h2>
 * 凡是租户级缓存，一律 {@link #key(Long, String)} 拼键；它**拒绝 null**，
 * 于是"忘了传租户"从"悄悄拼出一个所有人共用的键"变成一次启动/调用期就炸的异常。
 * 这与 {@link com.duoduo.common.security.perm.PermVersion#key(Long)} 是同一条纪律：
 * 拼出 {@code duoduo:perm:ver:null} 比返回 null 危险得多 —— 它会成为一个**跨用户共享的假键**。
 */
public final class TenantKeys {

    /** 全部租户级键的公共前缀。 */
    public static final String PREFIX = "duoduo:tenant:";

    private TenantKeys() {
    }

    /**
     * 拼一个租户级键：{@code duoduo:tenant:{tenantId}:{suffix}}。
     *
     * @param tenantId 租户标识（店铺主体 userId；0 = 平台自营，**是有效值**）
     * @param suffix   业务后缀，例如 {@code "summary:spu-count"}
     * @throws IllegalArgumentException 租户标识或后缀为空 —— 宁可炸，也不要拼出一个跨租户共享的键
     */
    public static String key(Long tenantId, String suffix) {
        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "租户级键必须带租户标识：拼成 " + PREFIX + "null:… 会成为一个所有租户共用的假键");
        }
        if (tenantId < 0) {
            throw new IllegalArgumentException("租户标识不能为负数：" + tenantId);
        }
        if (suffix == null || suffix.isBlank()) {
            throw new IllegalArgumentException("租户级键必须带业务后缀，否则不同用途会撞同一个键");
        }
        return PREFIX + tenantId + ':' + suffix;
    }

    /** 该键是否是租户级键（供巡检 / 断言用）。 */
    public static boolean isTenantScoped(String key) {
        return key != null && key.startsWith(PREFIX);
    }
}

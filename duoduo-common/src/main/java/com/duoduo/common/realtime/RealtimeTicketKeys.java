package com.duoduo.common.realtime;

/**
 * WebSocket 票据的共享约定（2026-10-23）。
 *
 * <p>票据存在 Redis 里，**网关与社交域都要读它**：
 * 网关在建连前做一次存在性校验（无效票据当场 401，不让升级发生），
 * 社交域在握手时消费（一次性，取走即删）。键名只有一份定义，避免两边写歪后
 * 出现"网关放行、社交域拒绝"的 1006 异常 —— 那种现象极难定位。
 *
 * <p>这里只放常量，不引 Redis 依赖：网关与社交域各自已有自己的 Redis 客户端。
 */
public final class RealtimeTicketKeys {

    /** 票据键前缀；完整键为 {@code duoduo:ws:ticket:<uuid>} */
    public static final String PREFIX = "duoduo:ws:ticket:";

    private RealtimeTicketKeys() {
    }
}

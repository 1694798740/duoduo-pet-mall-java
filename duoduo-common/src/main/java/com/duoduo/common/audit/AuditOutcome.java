package com.duoduo.common.audit;

/**
 * 审计结果（E18）。
 *
 * <p>为什么要记「失败」：审计的一半价值在<b>「谁试过但没成功」</b>。
 * 一次成功的角色变更说明流程正常；而<b>连续 20 次失败的提权尝试</b>才是有意义的信号 ——
 * 只记成功的审计表看不见后者。
 *
 * <p>失败不等于异常：被权限拦截（403）也是失败，且它恰恰是最需要留痕的一类。
 * 因此审计拦截器注册在权限拦截器**之前**，让「越权被拒」也能落到表里。
 */
public enum AuditOutcome {

    /** 执行成功。 */
    SUCCESS,

    /** 执行失败（业务拒绝 / 越权拦截 / 异常）。 */
    FAILED;

    /** 宽容解析：只认字面量，其余一律 FAILED（读不懂的历史记录不能当成「成功」）。 */
    public static AuditOutcome parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FAILED;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return FAILED;
        }
    }
}

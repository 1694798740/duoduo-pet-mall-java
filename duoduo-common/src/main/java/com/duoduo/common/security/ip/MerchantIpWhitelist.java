package com.duoduo.common.security.ip;

import java.util.ArrayList;
import java.util.List;

/**
 * 商家 IP 白名单的 Redis 副本格式（网关读、用户域写，**键名与格式只允许在这里定义**）。
 *
 * <h2>为什么是 Redis 副本而不是让网关查库</h2>
 * 权威值在 {@code duoduo_user.t_merchant}（可审计、重启不丢、与变更同事务），
 * 但执行发生在网关上，而网关不连业务库 —— 每个商家端请求去查一次库会把网关绑死在用户库上。
 * 于是变更时把当前规则复制一份到 Redis，网关读它。这与 E17 权限版本是同一套做法。
 *
 * <h2>键的存在与否就是「是否启用」</h2>
 * <ul>
 *   <li>键<b>不存在</b> → 未启用（或从没配过）→ 放行；</li>
 *   <li>键<b>存在且为空串</b> → 已启用但一条规则都没有 → <b>拒绝</b>（失败关闭）。</li>
 * </ul>
 * 把「启用但空名单」也当放行，会让「配了白名单但漏填」变成一次静默失效 ——
 * 安全配置的失效方向必须指向拒绝（与平台后台白名单同一条纪律）。
 *
 * <p>Redis 不可用 / 读超时时网关按「未知」放行：那是可用性故障，不是配置错误，
 * 不该让全站商家一起进不去（取舍见网关 {@code RedisMerchantIpWhitelistChecker}）。
 */
public final class MerchantIpWhitelist {

    /** 键前缀：{@code duoduo:merchant:ipwl:{merchantId}} */
    public static final String KEY_PREFIX = "duoduo:merchant:ipwl:";

    private MerchantIpWhitelist() {
    }

    /** 商家维度的键；merchantId 为空时返回 null（调用方据此跳过） */
    public static String key(Long merchantId) {
        return merchantId == null ? null : KEY_PREFIX + merchantId;
    }

    /** 规则列表 → Redis 值（逗号分隔，去空白、去空项） */
    public static String format(List<String> rules) {
        if (rules == null || rules.isEmpty()) {
            return "";
        }
        List<String> cleaned = new ArrayList<>(rules.size());
        for (String rule : rules) {
            if (rule != null && !rule.isBlank()) {
                cleaned.add(rule.trim());
            }
        }
        return String.join(",", cleaned);
    }

    /** Redis 值 → 规则列表（空值返回空列表，不是 null，避免调用方 NPE 分支） */
    public static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> rules = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                rules.add(part.trim());
            }
        }
        return rules;
    }
}

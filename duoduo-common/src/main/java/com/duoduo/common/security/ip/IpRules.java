package com.duoduo.common.security.ip;

import java.util.List;

/**
 * IP 规则匹配（网关执行、用户域「保存前自检」共用同一份实现）。
 *
 * <p>支持三种写法（与 E21 平台后台白名单完全一致，运营不需要学第二套语法）：
 * <ul>
 *   <li><b>精确地址</b>：{@code 203.0.113.7}（IPv6 字面量也按字符串精确比对）；</li>
 *   <li><b>IPv4 CIDR</b>：{@code 10.0.0.0/8}、{@code 192.168.1.0/24}（位运算比对）；</li>
 *   <li><b>前缀通配</b>：{@code 192.168.1.*}（按字符串前缀比对，仅供临时配置）。</li>
 * </ul>
 *
 * <p><b>刻意不用 {@code InetAddress.getByName}</b>：那个方法对"不是 IP 的字符串"会去做 DNS 解析。
 * 在认证链路上引入计划外的网络查询有两个坏处：延迟受外部 DNS 影响，
 * 且一个被污染的配置值能把网关变成 DNS 探测跳板。这里只认字面量 ——
 * IPv4 按四段数字解析，其余形态一律退化为字符串精确比对。
 *
 * <p>为什么放在 {@code duoduo-common}：保存白名单时的「当前 IP 是否还在名单里」自检由用户域做，
 * 执行拦截由网关做。两边各写一份匹配逻辑，迟早出现「自检说没锁死、实际进不去」——
 * 那正是这个功能最容易伤到人的地方，因此规则解析只允许有一个实现。
 */
public final class IpRules {

    private IpRules() {
    }

    /** 单条规则与地址是否匹配；规则或地址为空一律不匹配 */
    public static boolean matches(String rule, String ip) {
        if (rule == null || rule.isBlank() || ip == null || ip.isBlank()) {
            return false;
        }
        String pattern = rule.trim();
        String addr = ip.trim();
        if (pattern.equals(addr)) {
            return true;
        }
        if (pattern.endsWith("*")) {
            return addr.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        int slash = pattern.indexOf('/');
        if (slash < 0) {
            return false;
        }
        return matchesIpv4Cidr(pattern.substring(0, slash), pattern.substring(slash + 1), addr);
    }

    /** 名单中任意一条命中即视为允许 */
    public static boolean matchesAny(List<String> rules, String ip) {
        if (rules == null || rules.isEmpty()) {
            return false;
        }
        for (String rule : rules) {
            if (matches(rule, ip)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 规则形状是否可解析（保存时校验用）。
     *
     * <p>只做形状检查，不判断「这条规则能匹配到任何地址」—— 那需要枚举地址空间。
     * 目的是拦住明显写错的值（如把中文、网址、空串当规则），而不是替运营兜所有逻辑错。
     */
    public static boolean isWellFormed(String rule) {
        if (rule == null || rule.isBlank() || rule.length() > 64) {
            return false;
        }
        String value = rule.trim();
        if (value.endsWith("*")) {
            // 只允许「IPv4 前缀 + *」，例如 192.168.1.*；不允许裸 * （等于放行全部，白名单失去意义）
            String prefix = value.substring(0, value.length() - 1);
            return prefix.matches("\\d{1,3}(\\.\\d{1,3}){0,2}\\.");
        }
        if (value.contains("/")) {
            int slash = value.indexOf('/');
            String network = value.substring(0, slash);
            String prefix = value.substring(slash + 1);
            if (!prefix.matches("\\d{1,2}")) {
                return false;
            }
            int bits;
            try {
                bits = Integer.parseInt(prefix);
            } catch (NumberFormatException ex) {
                return false;
            }
            return bits >= 0 && bits <= 32 && ipv4Bytes(network) != null;
        }
        if (value.equals("localhost")) {
            return false;
        }
        // 精确地址：IPv4 必须是合法四段；其他形态（IPv6）只做字符集与冒号检查
        if (ipv4Bytes(value) != null) {
            return true;
        }
        return value.matches("[0-9a-fA-F:]+") && value.contains(":");
    }

    private static boolean matchesIpv4Cidr(String network, String prefixRaw, String ip) {
        byte[] net = ipv4Bytes(network);
        byte[] addr = ipv4Bytes(ip);
        if (net == null || addr == null) {
            return false;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(prefixRaw.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        if (prefix < 0 || prefix > 32) {
            return false;
        }
        int fullBytes = prefix / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (net[i] != addr[i]) {
                return false;
            }
        }
        int remainingBits = prefix % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (net[fullBytes] & mask) == (addr[fullBytes] & mask);
    }

    /** 只解析 IPv4 字面量（四段、每段 0-255）；不是这个形状就返回 null，绝不触发 DNS 查询 */
    private static byte[] ipv4Bytes(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty()) {
                return null;
            }
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return null;
                }
                out[i] = (byte) v;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return out;
    }
}

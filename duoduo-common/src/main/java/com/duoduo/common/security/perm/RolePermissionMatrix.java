package com.duoduo.common.security.perm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 角色 → 权限点 的唯一权威矩阵（E16 · RBAC 的「表」）。
 *
 * <p><b>本类是「谁能做什么」的唯一答案</b>。任何判权处都只应问
 * {@link #has(String, String)}（或经 {@code LoginUser.hasPerm / OperatorGuard.requirePerm}），
 * 不得再写 {@code "OPERATOR".equals(role)} 这类裸比较 —— 那正是 E16 要消灭的东西：
 * 同类判定散落在 6 个模块的 20+ 处，口径迟早分叉。
 *
 * <p><b>★ 与 E16 之前的判定逐条等价（这是硬约束）</b>：
 * <ul>
 *   <li>{@code platform:console} ←→ 旧 {@code LoginUser.isOperator()}（OPERATOR / ADMIN）</li>
 *   <li>{@code seller:console} ←→ 旧 {@code LoginUser.canAccessSellerConsole()}
 *       （SELLER 本人 + OPERATOR/ADMIN 代运营）</li>
 *   <li>{@code user:role:manage} ←→ 旧 {@code AdminUserService.changeRole} 的
 *       {@code actor.isAdmin()} 门（仅 ADMIN）</li>
 * </ul>
 * 引入权限点体系**不得**让任何人多出或少掉一项能力；只允许把口径收到一处。
 * {@code RolePermissionMatrixTest} 用「矩阵 vs 旧布尔方法」的等价断言钉死这一点。
 *
 * <p><b>未知角色一律降级为最小权限</b>（等价于 USER）：库里若出现拼错的角色
 * （如 {@code "SELLLER"}），判权应答「无权限」而不是「按 ADMIN 处理」。
 * 这是一处刻意的不对称 —— 失败方向必须指向「拒绝」。
 *
 * <p><b>不缓存、不查库</b>：矩阵是编译期常量。读 DB 的角色映射会引入
 * 「库改了缓存没失效 → 越权或误拒」这一整类问题；当前 4 个系统角色是产品定义，
 * 不是运营可配置项，落在代码里比落在表里更安全。需要动态角色时再改（见 Permission 类注释）。
 */
public final class RolePermissionMatrix {

    /** 超级管理员：拥有全部权限点。 */
    public static final String ROLE_ADMIN = "ADMIN";

    /** 平台运营：除「改角色」与「资金」之外的运营能力。 */
    public static final String ROLE_OPERATOR = "OPERATOR";

    /** 商家（店主 / 卖家本人）。 */
    public static final String ROLE_SELLER = "SELLER";

    /** 普通用户（含 E13 的店员 —— 店员权限由成员身份驱动，不落在角色矩阵）。 */
    public static final String ROLE_USER = "USER";

    /** 已知角色（顺序固定，供测试遍历与诊断输出使用）。 */
    public static final Set<String> KNOWN_ROLES = knownRoles();

    private static final Map<String, Set<String>> MATRIX = build();

    private RolePermissionMatrix() {
    }

    private static Set<String> knownRoles() {
        Set<String> set = new LinkedHashSet<>();
        set.add(ROLE_ADMIN);
        set.add(ROLE_OPERATOR);
        set.add(ROLE_SELLER);
        set.add(ROLE_USER);
        return Collections.unmodifiableSet(set);
    }

    private static Map<String, Set<String>> build() {
        Map<String, Set<String>> map = new LinkedHashMap<>();

        // ADMIN：全部权限点（直接引用 Permission.ALL，避免"加了权限点忘了给 ADMIN"）
        map.put(ROLE_ADMIN, Permission.ALL);

        // OPERATOR：全部减去「改角色」与「资金」—— 这两项是 E16 之前
        // OPERATOR 唯一被明确排除的能力（changeRole 仅 ADMIN；资金链路此前无运营入口）
        Set<String> operator = new LinkedHashSet<>(Permission.ALL);
        operator.remove(Permission.USER_ROLE_MANAGE);
        operator.remove(Permission.FINANCE_SETTLEMENT);
        map.put(ROLE_OPERATOR, Collections.unmodifiableSet(operator));

        // SELLER：只能进商家端
        map.put(ROLE_SELLER, Set.of(Permission.SELLER_CONSOLE));

        // USER：无平台 / 商家权限
        map.put(ROLE_USER, Set.of());

        return Collections.unmodifiableMap(map);
    }

    /**
     * 角色归一化：去空格 → 大写 → 未知值折叠为 {@code USER}。
     *
     * <p>E16 之前各处的写法是 {@code equalsIgnoreCase}（LoginUser）与
     * {@code toUpperCase()}（AdminUserService / MerchantOnboardingService）混用，
     * 归一化只做一次，把这种「同一件事两种写法」消掉。
     */
    public static String canonical(String rawRole) {
        if (rawRole == null || rawRole.isBlank()) {
            return ROLE_USER;
        }
        String upper = rawRole.trim().toUpperCase();
        return MATRIX.containsKey(upper) ? upper : ROLE_USER;
    }

    /**
     * 某角色拥有的全部权限点。
     *
     * <p>返回的是**不可变集合**；调用方只应做 {@code contains}，不要 {@code get} 类操作
     * （E29 的教训：不可变容器在 null 入参上的行为与直觉不同）。
     * 未知角色返回空集而非 null，调用方无需判空。
     */
    public static Set<String> of(String rawRole) {
        Set<String> set = MATRIX.get(canonical(rawRole));
        return set == null ? Set.of() : set;
    }

    /**
     * 判权：该角色是否拥有该权限点。
     *
     * <p>{@code perm} 为 null / 空白时返回 false（不是抛异常）——
     * 判权函数在异常路径上必须给出「拒绝」，且调用方可能来自注解反射，拿不到值也不该 500。
     */
    public static boolean has(String rawRole, String perm) {
        if (perm == null || perm.isBlank()) {
            return false;
        }
        return of(rawRole).contains(perm);
    }

    /** 每个角色拥有的权限点数量（诊断 / 测试用，不参与判权）。 */
    public static int sizeOf(String rawRole) {
        return of(rawRole).size();
    }
}

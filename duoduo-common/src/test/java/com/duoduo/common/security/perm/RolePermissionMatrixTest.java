package com.duoduo.common.security.perm;

import com.duoduo.common.context.LoginUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RBAC 权限矩阵单测（E16）。
 *
 * <h2>这组测试守的是什么</h2>
 * E16 的改动是「把散落在 6 个模块的判权口径收进一张矩阵」。这类重构有两个
 * 典型失效方式，都**不会报错**：
 *
 * <ol>
 *   <li><b>行为漂移</b>：收口过程中某人多了或少了一项能力。表现可能是「某运营端点
 *       突然 403」（被投诉才发现），也可能是「某个高危端点突然谁都能调」（更糟，
 *       而且没有任何现象）。因此第一条硬约束是
 *       {@link #legacy_boolean_methods_agree_with_matrix()}：
 *       <b>矩阵必须与 E16 之前的布尔方法逐角色等价</b>，多一项少一项都算回归。</li>
 *   <li><b>清单漏登记</b>：新增一个权限点常量，却忘了给它分配角色 / 忘了加进
 *       {@link Permission#ALL}。结果是这个点永远没人有 —— 或者更隐蔽地，
 *       「ADMIN 拥有全部」的实现因引用 ALL 而漏掉它。
 *       {@link #permission_all_covers_every_declared_constant()} 用反射
 *       把常量和清单钉在一起。</li>
 * </ol>
 *
 * <p>第二条真值表（{@link #EXPECTED_HOLDERS}）刻意**独立于实现**书写 ——
 * 不复用 {@code Permission.ALL} 或矩阵的任何内部结构。否则「实现改了、表跟着改」
 * 就成了同义反复，测试看不出任何东西。改权限必须手工改这张表，这是设计意图：
 * 它强迫改动者想清楚「这一项到底该给谁」。
 */
class RolePermissionMatrixTest {

    /**
     * 权限点 → 应该拥有它的角色（真值表，独立于实现书写）。
     *
     * <p>每加一个权限点，这里必须同步加一行；少加一行会被
     * {@link #truth_table_covers_exactly_the_declared_permissions()} 抓出来。
     */
    private static final Map<String, Set<String>> EXPECTED_HOLDERS = new LinkedHashMap<>();

    static {
        // 平台后台与用户管理
        EXPECTED_HOLDERS.put(Permission.PLATFORM_CONSOLE, Set.of("ADMIN", "OPERATOR"));
        EXPECTED_HOLDERS.put(Permission.USER_ROLE_MANAGE, Set.of("ADMIN"));
        // E21 · 重置他人两步验证：**仅 ADMIN**。它与「改角色」同属账号级高危动作，
        // 但方向相反 —— 改角色是"给权限"，重置 2FA 是"替别人摘掉第二把锁"。
        // 写在这里是一句显式的决定：运营**不**拥有它。
        EXPECTED_HOLDERS.put(Permission.USER_SECURITY_MANAGE, Set.of("ADMIN"));
        // 商家
        EXPECTED_HOLDERS.put(Permission.MERCHANT_AUDIT, Set.of("ADMIN", "OPERATOR"));
        EXPECTED_HOLDERS.put(Permission.MERCHANT_MATERIAL_READ, Set.of("ADMIN", "OPERATOR"));
        EXPECTED_HOLDERS.put(Permission.MERCHANT_STAFF_DISPOSE, Set.of("ADMIN", "OPERATOR"));
        EXPECTED_HOLDERS.put(Permission.MERCHANT_LICENSE_SWEEP, Set.of("ADMIN", "OPERATOR"));
        // 商品
        EXPECTED_HOLDERS.put(Permission.PRODUCT_AUDIT, Set.of("ADMIN", "OPERATOR"));
        EXPECTED_HOLDERS.put(Permission.PRODUCT_CATALOG_MANAGE, Set.of("ADMIN", "OPERATOR"));
        EXPECTED_HOLDERS.put(Permission.PRODUCT_SEARCH_REINDEX, Set.of("ADMIN", "OPERATOR"));
        EXPECTED_HOLDERS.put(Permission.PRODUCT_LISTING_ADMIN, Set.of("ADMIN", "OPERATOR"));
        EXPECTED_HOLDERS.put(Permission.AUDIT_SLA, Set.of("ADMIN", "OPERATOR"));
        // 交易与履约
        EXPECTED_HOLDERS.put(Permission.ORDER_OPS, Set.of("ADMIN", "OPERATOR"));
        EXPECTED_HOLDERS.put(Permission.ORDER_SECKILL_MANAGE, Set.of("ADMIN", "OPERATOR"));
        EXPECTED_HOLDERS.put(Permission.ORDER_MQ_MANAGE, Set.of("ADMIN", "OPERATOR"));
        EXPECTED_HOLDERS.put(Permission.AFTERSALE_ARBITRATE, Set.of("ADMIN", "OPERATOR"));
        EXPECTED_HOLDERS.put(Permission.COUPON_TEMPLATE_MANAGE, Set.of("ADMIN", "OPERATOR"));
        // 审计（E18）：运营也能看 —— 运营是后台操作的主体，看记录不改变任何事实；
        // 真正的边界是「审计记录不可删改」（任何角色都没有修改入口）
        EXPECTED_HOLDERS.put(Permission.AUDIT_LOG_READ, Set.of("ADMIN", "OPERATOR"));
        // 财务（E22 落地，当前仅 ADMIN）
        EXPECTED_HOLDERS.put(Permission.FINANCE_SETTLEMENT, Set.of("ADMIN"));
        // 商家端
        EXPECTED_HOLDERS.put(Permission.SELLER_CONSOLE, Set.of("ADMIN", "OPERATOR", "SELLER"));
    }

    // ---------------- 1. 逐条矩阵 ----------------

    @Test
    @DisplayName("逐条矩阵：4 角色 × 20 权限点，每条都按真值表钉死")
    void every_role_permission_pair_matches_truth_table() {
        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        for (String role : RolePermissionMatrix.KNOWN_ROLES) {
            for (String perm : Permission.ALL) {
                checked++;
                boolean expected = EXPECTED_HOLDERS.getOrDefault(perm, Set.of()).contains(role);
                boolean actual = RolePermissionMatrix.has(role, perm);
                if (expected != actual) {
                    mismatches.add(role + " × " + perm + " 期望=" + expected + " 实际=" + actual);
                }
            }
        }
        // 断言条数是为了让「遍历没生效」这种假绿也失败（若 KNOWN_ROLES 变空，mismatches 也会为空）
        assertThat(checked).isEqualTo(RolePermissionMatrix.KNOWN_ROLES.size() * Permission.ALL.size());
        assertThat(checked).isGreaterThanOrEqualTo(72);
        assertThat(mismatches).as("权限矩阵与真值表不一致").isEmpty();
    }

    @Test
    @DisplayName("真值表与权限点清单一一对应（不多不少）")
    void truth_table_covers_exactly_the_declared_permissions() {
        assertThat(EXPECTED_HOLDERS.keySet())
                .as("新增权限点必须在真值表里登记，否则永远没人拥有它")
                .containsExactlyInAnyOrderElementsOf(Permission.ALL);
    }

    @Test
    @DisplayName("反射校验：Permission 里声明的每个常量都进了 ALL")
    void permission_all_covers_every_declared_constant() throws Exception {
        List<String> declared = new ArrayList<>();
        for (Field field : Permission.class.getDeclaredFields()) {
            int mod = field.getModifiers();
            if (Modifier.isStatic(mod) && Modifier.isPublic(mod) && field.getType() == String.class) {
                declared.add((String) field.get(null));
            }
        }
        assertThat(declared).as("权限点常量应当不止一个才好意思叫清单").hasSizeGreaterThan(10);
        assertThat(Permission.ALL)
                .as("新增常量忘了加进 Permission.ALL：ADMIN 会因此少一项能力")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    // ---------------- 2. 角色侧口径 ----------------

    @Test
    @DisplayName("ADMIN 拥有全部权限点")
    void admin_holds_everything() {
        assertThat(RolePermissionMatrix.of("ADMIN")).containsExactlyInAnyOrderElementsOf(Permission.ALL);
    }

    @Test
    @DisplayName("OPERATOR 恰好被排除三项：改角色、资金、重置他人两步验证")
    void operator_is_excluded_from_exactly_three_points() {
        Set<String> missing = new java.util.LinkedHashSet<>(Permission.ALL);
        missing.removeAll(RolePermissionMatrix.of("OPERATOR"));
        assertThat(missing)
                .as("被排除的必须是且仅是这三项 —— 多排除一项是运营干不了活，少排除一项是越权")
                .containsExactlyInAnyOrder(
                        Permission.USER_ROLE_MANAGE,
                        Permission.FINANCE_SETTLEMENT,
                        Permission.USER_SECURITY_MANAGE);
    }

    @Test
    @DisplayName("SELLER 只能进商家端，进不了平台后台")
    void seller_reaches_only_seller_console() {
        assertThat(RolePermissionMatrix.of("SELLER")).containsExactly(Permission.SELLER_CONSOLE);
        assertThat(RolePermissionMatrix.has("SELLER", Permission.PLATFORM_CONSOLE)).isFalse();
    }

    @Test
    @DisplayName("USER 无任何权限（店员的权限由成员身份驱动，不落在角色矩阵）")
    void user_has_no_privilege() {
        assertThat(RolePermissionMatrix.of("USER")).isEmpty();
        for (String perm : Permission.ALL) {
            assertThat(RolePermissionMatrix.has("USER", perm)).isFalse();
        }
    }

    // ---------------- 3. 归一化与未知角色 ----------------

    @Test
    @DisplayName("角色归一化：忽略大小写与空格，未知值降级为 USER")
    void canonical_normalizes_case_and_whitespace() {
        assertThat(RolePermissionMatrix.canonical("admin")).isEqualTo("ADMIN");
        assertThat(RolePermissionMatrix.canonical("  Admin ")).isEqualTo("ADMIN");
        assertThat(RolePermissionMatrix.canonical("oPeRaToR")).isEqualTo("OPERATOR");
        assertThat(RolePermissionMatrix.canonical("SELLER")).isEqualTo("SELLER");
        assertThat(RolePermissionMatrix.canonical(null)).isEqualTo("USER");
        assertThat(RolePermissionMatrix.canonical("")).isEqualTo("USER");
        assertThat(RolePermissionMatrix.canonical("   ")).isEqualTo("USER");
    }

    @Test
    @DisplayName("未知角色降级为最小权限（失败方向必须指向拒绝）")
    void unknown_role_degrades_to_user() {
        // 拼错的角色名绝不能按接近的名字处理（"SUPERADMIN" 不能变成 ADMIN）
        for (String bogus : List.of("SUPERADMIN", "ADMINISTRATOR", "SELLLER", "ROOT", "老板")) {
            assertThat(RolePermissionMatrix.of(bogus))
                    .as("未知角色 %s 必须无权限", bogus)
                    .isEmpty();
            assertThat(RolePermissionMatrix.canonical(bogus)).isEqualTo("USER");
        }
    }

    @Test
    @DisplayName("大小写不敏感：小写角色与库内大写存储等价")
    void role_lookup_is_case_insensitive() {
        assertThat(RolePermissionMatrix.has("operator", Permission.PLATFORM_CONSOLE)).isTrue();
        assertThat(RolePermissionMatrix.has("Admin", Permission.USER_ROLE_MANAGE)).isTrue();
        assertThat(RolePermissionMatrix.has("seller", Permission.SELLER_CONSOLE)).isTrue();
    }

    // ---------------- 4. 判权入参防御 ----------------

    @Test
    @DisplayName("权限点为 null / 空白 → 拒绝（不抛异常）")
    void null_or_blank_permission_is_denied() {
        assertThat(RolePermissionMatrix.has("ADMIN", null)).isFalse();
        assertThat(RolePermissionMatrix.has("ADMIN", "")).isFalse();
        assertThat(RolePermissionMatrix.has("ADMIN", "   ")).isFalse();
        // 未登记在权限点清单里的字符串同样拒绝
        assertThat(RolePermissionMatrix.has("ADMIN", "platform:consolex")).isFalse();
        assertThat(RolePermissionMatrix.has("ADMIN", "PLATFORM_CONSOLE")).isFalse();
    }

    @Test
    @DisplayName("of(null 角色) 返回空集而不是 null（调用方无需判空）")
    void of_null_role_returns_empty_set_not_null() {
        assertThat(RolePermissionMatrix.of(null)).isNotNull().isEmpty();
        assertThat(RolePermissionMatrix.sizeOf(null)).isZero();
    }

    // ---------------- 5. ★ 老角色兼容：与 E16 之前的布尔方法等价 ----------------

    @Test
    @DisplayName("★ 等价性：矩阵与 E16 之前的 isOperator / isAdmin / canAccessSellerConsole 逐角色一致")
    void legacy_boolean_methods_agree_with_matrix() {
        record Legacy(String role, boolean operator, boolean admin, boolean sellerConsole) {
        }
        // 期望值 = E16 之前的实现口径（改这三列就等于改变行为，必须同时改这条测试并说明理由）
        List<Legacy> cases = List.of(
                new Legacy("ADMIN", true, true, true),
                new Legacy("OPERATOR", true, false, true),
                new Legacy("SELLER", false, false, true),
                new Legacy("USER", false, false, false),
                // 小写 / 带空格：老实现用 equalsIgnoreCase，同样成立
                new Legacy("admin", true, true, true),
                new Legacy("Operator ", true, false, true)
        );
        for (Legacy c : cases) {
            LoginUser user = new LoginUser(1L, "13800000000", c.role(), true);
            assertThat(user.isOperator())
                    .as("isOperator(%s)", c.role()).isEqualTo(c.operator());
            assertThat(user.isAdmin())
                    .as("isAdmin(%s)", c.role()).isEqualTo(c.admin());
            assertThat(user.canAccessSellerConsole())
                    .as("canAccessSellerConsole(%s)", c.role()).isEqualTo(c.sellerConsole());

            // 与矩阵的直接对应关系（这两个布尔方法就是这两个权限点的别名）
            assertThat(user.isOperator())
                    .isEqualTo(RolePermissionMatrix.has(c.role(), Permission.PLATFORM_CONSOLE));
            assertThat(user.canAccessSellerConsole())
                    .isEqualTo(RolePermissionMatrix.has(c.role(), Permission.SELLER_CONSOLE));
        }
    }

    @Test
    @DisplayName("★ 等价性：changeRole 的门（user:role:manage）只对 ADMIN 开放")
    void role_manage_gate_matches_previous_isAdmin_check() {
        for (String role : RolePermissionMatrix.KNOWN_ROLES) {
            LoginUser user = new LoginUser(1L, "13800000000", role, true);
            assertThat(user.hasPerm(Permission.USER_ROLE_MANAGE))
                    .as("改角色权限的角色分布必须与旧的 actor.isAdmin() 完全一致")
                    .isEqualTo(user.isAdmin());
        }
    }

    @Test
    @DisplayName("hasPerm 走同一张矩阵（LoginUser 不回退到字符串比较）")
    void loginUser_hasPerm_delegates_to_matrix() {
        LoginUser operator = new LoginUser(1L, "13800000000", "OPERATOR", true);
        assertThat(operator.hasPerm(Permission.PLATFORM_CONSOLE)).isTrue();
        assertThat(operator.hasPerm(Permission.USER_ROLE_MANAGE)).isFalse();
        assertThat(operator.hasPerm(Permission.FINANCE_SETTLEMENT)).isFalse();
        // E21：重置他人两步验证同样只给 ADMIN —— 运营能进后台，但不能替别人关掉第二把锁
        assertThat(operator.hasPerm(Permission.USER_SECURITY_MANAGE)).isFalse();
        assertThat(operator.hasPerm("不存在的权限点")).isFalse();
        assertThat(operator.hasPerm(null)).isFalse();

        LoginUser admin = new LoginUser(2L, "13800000001", "ADMIN", true);
        assertThat(admin.hasPerm(Permission.USER_ROLE_MANAGE)).isTrue();
        assertThat(admin.hasPerm(Permission.FINANCE_SETTLEMENT)).isTrue();
    }

    // ---------------- 6. 角色清单本身 ----------------

    @Test
    @DisplayName("系统角色恰好 4 个，且与库内存储口径一致（USER/SELLER/OPERATOR/ADMIN）")
    void known_roles_are_exactly_four() {
        assertThat(RolePermissionMatrix.KNOWN_ROLES)
                .containsExactlyInAnyOrder("USER", "SELLER", "OPERATOR", "ADMIN");
    }

    @Test
    @DisplayName("矩阵与角色清单是不可变结构（调用方改不动）")
    void matrix_is_immutable() {
        assertThat(RolePermissionMatrix.KNOWN_ROLES).isUnmodifiable();
        assertThat(RolePermissionMatrix.of("ADMIN")).isUnmodifiable();
        assertThat(Permission.ALL).isUnmodifiable();
    }
}

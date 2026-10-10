package com.duoduo.common.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计动作清单（E18）。
 *
 * <h2>本文件最重要的一条：历史标识逐字不变</h2>
 * E18 之前已有 9 个动作标识以 private {@code String} 常量散在各服务里，并已经写进了
 * {@code t_audit_log}。改成枚举时，只要有人「顺手规整一下命名」
 * （{@code USER_ROLE_CHANGE} → {@code USER_ROLE_CHANGED}），历史记录就会与新记录分叉：
 * 查「这个人被改过几次角色」只返回一半，而两处代码看起来都是对的。
 *
 * <p>因此这里把 9 个名字**逐字写死**在断言里。这不是「钉住实现细节」——
 * 落库值本身是外部契约（已存在的历史数据依赖它），属于必须钉住的那一类。
 *
 * <h2>为什么要有「每个动作都必须声明类目与目标类型」</h2>
 * 新增动作时最容易被跳过的一步就是回答「它属于资金档吗」。
 * 枚举构造器把这件事变成编译期必答题；再加一条「类目非空」的遍历断言，
 * 防止将来出现 {@code XXX(null, "...")} 这种「为了编过而填 null」的写法。
 */
class AuditActionTest {

    /**
     * E18 之前已落库的 9 个动作标识。
     *
     * <p><b>不要修改这份清单去「适配」重命名</b>：它与库里的历史行是同一份事实。
     * 若确实需要改名，先写数据迁移更新历史行，再改这里。
     */
    private static final Set<String> LEGACY_CODES = Set.of(
            "USER_ROLE_CHANGE",
            "MERCHANT_LICENSE_SUSPEND",
            "MERCHANT_VIOLATION_SUSPEND",
            "MERCHANT_MATERIAL_READ",
            "INVITE_STAFF",
            "ENABLE_STAFF",
            "DISABLE_STAFF",
            "ADMIN_ENABLE_STAFF",
            "ADMIN_DISABLE_STAFF"
    );

    @Test
    @DisplayName("E18 之前落库的 9 个动作标识必须逐字保留")
    void 历史动作标识逐字保留() {
        Set<String> current = Arrays.stream(AuditAction.values())
                .map(AuditAction::code)
                .collect(Collectors.toSet());

        // ★ 用「包含」而不是「相等」：将来新增动作不该让本用例变红，
        //   本用例只守「老的没被改名 / 删除」这一件事
        assertThat(current)
                .as("历史动作标识必须全部存在且同名（改名会让历史查询少一半）")
                .containsAll(LEGACY_CODES);
    }

    @Test
    @DisplayName("落库值就是枚举名：code() 与 name() 必须一致")
    void code等于枚举名() {
        for (AuditAction action : AuditAction.values()) {
            assertThat(action.code())
                    .as("动作 %s 的落库值", action)
                    .isEqualTo(action.name());
        }
    }

    @Test
    @DisplayName("每个动作都必须声明非空类目与目标类型")
    void 每个动作都必须声明类目() {
        for (AuditAction action : AuditAction.values()) {
            assertThat(action.category())
                    .as("动作 %s 的类目不能为空（构造器里填 null 只为编过是不可接受的）", action)
                    .isNotNull();
            assertThat(action.targetType())
                    .as("动作 %s 的目标类型不能为 null（空串表示未细分，但 null 会污染落库列）", action)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("资金与权限类动作必须落在不可删改档")
    void 关键动作必须不可删改() {
        // 逐个点名而不是按前缀批量判断：这条断言的意义正是「有人把某个动作的档位改松了，这里会红」。
        // 若改成 setOf(...) 批量包含，就无法表达「这个具体动作必须是 FUNDS」。
        assertThat(AuditAction.ORDER_FORCE_CLOSE.category()).isEqualTo(AuditCategory.FUNDS);
        assertThat(AuditAction.AFTERSALE_ARBITRATE.category()).isEqualTo(AuditCategory.FUNDS);
        assertThat(AuditAction.SETTLEMENT_BACKFILL.category()).isEqualTo(AuditCategory.FUNDS);
        assertThat(AuditAction.SETTLEMENT_COMPENSATE.category()).isEqualTo(AuditCategory.FUNDS);
        assertThat(AuditAction.SETTLEMENT_DEBT_SETTLE.category()).isEqualTo(AuditCategory.FUNDS);
        assertThat(AuditAction.SETTLEMENT_DEBT_WRITE_OFF.category()).isEqualTo(AuditCategory.FUNDS);

        assertThat(AuditAction.USER_ROLE_CHANGE.category()).isEqualTo(AuditCategory.SECURITY);
        assertThat(AuditAction.MERCHANT_MATERIAL_READ.category()).isEqualTo(AuditCategory.SECURITY);
        assertThat(AuditAction.INVITE_STAFF.category()).isEqualTo(AuditCategory.SECURITY);
        assertThat(AuditAction.ENABLE_STAFF.category()).isEqualTo(AuditCategory.SECURITY);
        assertThat(AuditAction.DISABLE_STAFF.category()).isEqualTo(AuditCategory.SECURITY);
        assertThat(AuditAction.ADMIN_ENABLE_STAFF.category()).isEqualTo(AuditCategory.SECURITY);
        assertThat(AuditAction.ADMIN_DISABLE_STAFF.category()).isEqualTo(AuditCategory.SECURITY);
    }

    @Test
    @DisplayName("读资质材料属于 SECURITY：它是敏感数据访问留痕，不是租户生命周期")
    void 读资质材料归为安全类() {
        // 这条单独写，是因为它的档位是最容易被「按业务模块归类」搞错的一个：
        // 它挂在商家模块下，但那不决定类目 —— 决定类目的是「这条记录丢了能不能重建」。
        // 「谁在什么时候看过别人的营业执照」一旦丢了，事后无法重建。
        assertThat(AuditAction.MERCHANT_MATERIAL_READ.category().immutable()).isTrue();
        assertThat(AuditAction.MERCHANT_MATERIAL_READ.category()).isNotEqualTo(AuditCategory.TENANT);

        // 对照：同一个模块下的其它动作确实是 TENANT —— 说明上面那条不是「模块整体归一类」的巧合
        assertThat(AuditAction.MERCHANT_APPROVE.category()).isEqualTo(AuditCategory.TENANT);
    }

    @Test
    @DisplayName("兜底动作 UNKNOWN 归入不可删改档")
    void 兜底动作归入不可删改档() {
        assertThat(AuditAction.UNKNOWN.category()).isEqualTo(AuditCategory.SECURITY);
        assertThat(AuditAction.UNKNOWN.category().immutable()).isTrue();
    }

    @Test
    @DisplayName("未知 / 空白一律折叠为 UNKNOWN，不返回 null、不抛异常")
    void 未知值折叠为兜底动作() {
        assertThat(AuditAction.parse(null)).isEqualTo(AuditAction.UNKNOWN);
        assertThat(AuditAction.parse("")).isEqualTo(AuditAction.UNKNOWN);
        assertThat(AuditAction.parse("   ")).isEqualTo(AuditAction.UNKNOWN);
        assertThat(AuditAction.parse("SOME_ACTION_FROM_ANOTHER_SERVICE")).isEqualTo(AuditAction.UNKNOWN);
        // 折叠方向：读不懂的动作不能落到「可删除」那一侧
        assertThat(AuditAction.parse("???").category().immutable()).isTrue();
    }

    @Test
    @DisplayName("正常值大小写与首尾空格都要能解析，且能还原成同一枚举")
    void 正常值宽容解析() {
        assertThat(AuditAction.parse("USER_ROLE_CHANGE")).isEqualTo(AuditAction.USER_ROLE_CHANGE);
        assertThat(AuditAction.parse("user_role_change")).isEqualTo(AuditAction.USER_ROLE_CHANGE);
        assertThat(AuditAction.parse("  Admin_Disable_Staff  ")).isEqualTo(AuditAction.ADMIN_DISABLE_STAFF);
    }

    @Test
    @DisplayName("动作标识不重复：重复会让查询把两类动作混成一条")
    void 动作标识不重复() {
        List<String> codes = Arrays.stream(AuditAction.values()).map(AuditAction::code).toList();
        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("ALL 与枚举值一一对应，且不可修改")
    void ALL与枚举值一致() {
        assertThat(AuditAction.ALL).hasSameSizeAs(AuditAction.values());
        assertThat(AuditAction.ALL).containsExactlyElementsOf(Arrays.asList(AuditAction.values()));
    }

    @Test
    @DisplayName("清单规模：41 个真实动作 + 1 个兜底")
    void 清单规模() {
        // 钉住数量是刻意的：动作清单是**审计覆盖面的分母**。
        // 少了会红（有人删了动作），多了也会红（有人加了动作但没读本文件的说明）。
        // 若是有意新增，请同时更新这里的数字，并在总纲 §5 记录。
        // E21：新增 3 个两步验证动作（USER_TOTP_ENABLE / USER_TOTP_DISABLE / USER_TOTP_RESET），39 → 42。
        assertThat(AuditAction.values()).hasSize(42);
        assertThat(AuditAction.values()).contains(AuditAction.UNKNOWN);
    }
}

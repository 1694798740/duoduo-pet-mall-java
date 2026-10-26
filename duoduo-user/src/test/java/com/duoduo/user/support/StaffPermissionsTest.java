package com.duoduo.user.support;

import com.duoduo.common.seller.StaffPermission;
import com.duoduo.common.seller.StaffPermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-04 · 权限清单解析 / 序列化单测。
 *
 * <p>这里守的是「库内值」这一层契约：{@code NULL} 与空串必须表达不同含义
 * （历史全量 vs 显式仅查看），未知键必须忽略或拒绝而不是静默改写。
 */
class StaffPermissionsTest {

    @Test
    @DisplayName("NULL = 历史行 → 全量；空串 = 显式仅查看")
    void nullAndEmptyHaveDifferentMeaning() {
        assertThat(StaffPermissions.effective(null)).containsExactlyElementsOf(StaffPermissions.all());
        assertThat(StaffPermissions.effective("")).isEmpty();
        assertThat(StaffPermissions.effective("  ")).isEmpty();
    }

    @Test
    @DisplayName("解析忽略空白与未知键，保持枚举顺序")
    void parseFiltersUnknownAndOrders() {
        assertThat(StaffPermissions.parse(" ORDER_SHIP, ,PRODUCT_EDIT,NOT_A_KEY "))
                .containsExactly("PRODUCT_EDIT", "ORDER_SHIP");
    }

    @Test
    @DisplayName("写路径拒绝未知键（拼错的键不能被当成有效配置）")
    void normalizeRejectsUnknown() {
        assertThatThrownBy(() -> StaffPermissions.normalize(List.of("ORDER_SHIPS")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知权限");
    }

    @Test
    @DisplayName("序列化去重且顺序稳定")
    void serializeIsStable() {
        assertThat(StaffPermissions.serialize(List.of("SUPPORT_REPLY", "ORDER_SHIP", "ORDER_SHIP")))
                .isEqualTo("ORDER_SHIP,SUPPORT_REPLY");
        assertThat(StaffPermissions.serialize(List.of())).isEmpty();
    }

    @Test
    @DisplayName("人读文案：空清单显示「仅查看」")
    void labelForEmpty() {
        assertThat(StaffPermissions.labelOf(List.of())).isEqualTo("仅查看");
        assertThat(StaffPermissions.labelOf(List.of("ORDER_SHIP")))
                .isEqualTo(StaffPermission.ORDER_SHIP.label());
    }
}

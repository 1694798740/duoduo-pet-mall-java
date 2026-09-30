package com.duoduo.trade.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资金托管状态单测（第五十八轮）。
 *
 * <p>重点：新增的 {@code ADVANCED}（平台垫付）必须有中文文案 ——
 * 若枚举里没有它，{@code labelOf} 会把英文原串直接显示到运营后台的资金列表上，
 * 运营看到的会是「ADVANCED」而不是「平台垫付」，遇到对账问题时无法判断这笔钱去了哪。
 */
class SettlementStatusTest {

    @Test
    void 四个状态都有中文文案() {
        assertThat(SettlementStatus.CUSTODY.label()).isEqualTo("托管中");
        assertThat(SettlementStatus.RELEASED.label()).isEqualTo("已放款");
        assertThat(SettlementStatus.REFUNDED.label()).isEqualTo("已退回买家");
        assertThat(SettlementStatus.ADVANCED.label()).isEqualTo("平台垫付");
    }

    @Test
    void 垫付状态可被解析且不被当成未知() {
        assertThat(SettlementStatus.of("ADVANCED")).isEqualTo(SettlementStatus.ADVANCED);
        // 关键断言：labelOf 对已知状态必须返回中文，而不是把英文原串透出去
        assertThat(SettlementStatus.labelOf("ADVANCED")).isEqualTo("平台垫付");
    }

    @Test
    void 未知状态返回原字符串_避免脏数据打挂展示() {
        assertThat(SettlementStatus.of("CUSTODIED")).isNull();
        assertThat(SettlementStatus.labelOf("CUSTODIED")).isEqualTo("CUSTODIED");
    }
}

package com.duoduo.common.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计结果解析（E18）。
 *
 * <p>与 {@link AuditCategory} 的折叠方向是同一原则，但**方向相反**：
 * 类目读不懂往「更严（更不可删）」走，结果读不懂往「更可疑（失败）」走。
 * 共同点是都指向保守一侧 —— 而「读不懂当成功」是本文件唯一要拦住的错法：
 * 一条写着 SUCCESS 其实被拒的记录，比没有记录更糟，因为它会让人相信一次越权成功了。
 */
class AuditOutcomeTest {

    @Test
    @DisplayName("未知 / 空白一律折叠为 FAILED，而不是 SUCCESS")
    void 未知值折叠为失败() {
        assertThat(AuditOutcome.parse(null)).isEqualTo(AuditOutcome.FAILED);
        assertThat(AuditOutcome.parse("")).isEqualTo(AuditOutcome.FAILED);
        assertThat(AuditOutcome.parse("   ")).isEqualTo(AuditOutcome.FAILED);
        assertThat(AuditOutcome.parse("OK")).isEqualTo(AuditOutcome.FAILED);
        assertThat(AuditOutcome.parse("SUCCEEDED")).isEqualTo(AuditOutcome.FAILED);
    }

    @Test
    @DisplayName("正常值大小写与首尾空格都要能解析")
    void 正常值宽容解析() {
        assertThat(AuditOutcome.parse("SUCCESS")).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(AuditOutcome.parse("success")).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(AuditOutcome.parse(" Failed ")).isEqualTo(AuditOutcome.FAILED);
    }

    @Test
    @DisplayName("恰好两个结果档：SUCCESS / FAILED")
    void 恰好两个档位() {
        assertThat(AuditOutcome.values()).containsExactly(AuditOutcome.SUCCESS, AuditOutcome.FAILED);
    }
}

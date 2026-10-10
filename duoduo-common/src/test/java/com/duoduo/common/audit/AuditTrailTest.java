package com.duoduo.common.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 审计上下文（E18）。
 *
 * <h2>这组断言守的核心：非 HTTP 入口不能因为「没有上下文」而炸</h2>
 * 同一段业务代码会被三条路径调用：后台 HTTP 请求、定时任务、内部调用。
 * 只有第一条有 {@link AuditTrail} 上下文。若公开方法在无上下文时抛异常，
 * 业务代码就得到处写「我现在是不是在 HTTP 线程里」—— 那正是这类工具最常出事的地方。
 * 所以「无上下文 = 静默 no-op」是一个**必须被钉住的性质**，而不是实现细节。
 *
 * <h2>另一条：覆盖而不是叠加</h2>
 * {@link AuditTrail#start(AuditAction)} 若写成累积语义，上一条记录的内容会泄漏到下一条里，
 * 写出张冠李戴的审计行 —— 比丢一条更糟。这里用「start 两次后旧的目标消失」来钉住。
 */
class AuditTrailTest {

    @AfterEach
    void cleanup() {
        AuditTrail.clear();
    }

    @Test
    @DisplayName("没有活动上下文时，所有补充入口都是静默 no-op（不抛异常）")
    void 无上下文时静默跳过() {
        assertThat(AuditTrail.current()).isNull();

        // 定时任务 / MQ 消费 / 内部调用走的就是这几行
        assertThatCode(() -> {
            AuditTrail.target(123L);
            AuditTrail.target(123L, "MERCHANT");
            AuditTrail.tenant(456L);
            AuditTrail.change("A", "B");
            AuditTrail.note("随便写点什么");
            AuditTrail.skip("没有变更");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("start 之后可以读到注解带来的动作与默认目标类型")
    void start建立上下文() {
        AuditTrail trail = AuditTrail.start(AuditAction.MERCHANT_APPROVE);

        assertThat(AuditTrail.current()).isSameAs(trail);
        assertThat(trail.action()).isEqualTo(AuditAction.MERCHANT_APPROVE);
        // 默认目标类型来自动作定义，业务代码不写也能用
        assertThat(trail.targetType()).isEqualTo("MERCHANT");
        // 默认目标 id / 租户 id 都是 0，即「平台级 / 无目标」
        assertThat(trail.targetId()).isZero();
        assertThat(trail.tenantId()).isZero();
        assertThat(trail.beforeValue()).isNull();
        assertThat(trail.afterValue()).isNull();
        assertThat(trail.skipped()).isFalse();
    }

    @Test
    @DisplayName("★ start 是覆盖而不是叠加：上一条记录的内容不能泄漏到这一条")
    void start覆盖而不是叠加() {
        AuditTrail.start(AuditAction.USER_ROLE_CHANGE);
        AuditTrail.target(111L, "USER");
        AuditTrail.tenant(222L);
        AuditTrail.change("USER", "ADMIN");
        AuditTrail.note("上一轮留下的说明");
        AuditTrail.skip("上一轮被抑制了");

        // 第二次 start 模拟「线程被复用、上一个请求的上下文还留着」的极端情况
        AuditTrail trail = AuditTrail.start(AuditAction.MERCHANT_REJECT);

        assertThat(trail.targetId()).as("上一条的目标不能带到这一条").isZero();
        assertThat(trail.tenantId()).isZero();
        assertThat(trail.beforeValue()).isNull();
        assertThat(trail.afterValue()).isNull();
        assertThat(trail.detail()).as("上一条的说明不能带到这一条").isEmpty();
        assertThat(trail.skipped()).as("上一条的抑制标记不能带到这一条").isFalse();
        assertThat(trail.targetType()).as("目标类型应回到新动作的默认值").isEqualTo("MERCHANT");
    }

    @Test
    @DisplayName("clear 之后上下文消失（拦截器在 finally 里调用的就是它）")
    void clear清空上下文() {
        AuditTrail.start(AuditAction.USER_ROLE_CHANGE);
        assertThat(AuditTrail.current()).isNotNull();

        AuditTrail.clear();

        assertThat(AuditTrail.current()).isNull();
        // 清理之后再调补充入口仍是 no-op（不能因为「上一个刚清掉」就出问题）
        assertThatCode(() -> AuditTrail.target(1L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("target：id <= 0 视为「无具体目标」，忽略而不是写 0")
    void target忽略非正数() {
        AuditTrail trail = AuditTrail.start(AuditAction.SEARCH_REINDEX);

        AuditTrail.target(0L);
        assertThat(trail.targetId()).isZero();

        AuditTrail.target(999L, "SEARCH");
        assertThat(trail.targetId()).isEqualTo(999L);

        // 平台级动作改回 0 时，不能把上一步已写入的值抹掉成「未知」——
        // 这里钉住的是「忽略」而不是「置零」
        AuditTrail.target(-5L);
        assertThat(trail.targetId()).as("负数应被忽略，保留上一次的有效目标").isEqualTo(999L);
    }

    @Test
    @DisplayName("target(id, type)：类型为空时不覆盖动作默认类型")
    void target类型为空时不覆盖默认() {
        AuditTrail trail = AuditTrail.start(AuditAction.MERCHANT_APPROVE);

        AuditTrail.target(7L, null);
        assertThat(trail.targetType()).isEqualTo("MERCHANT");

        AuditTrail.target(7L, "   ");
        assertThat(trail.targetType()).isEqualTo("MERCHANT");

        AuditTrail.target(7L, "STORE");
        assertThat(trail.targetType()).isEqualTo("STORE");
    }

    @Test
    @DisplayName("tenant：id <= 0（平台级 / 自营）忽略写入")
    void tenant忽略平台级() {
        AuditTrail trail = AuditTrail.start(AuditAction.USER_ROLE_CHANGE);

        AuditTrail.tenant(0L);
        assertThat(trail.tenantId()).as("0 = 平台级，不该被写成别的东西").isZero();

        AuditTrail.tenant(888L);
        assertThat(trail.tenantId()).isEqualTo(888L);
    }

    @Test
    @DisplayName("change：同时填好前后值与一句人读摘要（只调一次就够）")
    void change同时填值与摘要() {
        AuditTrail trail = AuditTrail.start(AuditAction.USER_ROLE_CHANGE);

        AuditTrail.change("USER", "SELLER");

        assertThat(trail.beforeValue()).isEqualTo("USER");
        assertThat(trail.afterValue()).isEqualTo("SELLER");
        assertThat(trail.detail()).as("人读摘要应自动补成 before -> after").isEqualTo("USER -> SELLER");
    }

    @Test
    @DisplayName("change：null 值在摘要里显示为「(空)」而不是字面量 null")
    void change的空值显示为人读文本() {
        AuditTrail trail = AuditTrail.start(AuditAction.MERCHANT_REJECT);

        AuditTrail.change(null, "REJECTED");

        assertThat(trail.beforeValue()).isNull();
        assertThat(trail.afterValue()).isEqualTo("REJECTED");
        assertThat(trail.detail()).isEqualTo("(空) -> REJECTED");
    }

    @Test
    @DisplayName("change：已经有摘要时不覆盖它（业务写的那句更具体）")
    void change不覆盖已有摘要() {
        AuditTrail trail = AuditTrail.start(AuditAction.MERCHANT_APPROVE);

        AuditTrail.note("驳回原因：资质照片模糊");
        AuditTrail.change("PENDING", "REJECTED");

        assertThat(trail.beforeValue()).isEqualTo("PENDING");
        assertThat(trail.afterValue()).isEqualTo("REJECTED");
        // ★ 若这里被覆盖成 "PENDING -> REJECTED"，业务写的那句具体原因就丢了 ——
        //   审计记录的可用性几乎全在 detail 上
        assertThat(trail.detail()).isEqualTo("驳回原因：资质照片模糊");
    }

    @Test
    @DisplayName("note：多次调用按 ; 串接，空白忽略")
    void note多次串接() {
        AuditTrail trail = AuditTrail.start(AuditAction.ADMIN_DISABLE_STAFF);

        AuditTrail.note("平台处置");
        AuditTrail.note(null);
        AuditTrail.note("   ");
        AuditTrail.note("merchant=1001");

        assertThat(trail.detail()).isEqualTo("平台处置; merchant=1001");
    }

    @Test
    @DisplayName("skip：标记为「无事实变化」，并保留原因供日志使用")
    void skip标记抑制并保留原因() {
        AuditTrail trail = AuditTrail.start(AuditAction.USER_ROLE_CHANGE);

        AuditTrail.skip("目标已是该角色，无实际变更");

        assertThat(trail.skipped()).isTrue();
        assertThat(trail.skipReason()).isEqualTo("目标已是该角色，无实际变更");
    }

    @Test
    @DisplayName("skip 是可复核的：原因必须被保留下来（不能只写个 true）")
    void skip必须带原因() {
        AuditTrail trail = AuditTrail.start(AuditAction.ENABLE_STAFF);

        AuditTrail.skip("店员状态已是 ACTIVE");

        // 若实现改成忽略入参，本断言会红 —— 那样抑制就成了不可复核的口子，
        // 而「不可复核的抑制」正是审计系统里最难被发现的问题
        assertThat(trail.skipReason()).isNotBlank();
    }

    @Test
    @DisplayName("未调用 skip 时 skipped 为 false（默认必须是不抑制）")
    void 默认不抑制() {
        AuditTrail trail = AuditTrail.start(AuditAction.USER_ROLE_CHANGE);

        AuditTrail.target(1L);
        AuditTrail.note("正常留痕");

        assertThat(trail.skipped()).as("★ 默认必须留痕，抑制只能显式声明").isFalse();
        assertThat(trail.skipReason()).isNull();
    }
}

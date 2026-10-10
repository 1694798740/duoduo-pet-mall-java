package com.duoduo.common.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计记录载荷（E18）。
 *
 * <h2>为什么这里要专门测「不被劈开的汉字」</h2>
 * {@link AuditLogRecord#sanitized()} 在写入前按列宽截断。用 {@code substring(0, 500)}
 * 对中文是**按 char 截**，而一个汉字占 2 个 char（BMP 内）—— 500 是偶数时侥幸没事，
 * 但列宽 500 之外还有 {@code ip(64)} 这种奇数边界，一截就会把一个汉字劈成半个，
 * 落库变乱码。所以截断必须按码点算，本文件用「截完仍是完整汉字」来钉住这件事。
 *
 * <h2>为什么截断在应用层而不是靠数据库报错</h2>
 * 审计是旁路。让它有能力把一次正常业务操作变成 500，是为了审计牺牲业务 —— 方向错了。
 */
class AuditLogRecordTest {

    /** 造一条各字段都短的基线记录，用例只改自己关心的那个字段。 */
    private static AuditLogRecord record(String detail, String ip, String requestId) {
        return new AuditLogRecord(
                1L, "13900000000", "USER_ROLE_CHANGE", "SECURITY", "SUCCESS",
                2L, "USER", 3L, "USER", "SELLER",
                ip, requestId, detail, "2026-10-09T12:00:00");
    }

    @Test
    @DisplayName("短字段原样保留，不做任何改写")
    void 短字段原样保留() {
        AuditLogRecord r = record("已处理", "1.2.3.4", "req-1").sanitized();

        assertThat(r.detail()).isEqualTo("已处理");
        assertThat(r.ip()).isEqualTo("1.2.3.4");
        assertThat(r.requestId()).isEqualTo("req-1");
        assertThat(r.action()).isEqualTo("USER_ROLE_CHANGE");
        assertThat(r.beforeValue()).isEqualTo("USER");
        assertThat(r.afterValue()).isEqualTo("SELLER");
        assertThat(r.targetId()).isEqualTo(2L);
        assertThat(r.tenantId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("超出列宽要截断，并留下可见的省略标记")
    void 超长字段截断并留标记() {
        String long500 = "x".repeat(600);
        AuditLogRecord r = record(long500, "1.2.3.4", "req-1").sanitized();

        assertThat(r.detail()).hasSize(AuditLogRecord.MAX_TEXT);
        assertThat(r.detail()).endsWith("…");
        assertThat(r.detail()).startsWith("x".repeat(10));
    }

    @Test
    @DisplayName("★ 截断按码点：恰好等于列宽不截，超出则截到列宽（不能劈开代理对）")
    void 截断按码点不劈汉字() {
        // ① 恰好等于列宽（action 列宽 50）时不该被截断。
        //    注意 BMP 内的汉字只占 1 个 char，所以这里 char 数与码点数相同。
        String h50 = "汉".repeat(50);
        assertThat(actionOf(h50).action()).as("恰好等于列宽时不该截断").isEqualTo(h50);

        // ② 超出 1 个码点：截到 49 码点 + 省略号 = 50 码点
        String h51 = "汉".repeat(51);
        String cut = actionOf(h51).action();
        assertThat(cut).as("超长必须被截断").isNotEqualTo(h51);
        assertThat(cut).endsWith("…");
        assertThat(cut.codePointCount(0, cut.length()))
                .as("截断后按码点数必须恰好 = 列宽（50）")
                .isEqualTo(50);

        // ③ ★ 真正的判别用例：代理对（emoji 占 2 个 char）。
        //    若实现写成 raw.substring(0, 50)，会把结果截成 25 个 emoji（码点数 25），
        //    上面的 isEqualTo(50) 就会红；写成 length() 比较则完全看不出差异。
        String dog = "\uD83D\uDC36"; // 狗头 emoji，U+1F436，UTF-16 两个 char
        String dogs = dog.repeat(51);
        String cutDog = actionOf(dogs).action();
        assertThat(cutDog.codePointCount(0, cutDog.length()))
                .as("★ 按码点截：51 个 emoji 应截成 49 个 + 省略号")
                .isEqualTo(50);
        assertThat(cutDog).doesNotContain("\uFFFD");
        assertThat(cutDog.codePoints().limit(49).allMatch(cp -> cp == 0x1F436))
                .as("前 49 个码点必须都是完整的狗头 emoji（劈开会出现替换字符或错位）")
                .isTrue();

        // ④ detail(500) 同样按码点：600 个汉字 > 500 码点 → 截到 500 码点
        AuditLogRecord r2 = record("汉".repeat(600), "1.2.3.4", "req-1").sanitized();
        assertThat(r2.detail().codePointCount(0, r2.detail().length()))
                .as("截断后按码点数不能超过列宽")
                .isEqualTo(AuditLogRecord.MAX_TEXT);
        assertThat(r2.detail()).doesNotContain("\uFFFD");
    }

    private static AuditLogRecord actionOf(String action) {
        return new AuditLogRecord(
                1L, "13900000000", action, "SECURITY", "SUCCESS",
                2L, "USER", 3L, null, null, "1.2.3.4", "req-1", "x", "2026-10-09T12:00:00"
        ).sanitized();
    }

    @Test
    @DisplayName("null 字段保持 null：不能把「本来就没有」写成空串以外的东西")
    void null字段保持null() {
        AuditLogRecord r = new AuditLogRecord(
                null, null, "USER_ROLE_CHANGE", null, null,
                0L, null, 0L, null, null, null, null, null, null
        ).sanitized();

        assertThat(r.actorPhone()).isNull();
        assertThat(r.beforeValue()).isNull();
        assertThat(r.afterValue()).isNull();
        assertThat(r.ip()).isNull();
        assertThat(r.requestId()).isNull();
        assertThat(r.detail()).isNull();
        assertThat(r.createdAt()).isNull();
        // 注意：targetId / tenantId 是基本类型 long，null 会变成 0 —— 这正是期望的
        // （「平台级 / 无目标」用 0 表示，不用 null，见类注释）
        assertThat(r.targetId()).isZero();
        assertThat(r.tenantId()).isZero();
    }

    @Test
    @DisplayName("类目与结果按宽容口径解析，读不懂往保守一侧折叠")
    void 类目与结果宽容解析() {
        AuditLogRecord r = new AuditLogRecord(
                1L, "13900000000", "USER_ROLE_CHANGE", "not-a-category", "not-an-outcome",
                2L, "USER", 3L, null, null, null, null, "x", "2026-10-09T12:00:00"
        );

        assertThat(r.categoryEnum()).isEqualTo(AuditCategory.SECURITY);
        assertThat(r.outcomeEnum()).isEqualTo(AuditOutcome.FAILED);
    }

    @Test
    @DisplayName("createdAt 是字符串：跨服务 JSON 不因 Jackson 配置差异产生 8 小时偏差")
    void createdAt是字符串() throws Exception {
        // 这条断言守的是「字段类型不能再改回 LocalDateTime」——
        // 那会让两个服务配置稍有不一致就得到「时间差 8 小时」，
        // 而这类错误只在跨域操作时才出现（很难被测到）。
        assertThat(AuditLogRecord.class.getRecordComponents())
                .filteredOn(c -> c.getName().equals("createdAt"))
                .singleElement()
                .satisfies(c -> assertThat(c.getType()).isEqualTo(String.class));
    }
}

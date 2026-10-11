package com.duoduo.trade.dto;

import com.duoduo.trade.entity.ReconDiffEntity;
import com.duoduo.trade.entity.ReconRunEntity;
import com.duoduo.trade.support.ReconRule;
import com.duoduo.trade.support.ReconScope;
import com.duoduo.trade.support.ReconStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 对账对外视图（S3）。
 *
 * <p><b>record 里的类型选择是有意的</b>（踩过的坑，见 {@code LongToStringJacksonModule}）：
 * <ul>
 *   <li>标识一律声明成 {@link String} 并显式转换 —— record 不经过「按字段名判断 Long 该不该转字符串」
 *       的那条链路，所以不能指望它自动处理，也不能把 id 声明成 {@code long}；</li>
 *   <li>计数用 {@code int}（模块只处理 {@code Long}/{@code long}/{@code BigInteger}），
 *       保持数字，前端可以直接比大小；</li>
 *   <li>时间一律转成 UTC 的 ISO 串 —— 全站时间口径一致，避免前端各自猜时区。</li>
 * </ul>
 */
public final class ReconDtos {

    private ReconDtos() {
    }

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private static String iso(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC).format(TS);
    }

    /** 一轮对账 */
    public record ReconRunView(
            String id,
            String bizDate,
            String scope,
            String scopeLabel,
            String status,
            String statusLabel,
            int checkedCount,
            int diffCount,
            int highCount,
            String startedAt,
            String finishedAt,
            String message
    ) {
        public static ReconRunView of(ReconRunEntity e) {
            ReconScope scope = ReconScope.of(e.getScope());
            ReconStatus status = ReconStatus.of(e.getStatus());
            return new ReconRunView(
                    e.getId() == null ? null : String.valueOf(e.getId()),
                    e.getBizDate() == null ? null : e.getBizDate().toString(),
                    e.getScope(),
                    scope == null ? e.getScope() : scope.label(),
                    e.getStatus(),
                    status == null ? e.getStatus() : status.label(),
                    e.getCheckedCount() == null ? 0 : e.getCheckedCount(),
                    e.getDiffCount() == null ? 0 : e.getDiffCount(),
                    e.getHighCount() == null ? 0 : e.getHighCount(),
                    iso(e.getStartedAt()),
                    iso(e.getFinishedAt()),
                    e.getMessage());
        }
    }

    /** 一条差异 */
    public record ReconDiffView(
            String id,
            String rule,
            String ruleLabel,
            String severity,
            String refType,
            String refId,
            String expected,
            String actual,
            String detail
    ) {
        public static ReconDiffView of(ReconDiffEntity e) {
            ReconRule rule = ReconRule.of(e.getRule());
            return new ReconDiffView(
                    e.getId() == null ? null : String.valueOf(e.getId()),
                    e.getRule(),
                    rule == null ? e.getRule() : rule.label(),
                    e.getSeverity(),
                    e.getRefType(),
                    e.getRefId() == null ? null : String.valueOf(e.getRefId()),
                    e.getExpected(),
                    e.getActual(),
                    e.getDetail());
        }
    }

    /**
     * 手动触发入参。
     *
     * <p>{@code bizDate} 可空 = 今天（UTC）。<b>允许指定历史日期</b>是为了验收与复盘：
     * 「昨天那批差异到底有没有」只能靠重跑昨天来回答，而不能靠"今天跑得干净"推断。
     */
    public record TriggerRequest(String scope, String bizDate) {
    }

    /** 规则清单项：让「声明了几条规则」在运行时也能被核对（脚本据此做声明-执行守卫） */
    public record ReconRuleView(
            String code,
            String label,
            String severity,
            String refType,
            String scope,
            String scopeLabel
    ) {
        public static ReconRuleView of(ReconRule r) {
            return new ReconRuleView(r.name(), r.label(), r.severity().name(), r.refType(),
                    r.scope().name(), r.scope().label());
        }
    }

    /** 触发结果：把这一轮的结论原样返回，不额外包一层"成功"（结论本身才是要看的） */
    public record TriggerResult(
            String runId,
            String scope,
            String bizDate,
            String status,
            int checkedCount,
            int diffCount,
            int highCount,
            String message
    ) {
        public static TriggerResult of(ReconRunEntity e) {
            return new TriggerResult(
                    e.getId() == null ? null : String.valueOf(e.getId()),
                    e.getScope(),
                    e.getBizDate() == null ? null : e.getBizDate().toString(),
                    e.getStatus(),
                    e.getCheckedCount() == null ? 0 : e.getCheckedCount(),
                    e.getDiffCount() == null ? 0 : e.getDiffCount(),
                    e.getHighCount() == null ? 0 : e.getHighCount(),
                    e.getMessage());
        }
    }

    /** 业务日解析：允许 {@code yyyy-MM-dd}；空 = 今天（UTC） */
    public static LocalDate parseBizDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.now(ZoneOffset.UTC);
        }
        return LocalDate.parse(raw.trim());
    }
}

package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.audit.AuditLogRecord;
import com.duoduo.user.service.AuditLogService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计记录汇总入口（E18）：供其它域把后台写操作汇总到唯一一张审计表。
 *
 * <p><b>为什么走内部端点而不是各域各写一张表</b>：审计的价值在**一处可查**。
 * 「商品审核」在商品域、「订单强制关闭」在交易域，如果各写各的表，
 * 追一个「这个人今天动了哪些数据」就得跨库 union，而跨库 union 恰恰是分库设计最不擅长的事。
 * 汇总到 user 域，是因为那里已经有审计表（A7 起），且**审计是账号域的事实**——
 * 「谁做的」本来就是账号域的概念。
 *
 * <p><b>安全</b>：路径挂在 {@code /api/users/internal/} 下，
 * 由 {@code InternalTokenFilter} 强校验服务间签名（调用方身份 + 时间戳 + 一次性 nonce），
 * 且**不经网关**（外部请求到不了这里）。这与库存写操作、地址快照是同一道门。
 *
 * <p><b>★ 返回 200 而不是在写入失败时报错</b>：审计是旁路，它的失败不该让
 * 发起方的业务动作失败（业务那边已经完成、无法回滚）。但也不能什么都不说 ——
 * 返回体里的 {@code written} 让调用方能判断「我这次留痕到底成没成」，
 * 从而在失败时把记录降级写进自己的日志，避免出现「审计表里没有、日志里也没有」
 * 这种事后完全无法重建的状态。
 */
@RestController
@RequestMapping("/api/users/internal/audit-logs")
public class AuditLogInternalController {

    private final AuditLogService auditLogService;

    public AuditLogInternalController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @PostMapping
    public ApiResponse<WriteResult> write(@RequestBody AuditLogRecord record) {
        boolean written = auditLogService.record(record);
        return ApiResponse.ok(new WriteResult(written));
    }

    /** 写入结果。字段名直白（{@code written}）而不是 {@code ok}：调用方要据此决定是否降级记日志。 */
    public record WriteResult(boolean written) {
    }
}

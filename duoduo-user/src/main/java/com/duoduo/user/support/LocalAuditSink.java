package com.duoduo.user.support;

import com.duoduo.common.audit.AuditLogRecord;
import com.duoduo.common.audit.AuditSink;
import com.duoduo.user.service.AuditLogService;
import org.springframework.stereotype.Component;

/**
 * 本地审计落库（E18）：{@code duoduo-user} 自己连得到 {@code t_audit_log}，
 * 因此它的 sink 实现是「直接写本库」，不需要绕内部通道。
 *
 * <p>与其它域的 {@code RemoteAuditSink} 是同一个端口（{@link AuditSink}）的两个实现 ——
 * 审计拦截器只认端口，因此「哪个服务自己写、哪个服务转发」这件事
 * 不会泄漏到框架代码里。
 *
 * <p>失败处理遵循端口契约：{@link AuditLogService#record} 内部已经吞掉异常并打 ERROR 日志，
 * 这里不再包一层 —— 多一层看不出差别的 try/catch 只会让人以为「这里才是兜底的地方」。
 */
@Component
public class LocalAuditSink implements AuditSink {

    private final AuditLogService auditLogService;

    public LocalAuditSink(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public void write(AuditLogRecord record) {
        auditLogService.record(record);
    }
}

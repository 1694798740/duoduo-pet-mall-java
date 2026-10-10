package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.audit.AuditLogRecord;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 审计记录汇总客户端（E18）：把本域的后台写操作投递给持有审计表的用户域。
 *
 * <p><b>contextId 必须显式给</b>：Spring Cloud OpenFeign 用 name 作 bean 名，
 * duoduo-user 在本应用里已被别的契约用过（成员身份 / 资质 / 风险查询），
 * 再以不同契约注册会撞 BeanDefinitionOverrideException —— <b>启动即崩</b>，
 * 而这个错误单测（用 Mockito 打桩、不走服务发现）是发现不了的，只有真起容器才暴露。
 *
 * <p><b>这是一个旁路调用</b>：失败不能影响业务，因此调用方（RemoteAuditSink）
 * 会吞掉异常并降级记日志。也正因如此，这里不做重试 ——
 * 审计的诉求是「别拖慢业务」，不是「一定送达」；真要保证送达需要外部队列，
 * 那是另一件事（已登记为后续待办）。
 *
 * <p>内部凭据由本域的 InternalCallConfig 统一注入（每次调用重新签名）。
 */
@FeignClient(name = "duoduo-user", contextId = "tradeAuditLogClient", path = "/api/users/internal/audit-logs")
public interface AuditLogClient {

    /** 目标端点恒返回 200，用响应体里的 written 表示「到底写进去没有」 */
    @PostMapping
    ApiResponse<Written> write(@RequestBody AuditLogRecord record);

    /** 写入结果（与用户域 AuditLogInternalController.WriteResult 结构一致） */
    record Written(boolean written) {
    }
}

package com.duoduo.common.web;

import com.duoduo.common.api.RequestIdHolder;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditLogRecord;
import com.duoduo.common.audit.AuditOutcome;
import com.duoduo.common.audit.AuditSink;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 后台写操作留痕拦截器（E18）。
 *
 * <p>对带 {@link Audited} 注解的控制器方法，环绕采集「人 / 时间 / 动作 / 目标 /
 * 前后值 / IP / 链路 / 结果」并交给 {@link AuditSink} 落库。
 *
 * <h2>★ 三个必须写清楚的实现细节</h2>
 *
 * <p><b>① 为什么注册在权限拦截器「之前」</b>：否则「越权被拒」不会留痕。
 * 权限拦截器返回 false / 抛异常时，Spring 只会为**已经成功执行过 preHandle** 的拦截器
 * 调用 afterCompletion。把审计排在权限之后，就等于「只有有权限的人的动作才被记录」——
 * 而连续失败的提权尝试恰恰是最该被看见的信号。注册顺序见 {@code WebAutoConfiguration}。
 *
 * <p><b>② 失败判定必须同时看响应状态码，不能只看异常</b>：业务异常（422 / 403 / 404）
 * 会被 {@code GlobalExceptionHandler} 解析成正常响应，此时 Spring 传给
 * {@code afterCompletion} 的 {@code ex} 是 <b>null</b>（异常已被处理掉），
 * 只有 {@code response.getStatus()} 变成了 4xx。
 * <b>只看 ex 的话，所有「被业务规则拒绝」的动作都会被记成 SUCCESS</b> ——
 * 一条写着「成功」其实被拒的记录，比没有记录更糟。
 *
 * <p><b>③ 必须是「旁路」，任何失败都不影响业务</b>：这里跑在响应已经写完之后，
 * 再抛异常已经改不了结果，只会污染日志。sink 的契约要求实现自己吞异常，
 * 这里再包一层是双保险 —— 审计系统不可用不该让业务不可用。
 *
 * <p>本类**不加 @Component**：它在 {@code com.duoduo.common.web} 包下，
 * 不在业务服务的扫描范围内，由 {@code WebAutoConfiguration} 显式登记。
 */
public class AuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

    /** 写库时间统一 UTC + Z（项目既有口径）；用 ISO-8601 字符串跨服务传，避免各服务 Jackson 配置差异。 */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * 网关透传的真实调用方 IP。
     *
     * <p>刻意不用 {@code X-Forwarded-For} 的首段：那是调用方**自己填**的，
     * 谁都能编。网关已把不可信的入站值清理掉、只写这一个头
     * （见网关过滤器的 {@code X-Real-IP} 注释），此处直接采信它。
     */
    private static final String HEADER_REAL_IP = "X-Real-IP";

    private final ObjectProvider<AuditSink> sinkProvider;

    public AuditInterceptor(ObjectProvider<AuditSink> sinkProvider) {
        this.sinkProvider = sinkProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Audited audited = auditedOf(handler);
        if (audited == null) {
            return true;
        }
        AuditTrail.start(audited.action());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 没有活动上下文 = 这个请求没被注解标记过（preHandle 没建）；直接返回，
        // 不要在这里 clear —— 那就成了一次多余的 ThreadLocal 操作，也会掩盖真正的泄漏。
        if (AuditTrail.current() == null) {
            return;
        }
        try {
            Audited audited = auditedOf(handler);
            if (audited == null) {
                return;
            }
            boolean failed = ex != null || response.getStatus() >= 400;
            if (failed && !audited.recordOnFailure()) {
                return;
            }
            AuditTrail trail = AuditTrail.current();
            if (trail != null && trail.skipped()) {
                // 幂等确认（无事实变化）：按业务口径不留痕。★ 不静默 —— DEBUG 下可见，
                // 这样「某个动作用的抑制变多了」是可以被发现的（打开 DEBUG 即可）。
                log.debug("审计抑制：动作成功但无事实变化 action={} path={} reason={}",
                        audited.action().code(), request.getRequestURI(), trail.skipReason());
                return;
            }
            write(request, audited, failed, response.getStatus());
        } catch (Exception e) {
            // 审计是旁路：任何问题都只记日志，绝不向外抛。
            log.warn("审计留痕失败（不影响业务）action={} path={}",
                    auditedOf(handler) == null ? "-" : auditedOf(handler).action(), request.getRequestURI(), e);
        } finally {
            // ★ 必须在 finally：线程会被复用，不清理会把上一条记录的上下文
            // 带到下一个请求上（那会写出张冠李戴的审计记录，比丢一条更糟）。
            AuditTrail.clear();
        }
    }

    private void write(HttpServletRequest request, Audited audited, boolean failed, int status) {
        AuditSink sink = sinkProvider.getIfAvailable();
        AuditTrail trail = AuditTrail.current();
        AuditAction action = trail == null || trail.action() == null ? audited.action() : trail.action();

        LoginUser actor = UserContext.get();
        AuditLogRecord record = new AuditLogRecord(
                actor == null ? null : actor.userId(),
                actor == null || actor.phone() == null ? "" : actor.phone(),
                action.code(),
                action.category().name(),
                (failed ? AuditOutcome.FAILED : AuditOutcome.SUCCESS).name(),
                trail == null ? 0L : trail.targetId(),
                trail == null || trail.targetType() == null ? action.targetType() : trail.targetType(),
                trail == null ? 0L : trail.tenantId(),
                trail == null ? null : trail.beforeValue(),
                trail == null ? null : trail.afterValue(),
                request.getHeader(HEADER_REAL_IP),
                RequestIdHolder.get(),
                detail(trail, request, failed, status),
                LocalDateTime.now(ZoneOffset.UTC).format(ISO)
        ).sanitized();

        if (sink == null) {
            // 没有 sink 实现（该服务不参与审计汇总）。这不是错误，但必须留下痕迹：
            // 「以为记了、其实没记」是审计里最危险的状态。
            log.debug("审计记录未落库（本服务未注册 AuditSink）action={} path={}",
                    action.code(), request.getRequestURI());
            return;
        }
        sink.write(record);
    }

    /**
     * 人读摘要的兜底。
     *
     * <p>{@code detail} 在库里是 {@code NOT NULL}，所以**永远不能给它 null**。
     * 业务代码没写摘要时，退回「方法 + 路径 + 状态」—— 信息量不大，
     * 但保证每一行都读得懂，而不是一列空白或一串 null。
     */
    private static String detail(AuditTrail trail, HttpServletRequest request, boolean failed, int status) {
        String explicit = trail == null ? null : trail.detail();
        if (explicit != null && !explicit.isBlank()) {
            return failed ? explicit + "（未生效，HTTP " + status + "）" : explicit;
        }
        String base = request.getMethod() + " " + request.getRequestURI();
        return failed ? base + "（未生效，HTTP " + status + "）" : base;
    }

    private static Audited auditedOf(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return null;
        }
        return method.getMethodAnnotation(Audited.class);
    }
}

package com.duoduo.trade.config;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.audit.AuditSink;
import com.duoduo.common.web.audit.RemoteAuditSink;
import com.duoduo.common.web.internal.InternalCallAuth;
import com.duoduo.trade.client.AuditLogClient;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 服务间调用配置：给所有 Feign 请求加上内部调用凭据。
 *
 * <p>商品服务的库存写接口（冻结 / 释放 / 扣减）要求 {@code X-Internal-Token}，
 * 这里统一注入，避免在每个业务方法里手写请求头（一定会有人漏）。
 *
 * <p>密钥与商品服务共用同一个配置项名（{@code duoduo.internal.token}），
 * 由环境变量 {@code INTERNAL_TOKEN} 覆盖；容器编排里两个服务注入同一个值。
 * <p>第五十八轮（P0-3）：这里不再把密钥原样放进请求头 —— {@link InternalCallAuth}
 * 改为签发「调用方身份 + 时间戳 + 一次性 nonce」的 HMAC 签名，密钥按调用方身份派生，
 * 想单独轮换时用 {@code duoduo.internal.caller-secrets} 给某个调用方指定显式密钥。
 * 根密钥仍是同一个 {@code INTERNAL_TOKEN} 环境变量，容器编排一行都不用改。
 */
@Configuration
public class InternalCallConfig {

    @Bean
    public RequestInterceptor internalTokenInterceptor(InternalCallAuth internalCallAuth) {
        // 每次调用都重新签发：签名里带一次性 nonce，复用同一个请求头会被判为重放。
        // 只签 METHOD、不签路径：见 InternalCallAuth 类注释「为什么签名里不含请求路径」——
        // Feign 的 template.path() **取不到** @FeignClient(path="/api/products") 那段前缀
        // （实测打印为 /internal/skus/batch，服务端看到的却是 /api/products/internal/skus/batch），
        // 签进去就是两侧算出的串不同，表现为全站内部调用 403。
        return template -> template.header(InternalCallAuth.HEADER,
                internalCallAuth.sign(template.method()));
    }

    /**
     * E18 · 审计汇总端口：本域的后台写操作转发到用户域的 t_audit_log。
     *
     * <p>AuditInterceptor 只依赖 AuditSink 这个端口，因此「哪个域自己写、
     * 哪个域转发」不会泄漏进框架代码 —— 新增一个业务域只要提供这样一个 Bean，
     * 拦截器一行都不用改。
     */
    @Bean
    public AuditSink auditSink(AuditLogClient auditLogClient) {
        return new RemoteAuditSink("duoduo-trade", record -> {
            ApiResponse<AuditLogClient.Written> resp = auditLogClient.write(record);
            // ① 判空：对端返回体结构变了、或代理吞了 body，都不该变成 NPE；
            // ② 看 written 而不是只看 HTTP 200：写入失败时对端**仍然返回 200**
            //    （审计是旁路，不能让它把发起方的请求变成错误），
            //    真相只在响应体里 —— 只看状态码会把「没写进去」当成成功。
            return resp != null && resp.data() != null && resp.data().written();
        });
    }
}

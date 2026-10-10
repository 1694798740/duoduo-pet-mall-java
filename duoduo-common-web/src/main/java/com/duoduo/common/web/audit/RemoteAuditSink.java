package com.duoduo.common.web.audit;

import com.duoduo.common.audit.AuditLogRecord;
import com.duoduo.common.audit.AuditSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 跨服务审计汇总落库（E18）：把本域的后台写操作转发给持有审计表的服务（user 域）。
 *
 * <p><b>为什么做成通用实现 + 一个「转发器」接口</b>：product / trade / fulfilment 三个域
 * 都需要它，区别只有「用哪个 Feign 客户端」。如果把「转发 + 判空 + 降级日志」这套逻辑
 * 在三处各写一遍，那么将来「转发失败要记什么、降级到什么程度」这件事也会有三份口径，
 * 而它们的差异只会在真正出事的那天才暴露。
 *
 * <p>本类**不加 @Component**：它需要各域自己提供 {@link Forwarder}，
 * 由各域的配置类登记为 Bean（见 {@code InternalCallConfig}）。
 *
 * <h2>★ 失败处理是这一层的主要工作</h2>
 * 审计是旁路，它有三种失败，每一种都要走向「留下痕迹」而不是「静默消失」：
 * <ol>
 *   <li><b>网络/服务不可用</b> → 记录本身降级写进本服务的 ERROR 日志
 *       （「审计表里没有、日志里也没有」的状态完全无法事后重建，必须避免）；</li>
 *   <li><b>对端 200 但写入失败</b>（{@code written=false}）→ 同样降级记日志。
 *       这条最容易被漏：HTTP 层看起来一切正常，只有响应体说了实话；</li>
 *   <li><b>异常抛出</b> → 全部吞掉。放它上去会打断一次已经完成的业务操作。</li>
 * </ol>
 */
public class RemoteAuditSink implements AuditSink {

    private static final Logger log = LoggerFactory.getLogger(RemoteAuditSink.class);

    /**
     * 把记录投递到汇总端。
     *
     * @return 对方是否确认写入成功（{@code false} 会触发降级日志，而不是异常）
     */
    @FunctionalInterface
    public interface Forwarder {
        boolean forward(AuditLogRecord record);
    }

    private final Forwarder forwarder;
    /** 本域名字，只用于日志定位「哪个域转发失败」。 */
    private final String domain;

    public RemoteAuditSink(String domain, Forwarder forwarder) {
        this.domain = domain;
        this.forwarder = forwarder;
    }

    @Override
    public void write(AuditLogRecord record) {
        if (record == null) {
            return;
        }
        AuditLogRecord payload = record.sanitized();
        try {
            if (!forwarder.forward(payload)) {
                log.error("审计汇总端未确认写入（对方返回 written=false），降级记录到本域日志 domain={} action={} actorId={} targetId={} detail={}",
                        domain, payload.action(), payload.actorId(), payload.targetId(), payload.detail());
            }
        } catch (Exception ex) {
            log.error("审计转发失败，降级记录到本域日志 domain={} action={} actorId={} targetId={} detail={}",
                    domain, payload.action(), payload.actorId(), payload.targetId(), payload.detail(), ex);
        }
    }
}

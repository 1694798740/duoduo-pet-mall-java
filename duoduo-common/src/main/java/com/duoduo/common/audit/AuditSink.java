package com.duoduo.common.audit;

/**
 * 审计记录的落库端口（E18）。
 *
 * <p>它是一个**端口（SPI）**而不是一个具体实现，因为审计记录只有一个去处
 * （{@code duoduo_user.t_audit_log}），但产生它的服务有五个，而其中只有
 * {@code duoduo-user} 连得到那张表。于是：
 * <ul>
 *   <li>{@code duoduo-user} 提供「本地写」（{@code LocalAuditSink}）；</li>
 *   <li>其余服务提供「转到 user 域」（{@code RemoteAuditSink}，走
 *       {@code /api/users/internal/audit-logs} 内部通道）。</li>
 * </ul>
 * 切面（{@code AuditInterceptor}）只依赖这个接口，因此**加一个新的业务域不需要改切面**。
 *
 * <p><b>★ 实现必须自己吞掉失败、只打日志，不得向外抛</b>：审计是**旁路**。
 * 让它有能力把一次正常的业务操作变成 500，是「为了审计而牺牲业务」——
 * 方向错了。切面侧还会再包一层 try/catch 兜底（双保险），
 * 但契约写在这里，是为了让实现者知道「吞掉失败」是设计要求，不是偷懒。
 *
 * <p><b>★ 记录不完整也比没有强</b>：跨域转发失败时，实现应把记录降级写成
 * 服务端日志（至少留下痕迹），而不是静默丢弃。一个「审计表里没有、日志里也没有」
 * 的动作，事后完全无法重建。
 */
public interface AuditSink {

    /**
     * 落一条审计记录。
     *
     * @param record 已经过 {@link AuditLogRecord#sanitized()} 收敛的记录；
     *               {@code null} 应被忽略（不抛异常）。
     */
    void write(AuditLogRecord record);
}

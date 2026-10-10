package com.duoduo.common.web;

import com.duoduo.common.exception.BizException;
import com.duoduo.common.tenant.TenantContext;
import com.duoduo.common.tenant.TenantTables;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户上下文拦截器（E20）：把网关写下的 {@link TenantTables#HEADER} 还原成
 * {@link TenantContext}，供数据层追加行级过滤条件。
 *
 * <h2>注册顺序</h2>
 * 必须排在 {@link UserContextInterceptor} **之后**（同一条"上下文类拦截器"的纪律：
 * 先把身份建好，再谈它属于谁），并且**在审计拦截器之前** ——
 * 审计记录里的租户维度读的就是本拦截器建好的上下文。
 *
 * <h2>★ 三种输入，三种处理（这里是本节最该读懂的地方）</h2>
 * <table>
 *   <tr><th>输入</th><th>处理</th><th>为什么</th></tr>
 *   <tr><td>头不存在</td><td>不设上下文（= 不追加租户条件）</td>
 *       <td>这是**合法且常见**的状态：平台运营/管理员跨租户巡查、服务间内部调用、
 *           定时任务、买家请求、没有 {@code tid} 声明的老令牌。跟 E20 之前的行为完全一致，
 *           上线零风险（纵深防御，不是唯一防线）。</td></tr>
 *   <tr><td>合法正整数（含 0 = 自营）</td><td>设上下文</td><td>正常路径。</td></tr>
 *   <tr><td><b>非法值</b></td><td><b>拒绝（400）</b></td>
 *       <td>网关是用 {@code String.valueOf(long)} 写这个头的，**它写不出非法值**。
 *           所以非法值只可能来自"有人绕过网关直连"或"中间件/版本不一致"。
 *           此时若按"未声明"处理，请求会以**不加任何租户条件**的姿态继续执行 ——
 *           一次**静默放宽**，而且接口 200、日志里什么都没有。
 *           宁可在这里响亮地失败：它要么是攻击，要么是配置错误，两种情况都该被人看见。</td></tr>
 * </table>
 *
 * <h2>清理</h2>
 * Servlet 容器线程是复用的，因此 <b>{@code preHandle} 先清一次、{@code afterCompletion} 再清一次</b>。
 * 只清一次不够：{@code preHandle} 里不先清，上一条请求（异常路径没走到 afterCompletion 的那种）
 * 残留的租户会被下一条请求继承；只在 afterCompletion 清，则抛异常跳出时可能漏掉。
 * 两处都清是廉价的冗余，而漏清的代价是**跨请求的数据越权**。
 */
public class TenantContextInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantContextInterceptor.class);

    /** 头值过长时不把原文写进日志（正常值最长 19 位数字） */
    private static final int MAX_LOG_VALUE_LENGTH = 64;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 先清：绝不能继承同一个线程上一条请求的租户（见类注释"清理"）
        TenantContext.clear();

        String raw = request.getHeader(TenantTables.HEADER);
        if (raw == null || raw.isBlank()) {
            return true;
        }

        String trimmed = raw.trim();
        Long tenantId;
        try {
            tenantId = Long.valueOf(trimmed);
        } catch (NumberFormatException ex) {
            // 失败方向必须"响亮"，不能"放宽"（见类注释的表格）
            log.warn("租户头无法解析，请求被拒绝 path={} value={}",
                    request.getRequestURI(), truncate(trimmed));
            throw BizException.validation("请求携带的租户标识不合法");
        }
        if (tenantId < 0) {
            log.warn("租户头为负数，请求被拒绝 path={} value={}", request.getRequestURI(), tenantId);
            throw BizException.validation("请求携带的租户标识不合法");
        }
        // 0 是平台自营，**是有效租户**，照常设置（见 TenantContext 的口径说明）
        TenantContext.set(tenantId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }

    private static String truncate(String value) {
        return value.length() <= MAX_LOG_VALUE_LENGTH ? value : value.substring(0, MAX_LOG_VALUE_LENGTH) + "…";
    }
}

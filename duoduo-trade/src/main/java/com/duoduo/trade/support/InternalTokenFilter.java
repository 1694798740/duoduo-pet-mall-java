package com.duoduo.trade.support;

import com.duoduo.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 内部接口访问控制：校验服务间 / 运营侧调用的共享密钥。
 *
 * <p>保护的接口：
 * <ul>
 *   <li>{@code POST /api/orders/{orderNo}/ship} —— <b>发货</b>。
 *       发货本该由卖家执行，但卖家账号体系要到卖家中心（M5-9）才落地。
 *       在那之前用内部凭据保护：既让整条链路（支付 → 发货 → 收货）现在就能测，
 *       又不至于把「任何人登录后都能替别人发货」这个口子留着。</li>
 *   <li>{@code /api/orders/internal/**} —— 补偿调度的手动触发（便于测试与运维排障）。</li>
 * </ul>
 *
 * <p>设计取舍同商品服务：<b>默认开启、失败关闭</b>，配置项带开发默认值以便本地容器直接跑通，
 * 生产必须用环境变量 {@code INTERNAL_TOKEN} 覆盖；绝不写「没配就放行」——
 * 那会把一次配置遗漏直接变成线上越权。
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PREFIX = "/api/orders/internal/";
    private static final String SHIP_SUFFIX = "/ship";
    private static final String HEADER = "X-Internal-Token";

    private final String expectedToken;
    private final ObjectMapper objectMapper;

    public InternalTokenFilter(@Value("${duoduo.internal.token}") String expectedToken,
                               ObjectMapper objectMapper) {
        this.expectedToken = expectedToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        boolean guarded = uri.startsWith(INTERNAL_PREFIX) || uri.endsWith(SHIP_SUFFIX);
        if (!guarded) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = request.getHeader(HEADER);
        if (token != null && !expectedToken.isEmpty() && token.equals(expectedToken)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.fail("FORBIDDEN", "该接口仅允许携带正确凭据的服务间 / 运营侧调用"));
    }
}

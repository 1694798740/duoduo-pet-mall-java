package com.duoduo.product.support;

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
 * 内部接口访问控制：校验服务间调用的共享密钥。
 *
 * <p>为什么需要：库存冻结 / 释放 / 扣减是<b>写操作</b>，一旦被外部直接调用，
 * 任何人就能凭空冻结或扣减商品库存。它不像只读接口那样「被看到也无所谓」。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>默认开启、失败关闭</b>：配置项带开发默认值（便于本地容器直接跑通），
 *       生产必须用环境变量 {@code INTERNAL_TOKEN} 覆盖。
 *       绝不采用「没配就放行」的写法 —— 那会让一次配置遗漏直接变成线上越权漏洞。</li>
 *   <li><b>用 Filter 而不是每个方法里判断</b>：控制器方法多起来时，逐个判断一定会有人漏。</li>
 *   <li>共享密钥只是第一道门。生产形态应换成 mTLS 或网关内网隔离 + 服务网格授权策略；
 *       本阶段用它把「完全裸奔」变成「需要密钥」，成本极低。</li>
 * </ul>
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 受保护的路径前缀 */
    private static final String PROTECTED_PREFIX = "/api/products/skus/stock/";
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
        if (!request.getRequestURI().startsWith(PROTECTED_PREFIX)) {
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
                ApiResponse.fail("FORBIDDEN", "内部接口仅允许携带正确凭据的服务间调用"));
    }
}

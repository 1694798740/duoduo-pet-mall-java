package com.duoduo.marketing.support;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.web.internal.InternalCallAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 内部接口访问控制：校验服务间调用的共享密钥（{@code X-Internal-Token}）。
 *
 * <p>为什么必须校验：锁券 / 解锁 / 核销都是<b>写操作</b>，且直接连通资金链路 ——
 * 凭空核销一张券就等于让用户白得一次折扣，凭空解锁则能让同一张券被两单同时使用。
 * 它们不像「券模板列表」那样被看到也无所谓。
 *
 * <p>三条取舍（与商品域 / 履约域的同类过滤器完全一致，改动必须同步四处）：
 * <ul>
 *   <li><b>默认开启、失败关闭</b>：配置项带开发默认值（便于本地容器直接跑通），
 *       生产必须用环境变量 {@code INTERNAL_TOKEN} 覆盖。
 *       绝不采用「没配就放行」的写法 —— 那会让一次配置遗漏直接变成线上越权漏洞（INV-11）。</li>
 *   <li><b>用 Filter 而不是每个方法里判断</b>：控制器方法多起来时，逐个判断一定会有人漏。</li>
 *   <li>共享密钥只是第一道门；生产形态应换成 mTLS 或网关内网隔离 + 服务网格授权策略。</li>
 * </ul>
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 受保护的前缀：券的所有内部写接口 */
    private static final String PROTECTED_PREFIX = "/api/coupons/internal/";

    private final InternalCallAuth internalCallAuth;
    private final ObjectMapper objectMapper;

    public InternalTokenFilter(InternalCallAuth internalCallAuth,
                               ObjectMapper objectMapper) {
        this.internalCallAuth = internalCallAuth;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!uri.startsWith(PROTECTED_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = request.getHeader(InternalCallAuth.HEADER);
        if (internalCallAuth.verify(token, request.getMethod()).ok()) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.fail("FORBIDDEN", "内部接口仅允许携带正确凭据的服务间调用"));
    }
}

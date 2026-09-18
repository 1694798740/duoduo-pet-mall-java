package com.duoduo.user.support;

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
 * <p>保护的接口：{@code /api/users/internal/**} —— 目前是「批量取昵称 / 头像」，
 * 供履约域在评价列表里补全展示信息。
 *
 * <p>为什么昵称头像也要凭据把关：它们是用户数据，且**批量**接口一旦裸露，
 * 就成了「按 id 遍历全站用户资料」的爬取入口。批量能力必须比单条查询更严格，
 * 这是行业内多起数据泄露事故的共同教训。
 *
 * <p>与其他服务同一取舍：<b>默认开启、失败关闭</b>，配置项带开发默认值以便本地容器跑通，
 * 生产必须用环境变量 {@code INTERNAL_TOKEN} 覆盖；绝不写「没配就放行」。
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PREFIX = "/api/users/internal/";
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
        if (!request.getRequestURI().startsWith(INTERNAL_PREFIX)) {
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
                ApiResponse.fail("FORBIDDEN", "该接口仅允许携带正确凭据的服务间调用"));
    }
}

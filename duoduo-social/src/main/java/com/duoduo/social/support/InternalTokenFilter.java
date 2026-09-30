package com.duoduo.social.support;

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
 * 内部接口访问控制：校验服务间调用的共享密钥（与用户域 / 营销域同一样板）。
 *
 * <p>保护的接口：{@code /api/socials/internal/**} —— 交易域 / 履约域推送订单、售后、
 * 物流通知的入口（第四十七轮）。
 *
 * <p>为什么通知推送也要凭据：它是**批量写入**入口（一次可给多个收件人落行），
 * 一旦裸露就成了「向全站用户投递任意内容」的垃圾信息通道 —— 与「批量取昵称必须凭据」
 * 是同一类风险，只是方向相反（一个往外爬数据，一个往里塞内容）。
 *
 * <p>同一取舍：<b>默认开启、失败关闭</b>；生产必须用环境变量 {@code INTERNAL_TOKEN} 覆盖。
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 列举式前缀（不用宽松 contains）：匹配规则的正确性靠阅读本文件即可确认 */
    private static final String[] INTERNAL_PREFIXES = {
            "/api/socials/internal/"
    };

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
        if (!isInternal(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = request.getHeader(InternalCallAuth.HEADER);
        if (internalCallAuth.verify(token, request.getMethod(), request.getRequestURI()).ok()) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.fail("FORBIDDEN", "该接口仅允许携带正确凭据的服务间调用"));
    }

    private static boolean isInternal(String uri) {
        for (String prefix : INTERNAL_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

package com.duoduo.product.support;

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
 * 内部接口访问控制：校验服务间调用的凭据。
 *
 * <p>为什么需要：库存冻结 / 释放 / 扣减是<b>写操作</b>，一旦被外部直接调用，
 * 任何人就能凭空冻结或扣减商品库存。它不像只读接口那样「被看到也无所谓」。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>默认开启、失败关闭</b>：校验逻辑在 {@link InternalCallAuth}，
 *       格式 / 时效 / 身份 / 签名 / nonce 任何一步对不上都是 403。
 *       绝不采用「没配就放行」的写法 —— 那会让一次配置遗漏直接变成线上越权漏洞。</li>
 *   <li><b>用 Filter 而不是每个方法里判断</b>：控制器方法多起来时，逐个判断一定会有人漏。</li>
 *   <li>共享密钥只是第一道门。生产形态应换成 mTLS 或网关内网隔离 + 服务网格授权策略；
 *       本阶段用它把「完全裸奔」变成「需要密钥」，成本极低。</li>
 * </ul>
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 受保护的路径前缀：库存写操作 + 服务间批量快照（快照含卖家归属等内部口径字段） */
    private static final String PROTECTED_PREFIX_STOCK = "/api/products/skus/stock/";
    private static final String PROTECTED_PREFIX_INTERNAL = "/api/products/internal/";
    /** 闲置服务间接口（第五十三轮）：InternalListingView 带 sellerId，同属内部口径 */
    private static final String PROTECTED_PREFIX_LISTING_INTERNAL = "/api/listings/internal/";

    private final InternalCallAuth internalCallAuth;
    private final ObjectMapper objectMapper;

    public InternalTokenFilter(InternalCallAuth internalCallAuth, ObjectMapper objectMapper) {
        this.internalCallAuth = internalCallAuth;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!uri.startsWith(PROTECTED_PREFIX_STOCK)
                && !uri.startsWith(PROTECTED_PREFIX_INTERNAL)
                && !uri.startsWith(PROTECTED_PREFIX_LISTING_INTERNAL)) {
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

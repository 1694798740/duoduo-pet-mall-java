package com.duoduo.trade.support;

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
 * 内部接口访问控制：校验服务间 / 运营侧调用的共享密钥。
 *
 * <p>保护的接口：
 * <ul>
 *   <li>{@code POST /api/orders/{orderNo}/ship} —— <b>发货</b>。
 *       发货本该由卖家执行；卖家账号体系在卖家中心（M5-9）落地后，
 *       真正的入口是 {@code POST /api/seller/orders/{orderNo}/ship}。
 *       这里保留内部凭据通道，是因为既有的运维脚本与集成测试仍在用它，
 *       且「运营代卖家发货」本身也是合法场景。</li>
 *   <li>{@code /api/orders/internal/**} —— 运营侧动作与补偿调度：
 *       售后退款（{@code /internal/{orderNo}/refund}）与手动触发补偿调度（{@code /internal/sweep}）。</li>
 * </ul>
 *
 * <p><b>不保护 {@code /api/seller/**}</b>：卖家中心走登录态 + 归属校验
 * （{@code SellerService#requireSellerScope} 校验角色、{@code requireOwnOrder} 校验订单归属），
 * 这是比共享密钥更细的授权 —— 内部凭据只能证明「你是自己人」，
 * 证明不了「这单是你的」。若不排除，{@code /api/seller/orders/{orderNo}/ship}
 * 会因为以 {@code /ship} 结尾被这里拦掉，卖家永远发不了货。
 *
 * <p>设计取舍同商品服务：<b>默认开启、失败关闭</b>，配置项带开发默认值以便本地容器直接跑通，
 * 生产必须用环境变量 {@code INTERNAL_TOKEN} 覆盖；绝不写「没配就放行」——
 * 那会把一次配置遗漏直接变成线上越权。
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PREFIX = "/api/orders/internal/";
    private static final String SHIP_SUFFIX = "/ship";
    private static final String SELLER_PREFIX = "/api/seller/";

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
        // 卖家中心自带登录态 + 归属校验，不走内部凭据（见类注释）
        boolean guarded = !uri.startsWith(SELLER_PREFIX)
                && (uri.startsWith(INTERNAL_PREFIX) || uri.endsWith(SHIP_SUFFIX));
        if (!guarded) {
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
                ApiResponse.fail("FORBIDDEN", "该接口仅允许携带正确凭据的服务间 / 运营侧调用"));
    }
}

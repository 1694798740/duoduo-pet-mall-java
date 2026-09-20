package com.duoduo.fulfilment.support;

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
import java.util.List;

/**
 * 内部接口访问控制：校验服务间调用的共享密钥。
 *
 * <p>本服务此前**没有**内部接口（只调别人，不被别人调），A2 起有了第一个：
 * 物流轨迹摄入 {@code /api/logistics/internal/**}；A4 起多了信用分回写刷新的
 * {@code /api/reviews/internal/**}。因此这个过滤器是新增的，
 * 口径与 user / product / trade 三份保持一致 —— 同一个安全规则在全项目只有一种实现方式。
 *
 * <p>被保护的前缀<b>显式列举</b>，不做后缀匹配：
 * 交易域之所以还要匹配以 {@code /ship} 结尾的路径，是因为那里有一条历史内部通道需要兼容；
 * 本服务没有这种历史包袱，**匹配面越小越安全** ——
 * 一条「以 /tracks 结尾就要求内部凭据」的规则，将来任何一条新增的用户侧
 * {@code /xxx/tracks} 都会被莫名其妙地拦掉，且错误表现是 403 而非编译失败。
 * 新增内部接口时，把它的前缀加进下面的列表即可。
 *
 * <p><b>默认开启、失败关闭</b>：配置项带开发默认值以便本地容器直接跑通，
 * 生产必须用环境变量 {@code INTERNAL_TOKEN} 覆盖。这里刻意不写「没配就放行」——
 * 那会把一次配置遗漏直接变成线上越权（INV-11）。
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 需要内部凭据的路径前缀（显式列举，见类注释） */
    private static final List<String> INTERNAL_PREFIXES = List.of(
            "/api/logistics/internal/",
            "/api/reviews/internal/");
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
        boolean guarded = INTERNAL_PREFIXES.stream().anyMatch(uri::startsWith);
        if (!guarded) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = request.getHeader(HEADER);
        // 两端都要判：expectedToken 为空时不得因为「token 也恰好为空」而放行
        if (expectedToken != null && !expectedToken.isEmpty()
                && token != null && token.equals(expectedToken)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.fail("FORBIDDEN", "该接口仅允许携带正确凭据的服务间调用"));
    }
}

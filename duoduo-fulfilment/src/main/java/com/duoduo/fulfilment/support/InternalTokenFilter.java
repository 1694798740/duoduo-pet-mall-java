package com.duoduo.fulfilment.support;

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
import java.util.List;

/**
 * 内部接口访问控制：校验服务间调用的凭据。
 *
 * <p>本服务此前**没有**内部接口（只调别人，不被别人调），A2 起有了第一个：
 * 物流轨迹摄入 {@code /api/logistics/internal/**}；A4 起多了信用分回写刷新的
 * {@code /api/reviews/internal/**}；E24 起多了售后聚合 {@code /api/aftersales/internal/**}
 * （供交易域的租户健康度面板跨域读）。因此这个过滤器是新增的，
 * 口径与 user / product / trade 三份保持一致 —— 同一个安全规则在全项目只有一种实现方式。
 *
 * <p>被保护的前缀<b>显式列举</b>，不做后缀匹配：
 * 交易域之所以还要匹配以 {@code /ship} 结尾的路径，是因为那里有一条历史内部通道需要兼容；
 * 本服务没有这种历史包袱，**匹配面越小越安全** ——
 * 一条「以 /tracks 结尾就要求内部凭据」的规则，将来任何一条新增的用户侧
 * {@code /xxx/tracks} 都会被莫名其妙地拦掉，且错误表现是 403 而非编译失败。
 * 新增内部接口时，把它的前缀加进下面的列表即可。
 *
 * <p><b>默认开启、失败关闭</b>：校验逻辑在 {@link InternalCallAuth}，
 * 格式 / 时效 / 身份 / 签名 / nonce 任何一步对不上都是 403。
 * 这里刻意不写「没配就放行」—— 那会把一次配置遗漏直接变成线上越权（INV-11）。
 *
 * <p>第五十八轮（P0-3）：凭据从「明文共享密钥」换成「签名」——
 * 链路上不再出现密钥本身，且签名只在时间窗内有效、绑定调用方身份与方法 + 路径。
 * 校验口径由公共模块的 {@link InternalCallAuth} 统一提供，本类只负责「哪些路径要保护」。
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 需要内部凭据的路径前缀（显式列举，见类注释） */
    private static final List<String> INTERNAL_PREFIXES = List.of(
            "/api/logistics/internal/",
            "/api/reviews/internal/",
            "/api/aftersales/internal/");

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
        boolean guarded = INTERNAL_PREFIXES.stream().anyMatch(uri::startsWith);
        if (!guarded) {
            filterChain.doFilter(request, response);
            return;
        }
        // 路径不含查询串：签名时两侧都用「方法 + 路径」，口径必须一致
        String token = request.getHeader(InternalCallAuth.HEADER);
        if (internalCallAuth.verify(token, request.getMethod()).ok()) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.fail("FORBIDDEN", "该接口仅允许携带正确凭据的服务间调用"));
    }
}

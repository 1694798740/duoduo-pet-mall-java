package com.duoduo.user.support;

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
 * 内部接口访问控制：校验服务间调用的共享密钥。
 *
 * <p>保护的接口：{@code /api/users/internal/**} 与 {@code /api/addresses/internal/**}。
 * 前者是「批量取昵称 / 头像」（供履约域在评价列表里补全展示信息），
 * 后者是「按下单用户 + 地址 ID 取地址快照」（A8，供交易域写订单收货信息）。
 *
 * <p>为什么昵称头像也要凭据把关：它们是用户数据，且**批量**接口一旦裸露，
 * 就成了「按 id 遍历全站用户资料」的爬取入口。批量能力必须比单条查询更严格，
 * 这是行业内多起数据泄露事故的共同教训。
 *
 * <p><b>地址快照接口为什么更要严格</b>：它的入参是
 * {@code userId + addressId}，两个都是可枚举的数字。一旦裸露，
 * 攻击者遍历两者就能把全站用户的收货人姓名与家庭地址拉下来 ——
 * 这是本项目里**敏感度最高**的一类数据。所以它比 {@code /api/users/internal/**}
 * 更需要凭据，而不是因为它「只是内部接口」就默认安全。
 *
 * <p>与其他服务同一取舍：<b>默认开启、失败关闭</b>，配置项带开发默认值以便本地容器跑通，
 * 生产必须用环境变量 {@code INTERNAL_TOKEN} 覆盖；绝不写「没配就放行」。
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    /**
     * 需要凭据保护的内部路径前缀。
     *
     * <p>刻意用「白名单式列举前缀」而不是 {@code contains("/internal/")} 这类宽松匹配：
     * 宽松匹配会让「以后任何新增的 /internal/** 都自动受保护」，看似更安全，
     * 但它同时意味着**匹配规则的正确性无法靠阅读本文件确认**——
     * 新增路径的人根本不会来看这里。列举式的代价是漏加，
     * 而漏加会被冒烟覆盖（脚本里有一条「不带凭据访问内部接口必须 403」的断言）。
     */
    private static final String[] INTERNAL_PREFIXES = {
            "/api/users/internal/",
            "/api/addresses/internal/",
            // 多多币内部接口（R-P1）：冻结 / 扣减 / 释放 / 退款退币 / 返币 / 对账，
            // 交易域写钱与币联动全走这里 —— 币是钱，绝不能裸露
            "/api/coins/internal/"
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
        if (internalCallAuth.verify(token, request.getMethod()).ok()) {
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

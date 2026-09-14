package com.duoduo.common.web;

import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录用户上下文拦截器：从网关透传的请求头还原 {@link LoginUser}。
 *
 * <p><b>安全前提</b>：这些请求头由网关在校验 JWT 之后写入。
 * 因此业务服务**必须禁止公网直连**（只允许网关网段访问，或校验内部令牌），
 * 否则客户端可以伪造 {@code X-User-Id} 冒充他人。生产部署请通过安全组 / Service Mesh 保证这一点。
 */
public class UserContextInterceptor implements HandlerInterceptor {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_PHONE = "X-User-Phone";
    public static final String HEADER_USER_ROLE = "X-User-Role";
    public static final String HEADER_REAL_NAME = "X-Real-Name-Verified";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String rawUserId = request.getHeader(HEADER_USER_ID);
        if (rawUserId != null && !rawUserId.isBlank()) {
            try {
                Long userId = Long.valueOf(rawUserId);
                String phone = request.getHeader(HEADER_USER_PHONE);
                String role = request.getHeader(HEADER_USER_ROLE);
                boolean verified = Boolean.parseBoolean(request.getHeader(HEADER_REAL_NAME));
                UserContext.set(new LoginUser(userId, phone, role == null ? "USER" : role, verified));
            } catch (NumberFormatException ignored) {
                // 非法 userId 视为未登录，交由业务侧 require() 决定是否 401
                UserContext.clear();
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}

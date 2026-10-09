package com.duoduo.common.web;

import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.security.perm.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 声明式判权拦截器（E16）：把 {@link RequirePerm} 注解变成真实的 403 边界。
 *
 * <p><b>必须在 {@link UserContextInterceptor} 之后注册</b>：
 * 本拦截器读的是 {@link com.duoduo.common.context.UserContext}，而上下文正是前一个
 * 拦截器从网关头里还原的。注册顺序颠倒的表现是「所有带注解的端点一律 401」——
 * 看起来像鉴权更严了，实际是上下文还没建好，属于最容易被误读成「正常工作」的故障。
 *
 * <p><b>★ 注解式判权的失效方向是「静默放开」</b>：若本拦截器没被注册（漏装配）、
 * 或不匹配到 handler（路径被 servlet 直接处理）、或注解拼错了权限点，
 * 表现统一是「接口正常返回 200，任何人都能调」，**没有任何报错**。
 * 因此：① 本项目用显式 {@code @Bean} + 自动装配注册（不靠组件扫描，见 WebAutoConfiguration 注释）；
 * ② 每处注解都必须配一条「无权限 → 403」的实测断言。
 */
public class PermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            // 静态资源 / 404 转发等非 Controller 方法：不参与判权
            return true;
        }
        RequirePerm required = handlerMethod.getMethodAnnotation(RequirePerm.class);
        if (required == null) {
            // 方法级优先；方法没写则看类级（类级 = 该 Controller 的默认要求）
            required = handlerMethod.getBeanType().getAnnotation(RequirePerm.class);
        }
        if (required == null) {
            // 未声明注解 → 本拦截器不表态，由业务方法自行 requireXxx()
            return true;
        }
        OperatorGuard.requirePerm(required.value());
        return true;
    }
}

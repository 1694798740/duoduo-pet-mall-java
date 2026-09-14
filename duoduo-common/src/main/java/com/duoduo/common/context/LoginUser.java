package com.duoduo.common.context;

/**
 * 当前登录用户（认证后由网关解析并透传）。
 *
 * <p>安全约定：userId 只能来自服务端校验过的令牌，**绝不接受客户端传入**，
 * 所有数据访问都必须带 userId 维度做越权校验。
 */
public record LoginUser(
        Long userId,
        String phone,
        String role,
        boolean realNameVerified
) {

    public boolean isAdmin() {
        return "admin".equals(role);
    }

    public boolean isOperator() {
        return "operator".equals(role) || isAdmin();
    }
}

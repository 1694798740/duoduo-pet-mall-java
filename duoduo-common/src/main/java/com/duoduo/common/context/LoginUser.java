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
        return "admin".equalsIgnoreCase(role);
    }

    public boolean isOperator() {
        return "operator".equalsIgnoreCase(role) || isAdmin();
    }

    /**
     * 是否为卖家（商家端可见性的唯一判据）。
     *
     * <p>角色取值在库里是大写（{@code USER / SELLER / OPERATOR / ADMIN}），
     * 这里一律忽略大小写比较 —— 历史代码曾用 {@code "admin".equals(role)}，
     * 在大写存储下永远为 false，属于「看起来有权限校验、实际形同虚设」的隐蔽缺陷。
     */
    public boolean isSeller() {
        return "seller".equalsIgnoreCase(role);
    }

    /** 能否进入商家端：卖家本人，或运营 / 管理员（后者用于代运营与排查） */
    public boolean canAccessSellerConsole() {
        return isSeller() || isOperator();
    }
}

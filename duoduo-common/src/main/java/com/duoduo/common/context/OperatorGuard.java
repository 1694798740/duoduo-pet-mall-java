package com.duoduo.common.context;

import com.duoduo.common.exception.BizException;

/**
 * 运营 / 管理员（管理后台）准入校验。
 *
 * <p>角色只在服务端判定：网关负责把令牌里的角色透传成 {@code X-User-Role}，
 * {@link UserContext} 把它还原成 {@link LoginUser}，这里只回答「这个人能不能进后台」。
 * 前端按角色显示入口只是体验优化，真正的边界永远是这一句 —— 与卖家中心
 * {@code SellerService.requireSellerScope()} 同一套「进门与动数分开」的原则：
 * 这里管「进后台」，各 admin service 再各自管「这行数据能不能动」。
 */
public final class OperatorGuard {

    private OperatorGuard() {
    }

    /** 要求调用者是运营或管理员，否则 403。返回当前登录用户供后续使用。 */
    public static LoginUser requireOperator() {
        LoginUser user = UserContext.require();
        if (user == null || !user.isOperator()) {
            throw BizException.forbidden("仅运营 / 管理员可执行此操作");
        }
        return user;
    }
}

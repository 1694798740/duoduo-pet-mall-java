package com.duoduo.common.context;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;

/**
 * 登录用户上下文（ThreadLocal）。
 * 由 servlet 侧的拦截器写入、请求结束时清理；服务层通过它取当前用户，避免层层传参。
 *
 * <p>注意：异步线程池中 ThreadLocal 不传递。跨线程场景请显式传递 LoginUser，
 * 或使用 TaskDecorator 复制上下文。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 取当前用户，未登录直接抛 401（用于必须登录的接口） */
    public static LoginUser require() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    public static Long requireUserId() {
        return require().userId();
    }

    /** 要求已实名（发布闲置、提现等能力） */
    public static LoginUser requireRealNameVerified() {
        LoginUser user = require();
        if (!user.realNameVerified()) {
            throw BizException.forbidden("该操作需要先完成实名认证");
        }
        return user;
    }
}

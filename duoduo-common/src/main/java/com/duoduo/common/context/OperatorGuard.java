package com.duoduo.common.context;

import com.duoduo.common.exception.BizException;
import com.duoduo.common.security.perm.Permission;
import com.duoduo.common.security.perm.RolePermissionMatrix;

/**
 * 平台后台准入校验（命令式）。
 *
 * <p>角色只在服务端判定：网关负责把令牌里的角色透传成 {@code X-User-Role}，
 * {@link UserContext} 把它还原成 {@link LoginUser}，这里只回答「这个人能不能做这件事」。
 * 前端按角色显示入口只是体验优化，真正的边界永远是这一句。
 *
 * <p><b>E16 起：本类不再是「角色比较」，而是「权限点校验」的薄封装</b>。
 * {@link #requireOperator()} 的实现从 {@code user.isOperator()} 改为委托
 * {@link #requirePerm(String)} + 权限点 {@code platform:console} ——
 * 行为完全一致（矩阵里该点恰好只给 OPERATOR/ADMIN），但口径落到了
 * {@link RolePermissionMatrix} 一处。「进门与动数分开」的既有原则不变：
 * 这里管「进后台」，各 admin service 再各自管「这行数据能不能动」。
 *
 * <p><b>新增端点优先用注解 {@code @RequirePerm} 而不是本类</b>：
 * 命令式写法漏写不会报错（接口静默变成公开），注解写在签名上一眼可见。
 * 本类保留给「方法体中途才判权」「需要拿到 LoginUser 做后续归属校验」
 * 以及历史 55 处调用点 —— 它们不必为了改名制造大面积 diff。
 */
public final class OperatorGuard {

    private OperatorGuard() {
    }

    /**
     * 要求调用者是运营或管理员（权限点 {@code platform:console}），否则 403。返回当前登录用户供后续使用。
     *
     * <p><b>报错文案与 E16 之前逐字一致</b>（「仅运营 / 管理员可执行此操作」）：
     * 已有单测（{@code SellerProductServiceTest}）断言了这句话，且它出现在约 55 处
     * 调用点共用的路径上。改文案属于「无收益的行为变化」—— 权限点标识只在
     * {@link #requirePerm(String)} 这一新入口上附加，老入口保持原样。
     */
    public static LoginUser requireOperator() {
        return require(Permission.PLATFORM_CONSOLE, "仅运营 / 管理员可执行此操作");
    }

    /**
     * 要求调用者拥有指定权限点，否则 403。返回当前登录用户供后续使用。
     *
     * <p>报错信息带上权限点标识 —— 排查越权问题时，日志里有权限点才知道
     * 该去 {@link RolePermissionMatrix} 的哪一行看。未登录的情形由
     * {@link UserContext#require()} 报 401，与「登录了但没这个权限」的 403 分得清。
     */
    public static LoginUser requirePerm(String perm) {
        return require(perm, "当前账号没有执行该操作的权限（" + perm + "）");
    }

    private static LoginUser require(String perm, String message) {
        LoginUser user = UserContext.require();
        if (user == null || !user.hasPerm(perm)) {
            throw BizException.forbidden(message);
        }
        return user;
    }
}

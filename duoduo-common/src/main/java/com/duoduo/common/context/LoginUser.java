package com.duoduo.common.context;

import com.duoduo.common.security.perm.Permission;
import com.duoduo.common.security.perm.RolePermissionMatrix;

/**
 * 当前登录用户（认证后由网关解析并透传）。
 *
 * <p>安全约定：userId 只能来自服务端校验过的令牌，**绝不接受客户端传入**，
 * 所有数据访问都必须带 userId 维度做越权校验。
 *
 * <p><b>E16 起：角色判定与权限判定分开</b>。
 * <ul>
 *   <li>{@link #isAdmin()} / {@link #isSeller()} 是**角色**判定（「你是谁」），
 *       仍然按归一化后的角色名比较 —— 「本轮最后一个 ADMIN 不可降级」这类规则
 *       本质是角色约束（要保住一个能改角色的人），不是权限点。</li>
 *   <li>{@link #hasPerm(String)} 是**权限**判定（「你能不能做这件事」），
 *       一律经 {@link RolePermissionMatrix}。业务判权只应使用它。</li>
 *   <li>{@link #isOperator()} / {@link #canAccessSellerConsole()} 保留为
 *       {@code hasPerm} 的别名 —— 它们的语义正是权限点 {@code platform:console}
 *       与 {@code seller:console}。保留而不删除，是因为全项目有约 55 处调用点，
 *       一次性改名换不来任何安全收益，只会制造大面积 diff 与漏改风险；
 *       而**语义已经在矩阵里收口**，改口径只需改矩阵一处。</li>
 * </ul>
 */
public record LoginUser(
        Long userId,
        String phone,
        String role,
        boolean realNameVerified
) {

    /** 角色判定：是否超级管理员。权限判定请用 {@link #hasPerm(String)}。 */
    public boolean isAdmin() {
        return RolePermissionMatrix.ROLE_ADMIN.equals(RolePermissionMatrix.canonical(role));
    }

    /**
     * 能否进入平台后台 —— 即权限点 {@code platform:console}。
     *
     * <p>（口径与 E16 之前完全一致：OPERATOR / ADMIN。改名不改行为，见类注释。）
     */
    public boolean isOperator() {
        return hasPerm(Permission.PLATFORM_CONSOLE);
    }

    /**
     * 是否为卖家（商家端可见性的角色判据）。
     *
     * <p>角色取值在库里是大写（{@code USER / SELLER / OPERATOR / ADMIN}）——
     * 历史代码曾用 {@code "admin".equals(role)}，在大写存储下永远为 false，
     * 属于「看起来有权限校验、实际形同虚设」的隐蔽缺陷。归一化统一在
     * {@link RolePermissionMatrix#canonical(String)} 里做，这里不再自行比较大小写。
     */
    public boolean isSeller() {
        return RolePermissionMatrix.ROLE_SELLER.equals(RolePermissionMatrix.canonical(role));
    }

    /**
     * 能否进入商家端 —— 即权限点 {@code seller:console}。
     *
     * <p>口径与 E16 之前一致：卖家本人，或运营 / 管理员（后者用于代运营与排查）。
     * 注意**店员不在此列**：店员 role 仍是 USER，其可见性由
     * {@code t_merchant_member} 的成员身份实时决定（E13），走各自 Service 的成员分支。
     */
    public boolean canAccessSellerConsole() {
        return hasPerm(Permission.SELLER_CONSOLE);
    }

    /**
     * 权限判定（E16）：当前角色是否拥有该权限点。
     *
     * <p>未知 / 空角色一律按最小权限处理（见 {@link RolePermissionMatrix#canonical}）。
     */
    public boolean hasPerm(String perm) {
        return RolePermissionMatrix.has(role, perm);
    }
}

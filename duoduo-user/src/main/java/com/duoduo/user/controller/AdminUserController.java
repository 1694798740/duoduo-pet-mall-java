package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.Audited;
import com.duoduo.user.dto.UserDtos.AdminUserView;
import com.duoduo.user.dto.UserDtos.RoleChangeRequest;
import com.duoduo.user.dto.UserDtos.RoleChangeResult;
import com.duoduo.user.service.AdminUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营侧账号管理，平台管理后台（A7）。
 *
 * <p>权限边界在 {@code AdminUserService}（与 {@code AdminListingController} 同一先例：
 * guard 放服务层）—— 这里只做「解析请求、调服务、格式化响应」。
 * 网关白名单不含 {@code /api/admin/**}，所有请求必须带登录令牌。
 *
 * <p>E18 起 {@code changeRole} 带 {@link Audited}：留痕由拦截器统一采集
 * （谁 / 何时 / 从哪个 IP / 哪条链路 / 成功还是失败），服务层只补「目标是谁、
 * 角色从什么变成什么」。这条端点**不再**自己写审计表 —— 一个动作两条记录
 * 会让审计页上的计数失去意义。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminUserView>> list(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(adminUserService.listUsers(role, keyword, page, pageSize));
    }

    @PostMapping("/{id}/role")
    @Audited(action = AuditAction.USER_ROLE_CHANGE)
    public ApiResponse<RoleChangeResult> changeRole(@PathVariable Long id,
                                                    @RequestBody RoleChangeRequest request) {
        return ApiResponse.ok(adminUserService.changeRole(id, request.role()));
    }

    /**
     * E21 · 重置某账号的两步验证（手机丢失 + 恢复码也丢失时的最后手段）。
     *
     * <p>为什么不做成"用户自助"：自助通道需要另一样能证明身份的东西，而这时两个因子都已经丢了
     * —— 只能由人来判断"这个人确实是账号主人"。因此它是**仅 ADMIN** 的高危动作，
     * 且必然留痕（{@link AuditAction#USER_TOTP_RESET}）：这是全项目唯一一处
     * "能替别人摘下第二把锁"的入口，没有记录就等于没有约束。
     *
     * <p>只返回"成功了"，不返回重置后的任何状态：重置后该账号回到未绑定，
     * 下次登录不再要求验证码 —— 后台页面只需要知道这一步完成了。
     */
    @PostMapping("/{id}/2fa/reset")
    @Audited(action = AuditAction.USER_TOTP_RESET)
    public ApiResponse<Void> resetTwoFactor(@PathVariable Long id) {
        adminUserService.resetTwoFactor(id);
        return ApiResponse.ok();
    }
}

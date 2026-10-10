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
}

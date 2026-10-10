package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.context.UserContext;
import com.duoduo.user.dto.MerchantDtos.StaffView;
import com.duoduo.user.service.MerchantStaffService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 店主侧成员管理（E13）。挂在 {@code /api/seller/**}（商家经营面专属族，E8 入口边界 +
 * 服务端 OWNER 准入两层）。**OWNER-only**：店员调用一律 403 —— 店员能拉人进店，
 * 成员名单就不再是店主可控的范围。
 *
 * <p>E18 · 三个写端点带 {@link Audited}：店主对成员的操作也要留痕，
 * 否则平台在需要复盘「这个店员是谁停的、什么时候停的」时无从查起。
 * 动作标识与平台侧**刻意不同**（店主侧 ENABLE_STAFF / 平台侧 ADMIN_ENABLE_STAFF）——
 * 复盘时要能一眼分清是谁停的，这个区分是 E13 就定下的口径。
 */
@RestController
@RequestMapping("/api/seller/staff")
public class MerchantStaffController {

    private final MerchantStaffService staffService;

    public MerchantStaffController(MerchantStaffService staffService) {
        this.staffService = staffService;
    }

    /** 本店成员列表（含店主本人） */
    @GetMapping
    public ApiResponse<List<StaffView>> list() {
        return ApiResponse.ok(staffService.listForOwner(UserContext.requireUserId()));
    }

    /** 邀请店员（按手机号；一人一店，已在别店的账号拒绝） */
    @PostMapping
    @Audited(action = AuditAction.INVITE_STAFF)
    public ApiResponse<StaffView> invite(@RequestBody Map<String, String> body) {
        String phone = body == null ? null : body.get("phone");
        return ApiResponse.ok(staffService.invite(UserContext.requireUserId(), phone));
    }

    /** 停用店员（即时生效） */
    @PostMapping("/{memberId}/disable")
    @Audited(action = AuditAction.DISABLE_STAFF)
    public ApiResponse<StaffView> disable(@PathVariable("memberId") long memberId) {
        return ApiResponse.ok(staffService.setStaffStatus(UserContext.requireUserId(), memberId, false));
    }

    /** 启用店员 */
    @PostMapping("/{memberId}/enable")
    @Audited(action = AuditAction.ENABLE_STAFF)
    public ApiResponse<StaffView> enable(@PathVariable("memberId") long memberId) {
        return ApiResponse.ok(staffService.setStaffStatus(UserContext.requireUserId(), memberId, true));
    }
}

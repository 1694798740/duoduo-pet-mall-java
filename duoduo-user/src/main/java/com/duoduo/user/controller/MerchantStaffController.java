package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
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
    public ApiResponse<StaffView> invite(@RequestBody Map<String, String> body) {
        String phone = body == null ? null : body.get("phone");
        return ApiResponse.ok(staffService.invite(UserContext.requireUserId(), phone));
    }

    /** 停用店员（即时生效） */
    @PostMapping("/{memberId}/disable")
    public ApiResponse<StaffView> disable(@PathVariable("memberId") long memberId) {
        return ApiResponse.ok(staffService.setStaffStatus(UserContext.requireUserId(), memberId, false));
    }

    /** 启用店员 */
    @PostMapping("/{memberId}/enable")
    public ApiResponse<StaffView> enable(@PathVariable("memberId") long memberId) {
        return ApiResponse.ok(staffService.setStaffStatus(UserContext.requireUserId(), memberId, true));
    }
}

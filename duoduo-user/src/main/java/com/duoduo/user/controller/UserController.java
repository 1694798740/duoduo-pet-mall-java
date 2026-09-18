package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import com.duoduo.user.dto.UserDtos.RealNameRequest;
import com.duoduo.user.dto.UserDtos.UpdateProfileRequest;
import com.duoduo.user.dto.UserDtos.UserView;
import com.duoduo.user.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号信息接口。
 *
 * <p>当前登录用户一律取自 {@link UserContext}（由网关校验令牌后透传），
 * **绝不接受客户端传入 userId**，从根上消除越权读取他人资料的可能。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me() {
        return ApiResponse.ok(authService.profile(UserContext.requireUserId()));
    }

    @PatchMapping("/me")
    public ApiResponse<UserView> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(authService.updateProfile(UserContext.requireUserId(), request));
    }

    /** 实名认证：通过后解锁发布闲置与提现（ACC-04） */
    @PostMapping("/me/realname")
    public ApiResponse<UserView> submitRealName(@Valid @RequestBody RealNameRequest request) {
        return ApiResponse.ok(authService.submitRealName(UserContext.requireUserId(), request));
    }

    /**
     * 申请成为卖家（商家入驻）：实名后把 role 升级为 {@code SELLER}。
     *
     * <p>幂等：已是卖家时直接返回当前资料。未实名返回 422 —— 钱最终要放款给卖家，
     * 匿名卖家会让资金流向不可追责。
     *
     * <p>注意：角色升级后必须<b>重新登录</b>才能生效，因为 access token 里带了 role 快照。
     */
    @PostMapping("/me/merchant")
    public ApiResponse<UserView> applyMerchant() {
        return ApiResponse.ok(authService.applyMerchant(UserContext.requireUserId()));
    }

    /**
     * 服务间只读：批量取用户公开资料（昵称 / 头像）。
     *
     * <p><b>内部接口</b>，由 {@code InternalTokenFilter} 校验 {@code X-Internal-Token}，
     * 因此即便有人带着登录态经网关访问，也会被服务自身拒绝（403）。
     * 参数用「逗号分隔字符串」而不是 {@code List<Long>}：两端的集合参数编码规则
     * （重复 query vs 逗号分隔）在不同 Feign 版本下有过变化，固定成单值后行为完全确定。
     *
     * <p>返回裁剪过的 {@link com.duoduo.user.dto.UserDtos.UserBrief}：只有 id / 昵称 / 头像，
     * 不含手机号、实名信息与角色。
     */
    @GetMapping("/internal/briefs")
    public ApiResponse<java.util.List<com.duoduo.user.dto.UserDtos.UserBrief>> briefs(
            @org.springframework.web.bind.annotation.RequestParam("ids") String ids) {
        java.util.List<Long> userIds = java.util.Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
        return ApiResponse.ok(authService.userBriefs(userIds));
    }
}

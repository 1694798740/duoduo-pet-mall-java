package com.duoduo.social.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.UserContext;
import com.duoduo.social.dto.SocialDtos.FollowCounts;
import com.duoduo.social.dto.SocialDtos.FollowState;
import com.duoduo.social.dto.SocialDtos.FollowView;
import com.duoduo.social.dto.SocialDtos.ProfileView;
import com.duoduo.social.service.FollowService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注接口（第四十七轮）。
 *
 * <p>全部走两段以上路径（{@code /api/socials/follows/**}），不进网关白名单 ——
 * 关注关系是用户社交数据，未登录既不该看别人的粉丝列表，也无从判断「我是否关注了 TA」。
 * 唯一公开的 {@code /follows/{userId}/counts}（某人粉丝数/关注数，他人主页展示）刻意挂在
 * 三段路径上，与登录路径天然分开（白名单按路径匹配、不区分方法，绝不整段放行）。
 *
 * <p>控制器只做「参数绑定 + 调服务 + 包响应」。
 */
@RestController
public class FollowController {

    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    // ---------------- 需登录 ----------------

    /** 关注（重复关注撞键按已关注幂等；关注自己 422） */
    @PostMapping("/api/socials/follows/{userId}")
    public ApiResponse<FollowState> follow(@PathVariable Long userId) {
        return ApiResponse.ok(followService.follow(UserContext.requireUserId(), userId));
    }

    /** 取关（幂等） */
    @DeleteMapping("/api/socials/follows/{userId}")
    public ApiResponse<FollowState> unfollow(@PathVariable Long userId) {
        return ApiResponse.ok(followService.unfollow(UserContext.requireUserId(), userId));
    }

    /** 我关注的人 */
    @GetMapping("/api/socials/follows/following")
    public ApiResponse<PageResult<FollowView>> following(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(followService.following(UserContext.requireUserId(), page, pageSize));
    }

    /** 我的粉丝 */
    @GetMapping("/api/socials/follows/followers")
    public ApiResponse<PageResult<FollowView>> followers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(followService.followers(UserContext.requireUserId(), page, pageSize));
    }

    /** 我与某人的关注关系（他人主页按钮状态源） */
    @GetMapping("/api/socials/follows/{userId}/state")
    public ApiResponse<FollowState> state(@PathVariable Long userId) {
        return ApiResponse.ok(followService.state(UserContext.requireUserId(), userId));
    }

    /** 他人主页：资料 + 计数 + 我与 TA 的关注关系（一次拿齐） */
    @GetMapping("/api/socials/users/{userId}/profile")
    public ApiResponse<ProfileView> profile(@PathVariable Long userId) {
        return ApiResponse.ok(followService.profile(UserContext.requireUserId(), userId));
    }

    // ---------------- 公开 ----------------

    /** 某人的粉丝数 / 关注数（他人主页展示；挂在三段路径，不进白名单也不会误伤登录接口） */
    @GetMapping("/api/socials/follows/{userId}/counts")
    public ApiResponse<FollowCounts> counts(@PathVariable Long userId) {
        return ApiResponse.ok(followService.counts(userId));
    }
}

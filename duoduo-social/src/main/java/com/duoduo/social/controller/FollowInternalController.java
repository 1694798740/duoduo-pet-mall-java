package com.duoduo.social.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.social.service.FollowService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 关注关系的内部端点（第五十六轮）。
 *
 * <p><b>为什么需要它</b>：分享圈的「关注流」要按「作者 ∈ 我关注的人」过滤，
 * 而分享数据在**履约库**、关注关系在**社交库** —— 跨域过滤只能由履约域拿到
 * 这批 id 后自己拼查询条件。这是一次纯读的服务间调用，与「取昵称头像」同性质。
 *
 * <p><b>为什么放进内部前缀</b>：整个 {@code /api/socials/internal/**} 由
 * {@link com.duoduo.social.support.InternalTokenFilter} 拦截，缺凭据一律 403。
 * 关注列表虽是公开可查的信息（主页上就显示关注数），但**批量拉取**是另一回事：
 * 一次调用就能把某个人的完整社交图谱拿走 —— 与「批量取昵称必须凭据」同一条纪律。
 *
 * <p>只返回 id 数组，不返回昵称头像：调用方要的是过滤条件。
 */
@RestController
@RequestMapping("/api/socials/internal/following")
public class FollowInternalController {

    private final FollowService followService;

    public FollowInternalController(FollowService followService) {
        this.followService = followService;
    }

    /**
     * 某用户关注的人（按关注时间倒序，最多 {@code limit} 条）。
     *
     * <p>{@code limit} 由服务侧再夹一次上限 —— 调用方传多大都不能让这条 SQL 变成
     * 超长 IN 列表。
     */
    @GetMapping
    public ApiResponse<List<Long>> following(@RequestParam Long userId,
                                            @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(followService.followingIds(userId, limit));
    }
}

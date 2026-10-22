package com.duoduo.social.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import com.duoduo.social.dto.SocialDtos.NoticePage;
import com.duoduo.social.dto.SocialDtos.UnreadCount;
import com.duoduo.social.dto.SocialDtos.UnreadSummary;
import com.duoduo.social.service.NoticeService;
import com.duoduo.social.service.UnreadService;
import com.duoduo.social.support.service.SupportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知中心接口（第五十轮启用；表结构第四十七轮建齐；第五十五轮补生产者与合并未读）。
 *
 * <p>全部走两段以上路径（{@code /api/socials/notices/**}），不进网关白名单 ——
 * 通知是收件人数据，「不登录就能看」本身就是缺陷，归属判断依赖 X-User-*。
 *
 * <p>控制器只做「参数绑定 + 调服务 + 包响应」。
 */
@RestController
@RequestMapping("/api/socials/notices")
public class NoticeController {

    private final NoticeService noticeService;
    private final UnreadService unreadService;
    private final SupportService supportService;

    public NoticeController(NoticeService noticeService, UnreadService unreadService,
                            SupportService supportService) {
        this.noticeService = noticeService;
        this.unreadService = unreadService;
        this.supportService = supportService;
    }

    /** 通知列表：定向通知 + 全站公告合并视图（category 可选过滤，page/pageSize 分页） */
    @GetMapping
    public ApiResponse<NoticePage> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String category) {
        return ApiResponse.ok(noticeService.list(UserContext.requireUserId(), page, pageSize, category));
    }

    /** 未读数（定向未读 + 我没读过的公告数） */
    @GetMapping("/unread-count")
    public ApiResponse<UnreadCount> unreadCount() {
        return ApiResponse.ok(noticeService.unreadCount(UserContext.requireUserId()));
    }

    /**
     * 合并未读（第五十五轮）：通知 + 私信 + 总数 —— tabBar 角标的唯一数据源。
     *
     * <p>放在通知控制器下而不是新建一个控制器：角标讲的是「通知中心的未读」，
     * 私信只是恰好也贡献未读；前端也只需要一个刷新入口（`utils/unread.js`）。
     */
    @GetMapping("/unread-summary")
    public ApiResponse<UnreadSummary> unreadSummary() {
        return ApiResponse.ok(unreadService.summary(UserContext.requireUserId()));
    }

    /** 标记单条已读（幂等；他人/不存在 404） */
    @PostMapping("/{noticeId}/read")
    public ApiResponse<Void> markRead(@PathVariable Long noticeId) {
        noticeService.markRead(UserContext.requireUserId(), noticeId);
        return ApiResponse.ok(null);
    }

    /** 全部已读（我的定向未读 + 我没读过的公告，幂等） */
    @PostMapping("/read-all")
    public ApiResponse<Void> readAll() {
        Long userId = UserContext.requireUserId();
        noticeService.readAll(userId);
        // 客服消息已在通知中心展示，全部已读要连会话未读一起清 —— 否则角标还亮着
        supportService.markAllReadForUser(userId);
        return ApiResponse.ok(null);
    }
}

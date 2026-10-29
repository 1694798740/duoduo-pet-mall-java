package com.duoduo.ai.client;

import com.duoduo.ai.client.dto.ButlerNoticeDtos.BatchRequest;
import com.duoduo.ai.client.dto.ButlerNoticeDtos.BatchResult;
import com.duoduo.ai.client.dto.ButlerNoticeDtos.NoticePage;
import com.duoduo.ai.client.dto.ButlerNoticeDtos.UnreadSummary;
import com.duoduo.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 通知域客户端（AI 全能管家 · 阶段二）。
 *
 * <p>三条纪律（与 {@link CartClient} 一致）：
 * <ol>
 *   <li>身份只走 {@code X-User-Id} 请求头，由服务端从登录态注入，
 *       <b>绝不把 userId 放进模型参数</b>——否则一次提示词注入就能查别人的通知；</li>
 *   <li>走 Nacos 服务发现直连 duoduo-social，不经网关；</li>
 *   <li>不新开内部接口：通知域已有的
 *       {@code /api/socials/notices}、{@code /batch}、{@code /unread-summary}
 *       就是权威口径（已读集合、未读排除私信/客服的规则都在那边），
 *       AI 侧再实现一遍必然出现「AI 说消了、角标没消」。</li>
 * </ol>
 */
@FeignClient(name = "duoduo-social", contextId = "butlerNoticeClient",
        configuration = AiFeignConfig.class)
public interface NoticeClient {

    /**
     * 通知列表（定向通知 + 全站公告合并视图）。
     *
     * @param read UNREAD / READ；null = 全部
     */
    @GetMapping("/api/socials/notices")
    ApiResponse<NoticePage> list(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "read", required = false) String read,
            @RequestParam(value = "keyword", required = false) String keyword);

    /**
     * 批量标记已读：只提交本次展示的通知 id（{@code all=false}），
     * 返回最新未读数，AI 直接拿它当 badge_patch，不必二次请求。
     */
    @PostMapping("/api/socials/notices/batch")
    ApiResponse<BatchResult> batch(@RequestHeader("X-User-Id") Long userId,
                                   @RequestBody BatchRequest request);

    /** 合并未读（notice 已排除私信与客服，正好是「通知」这一路的角标）。 */
    @GetMapping("/api/socials/notices/unread-summary")
    ApiResponse<UnreadSummary> unreadSummary(@RequestHeader("X-User-Id") Long userId);
}

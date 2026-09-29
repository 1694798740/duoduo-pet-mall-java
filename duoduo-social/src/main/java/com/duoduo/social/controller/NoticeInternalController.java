package com.duoduo.social.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.social.dto.SocialDtos.AnnounceRequest;
import com.duoduo.social.dto.SocialDtos.NoticePushRequest;
import com.duoduo.social.dto.SocialDtos.NoticePushResult;
import com.duoduo.social.service.NoticeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知推送的内部端点（第五十五轮）。
 *
 * <p><b>这是设计稿里写了、却一直没有实现的那个入口</b>：此前的通知中心只有读路径与
 * 三条种子公告，页面能打开、永远没有新通知 —— 因为没有任何服务往里写。
 *
 * <p>调用方：交易域（下单 / 付款 / 退款）、履约域（发货 / 物流节点 / 售后进度）、
 * 分享圈（评论 / 点赞）。
 *
 * <p><b>为什么写通知也要凭据</b>：整个 {@code /api/socials/internal/**} 前缀由
 * {@link com.duoduo.social.support.InternalTokenFilter} 拦截，缺 {@code X-Internal-Token}
 * 一律 403。它是<b>批量写入</b>入口，一次调用可以给任意多个收件人落行 ——
 * 裸露就等于开放「向全站用户投递任意内容」的垃圾信息通道。这与「批量取昵称必须凭据」
 * 是同一类风险，只是方向相反：一个往外爬数据，一个往里塞内容。
 *
 * <p><b>只写不读</b>：内部端点不提供查询/分页。读一律走用户侧接口（带登录态 +
 * 归属校验），否则「谁能看谁的通知」就成了调用方自觉遵守的约定。
 */
@RestController
@RequestMapping("/api/socials/internal/notices")
public class NoticeInternalController {

    private final NoticeService noticeService;

    public NoticeInternalController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    /**
     * 批量推送通知：一次可给多个收件人 / 多条事件落行。
     *
     * <p>返回 {@code accepted} 是本次落库条数；校验不通过时整批不写并返回 400
     * （两阶段：先全校验、再统一落库），调用方不必担心「一半成功」。
     */
    @PostMapping
    public ApiResponse<NoticePushResult> push(@Valid @RequestBody NoticePushRequest request) {
        return ApiResponse.ok(new NoticePushResult(noticeService.pushBatch(request.items())));
    }

    /**
     * 发全站公告（运营）：收件人由服务端固定为 {@code 0}（公共行，一行多读者，
     * 已读记在集合表），契约里干脆不给这个字段 —— 公告写错收件人就是全站可见，代价太大。
     */
    @PostMapping("/announce")
    public ApiResponse<NoticePushResult> announce(@Valid @RequestBody AnnounceRequest request) {
        return ApiResponse.ok(new NoticePushResult(noticeService.announce(request)));
    }
}

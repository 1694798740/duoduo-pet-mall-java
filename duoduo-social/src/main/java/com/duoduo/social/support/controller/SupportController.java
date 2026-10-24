package com.duoduo.social.support.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import com.duoduo.social.support.dto.SupportDtos;
import com.duoduo.social.support.dto.SupportDtos.MessageView;
import com.duoduo.social.support.dto.SupportDtos.SendRequest;
import com.duoduo.social.support.dto.SupportDtos.SessionView;
import com.duoduo.social.support.dto.SupportDtos.ThreadView;
import com.duoduo.social.support.service.SupportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户侧客服接口（自营客服 + 第三方商家客服统一入口）。
 *
 * <p>与私信彻底分开：路径前缀是 {@code /api/support}，不经过 {@code /api/socials/**}。
 * 这样「私信列表里出现客服会话」在路由层就已经不可能发生，而不是靠查询里加过滤。
 *
 * <p>{@code sellerId} 缺省 = 自营客服账号（由服务端配置决定，前端不硬编码）。
 * 第三方商品的「问一问」带上该商品的 sellerId，两处共用同一套会话与消息。
 */
@RestController
public class SupportController {

    private final SupportService supportService;

    public SupportController(SupportService supportService) {
        this.supportService = supportService;
    }

    /** 取或建「我 ↔ 某接待方」的会话；sellerId 为空 = 自营客服 */
    @GetMapping("/api/support/session")
    public ApiResponse<SessionView> session(@RequestParam(required = false) Long sellerId) {
        return ApiResponse.ok(supportService.ensureForUser(UserContext.requireUserId(), sellerId));
    }

    /** 我的客服会话列表（按最后消息倒序；自营与各商家各一条） */
    @GetMapping("/api/support/sessions")
    public ApiResponse<List<SessionView>> sessions() {
        return ApiResponse.ok(supportService.listForUser(UserContext.requireUserId()));
    }

    /** 会话消息（时间正序，分页取最新一页）；{@code aroundId} 非空 = 引用跳转定位窗口（2026-10-25） */
    @GetMapping("/api/support/messages")
    public ApiResponse<ThreadView> messages(@RequestParam(required = false) Long sellerId,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int pageSize,
                                            @RequestParam(required = false) Long aroundId) {
        return ApiResponse.ok(supportService.userThread(UserContext.requireUserId(), sellerId, page, pageSize, aroundId));
    }

    /** 单向删除（2026-10-25）：批量隐藏本人视角的消息，对方仍可见。幂等 */
    @PostMapping("/api/support/messages/delete")
    public ApiResponse<Integer> delete(@RequestBody SupportDtos.DeleteRequest request) {
        return ApiResponse.ok(supportService.deleteByUser(UserContext.requireUserId(), request.messageIds()));
    }

    /**
     * 发消息。
     *
     * <p>这里**没有**「先关注对方才能发」的判定 —— 客服是消费场景里的正当诉求，
     * 社交防骚扰规则不适用。频控仍保留（60 秒 20 条），防的是刷屏而不是咨询本身。
     */
    @PostMapping("/api/support/messages")
    public ApiResponse<MessageView> send(@Valid @RequestBody SendRequest request) {
        return ApiResponse.ok(supportService.sendByUser(UserContext.requireUserId(), request));
    }

    /** 打开会话即标记已读（幂等） */
    @PostMapping("/api/support/read")
    public ApiResponse<Long> read(@RequestParam Long sessionId) {
        return ApiResponse.ok(supportService.markReadByUser(UserContext.requireUserId(), sessionId));
    }

    /** 撤回自己 2 分钟内发出的消息（2026-10-24，幂等） */
    @PostMapping("/api/support/messages/{messageId}/recall")
    public ApiResponse<MessageView> recall(@PathVariable Long messageId) {
        return ApiResponse.ok(supportService.recallByUser(UserContext.requireUserId(), messageId));
    }

    /**
     * 结束本次咨询（仅买家）。
     *
     * <p>语义是「我这边问完了」，不是「禁止再发」：买家随时可以继续发消息，服务端会自动重开。
     * 因此端侧**不展示任何关闭状态**，这个接口只影响客服工作台的收尾与筛选。
     * 客服侧没有对应接口 —— 关闭权只在买家手里（2026-10-23）。
     */
    @PostMapping("/api/support/close")
    public ApiResponse<Void> close(@RequestParam Long sessionId) {
        supportService.closeByUser(UserContext.requireUserId(), sessionId);
        return ApiResponse.ok(null);
    }

    /** 我的客服未读总数（角标合并口径的一项） */
    @GetMapping("/api/support/unread-count")
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.ok(supportService.unreadForUser(UserContext.requireUserId()));
    }
}

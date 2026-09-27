package com.duoduo.social.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.UserContext;
import com.duoduo.social.dto.SocialDtos.ConversationView;
import com.duoduo.social.dto.SocialDtos.MessagePage;
import com.duoduo.social.dto.SocialDtos.MessageView;
import com.duoduo.social.dto.SocialDtos.SendMessageRequest;
import com.duoduo.social.service.MessageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 私信接口（第四十七轮）。
 *
 * <p>全部走两段以上路径，不进网关白名单 —— 私信是当事人内容，
 * 「不登录就能看别人的会话」本身就是缺陷；归属校验依赖 X-User-*，不登录无从谈起。
 *
 * <p>控制器只做「参数绑定 + 调服务 + 包响应」。
 */
@RestController
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /** 发消息（卡片 / 咨询类摘要由服务端取事实；非互关每人限发一条，咨询会话豁免） */
    @PostMapping("/api/socials/messages")
    public ApiResponse<MessageView> send(@Valid @RequestBody SendMessageRequest request) {
        return ApiResponse.ok(messageService.send(UserContext.requireUserId(), request));
    }

    /** 会话列表（每会话最新一条 + 未读数 + 我是否还能发） */
    @GetMapping("/api/socials/messages/conversations")
    public ApiResponse<PageResult<ConversationView>> conversations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(messageService.conversations(UserContext.requireUserId(), page, pageSize));
    }

    /** 与某人的双向消息（时间正序，分页取最新一页；头部带互关/可发状态） */
    @GetMapping("/api/socials/messages/conversations/{peerId}")
    public ApiResponse<MessagePage> conversation(
            @PathVariable Long peerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(messageService.conversation(UserContext.requireUserId(), peerId, page, pageSize));
    }

    /** 打开会话后标记已读（幂等） */
    @PostMapping("/api/socials/messages/conversations/{peerId}/read")
    public ApiResponse<Long> markRead(@PathVariable Long peerId) {
        return ApiResponse.ok(messageService.markRead(UserContext.requireUserId(), peerId));
    }
}

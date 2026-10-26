package com.duoduo.social.support.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.social.support.dto.SupportDtos;
import com.duoduo.social.support.dto.SupportDtos.MessageView;
import com.duoduo.social.support.dto.SupportDtos.SellerSendRequest;
import com.duoduo.social.support.dto.SupportDtos.SessionView;
import com.duoduo.social.support.dto.SupportDtos.ThreadView;
import com.duoduo.social.support.dto.SupportDtos.TicketTransferRequest;
import com.duoduo.social.support.dto.SupportDtos.TicketView;
import com.duoduo.social.support.service.SupportService;
import com.duoduo.social.support.service.SupportTicketService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 商家工作台 · 客服信息。
 *
 * <h2>职责划分（本类就是那条边界）</h2>
 * 私信与客服的接待**只在这里**发生：自营客服账号是 SELLER，第三方商家也是 SELLER，
 * 两者用同一套接口、同一套会话表，区别只在 {@code seller_id} 是谁。
 * 平台运营端（{@code /api/admin/**}）**不提供**任何读取私信或客服会话的接口 ——
 * 运营管平台级事务（审核、仲裁、干预），不介入买卖双方的沟通内容。
 *
 * <h2>为什么 sellerId 不由请求传</h2>
 * 会话归属从登录态推导（用户域 {@code /internal/merchant-membership}）。
 * 一旦允许调用方传 sellerId，「换个 id 看别家客服会话」这条路就出现了 ——
 * 这类越权在测试里很难覆盖，只能从接口契约上不给口子。
 *
 * <h2>店铺维度（2026-10-23 修复）</h2>
 * 会话的 {@code seller_id} 是**店铺主体**（店主 userId）。登录账号无论是店主还是店员，
 * 都先解析出同一个 {@code ownerUserId} 再查会话 —— 否则店员登录后看到的是空列表，
 * 同一家店换个账号接待就「会话消失」。自营客服账号没有入驻行，按自身 userId 兜底。
 */
@RestController
public class SellerSupportController {

    private static final Logger log = LoggerFactory.getLogger(SellerSupportController.class);

    private final SupportService supportService;
    private final SupportTicketService ticketService;
    private final com.duoduo.social.client.UserClient userClient;

    public SellerSupportController(SupportService supportService,
                                   SupportTicketService ticketService,
                                   com.duoduo.social.client.UserClient userClient) {
        this.supportService = supportService;
        this.ticketService = ticketService;
        this.userClient = userClient;
    }

    /** 本店客服会话（按最后消息倒序）；filter = ALL / UNREAD / ANSWERED / CLOSED */
    @GetMapping("/api/seller/support/sessions")
    public ApiResponse<PageResult<SessionView>> sessions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String filter,
            @RequestParam(defaultValue = "false") boolean onlyUnread,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(supportService.listForSeller(requireStoreOwnerId(), status, filter,
                onlyUnread, page, pageSize));
    }

    @GetMapping("/api/seller/support/sessions/{id}/messages")
    public ApiResponse<ThreadView> messages(@PathVariable Long id,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int pageSize,
                                            @RequestParam(required = false) Long aroundId) {
        return ApiResponse.ok(supportService.sellerThread(requireStoreOwnerId(), id, page, pageSize, aroundId));
    }

    /** 单向删除（2026-10-25）：批量隐藏商家侧视角的消息，用户仍可见。幂等 */
    @PostMapping("/api/seller/support/sessions/{id}/messages/delete")
    public ApiResponse<Integer> delete(@PathVariable Long id,
                                       @RequestBody SupportDtos.DeleteRequest request) {
        return ApiResponse.ok(supportService.deleteBySeller(requireStoreOwnerId(), id, request.messageIds()));
    }

    /**
     * 商家回复。
     *
     * <p>发送人就是当前登录的店铺主体 —— 自营客服账号登录时即以「多多官方客服」身份回复，
     * 不需要平台运营代发，也就没有「运营代发会让回复人变成运营本人」那个坑。
     */
    @PostMapping("/api/seller/support/sessions/{id}/messages")
    public ApiResponse<MessageView> reply(@PathVariable Long id,
                                          @Valid @RequestBody SellerSendRequest request) {
        return ApiResponse.ok(supportService.sendBySeller(requireStoreOwnerId(), id, request));
    }

    @PostMapping("/api/seller/support/sessions/{id}/read")
    public ApiResponse<Long> read(@PathVariable Long id) {
        return ApiResponse.ok(supportService.markReadBySeller(requireStoreOwnerId(), id));
    }

    /** 撤回客服 2 分钟内发出的回复（2026-10-24，幂等） */
    @PostMapping("/api/seller/support/sessions/{id}/messages/{messageId}/recall")
    public ApiResponse<MessageView> recall(@PathVariable Long id, @PathVariable Long messageId) {
        return ApiResponse.ok(supportService.recallBySeller(requireStoreOwnerId(), id, messageId));
    }

    /**
     * 【已下线】关闭 / 重开会话（2026-10-23）。
     *
     * <p>关闭权只在买家手里：客服若能关会话，就存在「不想答就关掉」的操作空间。
     * 这里保留路径并固定返回 403，而不是直接删掉路由 —— 删掉会变成 404，
     * 老客户端/脚本只会看到「接口不见了」，而 403 + 明确文案能直接说清口径变化。
     */
    @Deprecated
    @PostMapping("/api/seller/support/sessions/{id}/status")
    public ApiResponse<Void> status(@PathVariable Long id, @RequestParam String value) {
        throw BizException.forbidden("会话只能由买家结束，客服无权关闭或重开");
    }

    /** 工作台页签角标 */
    @GetMapping("/api/seller/support/unread-count")
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.ok(supportService.unreadForSeller(requireStoreOwnerId()));
    }

    // ---------------- C-04 · 客服工单认领 / 转交 / 审计 ----------------

    /** 工单详情：当前处理人 + 本店在职成员 + 处理记录（店主与店员均可看） */
    @GetMapping("/api/seller/support/sessions/{id}/ticket")
    public ApiResponse<TicketView> ticket(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.view(requireStoreOwnerId(), id));
    }

    /**
     * 认领：并发认领只有一个成功；重复认领（已是本人）幂等。
     * 处理人只表达「谁在接」，不改变店主/店员本就能看全店会话的权限。
     */
    @PostMapping("/api/seller/support/sessions/{id}/ticket/claim")
    public ApiResponse<TicketView> claim(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.claim(requireStoreOwnerId(), id));
    }

    /** 转交：当前处理人或店主可操作；目标必须是本店在职成员；备注可选 */
    @PostMapping("/api/seller/support/sessions/{id}/ticket/transfer")
    public ApiResponse<TicketView> transfer(@PathVariable Long id,
                                              @RequestBody(required = false) TicketTransferRequest request) {
        return ApiResponse.ok(ticketService.transfer(requireStoreOwnerId(), id, request));
    }

    /** 释放：当前处理人或店主把会话归还为未认领 */
    @PostMapping("/api/seller/support/sessions/{id}/ticket/release")
    public ApiResponse<TicketView> release(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.release(requireStoreOwnerId(), id));
    }

    /**
     * 登录账号 → 店铺主体 id。
     *
     * <p>顺序：成员身份优先（店主/店员都归到 ownerUserId）；查不到时，
     * 仅 {@code SELLER} 角色按自身 userId 兜底（自营客服账号没有入驻行，这是它唯一的路径）。
     * 其他账号（平台运营、普通用户）一律 403 —— 运营不介入买卖双方会话内容。
     */
    private Long requireStoreOwnerId() {
        LoginUser user = UserContext.require();
        if (user == null) {
            throw BizException.forbidden("仅店铺成员可处理客服消息");
        }
        try {
            var resp = userClient.merchantMembership(user.userId());
            var membership = resp == null ? null : resp.data();
            if (membership != null && membership.ownerUserId() != null && !membership.ownerUserId().isBlank()) {
                return Long.valueOf(membership.ownerUserId());
            }
        } catch (Exception e) {
            // 用户域暂时不可用时不让整个工作台 500：退回角色判断，店主仍可接待。
            log.warn("解析店铺归属失败 userId={}", user.userId(), e);
        }
        if (user.isSeller()) {
            return user.userId();
        }
        throw BizException.forbidden("仅店铺成员可处理客服消息");
    }
}

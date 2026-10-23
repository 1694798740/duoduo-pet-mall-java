package com.duoduo.social.support.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.social.support.dto.SupportDtos.MessageView;
import com.duoduo.social.support.dto.SupportDtos.SellerSendRequest;
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
    private final com.duoduo.social.client.UserClient userClient;

    public SellerSupportController(SupportService supportService,
                                   com.duoduo.social.client.UserClient userClient) {
        this.supportService = supportService;
        this.userClient = userClient;
    }

    /** 本店客服会话（按最后消息倒序，可按 OPEN/CLOSED 过滤） */
    @GetMapping("/api/seller/support/sessions")
    public ApiResponse<PageResult<SessionView>> sessions(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean onlyUnread,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(supportService.listForSeller(requireStoreOwnerId(), status, onlyUnread, page, pageSize));
    }

    @GetMapping("/api/seller/support/sessions/{id}/messages")
    public ApiResponse<ThreadView> messages(@PathVariable Long id,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(supportService.sellerThread(requireStoreOwnerId(), id, page, pageSize));
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

    /** 关闭 / 重开会话：{@code value=CLOSED|OPEN} */
    @PostMapping("/api/seller/support/sessions/{id}/status")
    public ApiResponse<Void> status(@PathVariable Long id, @RequestParam String value) {
        supportService.setStatus(requireStoreOwnerId(), id, value);
        return ApiResponse.ok(null);
    }

    /** 工作台页签角标 */
    @GetMapping("/api/seller/support/unread-count")
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.ok(supportService.unreadForSeller(requireStoreOwnerId()));
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

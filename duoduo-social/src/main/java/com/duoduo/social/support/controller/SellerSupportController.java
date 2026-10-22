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
 * 会话归属从登录态推导（{@link LoginUser#isSeller()} → {@code userId}）。
 * 一旦允许调用方传 sellerId，「换个 id 看别家客服会话」这条路就出现了 ——
 * 这类越权在测试里很难覆盖，只能从接口契约上不给口子。
 *
 * <p>店员（role=USER + 店铺成员身份）本轮不在准入内：客服会话归属是店铺主体，
 * 店员的归属解析需要额外的成员域回源，等接入店员分权时一并处理。
 */
@RestController
public class SellerSupportController {

    private final SupportService supportService;

    public SellerSupportController(SupportService supportService) {
        this.supportService = supportService;
    }

    /** 本店客服会话（按最后消息倒序，可按 OPEN/CLOSED 过滤） */
    @GetMapping("/api/seller/support/sessions")
    public ApiResponse<PageResult<SessionView>> sessions(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean onlyUnread,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(supportService.listForSeller(requireSellerId(), status, onlyUnread, page, pageSize));
    }

    @GetMapping("/api/seller/support/sessions/{id}/messages")
    public ApiResponse<ThreadView> messages(@PathVariable Long id,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(supportService.sellerThread(requireSellerId(), id, page, pageSize));
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
        return ApiResponse.ok(supportService.sendBySeller(requireSellerId(), id, request));
    }

    @PostMapping("/api/seller/support/sessions/{id}/read")
    public ApiResponse<Long> read(@PathVariable Long id) {
        return ApiResponse.ok(supportService.markReadBySeller(requireSellerId(), id));
    }

    /** 关闭 / 重开会话：{@code value=CLOSED|OPEN} */
    @PostMapping("/api/seller/support/sessions/{id}/status")
    public ApiResponse<Void> status(@PathVariable Long id, @RequestParam String value) {
        supportService.setStatus(requireSellerId(), id, value);
        return ApiResponse.ok(null);
    }

    /** 工作台页签角标 */
    @GetMapping("/api/seller/support/unread-count")
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.ok(supportService.unreadForSeller(requireSellerId()));
    }

    private Long requireSellerId() {
        LoginUser user = UserContext.require();
        if (user == null || !user.isSeller()) {
            throw BizException.forbidden("仅店铺主体账号可处理客服消息");
        }
        return user.userId();
    }
}

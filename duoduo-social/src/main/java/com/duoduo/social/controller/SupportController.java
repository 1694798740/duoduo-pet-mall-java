package com.duoduo.social.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.social.client.UserClient;
import com.duoduo.social.client.dto.UserBrief;
import com.duoduo.social.dto.SocialDtos.ConversationView;
import com.duoduo.social.service.MessageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 「问客服」直连（A4，2026-10-20）。
 *
 * <h2>它解决什么</h2>
 * 此前 Web / 小程序里的「问客服」是一个占位弹窗，明确写着"人工客服正在建设中"。
 * 那是诚实的做法（比假客服好），但用户提的问题最终还是要落到一条真实通道上。
 * 本控制器提供两件事：
 * <ol>
 *   <li>给买家：官方客服账号是谁 → 端侧据此直接打开会话（直连，不需要用户自己找人）；</li>
 *   <li>给平台侧：列出全部咨询会话 → 运营能在客服工作台里看到并回复。</li>
 * </ol>
 *
 * <h2>为什么不做"全员可见、谁都能回"</h2>
 * 见工作区《问客服-直连平台消息方案评估-2026-10-18.md》：多人共享的群聊式客服
 * 会带来**口径冲突**（A 说能退、B 说不能退，用户按有利的那条主张）与
 * **响应归属冲突**（谁算首响、谁算解决，SLA 无从统计）。
 * 本轮先把**通道**打通（能问、能看到、能回），
 * 「认领 - 单一责任人 - 转交 - 审计」那套工单模型留到下一轮，
 * 因为它是组织规则而非技术问题，需要先定清楚再动代码。
 *
 * <h2>★ 为什么 agent() 要回源查一次用户</h2>
 * 实测踩到：配置默认值 {@code 710000000000000001} 在本机库中**并不存在**
 * （种子增量 16 没在这个环境执行过），而私信会话端点对不存在的 peer **不报错** ——
 * 它返回 200，昵称退化成「用户710000000000000001」。于是端侧会顺利跳进一个
 * 「查无此人」的会话，用户以为接通了，消息发出去永远没人看。
 *
 * <p>这就是典型的**静默失败**：全链路每一层都返回成功，只有结果不对。
 * 所以这里刻意多花一次跨域回源，把"配置指向的账号是否真实存在"变成可判定的前置条件；
 * 不存在就 503 + 独立错误码，端侧退回说明弹窗 —— 比假装接通诚实。
 * 回源只在用户点「问客服」时发生（低频），不缓存，避免账号重建后仍读到旧结论。
 */
@RestController
public class SupportController {

    private final MessageService messageService;
    private final UserClient userClient;
    private final Long officialCsUserId;

    public SupportController(MessageService messageService,
                             UserClient userClient,
                             @Value("${duoduo.social.official-cs-user-id:710000000000000001}")
                             Long officialCsUserId) {
        this.messageService = messageService;
        this.userClient = userClient;
        this.officialCsUserId = officialCsUserId;
    }

    /**
     * 官方客服账号（咨询归属）。
     *
     * <p>与商品域的定人规则保持一致：自营商品（supplyType=self）的咨询收件人
     * 就是这个账号（见 {@code MessageService} 的 {@code officialCsUserId}），
     * 所以端侧"问客服"与"问一问"指向同一个人，不会出现两个客服号。
     *
     * <p>★ 账号不存在时回 503 而不是 200 —— 见类注释里那段实测记录。
     */
    @GetMapping("/api/socials/support/agent")
    public ApiResponse<SupportAgentView> agent() {
        requireAgentExists();
        return ApiResponse.ok(new SupportAgentView(officialCsUserId));
    }

    /** 平台侧客服工作台：列出官方客服账号收到的全部会话（按最后消息倒序） */
    @GetMapping("/api/socials/support/conversations")
    public ApiResponse<List<ConversationView>> conversations(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        requireAgentExists();
        PageResult<ConversationView> result = messageService.conversations(officialCsUserId, page, pageSize);
        return ApiResponse.ok(result.items());
    }

    private void requireAgentExists() {
        if (!agentExists()) {
            throw new BizException(ErrorCode.SUPPORT_AGENT_NOT_READY,
                    "客服账号（id=" + officialCsUserId + "）在用户库中不存在，请先执行种子数据");
        }
    }

    /**
     * 客服账号是否存在。
     *
     * <p>复用既有跨域批量资料端点 {@code /api/users/internal/briefs}（只取 id/昵称/头像，
     * 不扩散隐私字段），不新增用户域接口。用户域异常时返回 {@code false}，
     * 端侧同样走「暂时没接通」兜底 —— 语义正确，且不会把下游故障说成"账号不存在"。
     */
    private boolean agentExists() {
        try {
            ApiResponse<List<UserBrief>> response = userClient.briefs(String.valueOf(officialCsUserId));
            if (response == null || response.data() == null) {
                return false;
            }
            return response.data().stream().anyMatch(brief -> brief != null
                    && officialCsUserId.equals(brief.id()));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public record SupportAgentView(Long userId) {
    }
}

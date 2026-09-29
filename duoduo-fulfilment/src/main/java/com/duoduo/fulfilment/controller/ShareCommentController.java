package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.fulfilment.dto.ShareDtos.CommentCreateRequest;
import com.duoduo.fulfilment.dto.ShareDtos.CommentView;
import com.duoduo.fulfilment.dto.ShareDtos.EngagementState;
import com.duoduo.fulfilment.dto.ShareDtos.ReportRequest;
import com.duoduo.fulfilment.dto.ShareDtos.ReportView;
import com.duoduo.fulfilment.service.ShareCommentService;
import com.duoduo.fulfilment.service.ShareReportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分享评论接口（第五十六轮）。
 *
 * <p><b>列表为什么用查询参数而不是 {@code /{id}/comments}</b>：这与分享的
 * 「{@code /feed} 两段 vs 发布单段」是同一个技巧，只是方向相反。
 * 网关白名单按路径、<b>不区分方法</b>：
 * <ul>
 *   <li>若列表写成 {@code GET /api/shares/{id}/comments}（两段），要放行它就得在白名单里
 *       加 {@code /api/shares/*\/comments}，而那会**连带把
 *       {@code POST /api/shares/{id}/comments}（发表评论）放行成匿名可调** ——
 *       等于开放匿名 UGC 直写入库；</li>
 *   <li>写成 {@code GET /api/shares/comments?shareId=}（一段）则可以安全地落到已有的
 *       {@code /api/shares/*} 白名单条目上，而发表评论仍在两段路径（{@code /{id}/comments}），
 *       永远强制登录。</li>
 * </ul>
 * 「评论列表要能匿名看」与「评论要登录才能发」这两件事，靠路径形状区分，
 * 而不是靠方法名 —— 因为网关这一层看不到方法。
 *
 * <p>控制器只做「参数绑定 + 调服务 + 包响应」。
 */
@RestController
@RequestMapping("/api/shares")
public class ShareCommentController {

    private final ShareCommentService commentService;
    private final ShareReportService reportService;

    public ShareCommentController(ShareCommentService commentService, ShareReportService reportService) {
        this.commentService = commentService;
        this.reportService = reportService;
    }

    /**
     * 评论列表（公开）。
     *
     * <p>{@code parentId} 为空 = 一级评论分页（每条带回复数）；
     * 非空 = 该评论下的回复分页。
     */
    @GetMapping("/comments")
    public ApiResponse<PageResult<CommentView>> list(
            @RequestParam Long shareId,
            @RequestParam(required = false) Long parentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(commentService.list(currentViewerId(), shareId, parentId, page, pageSize));
    }

    /** 发表评论 / 回复（需登录）：{@code parentCommentId} 为空是一级评论，非空是对该评论的回复 */
    @PostMapping("/{id}/comments")
    public ApiResponse<CommentView> create(@PathVariable Long id,
                                           @Valid @RequestBody CommentCreateRequest request) {
        return ApiResponse.ok(commentService.create(UserContext.requireUserId(), id, request));
    }

    /** 评论点赞 / 取消（toggle，需登录） */
    @PostMapping("/comments/{commentId}/like")
    public ApiResponse<EngagementState> toggleLike(@PathVariable Long commentId) {
        return ApiResponse.ok(commentService.toggleLike(UserContext.requireUserId(), commentId));
    }

    /** 举报评论（需登录） */
    @PostMapping("/comments/{commentId}/report")
    public ApiResponse<ReportView> report(@PathVariable Long commentId,
                                          @Valid @RequestBody ReportRequest request) {
        return ApiResponse.ok(reportService.reportComment(UserContext.requireUserId(), commentId, request.reason()));
    }

    /** 公开端点的可选浏览者：网关对携带合法令牌的请求透传 X-User-*，匿名请求上下文为空 */
    private Long currentViewerId() {
        LoginUser user = UserContext.get();
        return user == null ? null : user.userId();
    }
}

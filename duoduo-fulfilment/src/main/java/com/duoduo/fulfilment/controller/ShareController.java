package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.fulfilment.dto.ShareDtos.EngagementState;
import com.duoduo.fulfilment.dto.ShareDtos.RepostRequest;
import com.duoduo.fulfilment.dto.ShareDtos.ReportRequest;
import com.duoduo.fulfilment.dto.ShareDtos.ReportView;
import com.duoduo.fulfilment.dto.ShareDtos.ShareCreateRequest;
import com.duoduo.fulfilment.dto.ShareDtos.ShareEditRequest;
import com.duoduo.fulfilment.dto.ShareDtos.ShareView;
import com.duoduo.fulfilment.dto.ShareDtos.TopicView;
import com.duoduo.fulfilment.service.ShareReportService;
import com.duoduo.fulfilment.service.ShareService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分享圈接口（第四十三轮；第五十六轮补删除 / 编辑 / 举报 / 关注流 / 话题）。
 *
 * <p>公开读 / 登录写的分界线（与评价接口同一口径）：
 * <ul>
 *   <li>{@code GET /feed}、{@code GET /topics}、{@code GET /topics/{name}}、
 *       {@code GET /{id}} —— <b>公开</b>。分享是商品口碑内容，未登录访客也该能逛信息流、
 *       能打开分享落地页、能点话题进去看；网关白名单按路径放行这几条，且刻意避开所有写路径；</li>
 *   <li>其余全部<b>需登录</b>：发布、转发、编辑、删除、点赞、收藏、举报、我的分享、关注流。</li>
 * </ul>
 *
 * <p><b>路径形状是安全设计的一部分</b>：网关白名单按路径匹配、<b>不区分 HTTP 方法</b>。
 * 公开的 {@code /feed} 与 {@code /topics} 都是一段路径，而所有写路径都在两段以上
 * （{@code /{id}/edit}、{@code /mine/list}…），因此永远不会被「放行公开读」连带放行。
 * 这条纪律在 {@code GatewaySecurityProperties} 里有逐条注解，新增端点时务必对照。
 *
 * <p>控制器只做「参数绑定 + 调服务 + 包响应」。
 */
@RestController
@RequestMapping("/api/shares")
public class ShareController {

    private final ShareService shareService;
    private final ShareReportService reportService;

    public ShareController(ShareService shareService, ShareReportService reportService) {
        this.shareService = shareService;
        this.reportService = reportService;
    }

    // ---------------- 公开 ----------------

    /**
     * 分享圈信息流（公开）：按发布时间倒序分页。
     *
     * <p>浏览者身份是**可选**的：带合法令牌 → 返回该用户的 liked/favorited/mine 标记；
     * 匿名 → 标记恒为 false（信息流本身公开可逛，第四十四轮）。
     *
     * <p>{@code scope}（第五十六轮）：{@code RECOMMEND}（默认）或 {@code FOLLOWING}。
     * 关注流要求登录，服务层会拒。
     */
    @GetMapping("/feed")
    public ApiResponse<PageResult<ShareView>> feed(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String scope) {
        return ApiResponse.ok(shareService.feed(currentViewerId(), scope, page, pageSize));
    }

    /** 热门话题榜（公开）：发布页的「大家都在聊」（一段路径，落在白名单里） */
    @GetMapping("/topics")
    public ApiResponse<List<TopicView>> hotTopics(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(shareService.hotTopics(limit));
    }

    /** 话题聚合页（公开）：{@code /topics/{name}} 是两段路径，白名单里单独放行这一条 */
    @GetMapping("/topics/{name}")
    public ApiResponse<PageResult<ShareView>> topicFeed(
            @PathVariable String name,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(shareService.topicFeed(currentViewerId(), name, page, pageSize));
    }

    /** 分享详情（公开）：分享落地页的数据源（同样支持可选浏览者标记） */
    @GetMapping("/{id}")
    public ApiResponse<ShareView> detail(@PathVariable Long id) {
        return ApiResponse.ok(shareService.detail(currentViewerId(), id));
    }

    // ---------------- 需登录 ----------------

    /** 发布分享（商品 + 用户自撰评价内容 + 最多 2 个话题） */
    @PostMapping
    public ApiResponse<ShareView> create(@Valid @RequestBody ShareCreateRequest request) {
        return ApiResponse.ok(shareService.create(UserContext.requireUserId(), request));
    }

    /**
     * 转发一条分享到分享圈（第四十六轮）。
     *
     * <p>与发布同属写操作，路径 {@code /{id}/repost} 是两段 —— 网关白名单里的
     * {@code /api/shares/*} 只匹配单段，转发**不会**被连带放行，仍强制登录。
     *
     * <p>响应体是<b>转发单</b>的视图：前端拿到 {@code id} 后据此刷新信息流、
     * 并把它作为「分享给好友」的落点（落点必须是转发单，详情页才显示转发人）。
     */
    @PostMapping("/{id}/repost")
    public ApiResponse<ShareView> repost(@PathVariable Long id,
                                         @Valid @RequestBody RepostRequest request) {
        return ApiResponse.ok(shareService.repost(UserContext.requireUserId(), id, request.content()));
    }

    /** 编辑自己的分享（第五十六轮）：只改文字与话题；topics 传 null 表示不改动 */
    @PostMapping("/{id}/edit")
    public ApiResponse<ShareView> edit(@PathVariable Long id,
                                       @Valid @RequestBody ShareEditRequest request) {
        return ApiResponse.ok(shareService.edit(UserContext.requireUserId(), id, request));
    }

    /** 删除自己的分享（第五十六轮，软删；别人转发过它的转发单仍在，引用块会变成「已删除」） */
    @PostMapping("/{id}/delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        shareService.delete(UserContext.requireUserId(), id);
        return ApiResponse.ok(null);
    }

    /** 点赞 / 取消点赞（toggle，第四十四轮） */
    @PostMapping("/{id}/like")
    public ApiResponse<EngagementState> toggleLike(@PathVariable Long id) {
        return ApiResponse.ok(shareService.toggleLike(UserContext.requireUserId(), id));
    }

    /** 收藏 / 取消收藏（toggle，第四十四轮） */
    @PostMapping("/{id}/favorite")
    public ApiResponse<EngagementState> toggleFavorite(@PathVariable Long id) {
        return ApiResponse.ok(shareService.toggleFavorite(UserContext.requireUserId(), id));
    }

    /** 举报分享（第五十六轮）：理由必填，重复举报 409，不能举报自己的 */
    @PostMapping("/{id}/report")
    public ApiResponse<ReportView> report(@PathVariable Long id,
                                          @Valid @RequestBody ReportRequest request) {
        return ApiResponse.ok(reportService.reportShare(UserContext.requireUserId(), id, request.reason()));
    }

    /** 我的分享（第五十六轮）：管理页数据源，编辑 / 删除入口挂在自己发的分享上 */
    @GetMapping("/mine/list")
    public ApiResponse<PageResult<ShareView>> mine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(shareService.mine(UserContext.requireUserId(), page, pageSize));
    }

    /** 公开端点的可选浏览者：网关对携带合法令牌的请求透传 X-User-*，匿名请求上下文为空 */
    private Long currentViewerId() {
        LoginUser user = UserContext.get();
        return user == null ? null : user.userId();
    }
}

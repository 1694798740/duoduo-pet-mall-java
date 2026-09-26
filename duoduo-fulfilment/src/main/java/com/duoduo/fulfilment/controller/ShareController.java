package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.UserContext;
import com.duoduo.fulfilment.dto.ShareDtos.ShareCreateRequest;
import com.duoduo.fulfilment.dto.ShareDtos.ShareView;
import com.duoduo.fulfilment.service.ShareService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分享圈接口（第四十三轮）。
 *
 * <p>公开读 / 登录写的分界线（与评价接口同一口径）：
 * <ul>
 *   <li>{@code GET /feed}、{@code GET /{id}} —— <b>公开</b>。分享是商品口碑内容，
 *       未登录访客也该能逛信息流、能打开分享落地页。网关白名单只放行这两段；</li>
 *   <li>{@code POST /api/shares} —— <b>需登录</b>：发布是写操作，且网关白名单
 *       按路径匹配、不区分方法，发布路径刻意与公开路径分开（{@code /feed} 两段），
 *       避免「放行信息流就连带放行发布」。</li>
 * </ul>
 *
 * <p>控制器只做「参数绑定 + 调服务 + 包响应」。
 */
@RestController
@RequestMapping("/api/shares")
public class ShareController {

    private final ShareService shareService;

    public ShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    // ---------------- 公开 ----------------

    /** 分享圈信息流（公开）：按发布时间倒序分页 */
    @GetMapping("/feed")
    public ApiResponse<PageResult<ShareView>> feed(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(shareService.feed(page, pageSize));
    }

    /** 分享详情（公开）：分享落地页的数据源 */
    @GetMapping("/{id}")
    public ApiResponse<ShareView> detail(@PathVariable Long id) {
        return ApiResponse.ok(shareService.detail(id));
    }

    // ---------------- 需登录 ----------------

    /** 发布分享（商品 + 用户自撰评价内容） */
    @PostMapping
    public ApiResponse<ShareView> create(@Valid @RequestBody ShareCreateRequest request) {
        return ApiResponse.ok(shareService.create(UserContext.requireUserId(), request));
    }
}

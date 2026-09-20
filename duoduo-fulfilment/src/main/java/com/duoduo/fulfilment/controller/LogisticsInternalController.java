package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.fulfilment.dto.LogisticsDtos.TrackIngestRequest;
import com.duoduo.fulfilment.dto.LogisticsDtos.TrackIngestResult;
import com.duoduo.fulfilment.service.LogisticsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 物流轨迹摄入（内部接口）。
 *
 * <p>整个前缀 {@code /api/logistics/internal/**} 由本服务的 {@code InternalTokenFilter}
 * 校验 {@code X-Internal-Token}，**失败关闭**。调用方是物流推送 / 交易域发货通知，
 * 都是服务间调用 —— 因此这条路径**不经网关**（网关白名单里没有 {@code /internal/}，
 * 走网关会先被 JWT 过滤器拦成 401，是 T-25 那类最难查的假象）。
 *
 * <p>路径风格 {@code <域>/internal/...} 与既有的 {@code /api/orders/internal/{orderNo}/fact}、
 * {@code /api/users/internal/briefs} 保持一致；{@code 07} 篇草案里写的
 * {@code /api/internal/logistics/...} 把 {@code internal} 放在了域前面，
 * 与全项目既有写法不同 —— 同一个仓库里两种内部路径风格，会让后来者按错的那一份去找接口。
 */
@RestController
@RequestMapping("/api/logistics/internal")
public class LogisticsInternalController {

    private final LogisticsService logisticsService;

    public LogisticsInternalController(LogisticsService logisticsService) {
        this.logisticsService = logisticsService;
    }

    /**
     * 批量上报某订单的轨迹节点（幂等：重复节点被唯一索引跳过，回执里单独计数）。
     *
     * <p>返回 200 而不是 201：这不是「创建一份资源」，而是「一批事实被吸收」——
     * 其中可能有若干条本来就是重复的，回执里的 {@code inserted} / {@code skipped}
     * 才是调用方真正需要看的东西。
     */
    @PostMapping("/{orderNo}/tracks")
    public ApiResponse<TrackIngestResult> ingest(@PathVariable String orderNo,
                                                @Valid @RequestBody TrackIngestRequest request) {
        return ApiResponse.ok(logisticsService.ingest(orderNo, request));
    }
}

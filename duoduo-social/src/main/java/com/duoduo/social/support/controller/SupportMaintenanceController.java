package com.duoduo.social.support.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.social.support.service.SupportMaintenanceService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客服会话维护的内部触发入口（2026-10-23）。
 *
 * <h2>为什么要有它</h2>
 * 两个任务是 {@code @Scheduled} 的：自动结束每 5 分钟、过期清理每天一次。
 * 但「清理」这类不可逆操作需要两种能力：
 * <ol>
 *   <li><b>运维手动跑一次</b>（换阈值、补跑、误判后核对），不能只能等下一个 cron；</li>
 *   <li><b>可测试</b> —— 否则自测只能把时钟拨 30 天，或者 sleep 到下一个周期。</li>
 * </ol>
 *
 * <h2>安全</h2>
 * 路径在 {@code /api/socials/internal/**} 下，由 {@code InternalTokenFilter} 校验服务间签名
 * （{@code X-Internal-Token}，含时效与一次性 nonce）；网关不对外暴露该前缀。
 * 两个动作都可以单独触发：{@code close=1}（自动结束）/ {@code cleanup=1}（过期清理）。
 */
@RestController
public class SupportMaintenanceController {

    private final SupportMaintenanceService maintenance;

    public SupportMaintenanceController(SupportMaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    @PostMapping("/api/socials/internal/support-sweep")
    public ApiResponse<Map<String, Object>> sweep(
            @RequestParam(defaultValue = "1") int close,
            @RequestParam(defaultValue = "1") int cleanup) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (close != 0) {
            result.put("closed", maintenance.autoCloseIdleSessions());
        }
        if (cleanup != 0) {
            SupportMaintenanceService.CleanupResult r = maintenance.cleanupExpiredSessions();
            result.put("deletedSessions", r.deleted());
            result.put("deletedObjects", r.objects());
            result.put("skipped", r.skipped());
        }
        return ApiResponse.ok(result);
    }
}

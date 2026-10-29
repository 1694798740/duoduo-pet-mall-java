package com.duoduo.ai.controller;

import com.duoduo.ai.dto.AiMemoryDtos.ConfirmTicket;
import com.duoduo.ai.dto.AiMemoryDtos.HighRiskConfirmRequest;
import com.duoduo.ai.dto.AiMemoryDtos.HighRiskUpdate;
import com.duoduo.ai.dto.AiMemoryDtos.LowRiskUpdate;
import com.duoduo.ai.dto.AiMemoryDtos.MemoryView;
import com.duoduo.ai.memory.AiMemoryService;
import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆管理端点（2026-10-29 · 第二阶段）。
 *
 * <p>全部需要登录：身份只从 {@code X-User-Id}（网关注入）取，
 * **接口不接受 userId 参数** —— 一旦接受，就等于给「查看/篡改别人的记忆」留了入口。
 *
 * <p>高风险内容（过敏原/健康备注）走两步：{@code /high-risk/request} 拿一次性令牌，
 * {@code /high-risk/confirm} 带令牌提交。令牌与内容绑定、5 分钟过期、消费即删。
 */
@RestController
@RequestMapping("/api/duoduo/memory")
public class AiMemoryController {

    private final AiMemoryService memoryService;

    public AiMemoryController(AiMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** 查看我的记忆（含高风险快照）。 */
    @GetMapping
    public ApiResponse<MemoryView> view() {
        return ApiResponse.ok(memoryService.view(UserContext.requireUserId()));
    }

    /** 低风险更新：物种 / 预算 / 偏好类目与品牌。 */
    @PutMapping
    public ApiResponse<MemoryView> update(@RequestBody LowRiskUpdate request) {
        return ApiResponse.ok(memoryService.updateLowRisk(UserContext.requireUserId(), request));
    }

    /** 删除我的记忆（幂等）。 */
    @DeleteMapping
    public ApiResponse<Void> delete() {
        memoryService.delete(UserContext.requireUserId());
        return ApiResponse.ok(null);
    }

    /** 申请写入高风险记忆：返回一次性确认令牌。 */
    @PostMapping("/high-risk/request")
    public ApiResponse<ConfirmTicket> requestHighRisk(@RequestBody HighRiskUpdate request) {
        return ApiResponse.ok(memoryService.requestHighRisk(UserContext.requireUserId(), request));
    }

    /** 确认写入高风险记忆：令牌 + 与申请时一致的内容。 */
    @PostMapping("/high-risk/confirm")
    public ApiResponse<MemoryView> confirmHighRisk(@RequestBody HighRiskConfirmRequest request) {
        return ApiResponse.ok(memoryService.confirmHighRisk(UserContext.requireUserId(), request));
    }
}

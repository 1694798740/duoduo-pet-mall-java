package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.dto.OrderDtos.DlqMessageView;
import com.duoduo.trade.dto.OrderDtos.DlqReplayRequest;
import com.duoduo.trade.dto.OrderDtos.DlqReplayResult;
import com.duoduo.trade.service.DlqService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 死信运维接口（A11）。
 *
 * <p><b>内部管理面</b>：双重门 —— 网关把 {@code /api/admin/mq/**} 路由到本服务
 * （不进白名单，须登录）+ {@link OperatorGuard#requireOperator()} 校验运营角色。
 * 死信里是用户的下单请求原文（含收货人信息），绝不能落到非运营手里。
 *
 * <p>仅 order-mode=queue 时存在；sync 部署下调用按 503 如实告知。
 */
@RestController
@RequestMapping("/api/admin/mq")
public class AdminMqController {

    private final ObjectProvider<DlqService> dlqProvider;

    public AdminMqController(ObjectProvider<DlqService> dlqProvider) {
        this.dlqProvider = dlqProvider;
    }

    /** 按幂等键查死信（近 24 小时）。幂等键从任务台账（PENDING 滞留的任务）获取 */
    @GetMapping("/dlq")
    public ApiResponse<List<DlqMessageView>> list(@RequestParam String key) {
        OperatorGuard.requireOperator();
        return ApiResponse.ok(dlqService().list(key));
    }

    /**
     * 重放死信：把该幂等键的死信原文发回主 topic，由消费者重新执行。
     * 重放安全 —— 订单幂等键唯一索引保证不会重复建单。
     */
    @PostMapping("/dlq/replay")
    public ApiResponse<DlqReplayResult> replay(@Valid @RequestBody DlqReplayRequest request) {
        OperatorGuard.requireOperator();
        int replayed = dlqService().replay(request.idempotentKey());
        return ApiResponse.ok(new DlqReplayResult(request.idempotentKey(), replayed));
    }

    private DlqService dlqService() {
        DlqService service = dlqProvider.getIfAvailable();
        if (service == null) {
            throw new BizException(ErrorCode.DEGRADED, "当前部署未开启削峰队列（order-mode=sync）");
        }
        return service;
    }
}

package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.exception.BizException;
import com.duoduo.user.dto.CoinDtos.CoinMutationResult;
import com.duoduo.user.dto.CoinDtos.CoinReconResult;
import com.duoduo.user.dto.CoinDtos.CoinTaskView;
import com.duoduo.user.service.CoinService;
import com.duoduo.user.service.CoinTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 多多币内部接口（R-P1）：交易域写钱与币联动的唯一入口。
 *
 * <p>凭据由 {@code InternalTokenFilter} 把关（{@code /api/coins/internal/} 前缀）。
 * 接口面刻意收窄为「命令 + 查询」两类，每个命令都幂等 —— 交易域的重试、
 * 支付回调重放、调度重跑，重复调用只会得到同一个结果。
 */
@RestController
@RequestMapping("/api/coins/internal")
public class CoinInternalController {

    private final CoinService coinService;
    private final CoinTaskService coinTaskService;

    public CoinInternalController(CoinService coinService, CoinTaskService coinTaskService) {
        this.coinService = coinService;
        this.coinTaskService = coinTaskService;
    }

    /** 可用余额（交易域预览 / 冻结前校验） */
    @GetMapping("/balance")
    public ApiResponse<Long> balance(@RequestParam("userId") Long userId) {
        return ApiResponse.ok(coinService.availableCoins(userId));
    }

    /** 下单冻结（幂等：一单一冻结，同单号同数量重入返回已处理） */
    @PostMapping("/freeze")
    public ApiResponse<CoinMutationResult> freeze(@RequestBody Map<String, Object> body) {
        Long userId = asLong(body.get("userId"));
        String orderNo = asString(body.get("orderNo"));
        Long coins = asLong(body.get("coins"));
        return ApiResponse.ok(coinService.freeze(userId, orderNo, coins));
    }

    /** 支付成功确认扣减（幂等） */
    @PostMapping("/deduct")
    public ApiResponse<CoinMutationResult> deduct(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(coinService.deductConfirm(asString(body.get("orderNo"))));
    }

    /** 取消 / 超时关单释放（幂等） */
    @PostMapping("/release")
    public ApiResponse<CoinMutationResult> release(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(coinService.release(asString(body.get("orderNo"))));
    }

    /** 退款退币（幂等；按批次明细退回原批次，过期批次补发 30 天新批次） */
    @PostMapping("/refund")
    public ApiResponse<CoinMutationResult> refund(@RequestBody Map<String, Object> body) {
        Long userId = asLong(body.get("userId"));
        String orderNo = asString(body.get("orderNo"));
        Long coins = asLong(body.get("coins"));
        return ApiResponse.ok(coinService.refundBack(userId, orderNo, coins));
    }

    /** 完成返币（幂等；交易域 T+7 调度调用） */
    @PostMapping("/earn")
    public ApiResponse<CoinMutationResult> earn(@RequestBody Map<String, Object> body) {
        Long userId = asLong(body.get("userId"));
        String orderNo = asString(body.get("orderNo"));
        Long coins = asLong(body.get("coins"));
        return ApiResponse.ok(coinService.earn(userId, orderNo, coins));
    }

    /** 对账（交易域冒烟断言零问题；定时任务调用打日志） */
    @GetMapping("/recon")
    public ApiResponse<CoinReconResult> recon() {
        return ApiResponse.ok(coinService.reconcile());
    }

    /**
     * 站内任务事件（R-P3）：收藏 / 加购 / 晒单 / 评价 / 新人任务完成，
     * 由产生该事实的域调用。事件本身幂等（唯一键），重复调用只返回当前进度。
     */
    @PostMapping("/task-event")
    public ApiResponse<CoinTaskView> taskEvent(@RequestBody Map<String, Object> body) {
        Long userId = asLong(body.get("userId"));
        if (userId == null) {
            throw BizException.validation("userId 必填");
        }
        return ApiResponse.ok(coinTaskService.handleEvent(
                userId, asString(body.get("event")), asString(body.get("bizId"))));
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

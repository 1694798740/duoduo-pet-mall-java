package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.exception.BizException;
import com.duoduo.fulfilment.dto.CreditDtos.CreditRefreshResult;
import com.duoduo.fulfilment.service.CreditService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 信用分内部接口（A4）。
 *
 * <p>路径以 {@code /api/reviews/internal/} 开头，由本域 {@code InternalTokenFilter} 校验
 * {@code X-Internal-Token}；<b>不经网关</b>（网关白名单不放行 {@code /internal/**}，
 * 走了也只会被网关先按「未登录」拦掉，见 08 篇 T-25）。
 *
 * <p>只有「刷新回写」一个动作 —— 没有「直接设分」的入口：
 * 分数必须由 {@link CreditService} 按权重算出来，不允许外部把一个数字塞进库。
 * 少一个入口就少一类「库里有个谁都不知道怎么来的分数」的排查现场。
 */
@RestController
@RequestMapping("/api/reviews/internal")
public class CreditInternalController {

    private final CreditService creditService;

    public CreditInternalController(CreditService creditService) {
        this.creditService = creditService;
    }

    /**
     * 重算并回写信用分（幂等）。
     *
     * <p>不传 {@code userIds} 时，刷新本域已知的全部卖家（有评价或售后记录者）。
     * 传了就只刷新这些用户 —— 运维排障与自动化测试都靠它把范围收窄到可断言的几行。
     *
     * @param userIds 逗号分隔的用户 ID，可选
     * @param limit   本次最多处理多少个用户（默认 200，防止一次刷爆）；
     *                传 {@code 0} 表示不限制
     */
    @PostMapping("/credits/refresh")
    public ApiResponse<CreditRefreshResult> refresh(
            @RequestParam(name = "userIds", required = false) String userIds,
            @RequestParam(name = "limit", defaultValue = "200") int limit) {
        List<Long> ids = parseIds(userIds);
        return ApiResponse.ok(creditService.refresh(ids, limit, "MANUAL_REFRESH"));
    }

    /**
     * 解析逗号分隔的用户 ID。
     *
     * <p>非法输入一律转 400，<b>不放任它变成 500</b>：{@code Long::valueOf} 遇到
     * {@code "abc"} 会抛 {@code NumberFormatException}，若不接住，调用方（运维 / 定时任务）
     * 只会看到一个没有上下文的服务端错误，误以为「刷新接口坏了」，而实际是参数写错了。
     */
    private static List<Long> parseIds(String userIds) {
        if (userIds == null || userIds.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(userIds.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::valueOf)
                    .toList();
        } catch (NumberFormatException e) {
            throw BizException.validation("userIds 必须是逗号分隔的数字用户 ID");
        }
    }
}

package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.fulfilment.dto.AfterSaleStatsDtos.TenantAfterSaleStats;
import com.duoduo.fulfilment.service.AfterSaleStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E24 · 售后聚合的内部接口（履约域 → 交易域）。
 *
 * <h2>为什么是内部接口而不是挂在 {@code /api/admin/**} 下</h2>
 * 它的调用方是交易域的租户健康度面板，不是浏览器：<b>服务间调用不走网关</b>
 * （网关承担的是对外入口职责，绕一圈网关只是平白多一跳、多一次鉴权解析）。
 * 因此它有两条独立的约束，缺一不可：
 * <ol>
 *   <li><b>不经网关</b>：路径前缀 {@code /api/aftersales/internal/} 虽然被 fulfilment 路由覆盖，
 *       但走到网关时既没有登录态、也没有内部签名 → 直接被拦掉（网关白名单不含它）；</li>
 *   <li><b>服务间签名</b>：本域 {@code InternalTokenFilter} 的 {@code INTERNAL_PREFIXES}
 *       显式列了这个前缀，校验调用方身份 + 时效 + 一次性 nonce（{@code InternalCallAuth}）。
 *       ★ 新增内部接口必须同步加进那个清单，否则它就是**一个匿名可读接口** ——
 *       而且不会有任何报错，只会安静地把全平台售后量暴露出去。</li>
 * </ol>
 *
 * <h2>为什么不返回租户名、不返回比例</h2>
 * 租户名在用户域、比例的分母（已付款订单数）在交易域。本域只回答
 * 「这个卖家在这段时间出了多少售后」——<b>一个域只出自己的事实</b>。
 * 拼装是调用方的事，这样将来换面板、换口径时不需要改本域。
 */
@RestController
@RequestMapping("/api/aftersales/internal")
public class AfterSaleInternalController {

    private final AfterSaleStatsService afterSaleStatsService;

    public AfterSaleInternalController(AfterSaleStatsService afterSaleStatsService) {
        this.afterSaleStatsService = afterSaleStatsService;
    }

    /**
     * 窗口内每个卖家的售后聚合。
     *
     * <p>窗口用查询串（两个 ISO 时间戳）而不是请求体：这是只读查询，
     * 用 GET + 查询串才能在网关/日志里被完整复现（POST + body 的请求在访问日志里只剩一条路径）。
     * 且本项目已经踩过「无 body 的 POST 被客户端默认 Content-Type 判成 415」的坑。
     *
     * @param from 起点（UTC，{@code yyyy-MM-ddTHH:mm:ss}）
     * @param to   终点（UTC，<b>开区间</b>）
     */
    @GetMapping("/tenant-stats")
    public ApiResponse<TenantAfterSaleStats> tenantStats(@RequestParam("from") String from,
                                                         @RequestParam("to") String to) {
        return ApiResponse.ok(afterSaleStatsService.tenantStats(from, to));
    }
}

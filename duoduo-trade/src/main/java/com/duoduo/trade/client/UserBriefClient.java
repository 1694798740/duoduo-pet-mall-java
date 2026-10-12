package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.client.dto.UserBriefCallDtos.UserBrief;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户域客户端：批量取用户简况（E24，只为给健康度面板补上"这串数字是哪家店"）。
 *
 * <p>复用用户域**既有的** {@code GET /api/users/internal/briefs}
 * （原本服务于社交域的私信展示），不新开接口：同一个事实只留一个出口。
 * 该接口只回 {@code id / nickname / avatar}，不含手机号 —— 面板不需要、
 * 也不该拿到能定位到自然人的字段。
 *
 * <p><b>这一路调用是"装饰性"的，允许显式降级</b>：取不到时面板照常出数，
 * 只是店铺名列显示租户 id（响应里 {@code nameResolved=false}）——
 * 见 {@code TenantHealthDtos.TenantHealthPanel#nameResolved} 的理由。
 * 作为对比，售后与接口指标两路取不到时**硬失败**：那两路是判据本身，
 * 缺了面板就会说谎。两者不是"重要程度不同"，而是"会不会导致结论错误"不同。
 */
@FeignClient(name = "duoduo-user", contextId = "tenantHealthUserBriefClient", path = "/api/users")
public interface UserBriefClient {

    /**
     * 批量取昵称。参数是逗号分隔的用户 id（与两端既有约定一致：
     * 集合参数用单值字符串，避免不同 Feign 版本对集合编码的差异）。
     */
    @GetMapping("/internal/briefs")
    ApiResponse<List<UserBrief>> briefs(@RequestParam("ids") String ids);
}

package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.client.dto.MembershipInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 用户域成员身份客户端（E13）：店员准入判定用。
 *
 * <p><b>每请求实时查、刻意不做缓存</b>：店员停用必须即时生效 ——
 * 若加 30 秒缓存，被停用的店员在这 30 秒里还能发货、改单。
 * 商家端接口是低频操作，一次内部 HTTP 往返（毫秒级）换"停用零延迟"，值得。
 * 内部凭据由 {@code InternalCallConfig} 统一注入 {@code X-Internal-Token}。
 */
@FeignClient(name = "duoduo-user", contextId = "membershipClient", path = "/api/users")
public interface MembershipClient {

    /** 无店 / 店主 / 已停用 → null（用户域侧保证），调用方按 null = 非店员处理 */
    @GetMapping("/internal/merchant-membership")
    ApiResponse<MembershipInfo> of(@RequestParam("userId") Long userId);
}

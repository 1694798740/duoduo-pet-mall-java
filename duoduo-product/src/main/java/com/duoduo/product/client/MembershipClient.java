package com.duoduo.product.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.product.client.dto.MembershipInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 用户域成员身份客户端（E14）：商家端商品管理的店员准入判定用。
 *
 * <p><b>每请求实时查、刻意不做缓存</b>（与交易域 E13 同一条硬要求）：
 * 店员被停用必须<b>即时生效</b>。若加 30 秒缓存，被停用的店员在这 30 秒里
 * 还能新建商品、改标题、上下架。商家端接口是低频操作，
 * 一次内部 HTTP 往返（毫秒级）换「停用零延迟」，值得。
 *
 * <p><b>contextId 必须显式给</b>：Spring Cloud OpenFeign 用 {@code name} 作 bean 名，
 * 同一个 {@code duoduo-user} 被第二处以不同契约调用时会撞
 * {@code BeanDefinitionOverrideException}（E13 在交易域踩过一次，启动即崩）。
 *
 * <p>内部凭据由 {@code InternalCallConfig} 统一注入（每次调用重新签名，带一次性 nonce）。
 * 路径前缀 {@code /api/users} 与交易域那份写法一致。
 */
@FeignClient(name = "duoduo-user", contextId = "productMembershipClient", path = "/api/users")
public interface MembershipClient {

    /** 无店 / 店主 / 已停用 → {@code null}（用户域侧保证），调用方按 null = 非店员处理 */
    @GetMapping("/internal/merchant-membership")
    ApiResponse<MembershipInfo> of(@RequestParam("userId") Long userId);
}

package com.duoduo.gateway.support;

import reactor.core.publisher.Mono;

/**
 * 商家 IP 白名单判定（E-06）。
 *
 * <p>抽成接口是为了让过滤器单测能直接钉住「什么时候查、查到什么就拒绝」——
 * 真的去连 Redis 会让单测变成环境测试。
 */
public interface MerchantIpWhitelistChecker {

    /**
     * 这次请求能否进入商家端。
     *
     * @param merchantId 令牌里声明的店铺 id（{@code tid}）；为空表示不属于任何店铺 → 调用方不应询问本方法
     * @param peer       真实对端地址（网关取 TCP 连接地址，不看请求头）
     * @return {@code true}=放行；{@code false}=拒绝
     */
    Mono<Boolean> allows(Long merchantId, String peer);
}

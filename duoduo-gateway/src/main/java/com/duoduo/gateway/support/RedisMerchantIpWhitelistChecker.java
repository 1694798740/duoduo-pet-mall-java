package com.duoduo.gateway.support;

import com.duoduo.common.security.ip.IpRules;
import com.duoduo.common.security.ip.MerchantIpWhitelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * E-06 · 从 Redis 读商家 IP 白名单并判定（副本，权威值在用户库 {@code t_merchant}）。
 *
 * <h2>三个刻意设计</h2>
 * <ol>
 *   <li><b>必须用 reactive 模板</b>：网关是 WebFlux，在 Netty 事件循环上做阻塞调用会卡住整条链路。</li>
 *   <li><b>短超时 + 失败即放行</b>：Redis 假死不能变成「所有商家进不去工作台」——
 *       那是可用性事故，而白名单本意是收紧安全边界，不该反过来扩大故障面。
 *       代价是 Redis 故障期间白名单不生效（已在文档里写明）。</li>
 *   <li><b>不缓存</b>：缓存会让「店主刚把自己加白名单」延迟生效或延迟解除，
 *       而这个功能的失败方向恰恰是「把人锁在门外」，宁可每次读一次 Redis。</li>
 * </ol>
 *
 * <p>键不存在 = 未启用 → 放行（绝大多数商家永远不会开这个功能，因此不产生任何额外语义）。
 * 键存在但为空 = 已启用且一条规则都没有 → <b>拒绝</b>（失败关闭，见 {@link MerchantIpWhitelist}）。
 */
@Component
public class RedisMerchantIpWhitelistChecker implements MerchantIpWhitelistChecker {

    private static final Logger log = LoggerFactory.getLogger(RedisMerchantIpWhitelistChecker.class);

    /** 单次读取的硬上限：超出即当作「查不到」→ 放行 */
    static final Duration TIMEOUT = Duration.ofMillis(150);

    private final ReactiveStringRedisTemplate redis;

    public RedisMerchantIpWhitelistChecker(ObjectProvider<ReactiveStringRedisTemplate> redisProvider) {
        this.redis = redisProvider == null ? null : redisProvider.getIfAvailable();
    }

    @Override
    public Mono<Boolean> allows(Long merchantId, String peer) {
        String key = MerchantIpWhitelist.key(merchantId);
        if (redis == null || key == null) {
            return Mono.just(true);
        }
        return redis.opsForValue()
                .get(key)
                .timeout(TIMEOUT)
                .map(raw -> decide(raw, peer, merchantId))
                .onErrorResume(ex -> {
                    log.warn("商家 IP 白名单读取失败，本次放行 merchantId={} peer={}: {}",
                            merchantId, peer, ex.toString());
                    return Mono.just(true);
                })
                // 键不存在 → 未启用 → 放行
                .defaultIfEmpty(true);
    }

    /** 纯函数式的判定，便于单测直接覆盖：raw 为 null 表示键不存在 */
    static boolean decide(String raw, String peer, Long merchantId) {
        if (raw == null) {
            return true;
        }
        List<String> rules = MerchantIpWhitelist.parse(raw);
        if (rules.isEmpty()) {
            log.warn("商家 IP 白名单已启用但没有任何规则，拒绝全部商家端访问 merchantId={}", merchantId);
            return false;
        }
        if (peer == null || peer.isBlank()) {
            // 取不到对端地址（连接已断开等）→ 拒绝：宁可误拒一次，也不放来源不明的请求进商家端
            return false;
        }
        boolean ok = IpRules.matchesAny(rules, peer);
        if (!ok) {
            log.warn("商家 IP 不在白名单内 merchantId={} peer={} rules={}", merchantId, peer, rules);
        }
        return ok;
    }
}

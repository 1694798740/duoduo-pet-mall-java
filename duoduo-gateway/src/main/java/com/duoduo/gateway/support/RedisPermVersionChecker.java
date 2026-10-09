package com.duoduo.gateway.support;

import com.duoduo.common.security.perm.PermVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

/**
 * E17 · 从 Redis 读权限版本（感知副本）。
 *
 * <h2>三个刻意设计</h2>
 * <ol>
 *   <li><b>必须用 reactive 模板</b>：本服务是 WebFlux，Netty 事件循环上做阻塞调用
 *       会把整条链路卡住（一个慢 Redis 就能让网关全线堆积）。用
 *       {@code ReactiveStringRedisTemplate} 才能让等待挂在整个请求链上。</li>
 *   <li><b>短超时 + 失败即"未知"</b>：{@link #TIMEOUT} 之内拿不到就当作查不到 → 放行。
 *       这是一个**鉴权相关的**外部依赖，最坏情况必须可算：最坏就是每个已认证请求多 150ms
 *       （只在 Redis 假死时才出现；连接被拒是立刻失败的）。若不给超时，
 *       "Redis 挂了"会变成"网关请求全部挂住直到 TCP 超时"。</li>
 *   <li><b>不缓存</b>：本地缓存（哪怕只有 1 秒）会直接削弱本机制要保证的
 *       "下一个请求即生效"。这里宁可每次读一次 Redis。</li>
 * </ol>
 *
 * <p>没有配置 Redis（本地零依赖开发 / 单实例演示）时 bean 为 null，一律返回"未知"，
 * 行为退化为 E17 之前 —— 与"Redis 不可用"走同一条路，不另开分支。
 */
@Component
public class RedisPermVersionChecker implements PermVersionChecker {

    private static final Logger log = LoggerFactory.getLogger(RedisPermVersionChecker.class);

    /** 单次读取的硬上限：超出即当作"未知"。取值依据见类注释第 2 点 */
    static final Duration TIMEOUT = Duration.ofMillis(150);

    private final ReactiveStringRedisTemplate redis;

    public RedisPermVersionChecker(ObjectProvider<ReactiveStringRedisTemplate> redisProvider) {
        this.redis = redisProvider == null ? null : redisProvider.getIfAvailable();
    }

    @Override
    public Mono<Optional<Long>> currentVersion(Long userId) {
        String key = PermVersion.key(userId);
        if (redis == null || key == null) {
            return Mono.just(Optional.empty());
        }
        return redis.opsForValue()
                .get(key)
                .map(raw -> Optional.ofNullable(PermVersion.parse(raw)))
                .timeout(TIMEOUT)
                .onErrorResume(ex -> {
                    // 只记 warn，不向上抛：调用方的契约是"拿到未知就放行"，
                    // 让异常穿透只会把一次缓存故障升级成一次请求失败
                    log.warn("权限版本读取失败，本次按未知处理（放行）userId={}: {}", userId, ex.toString());
                    return Mono.just(Optional.empty());
                })
                // Redis 里没有这个键时源会直接完成（不 emit）→ 补一个"未知"，
                // 保证下游 flatMap 一定会被调用（否则请求会挂住）
                .defaultIfEmpty(Optional.empty());
    }
}
